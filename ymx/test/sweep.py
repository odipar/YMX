"""Corpus sweep: pack each tune and verify the player's chip writes against
the YM truth, frame by frame, in the Unicorn rig.

    python3 ymx/test/sweep.py song.ym [more.ym ...]

Each tune is packed at k=1 - no padding, so the YM registers are the exact
expectation - then played through the real 68000 player under emulation.
Every chip write is compared against the masked YM data, R13's hold/shape
semantics included, the loop crossing exercised for tunes up to 3000 frames
(longer ones play their first 1200). The v2 split rotation is replayed here:
a rotated tune's played timeline shows some source frames twice, and the
walk follows the same map the packer compiled.

The effect-owned registers are checked against an INDEPENDENT model of the
script semantics, written here in Python: a skipped voice's volume register
must be absent from the frame's writes, an open one exact; R7 must carry
exactly the baked mixer force of the drums the model says are running. The
model recomputes drum windows - downsample factors, durations, retriggers,
cuts, arbitration - from the YM data alone, so a packer bug and a player
bug cannot cancel out. The tick handlers' own audio is not rendered here;
that side is the directed effect test's. Needs mvn compile, rmac and
unicorn, like the rigs.

One status line per tune: OK, ISSUE, PACKFAIL or SKIP.
"""
import math, subprocess, sys, struct, tempfile, os

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
EMU = os.path.join(REPO, 'ymx', 'test', 'emu')
CLASSES = os.path.join(REPO, 'target', 'classes')
WORK = os.path.join(EMU, '.work')
sys.path.insert(0, EMU)
os.chdir(EMU)
import test_ymx as T

MASK = [0xFF, 0x0F, 0xFF, 0x0F, 0xFF, 0x0F, 0x1F, 0x3F,
        0x1F, 0x1F, 0x1F, 0xFF, 0xFF, 0xFF]
PREDIV = [0, 4, 10, 16, 50, 64, 100, 200]
MFP_CLOCK = 2457600
MAX_HZ = 25600

def dumper():
    """Compiles DumpYm.java into the rig's work directory once."""
    java = os.path.join(REPO, 'ymx', 'test', 'DumpYm.java')
    klass = os.path.join(WORK, 'DumpYm.class')
    if not os.path.exists(klass) or os.path.getmtime(klass) < os.path.getmtime(java):
        subprocess.run(['javac', '-cp', CLASSES, '-d', WORK, java], check=True)
    return WORK


def read_ym(path):
    """The YM registers and drum lengths, via the Java reader."""
    out = subprocess.run(['java', '-cp', CLASSES + ':' + dumper(), 'DumpYm', path],
                         capture_output=True)
    if out.returncode:
        err = out.stderr.decode().strip().splitlines()
        msg = next((l for l in err if 'Exception' in l), err[-1] if err else 'reader failed')
        raise ValueError(msg.split(':', 1)[-1].strip()[:70])
    raw = out.stdout
    fmt, frames, drums, hz = struct.unpack('>IIII', raw[:16])
    regs = [raw[16 + r*frames: 16 + (r+1)*frames] for r in range(16)]
    at = 16 + 16 * frames
    lengths = list(struct.unpack(f'>{drums}I', raw[at:at + 4*drums])) if drums else []
    return fmt, frames, drums, hz, regs, lengths


def slot_codes(fmt, regs, f):
    """The two slots' (code, prescaler, count) for source frame f, both
    dialects, exactly as the packer normalizes them."""
    if fmt == 6:
        return [(regs[1][f] & 0xF0, (regs[6][f] >> 5) & 7, regs[14][f]),
                (regs[3][f] & 0xF0, (regs[8][f] >> 5) & 7, regs[15][f])]
    return [((regs[1][f] & 0x30), (regs[6][f] >> 5) & 7, regs[14][f]),
            (0x40 | (regs[3][f] & 0x30) if regs[3][f] & 0x30 else 0,
             (regs[8][f] >> 5) & 7, regs[15][f])]


def validate(code, tp, tc, regs, f, drums, scale):
    """The packer's drop rules: returns the effective (code, divisor) or
    None when the slot is idle this frame."""
    v = (code >> 4) & 3
    if v == 0 or tp == 0 or tc == 0:
        return None
    kind = code & 0xC0
    if kind == 0x80:
        return None                     # sinus: never packs
    if kind == 0x40:
        n = regs[8 + v - 1][f] & 0x1F
        if n >= drums:
            return None                 # missing drum
        num, den = scale[n]
        return code, PREDIV[tp] * tc * num // den
    if MFP_CLOCK // (PREDIV[tp] * tc) > MAX_HZ:
        return None                     # too-fast SID/buzzer
    return code, PREDIV[tp] * tc


def representable(divisor):
    return any(divisor % PREDIV[p] == 0 and 1 <= divisor // PREDIV[p] <= 255
               for p in range(1, 8))


def ceiling_divisor():
    """The smallest representable divisor at or under the rate ceiling."""
    needed = -(-MFP_CLOCK // MAX_HZ)
    return min(PREDIV[p] * -(-needed // PREDIV[p]) for p in range(1, 8)
               if -(-needed // PREDIV[p]) <= 255)


def drum_scales(fmt, frames, drums, regs):
    """Each drum's divisor scale num/den, mirroring the packer: resample to
    the highest representable rate under the ceiling when every trigger
    takes the exact ratio, the power-of-two factor otherwise."""
    seen = [set() for _ in range(drums)]
    for f in range(frames):
        for code, tp, tc in slot_codes(fmt, regs, f):
            if (code & 0xC0) != 0x40 or (code & 0x30) == 0:
                continue
            if tp == 0 or tc == 0:
                continue
            n = regs[8 + ((code >> 4) & 3) - 1][f] & 0x1F
            if n >= drums:
                continue
            seen[n].add(PREDIV[tp] * tc)
    scale = [(1, 1)] * drums
    for n in range(drums):
        if not seen[n]:
            continue
        fastest = min(seen[n])
        if MAX_HZ * fastest >= MFP_CLOCK:
            continue
        target = ceiling_divisor()
        g = math.gcd(target, fastest)
        num, den = target // g, fastest // g
        if all(d * num % den == 0 and representable(d * num // den)
               for d in seen[n]):
            scale[n] = (num, den)
        else:
            f2 = 1
            while MAX_HZ * fastest * f2 < MFP_CLOCK and f2 < 64:
                f2 *= 2
            scale[n] = (f2, 1)
    return scale


class Model:
    """An independent replay of the script semantics: which voices are
    skipped and which drums force the mixer, per played frame. The same
    decision rules as the packer's simulator, written a second time so the
    two implementations check each other through the player in between."""

    def __init__(self, fmt, frames, drums, hz, regs, lengths):
        self.fmt, self.frames, self.drums = fmt, frames, drums
        self.hz, self.regs = hz, regs
        self.scale = drum_scales(fmt, frames, drums, regs)
        self.lengths = [max(1, lengths[n] * self.scale[n][1] // self.scale[n][0])
                        if n < len(lengths) else 1 for n in range(drums)]
        self.elast = [0, 0]
        self.owner = [-1, -1, -1]       # per voice: the owning slot
        self.left = [0, 0, 0]           # frames until the skip lifts;
        self.skipped = 0                  # -1 = stuck (a cut drum)
        self.silenced = 0               # voices whose SID starts this frame
        STUCK = None

    def step(self, f):
        """Advances one played frame showing source frame f; returns
        (skipped_mask, forced_mask, silenced_mask) for the frame's writes.

        A voice in the silenced mask is one whose SID starts this frame:
        the ym2149-rs gap model writes its volume register to zero before
        installing the loud half, so a skipped voice leaves exactly that
        one write instead of none."""
        regs, drums = self.regs, self.drums
        self.silenced = 0
        for v in range(3):
            if self.owner[v] >= 0 and self.left[v] > 0:
                self.left[v] -= 1
                if self.left[v] == 0:
                    self.owner[v] = -1
                    self.skipped &= ~(1 << v)
        for slot in (0, 1):
            code, tp, tc = slot_codes(self.fmt, regs, f)[slot]
            valid = validate(code, tp, tc, regs, f, drums, self.scale)
            if valid is None:
                code = 0
            if code == self.elast[slot]:
                if code and (code & 0xC0) == 0x40:
                    self._drum(slot, code, valid[1], f)     # retrigger
                continue
            old, self.elast[slot] = self.elast[slot], code
            if code == 0:
                if (old & 0xC0) == 0x00 and old:
                    self.skipped &= ~(1 << (((old >> 4) & 3) - 1))
                if (old & 0xC0) != 0x40:
                    self._cut(slot)
                continue
            v = ((code >> 4) & 3) - 1
            kind = code & 0xC0
            if kind == 0x00:                                # SID
                if self.owner[v] >= 0:
                    self.elast[slot] = 0                    # suppressed
                    if (old & 0xC0) == 0x00 and old:
                        self.skipped &= ~(1 << (((old >> 4) & 3) - 1))
                    continue
                if (old & 0xC0) == 0x00 and old:
                    self.skipped &= ~(1 << (((old >> 4) & 3) - 1))
                self._cut(slot)
                self.skipped |= 1 << v
                self.silenced |= 1 << v         # SID_START silences first
            elif kind == 0x40:                              # drum
                if (old & 0xC0) == 0x00 and old:
                    self.skipped &= ~(1 << (((old >> 4) & 3) - 1))
                elif (old & 0xC0) == 0x40 and old and ((old ^ code) & 0x30):
                    o = ((old >> 4) & 3) - 1
                    if self.owner[o] == slot:
                        self.owner[o] = -1                  # orphan cleanup
                        self.left[o] = 0
                        self.skipped &= ~(1 << o)
                other = self.elast[1 - slot]
                if (other & 0xC0) == 0x00 and other \
                        and ((other >> 4) & 3) - 1 == v:
                    self.elast[1 - slot] = 0                # arbitration
                self._cut(slot, skip=v)
                self._drum(slot, code, valid[1], f)
            else:                                           # buzzer
                self._cut(slot)
        forced = 0
        for v in range(3):
            if self.owner[v] >= 0:
                forced |= 1 << v
        return self.skipped, forced, self.silenced

    def _drum(self, slot, code, divisor, f):
        v = ((code >> 4) & 3) - 1
        n = self.regs[8 + v][f] & 0x1F
        ticks = self.lengths[n] + 1
        # the packer's duration(): a sixteenth of a frame covers the arming
        # phase; the old whole spare frame was the reopen click
        frames = -(-(ticks * divisor * self.hz + MFP_CLOCK // 16) // MFP_CLOCK)
        self.owner[v] = slot
        self.left[v] = frames
        self.skipped |= 1 << v

    def _cut(self, slot, skip=-1):
        """A program on this slot's timer: a drum it still owes ticks to is
        cut, its marker never runs, its voice stays skipped - v1 semantics."""
        for v in range(3):
            if v != skip and self.owner[v] == slot and self.left[v] > 0:
                self.left[v] = -1                           # stuck


# Extra packer options, for verifying a shape the corpus never asks for:
#   YMX_PACK_OPTIONS='-timersBC' python3 ymx/test/sweep.py one.ym
PACK_OPTIONS = os.environ.get('YMX_PACK_OPTIONS', '').split()


def sweep(path):
    name = os.path.basename(path)
    try:
        fmt, frames, drums, hz, regs, lengths = read_ym(path)
    except ValueError as e:
        return f'SKIP {name}: {e}'
    with tempfile.NamedTemporaryFile(suffix='.ymx', delete=False) as tf:
        ymx = tf.name
    try:
        out = subprocess.run(['java', '-cp', CLASSES, 'org.ym6.Ymx',
                              '-f', '-k1', *PACK_OPTIONS, path, ymx],
                             capture_output=True, text=True)
        if out.returncode:
            return f'PACKFAIL {name}: {(out.stderr or out.stdout).strip().splitlines()[-1][:70]}'
        warns = [l for l in out.stdout.splitlines()
                 if 'Warning' in l or 'rotated' in l or 'muted' in l]
        packed = open(ymx, 'rb').read()
        ring = struct.unpack('>H', packed[16:18])[0]
        header_frames = struct.unpack('>I', packed[8:12])[0]
        split = struct.unpack('>I', packed[20:24])[0]
        loops = struct.unpack('>H', packed[6:8])[0] & 1
        rotation = header_frames - frames               # the played surplus
        loop_frame = split - rotation                   # the musical L
        player = T.Player(packed, T.YMX_FIXED + T.STREAMS * ring)
        if player.init() != 0:
            return f'INITFAIL {name}'
        model = Model(fmt, frames, drums, hz, regs, lengths)
        strict = (0, 1, 2, 3, 4, 5, 6, 11, 12)
        budget = frames + 200 if frames <= 3000 else 1200
        wrapped = False
        played = 0
        cycle = header_frames - split
        for f in range(budget):
            # The played timeline: the packer's split rotation replayed.
            p = played if played < header_frames else \
                split + (played - split) % cycle if cycle else header_frames - 1
            src = p if p < frames else loop_frame + (p - frames)
            result, writes = player.frame()
            if result == -1:
                if f < frames:
                    return f'ISSUE {name}: ended early at frame {f}/{frames}'
                break
            if result == 1:
                wrapped = True
            skipped, forced, silenced = model.step(src)
            got = dict(writes)
            for r in strict:
                want = regs[r][src] & MASK[r]
                if r == 7:
                    want |= (forced | forced << 3)
                if got.get(r) != want:
                    return (f'ISSUE {name}: frame {f} R{r} wrote '
                            f'{got.get(r)} want {want}')
            for v in range(3):
                r = 8 + v
                if silenced & (1 << v):
                    # the SID start's own silence write, then the loud half
                    if got.get(r) != 0:
                        return (f'ISSUE {name}: frame {f} started a SID on '
                                f'voice {"ABC"[v]} without silencing R{r} '
                                f'(wrote {got.get(r)})')
                elif skipped & (1 << v):
                    if r in got:
                        return (f'ISSUE {name}: frame {f} wrote R{r} '
                                'while it was skipped')
                else:
                    want = regs[r][src] & MASK[r]
                    if got.get(r) != want:
                        return (f'ISSUE {name}: frame {f} R{r} wrote '
                                f'{got.get(r)} want {want}')
            r13 = regs[13][src] & 0xFF
            if r13 == 0xFF and 13 in got:
                return f'ISSUE {name}: frame {f} wrote held R13'
            if r13 != 0xFF and got.get(13) != (r13 & 0x0F):
                return (f'ISSUE {name}: frame {f} R13 {got.get(13)} '
                        f'want {r13 & 0x0F}')
            played += 1
            if not loops and played == frames:
                break
        loop = 'looped' if wrapped else 'partial' if frames > 3000 else 'once'
        w = (' [' + '; '.join(warns)[:60] + ']') if warns else ''
        return f'OK {name} ({min(budget, frames + 200)}f {loop}){w}'
    except AssertionError as e:
        return f'ISSUE {name}: {e}'
    finally:
        os.unlink(ymx)

if __name__ == '__main__':
    for p in sys.argv[1:]:
        print(sweep(p), flush=True)
