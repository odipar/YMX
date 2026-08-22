#!/usr/bin/env python3
"""Differential test for the YMX player: does the ST write the right YM frames?

Packs a synthetic tune with the Java YMX tool, assembles YMX.S together with
ST4_wrap.S, runs the real player under Unicorn as a plain 68000, and captures
every write to the sound chip. The captured (register, value) pairs must match,
frame by frame and in order, what a YM2149 should have received - which the
generator computes independently of both the packer and the player.

    python3 ymx/test/emu/test_ymx.py [--quick]

Needs `mvn compile` for the packer, rmac on PATH, and `pip install unicorn`.
"""
import hashlib
import importlib.util
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

from unicorn import Uc, UC_ARCH_M68K, UC_MODE_BIG_ENDIAN, UC_HOOK_MEM_WRITE, UcError
from unicorn.m68k_const import (
    UC_CPU_M68K_M68000, UC_M68K_REG_A0, UC_M68K_REG_A1, UC_M68K_REG_A2,
    UC_M68K_REG_A3, UC_M68K_REG_A4, UC_M68K_REG_A5, UC_M68K_REG_A6,
    UC_M68K_REG_A7, UC_M68K_REG_D0, UC_M68K_REG_D1, UC_M68K_REG_D2,
    UC_M68K_REG_D3, UC_M68K_REG_D4, UC_M68K_REG_D5, UC_M68K_REG_D6,
    UC_M68K_REG_D7, UC_M68K_REG_PC, UC_M68K_REG_SR,
)

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
YMX = REPO / 'ymx'
CLASSES = REPO / 'target' / 'classes'
SCRATCH = HERE / '.work'

sys.path.insert(0, str(YMX / 'test'))
import gen_ym                                                       # noqa: E402

_spec = importlib.util.spec_from_file_location(
    'cycle_model', REPO / '68k' / 'test' / 'emu' / 'm68k_cycles.py')
cycle_model = importlib.util.module_from_spec(_spec)
sys.modules['cycle_model'] = cycle_model        # its dataclass needs to find itself
_spec.loader.exec_module(cycle_model)

CODE = 0x001000
FILE = 0x010000
WORK = 0x040000
STACK_TOP = 0x090000
MAGIC = 0x0A0000
PSG = 0xFFFF8800
PSG_PAGE = 0xFFFF8000
MFP_PAGE = 0xFFFFF000           # $FFFFFAxx: the effect stage's timers
VECTORS = 0x000000              # $110/$134: the two timer vectors
STREAMS = 25                    # fourteen register, eleven script
CHANNELS = 4                    # timer channels; stream T maps them
Ymx_DEFAULT_MAP = 0x9C          # what the packer emits: 0->A 1->D 2->B 3->C
YMX_FIXED = 54 + STREAMS * 64   # the workspace before the rings

QUICK = '--quick' in sys.argv

# The player's own contract: these must come back untouched from every call.
# ST4's decoder state spans a4 and a5, so YMX's contract shrank to these.
PRESERVED = {
    UC_M68K_REG_D6: 0xD6D6D6D6,
    UC_M68K_REG_D7: 0xD7D7D7D7,
    UC_M68K_REG_A6: 0x00A6A600,
}
SCRATCH_REGISTERS = (UC_M68K_REG_D1, UC_M68K_REG_D2, UC_M68K_REG_D3,
                     UC_M68K_REG_D4, UC_M68K_REG_D5, UC_M68K_REG_A2,
                     UC_M68K_REG_A3, UC_M68K_REG_A4, UC_M68K_REG_A5)


_ASSEMBLED = {}


def assemble(unit: int = 1, super_host: bool = False, perf: bool = False):
    """YMX.S plus the decoder, built for one unit size, as one flat blob.
    super_host builds the YMX_SUPER_HOST variant: the PCM tick parks a0 in
    the USP instead of the stack. perf builds the raster monitor in."""
    key = (unit, super_host, perf)
    if key in _ASSEMBLED:
        return _ASSEMBLED[key]
    SCRATCH.mkdir(exist_ok=True)
    tag = f'{unit}{"u" if super_host else ""}{"p" if perf else ""}'
    source = SCRATCH / f'link{tag}.S'
    source.write_text(f'ST4_UNIT    equ     {unit}\n'
                      + ('YMX_SUPER_HOST equ  1\n' if super_host else '')
                      + ('YMX_PERF    equ     1\n' if perf else '')
    # The frame write masks interrupts by default; YMX_NOMASK=1 runs the
    # whole rig against the variant that does not, the tools'
    # -nomask.
    + ('YMX_MASK_BURST equ  0\n' if os.environ.get('YMX_NOMASK') else '')
                      + '        include "YMX.S"\n'
                      '        include "ST4_wrap.S"\n')
    binary = SCRATCH / f'link{tag}.bin'
    listing = SCRATCH / f'link{tag}.lst'
    command = ['rmac', '-m68000', '-fr', '+o3',
               '-i' + str(REPO / '68k'),
               f'-l*{listing}', '-o', str(binary), str(source)]
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode:
        raise SystemExit(result.stdout + result.stderr)
    built = (binary.read_bytes(), symbol_table(listing))
    _ASSEMBLED[key] = built
    return built


def symbol_table(listing: Path) -> dict:
    """Every label in an rmac listing, from its symbol table.

    rmac prints two symbols per line, which is why this does not reuse
    cycle_model.parse_listing: that one takes a whole line per symbol and would
    silently miss half of them - including, on a bad day, YMX_play.
    """
    pattern = re.compile(r'(\S+)\s+([0-9A-F]{16})\s+[atdb]\b')
    symbols = {}
    for line in listing.read_text().splitlines():
        for name, value in pattern.findall(line):
            symbols[name] = int(value, 16)
    for wanted in ('YMX_init', 'YMX_play', 'YMX_stop'):
        if wanted not in symbols:
            raise AssertionError(f'{wanted} missing from the listing')
    return symbols


def pack(tune: bytes, ring: int, chunk: int, loop, unit: int = 1,
         extra: tuple = ()) -> bytes:
    """Runs the real packer, cached on the tune and the packing options.

    loop is the frame to loop from, or None to pack a tune that plays once.
    NOTE: the cache keys on the tune bytes and options, NOT on the packer's
    code - after changing the packer or the simulator, rm -rf the scratch.
    """
    if not CLASSES.exists():
        raise SystemExit('target/classes is missing; run `mvn compile` first')
    SCRATCH.mkdir(exist_ok=True)
    option = '-o' if loop is None else f'-l{loop}'
    key = hashlib.sha1(tune).hexdigest()[:12]
    tag = ''.join(extra)
    cached = SCRATCH / f'{key}-n{ring}-c{chunk}-k{unit}{option}{tag}.ymx'
    if not cached.exists():
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'tune.ym'
            source.write_bytes(tune)
            subprocess.run(['java', '-ea', '-cp', str(CLASSES), 'org.ym6.Ymx', '-f',
                            f'-n{ring}', f'-c{chunk}', f'-k{unit}', option,
                            *extra, str(source), str(cached)],
                           check=True, capture_output=True)
    return cached.read_bytes()


class Player:
    """One emulated ST running YMX over a packed tune."""

    def __init__(self, packed: bytes, workspace_size: int, unit: int = 1,
                 super_host: bool = False, perf: bool = False):
        self.uc = Uc(UC_ARCH_M68K, UC_MODE_BIG_ENDIAN)
        self.uc.ctl_set_cpu_model(UC_CPU_M68K_M68000)
        for base, size in ((CODE, 0x4000), (FILE, 0x30000), (WORK, 0x40000),
                           (STACK_TOP - 0x8000, 0x8000), (MAGIC, 0x1000),
                           (PSG_PAGE, 0x1000), (MFP_PAGE, 0x1000),
                           (VECTORS, 0x1000)):
            self.uc.mem_map(base, size)
        self.binary, self.symbols = assemble(unit, super_host, perf)
        self.uc.mem_write(CODE, self.binary)
        # Odd-but-even addresses on purpose: the 68000 needs word alignment,
        # not long alignment, and the player must not assume more.
        self.file = FILE + 2
        self.work = WORK + 2
        self.work_end = self.work + workspace_size
        self.uc.mem_write(self.file, packed)
        self.uc.mem_write(self.work, b'\xA5' * workspace_size)
        self.writes = []
        self.mfp = []                   # (address, value) writes to the MFP
        self.palette = []               # word writes to $FFFF8240 (perf)
        self.stray = []
        self.uc.hook_add(UC_HOOK_MEM_WRITE, self._watch)

    def _watch(self, uc, access, address, size, value, data):
        if address == 0xFFFF8240:       # the raster monitor's background
            self.palette.append(value)
            return
        if PSG_PAGE <= address < PSG_PAGE + 0x1000:
            # A wide write lands one byte per bus lane: a move.l to $8800 is
            # the select at $8800 and the data at $8802, exactly as the chip
            # sees it; the odd lanes fall into shadow.
            for i in range(size):
                self.writes.append((address + i, (value >> (8 * (size - 1 - i))) & 0xFF))
        elif MFP_PAGE <= address < MFP_PAGE + 0x1000:
            self.mfp.append((address, value & 0xFF))
        elif address < 0x1000:
            pass                        # the timer vectors
        elif CODE <= address < CODE + len(self.binary):
            pass                        # the skeletons' self-modified operands
        elif not (self.work <= address and address + size <= self.work_end
                  or STACK_TOP - 0x8000 <= address < STACK_TOP):
            self.stray.append((address, size))

    def call(self, entry: str, registers=()):
        stack = STACK_TOP - 256
        self.uc.mem_write(stack, MAGIC.to_bytes(4, 'big'))
        # Supervisor state, interrupts enabled: what a VBL handler runs in, and
        # what the player needs - it touches the sound chip and its own mask.
        # Set before a7, which is a different register in each state.
        self.uc.reg_write(UC_M68K_REG_SR, 0x2000)
        self.uc.reg_write(UC_M68K_REG_A7, stack)
        for register, canary in PRESERVED.items():
            self.uc.reg_write(register, canary)
        for register in SCRATCH_REGISTERS:
            self.uc.reg_write(register, 0xBAD0BAD0)
        for register, value in registers:
            self.uc.reg_write(register, value)
        address = CODE + self.symbols[entry]
        self.uc.emu_start(address, MAGIC, count=50_000_000)
        if self.uc.reg_read(UC_M68K_REG_PC) != MAGIC:
            raise AssertionError(f'{entry} did not return')
        for register, canary in PRESERVED.items():
            if self.uc.reg_read(register) != canary:
                raise AssertionError(f'{entry} clobbered a preserved register')
        if self.stray:
            raise AssertionError('wrote outside the workspace at '
                                 + ', '.join(hex(a) for a, _ in self.stray[:3]))
        result = self.uc.reg_read(UC_M68K_REG_D0)
        return result - (1 << 32) if result >> 31 else result      # d0 is signed

    def init(self):
        return self.call('YMX_init', ((UC_M68K_REG_A0, self.file),
                                      (UC_M68K_REG_A1, self.work)))

    def stop(self):
        return self.call('YMX_stop', ((UC_M68K_REG_A0, self.work),))

    def frame(self):
        """Plays one frame; returns (result, [(register, value), ...])."""
        self.writes.clear()
        self.mfp.clear()
        result = self.call('YMX_play', ((UC_M68K_REG_A0, self.work),))
        return result, self._decode_writes()

    def _decode_writes(self):
        """Pairs up select/write accesses the way the sound chip sees them."""
        pairs = []
        selected = None
        for address, value in self.writes:
            if address == PSG:
                selected = value
            elif address == PSG + 2:
                if selected is None:
                    raise AssertionError('wrote a value before selecting a register')
                pairs.append((selected, value))
            elif address in (PSG + 1, PSG + 3):
                pass                    # a wide write's shadow lanes
            else:
                raise AssertionError(f'wrote to {address:#x}, not the sound chip')
        return pairs


def workspace_size(ring: int) -> int:
    return YMX_FIXED + STREAMS * ring


def apply_writes(state, writes):
    """Feeds captured writes to a model of the chip; reports R13 writes.

    The player may skip a register whose value has not changed, so what has to
    match is the chip's contents. Writing R13 restarts the envelope, though, so
    that one write is an event in its own right.
    """
    envelope_written = False
    for register, value in writes:
        if register >= gen_ym.PLAY_REGISTERS:
            raise AssertionError(f'wrote R{register}, which is an I/O port')
        if register == 13:
            envelope_written = True
        state[register] = value
    return envelope_written


def run_shape(frames: int, ring: int, chunk: int, label: str,
              loop=0, passes: int = 1, unit: int = 1) -> str:
    """Plays a whole tune (and `passes` times round its loop) and checks it.

    loop is the frame the packed tune loops from, or None for one that plays
    once and stops.
    """
    source = gen_ym.registers(frames)
    packed = pack(gen_ym.ym6_file(frames, source, loop_frame=loop or 0),
                  ring, chunk, loop, unit)
    played = frames if loop is None else frames + passes * (frames - loop)
    expected = gen_ym.chip_states(frames, source, loop, played)

    player = Player(packed, workspace_size(ring), unit)
    if player.init() != 0:
        return f'{label}: YMX_init rejected the file'

    state = [0] * gen_ym.PLAY_REGISTERS
    position = 0                                  # where in the tune we are
    for index in range(played):
        result, writes = player.frame()
        envelope = apply_writes(state, writes)
        wanted, wanted_envelope = expected[index]
        if state != wanted:
            differs = [f'R{r}={state[r]:#04x} want {wanted[r]:#04x}'
                       for r in range(gen_ym.PLAY_REGISTERS) if state[r] != wanted[r]]
            return f'{label}: after frame {index} the chip has ' + ', '.join(differs)
        if envelope != wanted_envelope:
            return (f'{label}: frame {index} {"wrote" if envelope else "skipped"}'
                    f' R13, expected the other')
        position += 1
        # d0 = 1 means "that frame ended the tune, the next one is the loop
        # frame". A tune that plays once never reports it: it reports -1 on the
        # call after its last frame instead.
        wrapped = position >= frames and loop is not None
        if wrapped:
            position = loop
        if result != (1 if wrapped else 0):
            return f'{label}: frame {index} returned {result}, expected {1 if wrapped else 0}'

    if loop is None:
        result, writes = player.frame()
        if result != -1 or writes:
            return f'{label}: past the end it wrote {writes} and returned {result}'

    # Re-initialising is the whole reset: the second pass must be identical.
    if player.init() != 0:
        return f'{label}: re-init rejected the file'
    state = [0] * gen_ym.PLAY_REGISTERS
    for index in range(min(played, 3 * chunk)):
        _, writes = player.frame()
        apply_writes(state, writes)
        if state != expected[index][0]:
            return f'{label}: frame {index} differs after re-init'
    return ''


TACR, TADR = 0xFFFFFA19, 0xFFFFFA1F
TCDCR, TDDR = 0xFFFFFA1D, 0xFFFFFA25


def run_effects(super_host: bool = False, perf: bool = False) -> str:
    """The effect stage, frame by frame, against the compiled script: a SID
    held, reloaded and retuned on slot 1, drums triggered and retriggered on
    slot 2, a buzzer, the same-voice arbitration - the same scene the v1
    interpreter was tested on, asserted at v2's frame-aligned edges. The
    tick handlers are then driven by direct invocation, after the walk, so
    the script and the hand-run ticks never disagree about state."""
    frames = 72
    values = [bytearray(frames) for _ in range(16)]
    for frame in range(frames):
        values[7][frame] = 0x38                     # tone on, noise off
        values[8][frame] = 10                       # steady volumes
        values[9][frame] = 11
        values[10][frame] = 12
        values[13][frame] = gen_ym.NO_ENVELOPE_CHANGE
    for frame in range(5, 21):                      # SID voice A on slot 1
        values[1][frame] |= 0x10
        values[6][frame] |= 1 << 5
        values[14][frame] = 80 if frame >= 15 else 100
    for frame in range(6, 15):                      # ...whose volume SLIDES:
        values[8][frame] = 10 - (frame - 4) // 2    # the script must track it
    # The retune scene: the same SID slides across a prescaler boundary -
    # E $11 to $12 at frame 25 - as wobbling basses do many times a second.
    # The script emits SID_RETUNE: the timer reprograms, the vector is
    # untouched, the square keeps whichever half was installed.
    for frame in range(22, 27):
        values[1][frame] |= 0x10
        values[6][frame] |= (1 if frame < 25 else 2) << 5
        values[14][frame] = 90
    values[3][30] = 0x70                            # drum voice C on slot 2
    values[8][30] |= 1 << 5                         # its prescaler rides R8
    values[15][30] = 122                            # ...at 5036 Hz
    # Real dumps trigger drums on back-to-back frames - an attack sample,
    # then a body sample - so frame 31 codes the same drum again with a
    # different number underneath: the script emits a fresh DRUM either way.
    values[3][31] = 0x70
    values[8][31] |= 1 << 5
    values[15][31] = 122
    # The ring-integrity trap: an 11-byte R10 pattern spanning the drum frame,
    # repeated 30 frames later - the packer emits a match that copies those
    # ring positions. v2 never edits the ring at runtime, so the trap now
    # proves the drum number travels in the ring unharmed. Frame 30's
    # value doubles as the drum number: sample 1; frame 31's is sample 0.
    pattern = [3, 4, 5, 6, 7, 1, 0, 6, 5, 4, 3]
    for i, v in enumerate(pattern):
        values[10][25 + i] = v
        values[10][55 + i] = v
    for frame in range(40, 43):                     # sync-buzzer voice B, 123 Hz
        values[1][frame] = 0xE0
        values[6][frame] |= 6 << 5
        values[14][frame] = 200
    # The arbitration scene: a SID runs on voice B from slot 2, and at frame
    # 48 a drum fires on the SAME voice from slot 1. The script compiled the
    # whole exchange: START_PCM_PREEMPT stops the SID's timer first, the suppressed
    # SID costs nothing, and it resumes BY RETUNE - phase intact - at
    # the frame after the drum's computed end.
    for frame in range(45, 53):
        values[3][frame] |= 0x20
        values[8][frame] |= 1 << 5
        values[15][frame] = 90
    values[1][48] = 0x60
    values[6][48] |= 1 << 5
    values[14][48] = 60
    values[9][48] = 0                               # its number: sample 0
    drums = (bytes([0x80, 0x40]), bytes([0x10, 0xF0, 0x50]))

    packed = pack(gen_ym.ym6_file(frames, values, drums=drums), 960, 24, 0, 1)
    player = Player(packed, workspace_size(960), super_host=super_host,
                    perf=perf)
    if player.init() != 0:
        return 'effects: YMX_init rejected the file'

    # The mechanism itself: stream T's byte, decoded into the channel
    # descriptors. Driven directly, since no YM tune moves the map.
    problem = check_assignment(player)
    if problem:
        return problem
    if player.init() != 0:                  # put the tune's own map back
        return 'effects: YMX_init rejected the file on the second pass'

    # Every tick-handler block must be byte-congruent with channel 1's: the
    # action handlers reach every patched operand through offsets measured
    # there, and ymx_link walks the blocks at a fixed stride.
    sym = player.symbols
    timers = ('a', 'b', 'c', 'd')
    for pattern in ('ymx_toggle_{}_on', 'ymx_toggle_{}_off',
                    'ymx_retrigger_{}', 'ymx_park_{}'):
        want = sym[pattern.format('a')] - sym['ymx_pcm_a']
        for timer in timers[1:]:
            label = pattern.format(timer)
            if sym[label] - sym[f'ymx_pcm_{timer}'] != want:
                return f'effects: {label} broke the ISR block congruence'
    stride = sym['ymx_pcm_b'] - sym['ymx_pcm_a']
    for i, timer in enumerate(timers):
        if sym[f'ymx_pcm_{timer}'] - sym['ymx_pcm_a'] != stride * i:
            return f'effects: block {timer} is not one stride along'

    # Channel 0 runs on Timer A and channel 1 on Timer D, which is what the
    # packer's default map says; the blocks are the timers'.
    drum_d = CODE + sym['ymx_pcm_d']
    sid_on = CODE + sym['ymx_toggle_a_on']
    sid_off = CODE + sym['ymx_toggle_a_off']

    # A burst write is twelve bytes and ends in the movep that sends it;
    # a skip replaces that instruction with two nops, so the state reads as
    # the opcode itself against $4E71.
    WRITE_SIZE, WRITE_MOVEP = 12, 8
    movep_opcode = int.from_bytes(
        player.uc.mem_read(CODE + sym['ymx_movep'], 2), 'big')

    def skipped(voice):
        """2 when the voice's burst write is open, 0 when it is muted -
        the same two values the old displacement trick reported."""
        at = CODE + sym['ymx_wB'] + WRITE_MOVEP + WRITE_SIZE * voice
        word = int.from_bytes(player.uc.mem_read(at, 2), 'big')
        return 2 if word == movep_opcode else 0 if word == 0x4E71 else -1

    acc = lambda: int.from_bytes(
        player.uc.mem_read(CODE + sym['ymx_perf_acc'], 2), 'big') \
        if perf else 0

    for frame in range(72):
        if frame == 25:                             # flip the square to its
            invoke_isr(player, sid_on)              # quiet half: the retune
        _, writes = player.frame()                  # below must preserve it
        mfp = player.mfp
        registers = dict(writes)
        if frame == 5:                              # SID start: stop, count,
            if mfp != [(TACR, 0), (TADR, 100), (TACR, 1),   # run, enabled
                       (0xFFFFFA07, 0x20)]:
                return f'effects: frame 5 programmed {mfp}'
            if skipped(0) != 0:
                return 'effects: frame 5 left the SID voice open'
        elif 6 <= frame <= 20 and frame != 15:      # held: the slide patches
            if mfp:                                 # the tick's immediate,
                return f'effects: frame {frame} wrote {mfp}'    # never the MFP
            if 8 in registers:
                return f'effects: frame {frame} wrote the SID voice volume'
            want = 10 - (frame - 4) // 2 if frame <= 14 else 10
            vol = player.uc.mem_read(
                CODE + sym['ymx_pcm_a'] + sym['ISR_TOGGLE_VOL'], 1)[0]
            if vol != want:
                return (f'effects: frame {frame} tick volume {vol}, '
                        f'the slide says {want}')
        elif frame == 15:                           # the count changed: a
            if mfp != [(TADR, 80)]:                 # HELD reload, data only
                return f'effects: frame 15 wrote {mfp}'
        elif frame == 21:                           # released: stopped, and
            if mfp != [(TACR, 0)]:                  # the skip lifts with
                return f'effects: frame 21 wrote {mfp}'         # the frame
            if 8 not in registers:
                return 'effects: frame 21 kept skipping the SID volume'
        elif frame == 22:                           # a re-start is a FULL
            if mfp != [(TACR, 0), (TADR, 90), (TACR, 1),    # start at phase
                       (0xFFFFFA07, 0x20)]:                 # zero...
                return f'effects: frame 22 programmed {mfp}'
            if registers.get(8) != 0:               # ...the voice silenced
                return ('effects: frame 22 volume '     # for its first
                        f'{registers.get(8)}, not silent')  # period
            vector = int.from_bytes(player.uc.mem_read(0x134, 4), 'big')
            if vector != sid_on:
                return ('effects: the start did not install the loud half: '
                        f'{vector:#x}')
        elif frame in (23, 24):
            if mfp:
                return f'effects: frame {frame} wrote {mfp}'
        elif frame == 25:                           # prescaler-only change:
            if mfp != [(TACR, 0), (TADR, 90), (TACR, 2),    # full timer
                       (0xFFFFFA07, 0x20)]:                 # reprogram...
                return f'effects: frame 25 programmed {mfp}'
            vector = int.from_bytes(player.uc.mem_read(0x134, 4), 'big')
            if vector != sid_off:                   # ...but the phase lives:
                return ('effects: the retune reset the square to '
                        f'{vector:#x}, not the installed quiet half')
        elif frame == 26:
            if mfp:
                return f'effects: frame 26 wrote {mfp}'
        elif frame == 27:                           # the scene's release
            if mfp != [(TACR, 0)]:
                return f'effects: frame 27 wrote {mfp}'
        elif frame == 30:                           # the drum start, slot 2
            if mfp != [(TCDCR, 0), (TDDR, 122), (TCDCR, 1),
                       (0xFFFFFA09, 0x10)]:
                return f'effects: frame 30 programmed {mfp}'
            if 10 in registers:                     # the drum owns R10 now
                return 'effects: frame 30 wrote the drummed volume'
            if registers.get(7) != 0x38 | 0xC0 | 0x24:  # baked, not forced
                return f'effects: frame 30 mixer {registers.get(7):#x}'
            position = int.from_bytes(player.uc.mem_read(
                drum_d + player.symbols['ISR_PCM_PTR'], 4), 'big')
            drum = int.from_bytes(player.uc.mem_read(
                player.file + Ymx_DRUM_TABLE(player) + 8, 4), 'big')
            if position != player.file + drum:
                return 'effects: the trigger points at the wrong sample'
        elif frame == 31:                           # the same code again: a
            if mfp != [(TCDCR, 0), (TDDR, 122), (TCDCR, 1),     # fresh DRUM
                       (0xFFFFFA09, 0x10)]:
                return f'effects: frame 31 programmed {mfp}'
            position = int.from_bytes(player.uc.mem_read(
                drum_d + player.symbols['ISR_PCM_PTR'], 4), 'big')
            drum = int.from_bytes(player.uc.mem_read(
                player.file + Ymx_DRUM_TABLE(player), 4), 'big')
            if position != player.file + drum:
                return 'effects: the retrigger points at the wrong sample'
        elif frame == 32:                           # the computed end, frame
            if mfp:                                 # aligned: skip and mixer
                return f'effects: frame 32 wrote {mfp}'     # come back as one
            if registers.get(7) != 0x38 | 0xC0:
                return 'effects: frame 32 mixer still forced'
            if 10 not in registers:
                return 'effects: frame 32 kept skipping the drum voice'
        elif frame == 40:                           # buzzer start on slot 1
            if mfp != [(TACR, 0), (TADR, 200), (TACR, 6),
                       (0xFFFFFA07, 0x20)]:
                return f'effects: frame 40 programmed {mfp}'
        elif frame in (41, 42):                     # held, nothing changed
            if mfp:
                return f'effects: frame {frame} wrote {mfp}'
        elif frame == 43:
            if mfp != [(TACR, 0)]:
                return f'effects: frame 43 wrote {mfp}'
        elif frame == 45:                           # the scene's SID, slot 2
            if mfp != [(TCDCR, 0), (TDDR, 90), (TCDCR, 1),
                       (0xFFFFFA09, 0x10)]:
                return f'effects: frame 45 programmed {mfp}'
        elif frame in (46, 47):                     # held: its volume is skipped
            if 9 in registers:
                return f'effects: frame {frame} wrote the skipped volume'
        elif frame == 48:                           # START_PCM_PREEMPT: the SID's
            want = [(TCDCR, 0),                     # timer stops FIRST,
                    (TACR, 0), (TADR, 60), (TACR, 1),   # then the drum arms
                    (0xFFFFFA07, 0x20)]
            if mfp != want:
                return f'effects: frame 48 programmed {mfp}'
            if 9 in registers:
                return 'effects: frame 48 wrote the drummed volume'
            if registers.get(7) != 0x38 | 0xC0 | 0x12:
                return f'effects: frame 48 mixer {registers.get(7):#x}'
        elif frame == 49:                           # the computed end: the
            want = [(TCDCR, 0), (TDDR, 90), (TCDCR, 1),     # SID re-STARTS -
                    (0xFFFFFA09, 0x10)]             # deterministic, phase 0
            if mfp != want:
                return f'effects: frame 49 programmed {mfp}'
            if registers.get(9) != 0:               # the start's own silence
                return ('effects: frame 49 volume '
                        f'{registers.get(9)}, not the silent first half')
            if registers.get(7) != 0x38 | 0xC0:
                return f'effects: frame 49 mixer {registers.get(7):#x}'
        elif frame in (50, 51, 52):
            if mfp:
                return f'effects: frame {frame} wrote {mfp}'
        elif frame == 53:                           # the restarted SID
            if mfp != [(TCDCR, 0)]:                 # releases
                return f'effects: frame 53 wrote {mfp}'
            if 9 not in registers:
                return 'effects: frame 53 kept skipping the voice'
        elif mfp:
            return f'effects: frame {frame} unexpectedly wrote {mfp}'
        if frame == 60 and registers.get(10) != 1:
            return (f'effects: frame 60 played {registers.get(10)} - the ring '
                    'did not keep the byte the drum number rode in on')

    # Both drums, tick by tick, by direct invocation: frame 31's on Timer D
    # (voice C), frame 48's on Timer A (voice B). Sample 0 is 0x80, 0x40 ->
    # nibbles 8, 4, then the marker parks the volume and stops the timer -
    # and nothing else: the script already scheduled the skip and mixer
    # edges at the frame boundary.
    problem = drum_ticks(player, drum_d, 10, TCDCR, 0xFFFFFA11, 0xEF)
    if problem:
        return problem
    problem = drum_ticks(player, CODE + sym['ymx_pcm_a'], 9, TACR, 0xFFFFFA0F, 0xDF)
    if problem:
        return problem
    if perf and acc() != 2 * (21 + 21 + 23):    # both drums' playouts
        return f'effects: the drum ticks accumulated {acc()}, not 130'

    # The toggle tick: the loud half writes the volume and installs the quiet
    # half as a whole vector, and back.
    pairs = invoke_isr(player, sid_on)              # the A block still holds
    vector = int.from_bytes(player.uc.mem_read(0x134, 4), 'big')
    if pairs != [(8, 10)] or vector != sid_off:     # frame 5's voice A and
        return f'effects: the loud half wrote {pairs}, vector {vector:#x}'
    pairs = invoke_isr(player, sid_off)             # frame 25's volume
    vector = int.from_bytes(player.uc.mem_read(0x134, 4), 'big')
    if pairs != [(8, 0)] or vector != sid_on:
        return f'effects: the quiet half wrote {pairs}, vector {vector:#x}'

    # And the buzzer from frame 40: every tick rewrites the shape to R13.
    pairs = invoke_isr(player, CODE + sym['ymx_retrigger_a'])
    if pairs != [(13, 11)]:
        return f'effects: the retrigger tick wrote {pairs}'
    if perf and acc() != 130 + 15 + 15 + 12:
        return f'effects: the ticks accumulated {acc()}'

    # One more frame clears the raster monitor's accumulator; the frame
    # itself replays the loop head, whose writes are the script's business.
    player.frame()
    if perf and acc() != 0:
        return 'effects: the frame did not clear the perf accumulator'

    # The library's stop contract: it quiesces its claim - timers stopped,
    # their interrupt bits disabled and masked, no voice skipped - and
    # restores nothing; the host owns the machine state (assumption 5).
    player.stop()
    mfp = lambda a: player.uc.mem_read(a, 1)[0]
    if mfp(0xFFFFFA19) & 0x0F or mfp(0xFFFFFA1D) & 0x0F:
        return 'effects: stop left a timer running'
    if mfp(0xFFFFFA07) & 0x20 or mfp(0xFFFFFA13) & 0x20 \
            or mfp(0xFFFFFA09) & 0x10 or mfp(0xFFFFFA15) & 0x10:
        return 'effects: stop left its claim enabled'
    for voice in range(3):
        if skipped(voice) != 2:
            return f'effects: stop left voice {voice} muted'

    # Claiming is per timer channel, and a second YMX_init must hand back
    # what the first one took: the file says which channels it uses, so a
    # tune that uses fewer leaves the player holding timers nothing needs
    # unless init gives them back first. Init the effect tune, then
    # init an effect-free one into the same blob and workspace.
    quiet = gen_ym.ym6_file(40, [bytearray(40) for _ in range(16)])
    reused = Player(pack(gen_ym.ym6_file(frames, values, drums=drums),
                         960, 24, 0, 1),
                    workspace_size(960), super_host=super_host, perf=perf)
    if reused.init() != 0:
        return 'effects: init rejected the two-channel pack'
    for _ in range(32):                         # far enough in to be running
        reused.frame()
    reused.uc.mem_write(reused.file, pack(quiet, 960, 24, 0, 1))
    if reused.init() != 0:
        return 'effects: init rejected the effect-free pack'
    mfp2 = lambda a: reused.uc.mem_read(a, 1)[0]
    if mfp2(0xFFFFFA19) & 0x0F or mfp2(0xFFFFFA1D) & 0x0F:
        return 'effects: re-init left an unclaimed timer running'
    if mfp2(0xFFFFFA07) & 0x20 or mfp2(0xFFFFFA13) & 0x20 \
            or mfp2(0xFFFFFA09) & 0x10 or mfp2(0xFFFFFA15) & 0x10:
        return 'effects: re-init left an unclaimed channel enabled'
    for _ in range(20):
        problem, _ = reused.frame()
        if problem:
            return 'effects: the re-inited tune ' + problem

    # The -sidresume gap model, on the same tune: a fresh player walks to
    # the release and resume and must see the mask, the counting-on timer,
    # and the reload-only comeback - the player's resume verbs, live.
    resumed = Player(pack(gen_ym.ym6_file(frames, values, drums=drums),
                          960, 24, 0, 1, extra=('-sidresume',)),
                     workspace_size(960), super_host=super_host, perf=perf)
    if resumed.init() != 0:
        return 'effects: init rejected the -sidresume pack'
    for frame in range(30):
        resumed.mfp.clear()
        _, writes = resumed.frame()
        registers = dict(writes)
        if frame == 21:
            if resumed.mfp != [(0xFFFFFA07, 0x00)]:     # masked: IER bit
                return ('effects: resume-model frame 21 wrote '
                        f'{resumed.mfp}')
            if 8 not in registers:
                return 'effects: resume-model frame 21 kept the voice skipped'
        elif frame == 22:
            if resumed.mfp != [(TADR, 90), (0xFFFFFA07, 0x20)]:
                return ('effects: resume-model frame 22 programmed '
                        f'{resumed.mfp}')
            if registers.get(8, None) is not None and registers[8] == 0:
                return 'effects: the resume silenced a running square'
        elif frame == 27:
            if resumed.mfp != [(0xFFFFFA07, 0x00)]:
                return ('effects: resume-model frame 27 wrote '
                        f'{resumed.mfp}')

    # The monitor's color protocol, unchanged from v1: every frame paints
    # the yellow timer bar, then its own red, then puts the original back;
    # every tick paints its timer's color and restores.
    if perf:
        seen = player.palette
        if set(seen) != {0x770, 0x700, 0x070, 0x007, 0}:
            return f'effects: the monitor painted {sorted(set(seen))}'
        if seen.count(0x770) != seen.count(0x700):
            return 'effects: a timer bar without its frame band'
        if seen[-1] != 0 or seen.count(0) != len(seen) - 2 * seen.count(0x770) \
                - seen.count(0x070) - seen.count(0x007):
            return 'effects: the monitor did not restore the background'
    elif player.palette:
        return 'effects: the monitor painted in a build without it'
    return ''


def drum_ticks(player, code, register, ctrl, eoi_reg, eoi_value) -> str:
    """Plays a patched drum out by direct invocation: two sample nibbles,
    then the marker - which parks the volume and stops the timer, nothing
    more: the script owns every frame-side consequence. Sample 0 is
    0x80, 0x40 -> nibbles 8, 4."""
    for tick, value in enumerate((8, 4)):
        pairs = invoke_isr(player, code)
        if pairs != [(register, value)]:
            return f'effects: PCM tick {tick} wrote {pairs}'
    pairs = invoke_isr(player, code)                # the marker tick
    if pairs != [(register, 0x80), (register, 0x0D)]:
        return f'effects: the marker tick wrote {pairs}'
    if player.mfp[-2:] != [(ctrl, 0), (eoi_reg, eoi_value)]:
        return f'effects: the marker tick programmed {player.mfp[-2:]}'
    return ''


class Sndh:
    """One emulated ST driving an SNDH blob through its three entries."""

    CANARY = {UC_M68K_REG_D0: 0xD0D0D0D0, UC_M68K_REG_D1: 0xD1D1D1D1,
              UC_M68K_REG_D2: 0xD2D2D2D2, UC_M68K_REG_D3: 0xD3D3D3D3,
              UC_M68K_REG_D4: 0xD4D4D4D4, UC_M68K_REG_D5: 0xD5D5D5D5,
              UC_M68K_REG_D6: 0xD6D6D6D6, UC_M68K_REG_D7: 0xD7D7D7D7,
              UC_M68K_REG_A0: 0xA0A0A0A0, UC_M68K_REG_A1: 0xA1A1A1A1,
              UC_M68K_REG_A2: 0xA2A2A2A2, UC_M68K_REG_A3: 0xA3A3A3A3,
              UC_M68K_REG_A4: 0xA4A4A4A4, UC_M68K_REG_A5: 0xA5A5A5A5,
              UC_M68K_REG_A6: 0xA6A6A6A6}

    def __init__(self, blob: bytes, offset: int = 0x1002):
        self.uc = Uc(UC_ARCH_M68K, UC_MODE_BIG_ENDIAN)
        self.uc.ctl_set_cpu_model(UC_CPU_M68K_M68000)
        size = (len(blob) + offset + 0xFFFF) & ~0xFFF
        for base, span in ((CODE, size), (STACK_TOP - 0x8000, 0x8000),
                           (MAGIC, 0x1000), (PSG_PAGE, 0x1000),
                           (MFP_PAGE, 0x1000), (VECTORS, 0x1000)):
            self.uc.mem_map(base, span)
        self.base = CODE + offset            # any even address must do
        self.uc.mem_write(self.base, blob)
        self.writes = []
        self.uc.hook_add(UC_HOOK_MEM_WRITE, self._psg, None, PSG, PSG + 4)
        self.uc.mem_write(MAGIC, b'\x4e\x71')

    def _psg(self, uc, access, address, size, value, user):
        for lane in range(size):
            self.writes.append((address + lane, (value >> (8 * (size - 1 - lane))) & 0xFF))

    def call(self, entry: int, d0: int) -> str:
        """Runs one SNDH entry; every register d0-a6 must come back."""
        self.uc.reg_write(UC_M68K_REG_SR, 0x2300)  # supervisor FIRST: writing
        for register, canary in self.CANARY.items():   # SR banks a7, so the
            self.uc.reg_write(register, canary)        # stack goes in after
        self.uc.reg_write(UC_M68K_REG_D0, d0)
        stack = STACK_TOP - 256
        self.uc.mem_write(stack, MAGIC.to_bytes(4, 'big'))
        self.uc.reg_write(UC_M68K_REG_A7, stack)
        self.uc.emu_start(self.base + entry, MAGIC, count=50_000_000)
        if self.uc.reg_read(UC_M68K_REG_PC) != MAGIC:
            return f'entry +{entry} did not return'
        for register, canary in self.CANARY.items():
            want = d0 if register == UC_M68K_REG_D0 else canary
            if self.uc.reg_read(register) != want:
                return f'entry +{entry} clobbered a register'
        return ''

    def frame(self):
        self.writes.clear()
        problem = self.call(8, 0xD0D0D0D0)
        pairs = []
        selected = None
        for address, value in self.writes:
            if address == PSG:
                selected = value
            elif address == PSG + 2 and selected is not None:
                pairs.append((selected, value))
        return problem, dict(pairs)


def run_sndh() -> str:
    """The SNDH container, end to end: two subtunes built by mksndh.sh, the
    blob loaded at an arbitrary even address, every entry preserving d0-a6,
    each subtune playing its own data, the machine state handed back at
    exit, and init-without-exit recovering by itself."""
    frames = 200
    sets = []
    for signature in (lambda f: (3 * f + 1) & 0xFF, lambda f: 0x55):
        values = [bytearray(frames) for _ in range(16)]
        for f in range(frames):
            values[2][f] = signature(f)
            values[13][f] = gen_ym.NO_ENVELOPE_CHANGE
        sets.append(pack(gen_ym.ym6_file(frames, values), 960, 24, 0, 2))
    for i, packed in enumerate(sets):
        (SCRATCH / f'sndh_tune{i + 1}.ymx').write_bytes(packed)
    out = SCRATCH / 'sndh_test.sndh'
    build = subprocess.run(['sh', str(YMX / 'mksndh.sh'), '-tRig',
                            str(out),
                            str(SCRATCH / 'sndh_tune1.ymx'),
                            str(SCRATCH / 'sndh_tune2.ymx')],
                           capture_output=True, text=True)
    if build.returncode:
        return 'sndh: build failed: ' + (build.stderr or build.stdout).strip()[:120]
    blob = out.read_bytes()
    if blob[12:16] != b'SNDH' or b'HDNS' not in blob[:256]:
        return 'sndh: the header is not an SNDH header'
    # the subtune-name tag: word offsets from the tag start to NUL strings
    sn = blob.index(b'!#SN')
    for i in range(2):
        at = sn + int.from_bytes(blob[sn + 4 + 2 * i:sn + 6 + 2 * i], 'big')
        name = blob[at:blob.index(0, at)].decode()
        if name != f'sndh_tune{i + 1}':
            return f'sndh: subtune {i + 1} is named {name!r}'

    player = Sndh(blob)
    # sentinels for everything init must save and exit must hand back
    player.uc.mem_write(0x134, (0xCAFE0134).to_bytes(4, 'big'))
    player.uc.mem_write(0x110, (0xCAFE0110).to_bytes(4, 'big'))
    for address, value in ((0xFFFFFA19, 3), (0xFFFFFA1D, 0x17),
                           (0xFFFFFA1F, 99), (0xFFFFFA25, 88),
                           (0xFFFFFA07, 0x21), (0xFFFFFA13, 0x20),
                           (0xFFFFFA09, 0x10), (0xFFFFFA15, 0x11)):
        player.uc.mem_write(address, bytes([value]))

    def signature(which, frame):
        return ((3 * frame + 1) & 0xFF) if which == 1 else 0x55

    def play_and_check(which, count=30):
        for f in range(count):
            problem, got = player.frame()
            if problem:
                return problem
            if got.get(2) != signature(which, f):
                return (f'sndh: subtune {which} frame {f} played '
                        f'{got.get(2)} want {signature(which, f)}')
        return ''

    problem = player.call(0, 1)                 # init subtune 1
    if problem:
        return 'sndh: ' + problem
    problem = play_and_check(1)
    if problem:
        return problem
    problem = player.call(4, 0xD0D0D0D0)        # exit
    if problem:
        return 'sndh: ' + problem
    for address, want in ((0x134, 0xCAFE0134), (0x110, 0xCAFE0110)):
        if int.from_bytes(player.uc.mem_read(address, 4), 'big') != want:
            return f'sndh: exit lost the vector at {address:#x}'
    for address, want in ((0xFFFFFA19, 3), (0xFFFFFA1F, 99), (0xFFFFFA25, 88),
                          (0xFFFFFA07, 0x21), (0xFFFFFA13, 0x20),
                          (0xFFFFFA09, 0x10), (0xFFFFFA15, 0x11)):
        if player.uc.mem_read(address, 1)[0] != want:
            return f'sndh: exit lost the register at {address:#x}'
    if player.uc.mem_read(0xFFFFFA1D, 1)[0] & 0x0F != 0x07:
        return 'sndh: exit lost Timer D\'s nibble'

    problem = player.call(0, 2)                 # subtune 2
    if problem:
        return 'sndh: ' + problem
    problem = play_and_check(2)
    if problem:
        return problem
    problem = player.call(0, 1)                 # init WITHOUT exit
    if problem:
        return 'sndh: ' + problem
    problem = play_and_check(1)
    if problem:
        return problem
    problem = player.call(0, 9)                 # out of range: subtune 1
    if problem:
        return 'sndh: ' + problem
    problem = play_and_check(1)
    if problem:
        return problem
    player.call(4, 0xD0D0D0D0)
    return ''


def Ymx_DRUM_TABLE(player) -> int:
    """The sample table's offset, straight from the packed file's header."""
    return int.from_bytes(player.uc.mem_read(player.file + 28, 4), 'big')


def check_assignment(player) -> str:
    """ymx_assign, driven directly: every map the T stream can express must put
    the right timer's row into the right channel's descriptor. The rows are
    the player's own, so this checks the copy and the two-bit decode, which
    is the whole of the mechanism."""
    sym = player.symbols
    rows = {}                                   # timer -> its row, as bytes
    for i, timer in enumerate(('a', 'b', 'c', 'd')):
        base = CODE + sym[f'ymx_timer_{timer}']
        rows[i] = bytes(player.uc.mem_read(base, 18))
    maps = (0x1B,          # 0->D 1->C 2->B 3->A: reversed
            0x00,          # every channel on Timer A, which the packer
                           # never emits but the copy must still do
            0xE4,          # 0->A 1->B 2->C 3->D: straight
            Ymx_DEFAULT_MAP)
    for assignments in maps:
        stack = STACK_TOP - 512
        player.uc.mem_write(stack, MAGIC.to_bytes(4, 'big'))
        player.uc.reg_write(UC_M68K_REG_SR, 0x2700)
        player.uc.reg_write(UC_M68K_REG_A7, stack)
        player.uc.reg_write(UC_M68K_REG_D0, assignments)
        player.uc.emu_start(CODE + sym['ymx_assign'], MAGIC, count=100_000)
        for channel in range(4):
            timer = (assignments >> (2 * channel)) & 3
            got = bytes(player.uc.mem_read(
                CODE + sym[f'ymx_desc_{channel}'], 18))
            if got != rows[timer]:
                return (f'assign: map {assignments:#04x} put the wrong row in'
                        f' channel {channel} (wanted timer {timer})')
    return ''


def invoke_isr(player, address):
    """Runs one tick handler to its rte, which this Unicorn build cannot
    execute - reaching it is the completed tick. Returns the chip writes."""
    stack = STACK_TOP - 512
    player.writes.clear()
    player.uc.reg_write(UC_M68K_REG_SR, 0x2600)
    player.uc.reg_write(UC_M68K_REG_A7, stack)
    try:
        player.uc.emu_start(address, MAGIC, count=1_000)
    except UcError:
        pass
    pc = player.uc.reg_read(UC_M68K_REG_PC)
    if bytes(player.uc.mem_read(pc, 2)) != b'\x4e\x73':
        raise AssertionError(f'the tick handler faulted at {pc:#x}')
    return player._decode_writes()


SHAPE_WIDTHS = [0, 2, 2, 2, 1, 1, 1, 1, 1, 2, 1, 1, 2, 1, 1, 2, 1, 1, 2, 1]


def ymr_image(frames: int, pops: list, streams: dict, loop: int = 0,
              samples: list = ()) -> bytes:
    """A .YMR v1.3 register dump with every stream stored uncompressed.

    The .ymr front end is the only source that sets the shape-from-R13 flag,
    so pinning the flag-set path needs a .YMR, and a hand-built one keeps the
    scene small enough to reason about. A ring size of 0 is the format's own
    "stored uncompressed", which lets this skip a ZX1 packer.

    pops[frame] is the stream indices that frame pops, ascending, and streams
    maps a stream index to its entries laid end to end. samples is a list of
    (levels, looped, loop_start) blocks, which sit between the map and the
    streams.
    """
    command = bytearray()
    for frame in range(frames):
        command += bytes(sorted(pops[frame])) + b'\0'
    present = dict(streams)
    present[0] = bytes(command)

    header = bytearray(b'YMR!')
    header += (0x0103).to_bytes(2, 'big') + frames.to_bytes(4, 'big')
    header += loop.to_bytes(4, 'big') + (50).to_bytes(2, 'big')
    header += len(samples).to_bytes(2, 'big') + (2000000).to_bytes(4, 'big')
    header += (20).to_bytes(2, 'big') + (0).to_bytes(4, 'big')

    blocks = bytearray()
    for levels, looped, start in samples:
        blocks += len(levels).to_bytes(4, 'big') + bytes(levels)
        blocks += bytes([1 if looped else 0]) + start.to_bytes(2, 'big') + b'\0'

    at = 268 + len(blocks)                          # the map, then the blocks
    body = bytearray()
    for stream in range(20):
        data = present.get(stream)
        if not data:
            header += bytes(12)                     # offset 0: not in the file
            continue
        header += at.to_bytes(4, 'big') + (0).to_bytes(4, 'big')
        header += (0).to_bytes(2, 'big') + (0).to_bytes(2, 'big')
        body += data
        at += len(data)
    assert len(header) == 268, len(header)
    return bytes(header + blocks + body)


def pack_ymr(image: bytes, ring: int = 960, chunk: int = 24) -> bytes:
    """The real .ymr packer, so the header flag is the one it really writes."""
    SCRATCH.mkdir(exist_ok=True)
    key = hashlib.sha1(image).hexdigest()[:12]
    cached = SCRATCH / f'ymr-{key}-n{ring}-c{chunk}.ymx'
    if not cached.exists():
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'tune.ymr'
            source.write_bytes(image)
            subprocess.run(['java', '-ea', '-cp', str(CLASSES), 'org.ymr.Ymr', '-f',
                            f'-n{ring}', f'-c{chunk}', '-k1', str(source), str(cached)],
                           check=True, capture_output=True)
    return cached.read_bytes()


def patched_shape(player, timer: str) -> int:
    """The byte the retrigger tick will write to R13, out of the running
    player's own code. It is a self-modified immediate inside the tick block,
    so nothing short of reading the instruction says what the buzzer will
    actually restart - and that is the whole of what this test is about."""
    return player.uc.mem_read(CODE + player.symbols[f'ymx_retrigger_{timer}'] + 4, 1)[0]


def run_shape_source() -> str:
    """Where a retrigger stream reads the shape it restarts.

    A sync-buzzer rewrites R13 with one shape at the timer's rate, and from
    the file CARRIES that shape rather than leaving the player to
    find it: the YM front end reads it out of the low nibble of the voice the
    channel runs on, and the .ymr front end out of R13, where RhYMe keeps it.
    The two are told apart here by making them
    DISAGREE - a voice whose nibble is one value while R13 holds another -
    and reading the tick's own patched immediate, which no chip write reveals
    and no other rig looks at.
    """
    # The flag-clear path, every YM tune. A buzzer on voice B with
    # R9's nibble at 11, and R13 never written: if the player were
    # reading the shadow it would restart 8, the value a tune that has
    # written no shape is taken to mean.
    frames = 16
    values = [bytearray(frames) for _ in range(16)]
    for frame in range(frames):
        values[7][frame] = 0x38
        values[9][frame] = 0x0B                     # voice B's level, nibble 11
        values[13][frame] = gen_ym.NO_ENVELOPE_CHANGE
    for frame in range(4, 12):                      # sync-buzzer, voice B
        values[1][frame] = 0xE0
        values[6][frame] |= 6 << 5
        values[14][frame] = 200
    player = Player(pack(gen_ym.ym6_file(frames, values), 960, 24, 0, 1),
                    workspace_size(960))
    if player.init() != 0:
        return 'shape source: YMX_init rejected the YM tune'
    for frame in range(6):
        player.frame()
    got = patched_shape(player, 'a')                # a YM tune's slot 1 is Timer A
    if got != 11:
        return (f'shape source: a YM buzzer restarts shape {got}, want 11 - the '
                f'nibble of the voice it runs on')

    # The flag-set path. R13 is popped to $0A on the very frame the RTE arms,
    # while voice B's level is $0C: the burst writes R13 before the actions
    # run, so the arm must take the NEW shape, 10, and not the 12 sitting in
    # the volume nibble. Frame 3 then moves the shape under the running
    # buzzer, which goes through the hold path rather than the arm.
    pops = [[] for _ in range(8)]
    pops[0] = [5, 7, 10, 14, 15]
    pops[3] = [10]
    image = ymr_image(8, pops, {
        5: bytes([0x38]),                           # mixer
        7: bytes([0x0C]),                           # voice B's level: nibble 12
        10: bytes([0x0A, 0x04]),                    # the shapes: 10, then 4
        14: bytes([3]),                             # Timer B runs an RTE
        15: bytes([6, 200]),                        # prescaler 6, count 200
    })
    player = Player(pack_ymr(image), workspace_size(960))
    if player.init() != 0:
        return 'shape source: YMX_init rejected the .ymr tune'
    player.frame()                                  # frame 0: R13 := 10, RTE arms
    got = patched_shape(player, 'b')                # channel 1 of a .ymr is Timer B
    if got != 10:
        return (f'shape source: a .ymr buzzer arms on shape {got}, want 10 - R13 '
                f'as this frame wrote it, not the 12 in the volume nibble')
    for frame in range(1, 4):                       # frame 3 pops the shape to 4
        player.frame()
    got = patched_shape(player, 'b')
    if got != 4:
        return (f'shape source: a shape moving under a running buzzer left the '
                f'tick on {got}, want 4 - the hold path reads R13 too')

    # And an RTE that arms before the tune has written any shape: the
    # spec says to assume 8, which is what RhYMe's own player primes. Voice
    # C's level is 15 here, so the two sources cannot be confused.
    pops = [[] for _ in range(8)]
    pops[0] = [5, 8, 17, 18]
    image = ymr_image(8, pops, {
        5: bytes([0x38]),
        8: bytes([0x1F]),                           # voice C: nibble 15
        17: bytes([3]),                             # Timer D runs an RTE
        18: bytes([6, 200]),
    })
    player = Player(pack_ymr(image), workspace_size(960))
    if player.init() != 0:
        return 'shape source: YMX_init rejected the unshaped .ymr tune'
    player.frame()
    got = patched_shape(player, 'd')                # channel 2 of a .ymr is Timer D
    if got != 8:
        return (f'shape source: an RTE armed before any shape restarts {got}, '
                f'want 8 - the assumed shape, not voice C\'s nibble')
    return ''


def run_sample_loop(perf: bool = False) -> str:
    """A looped sample, which the player loops rather than stopping.

    RhYMe holds a drum loop under a whole pattern, and a PCM tick that only
    walked forward would have to have the loop region written out again and
    again until some ceiling stopped it - so a song that held it longer would
    hear it die. The file carries the point the sample comes back to and the
    tick does the coming back, so the proof is the pointer: play
    the block out, and the tick after the marker must be reading the loop
    start rather than a stopped timer.
    """
    pops = [[] for _ in range(8)]
    pops[0] = [5, 7, 14, 15, 16]
    image = ymr_image(8, pops, {
        5: bytes([0x38]),                           # mixer
        7: bytes([0x0C]),                           # voice B's level
        14: bytes([2]),                             # Timer B runs a Sample
        15: bytes([6, 200]),                        # prescaler 6, count 200
        16: bytes([0]),                             # sample 0
    }, samples=[(bytes([1, 2, 3, 4]), True, 1)])    # four levels, back to [1]
    player = Player(pack_ymr(image), workspace_size(960), perf=perf)
    if player.init() != 0:
        return 'sample loop: YMX_init rejected the tune'
    player.frame()                                  # frame 0 starts the sample

    table = Ymx_DRUM_TABLE(player)
    offset = int.from_bytes(player.uc.mem_read(player.file + table, 4), 'big')
    loop = int.from_bytes(player.uc.mem_read(player.file + table + 6, 2), 'big')
    if loop != 1:
        return f'sample loop: the file stores loop point {loop}, want 1'

    code = CODE + player.symbols['ymx_pcm_b']
    register = 9                                    # voice B's volume, which
    for tick, value in enumerate((1, 2, 3, 4)):     # the tick selects itself
        pairs = invoke_isr(player, code)
        if pairs != [(register, value)]:
            return f'sample loop: tick {tick} wrote {pairs}, want level {value}'

    # The marker tick. It has already written the marker as a level - one
    # sample of silence - but it must NOT stop the timer, and must leave the
    # pointer on the loop start rather than past the end.
    at = len(player.mfp)
    pairs = invoke_isr(player, code)
    if pairs != [(register, 0x80)]:
        return f'sample loop: the marker tick wrote {pairs}, want the marker alone'
    TBCR = 0xFFFFFA1B                               # every tick ends with an
    stopped = [w for w in player.mfp[at:] if w[0] == TBCR]   # EOI; only a stop
    if stopped:                                     # touches the control
        return (f'sample loop: the marker tick programmed {stopped} - a looping'
                f' stream stops nothing')
    # The tick is reached, never returned from - this Unicorn build cannot
    # run an rte - so a stack left unbalanced is invisible unless it is read
    # off directly. The loop leaves the tick by a different door than the
    # stop does, and the PERF build has a colour band stacked at that point.
    left = player.uc.reg_read(UC_M68K_REG_A7)
    if left != STACK_TOP - 512:
        return (f'sample loop: the marker tick reached its rte with the stack '
                f'{STACK_TOP - 512 - left:+d} bytes off')
    at = int.from_bytes(player.uc.mem_read(
        code + player.symbols['ISR_PCM_PTR'], 4), 'big')
    if at != player.file + offset + 1:
        return (f'sample loop: after the marker the tick reads {at:#x}, want '
                f'{player.file + offset + 1:#x} - the loop start')

    for tick, value in enumerate((2, 3, 4)):        # round it goes again
        pairs = invoke_isr(player, code)
        if pairs != [(register, value)]:
            return f'sample loop: pass 2 tick {tick} wrote {pairs}, want {value}'
    return ''


def run_live_retune() -> str:
    """A rate pop under a running effect, done without stopping it.

    RhYMe reprograms a RUNNING timer - control register, then data - so a
    rate slide bends what is playing instead of restarting it. The verb is
    RETUNE addressed to voice 3, no such voice being the one free corner of
    the action byte. What makes it different is only visible in the MFP
    traffic: the ordinary retune stops the timer and runs it again, and this
    must never write a zero into the timer's nibble.
    """
    pops = [[] for _ in range(6)]
    pops[0] = [5, 7, 14, 15]
    pops[3] = [15]                                  # the rate alone moves
    image = ymr_image(6, pops, {
        5: bytes([0x38]),
        7: bytes([0x0C]),                           # voice B's level, unmoved
        14: bytes([1]),                             # Timer B runs a PWM
        15: bytes([6, 200, 5, 200]),                # prescaler 6 -> 5
    })
    player = Player(pack_ymr(image), workspace_size(960))
    if player.init() != 0:
        return 'live retune: YMX_init rejected the tune'
    for frame in range(3):
        player.frame()
    player.mfp.clear()
    player.frame()                                  # frame 3: the rate pop
    ctrl, data = 0xFFFFFA1B, 0xFFFFFA21             # Timer B's control, data
    written = [w for w in player.mfp if w[0] in (ctrl, data)]
    if len(written) != 2 or written[0][0] != ctrl or written[1][0] != data:
        return (f'live retune: frame 3 touched the MFP as {written}, want the '
                f'control register then the data register, once each')
    if written[0][1] & 7 == 0:
        return ('live retune: the control write stopped the timer - the whole '
                'point is that it never stops')
    if written[0][1] & 7 != 5 or written[1][1] != 200:
        return (f'live retune: frame 3 programmed prescaler {written[0][1] & 7}'
                f' count {written[1][1]}, want 5 and 200')
    return ''


class Reworded(Exception):
    """A sentence this rig reads numbers out of has been reworded."""

def corpus_tune(name: str):
    """Where a tune the documentation measures can be found, or None.

    The tunes are not in the tree. YMX_CORPUS says which directory holds
    them; without it, the home directory and the repository root are tried.
    """
    named = os.environ.get('YMX_CORPUS')
    for where in ([named] if named else [Path.home(), REPO]):
        if (Path(where) / name).exists():
            return Path(where) / name
    return None


def packer_report(tune: Path, extra: tuple = ()) -> str:
    """The packer's own report for one tune, as the documentation quotes it."""
    out = SCRATCH / (tune.stem + '.ymx')
    SCRATCH.mkdir(exist_ok=True)
    run = subprocess.run(['java', '-ea', '-cp', str(CLASSES), 'org.ymr.Ymr', '-f',
                          *extra, str(tune), str(out)],
                         capture_output=True, text=True)
    if run.returncode:
        raise AssertionError(f'packing {tune.name}: {run.stdout}{run.stderr}')
    return run.stdout


def script_verbs(tune: Path) -> dict:
    """Verb counts from one tune's compiled script.

    A RETUNE addressed to voice 3 is the live retune, one to a real voice
    stops the timer to reprogram it, and a HOLD with bit 0 reloads the count
    under a running one - the three the conversion account counts.
    """
    run = subprocess.run(['java', '-ea', '-cp', str(CLASSES), 'org.ymr.Ymr',
                          '-script', str(tune)], capture_output=True, text=True)
    if run.returncode:
        raise AssertionError(f'compiling {tune.name}: {run.stdout}{run.stderr}')
    counts = {'live retune': 0, 'stopping retune': 0, 'live reload': 0}
    for action in re.findall(r'A[0-3]=([0-9A-F]{2})', run.stdout):
        byte = int(action, 16)
        verb, voice, flags = byte >> 5, (byte >> 3) & 3, byte & 7
        if verb == 4:
            counts['live retune' if voice == 3 else 'stopping retune'] += 1
        elif verb == 1 and flags & 1:
            counts['live reload'] += 1
    return counts


def run_conversion_numbers() -> str:
    """The figures ymr/CONVERSION.md quotes, against the tunes it names.

    Every one of them is a measurement, and a measurement in prose goes stale
    the first time the packer changes. They are re-measured the way the
    document says they were taken: the packer's own report for the byte
    counts, and the compiled script for the verb counts.
    """
    flat = ' '.join((REPO / 'ymr' / 'CONVERSION.md').read_text().split())

    def said(pattern, what):
        found = re.search(pattern, flat)
        if not found:
            raise Reworded(f'conversion numbers: the sentence giving {what} no '
                           f'longer matches {pattern!r} - this check reads them '
                           f'out of it')
        return [int(g.replace(',', '')) if g.replace(',', '').isdigit() else g
                for g in found.groups()]

    try:
        tune, frames, rate = said(r'`([\w.-]+\.ymr)` is ([\d,]+) frames at (\d+) Hz',
                                  'the tune and its length')
        total, packed, _, size = said(
            r'reports ([\d,]+) bytes of register and script data packed into '
            r'([\d,]+) \(([\d,.]+)%\) in a ([\d,]+)-byte file',
            'the packed sizes')
        rings, ring, decoded, streams = said(
            r'([\d,]+) rings of ([\d,]+) bytes, decoding (\d+) of the (\d+) streams',
            'the ring shape')
        rotation, = said(r'rotated the split forward ([\d,]+) frames', 'the rotation')
        script, of, image = said(
            r'([\d,]+) of those ([\d,]+) packed bytes are the eleven script '
            r'streams, which the `\.YMR` — ([\d,]+) bytes', 'the script streams')
        reloads, retunes = said(
            r'on `' + re.escape(tune) + r'` the compiled script carries ([\d,]+) '
            r'live reloads and ([\d,]+) live retunes against no verb that stops',
            'the verb counts')
        other, otherRetunes, otherStops = said(
            r'`([\w.-]+\.ymr)` has ([\d,]+) live retunes and (\d+) that stop',
            "the second tune's verb counts")
    except Reworded as reworded:
        return str(reworded)

    paths = {name: corpus_tune(name) for name in (tune, other)}
    missing = [name for name, path in paths.items() if path is None]
    if missing:
        return ('SKIP ' + ', '.join(missing) + ' not found - set YMX_CORPUS to the '
                'directory holding the tunes ymr/CONVERSION.md measures')

    report = packer_report(paths[tune])
    measured = {}
    got = re.search(r'Packed (\d+) register bytes into (\d+) \([\d,.]+%\), '
                    r'file (\d+) bytes', report)
    if not got:
        return 'conversion numbers: the packer no longer reports its packed sizes'
    measured['register bytes'] = (int(got.group(1)), total)
    measured['packed bytes'] = (int(got.group(2)), packed)
    measured['file bytes'] = (int(got.group(3)), size)

    shape = re.search(r'Player needs (\d+) bytes of ring .*? decodes (\d+) of the '
                      r'(\d+) streams', report)
    if not shape:
        return 'conversion numbers: the packer no longer reports its ring shape'
    measured['ring bytes'] = (int(shape.group(1)), rings * ring)
    measured['streams decoded'] = (int(shape.group(2)), decoded)
    measured['streams stored'] = (int(shape.group(3)), streams)

    rotated = re.search(r'loop split rotated by (\d+) frames', report)
    measured['rotation'] = (int(rotated.group(1)) if rotated else 0, rotation)

    # the eleven script streams, summed out of the packer's per-stream listing
    measured['script bytes'] = (sum(
        int(size_) for name_, size_ in
        re.findall(r'^\s+(M|X|T|A[0-3]|P[0-3])\s+\w+\s+\d+\s+->\s+(\d+) bytes',
                   report, re.M)), script)
    measured['packed bytes, again'] = (int(got.group(2)), of)
    measured['.YMR bytes'] = (paths[tune].stat().st_size, image)
    measured['source frames'] = (
        max(int(n) for n in re.findall(r'^\s+\w+\s+loop\s+(\d+)', report, re.M)),
        frames)

    verbs = script_verbs(paths[tune])
    measured['live reloads'] = (verbs['live reload'], reloads)
    measured['live retunes'] = (verbs['live retune'], retunes)
    measured['stopping retunes'] = (verbs['stopping retune'], 0)
    second = script_verbs(paths[other])
    measured[f'{other} live retunes'] = (second['live retune'], otherRetunes)
    measured[f'{other} stopping retunes'] = (second['stopping retune'], otherStops)

    wrong = [f'{what} {is_:,} not {said_:,}'
             for what, (is_, said_) in measured.items() if is_ != said_]
    if wrong:
        return 'conversion numbers: ' + '; '.join(wrong)
    return ''


def run_readme_sizes() -> str:
    """The README's two byte counts, against what the assembler just produced.

    A size written into prose goes stale the first time the code moves, and
    nothing else would notice. Both numbers come out of one build: YMX.S runs
    from the start of the binary to ST4_wrap.S's first symbol, and ST4_wrap.S
    is the rest of it. The README gives the ST4_UNIT they hold for, because
    the decoder's size moves with it.
    """
    text = (REPO / 'README.md').read_text()
    player_said = re.search(r'is the player, ([\d,]+) bytes at the '
                            r'`ST4_UNIT` (\d)', text)
    wrap_said = re.search(r'plus the ([\d,]+) of \[68k/ST4_wrap\.S\]', text)
    if not player_said or not wrap_said:
        return ('README sizes: the sentence carrying them has been reworded. '
                'It must still read "is the player, N bytes at the `ST4_UNIT` '
                'k" and "plus the M of [68k/ST4_wrap.S]", which is what this '
                'check reads them out of')
    unit = int(player_said.group(2))
    binary, symbols = assemble(unit)
    player = symbols['ST4_init']        # where YMX.S stops and the decoder starts
    wrap = len(binary) - player
    said = (int(player_said.group(1).replace(',', '')),
            int(wrap_said.group(1).replace(',', '')))
    if said != (player, wrap):
        return (f'README sizes: it says {said[0]:,} + {said[1]:,} bytes at '
                f'ST4_UNIT {unit}; this build is {player:,} + {wrap:,}')
    return ''


def main() -> int:
    # frames, ring, chunk, label, loop frame (None = play once), passes, unit
    shapes = [
        (600, 960, 24, 'default 960/24', 0, 1),
        (600, 960, 24, 'plays once', None, 0),
        (600, 960, 24, 'loops from frame 397', 397, 2),
        (600, 240, 24, 'small ring 240/24', 0, 1),
        (600, 48, 24, 'two-group ring 48/24', 128, 1),
        (600, 960, 64, 'long calls 960/64', 401, 1),
        (608, 34, 17, 'tightest legal 34/17', 13, 1),
        (37, 960, 24, 'shorter than a ring', 5, 3),
        (40, 960, 24, 'loop shorter than a group', 35, 4),
        (24, 960, 24, 'exactly one group', 0, 2),
        (9, 960, 24, 'shorter than one group', 0, 3),
        (1, 960, 24, 'a single frame', 0, 5),
        (1, 960, 24, 'a single frame, once', None, 0),
        # Wider units: cheaper refills, and the packer's whole-unit rules for
        # the tune length, the loop frame and C must hold. The decoder is a
        # different build for each.
        (600, 960, 24, 'unit 2, loops at 398', 398, 2, 2),
        (600, 960, 24, 'unit 2, plays once', None, 0, 2),
        (600, 960, 24, 'unit 4, loops at 396', 396, 1, 4),
    ]
    if not QUICK:
        shapes.append((4000, 960, 24, 'four thousand frames', 1234, 1))
        shapes.append((4000, 2048, 32, 'four thousand, 2048/32', 0, 1))
        shapes.append((4000, 960, 24, 'four thousand, unit 2', 1234, 1, 2))
        shapes.append((4000, 2048, 32, 'four thousand, unit 4', 0, 1, 4))

    failures = 0
    for shape in shapes:
        frames, ring, chunk, label, loop, passes = shape[:6]
        unit = shape[6] if len(shape) > 6 else 1
        problem = run_shape(frames, ring, chunk, label, loop, passes, unit)
        if problem:
            print(f'FAIL {problem}')
            failures += 1
        else:
            where = 'plays once' if loop is None else f'loops at {loop}'
            print(f'OK   {label:26s} ({frames} frames, {ring}-byte rings, {where})')

    problem = run_sndh()
    if problem:
        print(f'FAIL {problem}')
        failures += 1
    else:
        print('OK   the SNDH container       (subtunes, handback, re-init)')

    problem = run_shape_source()
    if problem:
        print(f'FAIL {problem}')
        failures += 1
    else:
        print('OK   the retrigger shape      (both sources, off the patched tick)')

    for perf in (False, True):
        problem = run_sample_loop(perf)
        # The PERF build stacks a colour band on the way in, so a loop that
        # leaves by a different door than the stop does has to put it back.
        if problem:
            print(f'FAIL {"PERF build: " if perf else ""}{problem}')
            failures += 1
        else:
            build = ', PERF build' if perf else ''
            print(f'OK   the sample loop{build:9s}    '
                  f'(back to the loop, not stopped)')

    problem = run_live_retune()
    if problem:
        print(f'FAIL {problem}')
        failures += 1
    else:
        print('OK   the live retune          (the timer is never stopped)')

    problem = run_readme_sizes()
    if problem:
        print(f'FAIL {problem}')
        failures += 1
    else:
        print('OK   the README sizes         (the two byte counts, measured)')

    problem = run_conversion_numbers()
    if problem.startswith('SKIP'):
        print(problem)
    elif problem:
        print(f'FAIL {problem}')
        failures += 1
    else:
        print('OK   the conversion numbers   (ymr/CONVERSION.md, re-measured)')

    for super_host, perf in ((False, False), (True, False), (False, True)):
        problem = run_effects(super_host, perf)
        build = 'USP a0' if super_host else 'PERF build' if perf else ''
        if problem:
            print(f'FAIL {build}: {problem}' if build else f'FAIL {problem}')
            failures += 1
        else:
            label = 'the effect stage' + (', ' + build if build else '')
            print(f'OK   {label:26s} (timers, sanitize, mixer, skeleton)')

    print('ALL YMX PLAYER TESTS PASS' if not failures else f'{failures} FAILURES')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
