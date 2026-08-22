"""Corpus sweep for the .ymr front end: pack each RhYMe register dump and
verify the real player's chip writes against the .YMR truth, frame by frame,
in the Unicorn rig.

    python3 ymx/test/ymr_sweep.py song.ymr [more.ymr ...]

Each tune is packed at k=1 - no padding frames, so the .YMR's own frames are
the exact expectation - then played through the real 68000 player under
emulation, exactly as sweep.py plays a .ym. The v2 split rotation is replayed
here too: a rotated tune's played timeline shows some source frames twice, and
the walk follows the same map the packer compiled. This one takes that map
from the PACKED FILE's header - O at offset 8, the split at 20, the loop flag
at 6 - rather than recomputing it, because the header is the contract the
player itself reads and a rotation the file and the player disagreed about
would show up as a wrong frame here anyway.

The truth side is an INDEPENDENT model of the .YMR image, written in this
file from the format spec: its own ZX1 decoder, its own stream map walk, its
own replay of the command stream. Independence is the whole point - the Java
reader and the 68000 player must not be able to cancel each other's bugs out -
so nothing here calls org.ymr except the packer under test, and a stream that
this decoder and the Java one disagree about lands as a wrong register value
on the frame it first matters.

What is checked, frame by frame:

  * R0-R6 and R11-R12 exactly, masked the way a YM2149 masks them.
  * R7 as the .YMR's mixer with the ST's two port-direction bits and NOTHING
    ELSE. The .ymr front end turns forceMixerOnPcm off - RhYMe's engine
    already bakes the mixer a sample needs into the exported mixer stream -
    so any bit beyond $C0 that the .YMR did not ask for is a divergence.
  * R13 as an EVENT, not only a value: the .YMR writes R13 on a frame that
    pops envelope_shape and on no other, so the register must be written
    exactly once on a pop frame and not at all on a held one.
  * R8/R9/R10 against the skips. A voice running PWM or Sample is SKIPPED - the
    same rule ymr_write_register applies in lib_data.s, and the player
    implements it by muting the voice's burst write - so its volume register
    must be ABSENT from that frame's writes. A voice running RTE or nothing
    is not skipped and its value must be exact. The one frame in between is a
    PWM's first: a fresh square restarts at phase zero, so the player writes
    that voice silent itself, after the burst and past the skipped write,
    and exactly that one write of zero is expected there.
  * The MFP: the player must claim exactly the timers the .YMR uses - A, B
    and D, never C - and a channel the tune leaves idle must claim nothing.

WHAT IS EXCLUDED, and why. This test checks one thing: the writes YMX_play
makes. Everything below is outside that window, and is listed here rather than
quietly skipped.

  * The tick handlers' own audio. A PWM's square, an RTE's R13 rewrites and a
    sample's bytes are written by MFP interrupt handlers the rig never runs -
    it calls YMX_play and nothing else - so none of them is observed. That
    side is the directed effect test's (run_effects in emu/test_ymx.py).
  * Nothing about a volume register under a running RTE. The
    retrigger stream's shape travels in the script, so the front end writes
    nothing over that byte and it is the .YMR's own on every frame: it is
    compared whole, like any other open register, stricter than the
    half-comparison it replaced. What is NOT checked is the shape itself,
    since the value only reaches the chip through a tick handler this rig
    never runs - the last value envelope_shape popped, or $08 before the song
    has popped one - a claim about the file rather than about the converter.
  * R14 and R15. They are the chip's I/O ports, a .YMR has no stream for
    them, and the rig faults on a write above R13 in any case.
  * A timer's data register as read back. It reads as the count the timer has
    reached rather than the value last written, so only the WRITES are used.
  * Which verb the script chose for an edge, and what the timer was
    programmed to. Those are the effect test's; here the timers are checked
    only for who claimed them.
  * Everything past the frame cap - the first 1200 frames of a long tune, the
    same budget sweep.py plays, which leaves the frames the split rotation
    added and the wrap after them unwalked on a long tune. YMR_FRAME_CAP
    raises it, and the cap is printed on the status line along with the
    boundaries it crossed, so a tune whose only interesting frame is past it
    says so rather than reading OK on nothing.

One status line per tune: OK, ISSUE, PACKFAIL or SKIP. A non-zero exit on any
ISSUE. Needs mvn compile, rmac and unicorn, like the rigs.
"""
import collections, os, struct, subprocess, sys, tempfile

# The tune names are resolved before the chdir below, because the rig needs to
# run from its own directory and a relative argument would stop meaning what
# the caller typed the moment it does.
TUNES = [os.path.abspath(p) for p in sys.argv[1:]] \
    or ['/Users/rapido/signals-grouped.ymr']

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
EMU = os.path.join(REPO, 'ymx', 'test', 'emu')
CLASSES = os.path.join(REPO, 'target', 'classes')
sys.path.insert(0, EMU)
os.chdir(EMU)
import test_ymx as T                                                # noqa: E402

# The bits a YM2149 actually keeps, which is what the packer masks a register
# down to before it ever reaches a stream. R13 is the exception: $FF passes through
# as the marker that means "do not write it at all".
MASK = [0xFF, 0x0F, 0xFF, 0x0F, 0xFF, 0x0F, 0x1F, 0x3F,
        0x1F, 0x1F, 0x1F, 0xFF, 0xFF, 0x0F]
NO_SHAPE = 0xFF                     # R13: this frame did not pop the shape
SHAPE_BEFORE_ANY_POP = 0x08         # what an RTE retriggers before the first pop
PORTS = 0xC0                        # R7 bits the ST needs as outputs

PRESCALE = [0, 4, 10, 16, 50, 64, 100, 200]     # index 0 is the MFP stopped
MFP_CLOCK = 2457600

# Effect types, as the timer_*_effect stream carries them.
FX_NONE, FX_PWM, FX_SAMPLE, FX_RTE = 0, 1, 2, 3

# The engine's code-byte kinds, the vocabulary the skip is decided
# in: a PWM becomes a toggle stream, a Sample a PCM stream, an RTE a
# retrigger stream. Only the first two own a volume register.
KIND_TOGGLE, KIND_PCM, KIND_RETRIGGER = 0x00, 0x40, 0xC0
TRIGGER = 0x08                      # code bit 3, flipped on every sample trigger

MAX_SAMPLES = 32                    # what five bits of a volume register hold
MAX_SAMPLE_BYTES = 65535            # what a ymx sample table's word-sized length holds

# How far the walk goes into a long tune: the same 1200 frames sweep.py
# plays. Raising it is the only way to reach the frames the split rotation
# added, which sit past the file's own length, and the wrap after them -
#   YMR_FRAME_CAP=11000 python3 ymx/test/ymr_sweep.py song.ymr
# is a whole pass of a four-minute tune and a couple of seconds of emulation.
FRAME_CAP = int(os.environ.get('YMR_FRAME_CAP', 1200))


class Malformed(Exception):
    """Anything about the .YMR image this file will not read."""


# --------------------------------------------------------------------- ZX1

def zx1(image, at, length, ring):
    """One ZX1 stream out of a .YMR image, decoded through its own ring.

    Written here rather than borrowed so that the Java reader has something
    to be checked against. ZX1 alternates literal runs and matches: a run
    length is an interlaced Elias gamma code, a match either repeats the last
    offset or carries a new one, and a non-positive offset is the end marker.
    The ring is both the window and the output queue, so a back-reference is
    resolved modulo its size - which is exactly what makes ring_size and the
    packer's offset limit one decision rather than two.

    A ring of 0 is not a small ring but a different thing entirely: the
    stream is stored uncompressed and its bytes are the data.
    """
    if ring == 0:
        return bytearray(image[at:at + length])
    source = image[at:at + length]
    out = bytearray()
    window = bytearray(ring)
    at_in, mask, bits, at_win = 0, 0, 0, 0

    def byte():
        nonlocal at_in
        if at_in >= len(source):
            raise Malformed('the stream ends mid-operation')
        at_in += 1
        return source[at_in - 1]

    def bit():
        nonlocal mask, bits
        mask >>= 1
        if mask == 0:
            mask, bits = 128, byte()
        return 1 if bits & mask else 0

    def gamma():
        value = 1
        while bit():
            value = value * 2 + bit()
        return value

    def emit(value):
        nonlocal at_win
        out.append(value)
        window[at_win] = value
        at_win = (at_win + 1) % ring

    offset = 1                      # the distance a stream starts at
    literals, length_left = True, gamma()
    while True:
        for _ in range(length_left):
            emit(byte() if literals else window[(at_win - offset) % ring])
        if bit():                   # a match from a new offset, or the end
            first = byte()
            if first & 1:
                second = byte()
                offset = 32512 - (second & 254) * 128 - (first & 254) - (second & 1)
            else:
                offset = 128 - first // 2
            if offset <= 0:
                if at_in != len(source):
                    raise Malformed('the end marker lands before the stream does')
                return out
            if offset > ring:
                raise Malformed('a match reaches back further than the %d-byte ring'
                                % ring)
            if offset > len(out):
                # Past the stream's own first byte, into whatever the ring
                # happened to hold. Nothing distinguishes those bytes once
                # they are copied out, so the reach is refused here instead.
                raise Malformed('a match reaches back for bytes the stream never wrote')
            literals, length_left = False, gamma() + 1
        elif literals:              # a match from the offset already in hand
            literals, length_left = False, gamma()
        else:
            literals, length_left = True, gamma()


# --------------------------------------------------------- the .YMR image

# One entry's width per stream, and the first YM register a register stream's
# entry writes. A width-2 entry is two registers in REGISTER ORDER - a tone is
# (R0, R1), an envelope period is (R11, R12), a timer's rate is (prescaler,
# counter) - and not a big-endian word: the spec's "all multi-byte values
# big-endian" line is true of the header and only of the header.
WIDTH = [0, 2, 2, 2, 1, 1, 1, 1, 1, 2, 1, 1, 2, 1, 1, 2, 1, 1, 2, 1]
FIRST_REGISTER = [-1, 0, 2, 4, 6, 7, 8, 9, 10, 11, 13]
STREAM_COUNT = 20
LAST_REGISTER_STREAM = 10
FIRST_TIMER_STREAM = 11
HEADER_SIZE = 28 + STREAM_COUNT * 12

# One frame of one timer: what it should be doing, and which of its three
# streams said so this frame. The flags are the events and are not the same
# information as the values - popping timer_*_sample restarts the sample even
# when the index has not moved, and popping timer_*_effect with 0 stops the
# timer even though an idle timer already held 0.
TimerFrame = collections.namedtuple(
    'TimerFrame', 'effect prescaler counter sample effect_pop rate_pop sample_pop')


class Ymr:
    """A .YMR v1.3 register dump, replayed onto the flat per-frame view.

    A .YMR stores no frames. Everything a frame can change lives in a stream,
    a stream holds one entry per change rather than one per frame, and a
    separate command stream lists for every frame the streams to pop. Popping
    applies an entry, so a held note costs nothing after the frame it
    arrives on - and no frame can be reached by index, which is why the only
    way in is to replay the command stream from the start, once, and write
    down what the chip held on every frame as it goes.
    """

    def __init__(self, image):
        self.image = image
        if image[:4] != b'YMR!':
            raise Malformed('not a .YMR image')
        version, = struct.unpack('>H', image[4:6])
        if version != 0x0103:
            raise Malformed('.YMR version %d.%d, not the 1.3 this reads'
                            % (version >> 8, version & 0xFF))
        frames, loop = struct.unpack('>II', image[6:14])
        self.rate, sample_count = struct.unpack('>HH', image[14:18])
        streams, = struct.unpack('>H', image[22:24])
        if streams != STREAM_COUNT:
            raise Malformed('the stream map has %d entries, not %d'
                            % (streams, STREAM_COUNT))
        if frames <= 0:
            raise Malformed('unusable frame count %d' % frames)
        self.frames = frames
        self.loop = -1 if loop == 0xFFFFFFFF else loop
        if self.loop >= frames:
            raise Malformed('loop frame %d is past the %d frames' % (loop, frames))

        self.map = [struct.unpack('>IIHH', image[28 + 12 * s:40 + 12 * s])
                    for s in range(STREAM_COUNT)]
        self._read_samples(sample_count)
        self.entries = [self._decode(s) for s in range(STREAM_COUNT)]
        if not self.map[0][0]:
            raise Malformed('no command stream: nothing says what any frame pops')
        self._replay()
        self._walk()

    def _read_samples(self, count):
        """The sample blocks, which sit between the map and the streams: a
        padded size, that many pre-converted 4-bit levels, then a four-byte
        trailer. Nothing is converted on the way in - RhYMe's exporter has
        already reduced every sample to what a volume register takes."""
        at = HEADER_SIZE
        self.blocks = []
        for index in range(count):
            size, = struct.unpack('>I', self.image[at:at + 4])
            at += 4
            if size > len(self.image) - at - 4:
                raise Malformed('sample %d claims %d bytes past the file'
                                % (index, size))
            data = self.image[at:at + size]
            at += size
            looped = self.image[at] & 1
            start, = struct.unpack('>H', self.image[at + 1:at + 3])
            at += 4
            self.blocks.append((data, bool(looped), start))
        self.samples = [self._prepare(b) for b in self.blocks[:MAX_SAMPLES]]

    @staticmethod
    def _prepare(block):
        """How many bytes a PCM stream plays before it stops, or None for a
        block that never stops.

        The skip window below is measured in the length of the sample that
        actually plays. A looped block plays until something else
        takes the timer, because the file says where the sample comes back to
        and the tick does the coming back; only a one-shot has a length at
        all. Only the length matters here, since nothing in this test plays
        the bytes.
        """
        data, looped, start = block
        data = data[:MAX_SAMPLE_BYTES]
        if not looped or start >= len(data):
            return len(data)
        return None

    def _decode(self, stream):
        """A stream's stored length is the distance to the next present
        stream's offset, and the last present one runs to the end of the
        file: the streams are written in map order, which is why nothing in
        the file stores a size."""
        offset, _, ring, _ = self.map[stream]
        if offset == 0:
            return bytearray()          # not in the file: nothing ever popped it
        end = len(self.image)
        for later in range(stream + 1, STREAM_COUNT):
            if self.map[later][0]:
                end = self.map[later][0]
                break
        if end < offset:
            raise Malformed('stream %d starts after the stream that follows it'
                            % stream)
        try:
            return zx1(self.image, offset, end - offset, ring)
        except Malformed as problem:
            raise Malformed('stream %d: %s' % (stream, problem)) from None

    def _replay(self):
        """The command stream, one byte per command: $00 ends the frame,
        $01-$BF pops the stream with that index, and $C0 upwards is reserved -
        a future command would define for itself how many bytes follow it, so
        meeting one is a stop rather than something to step over."""
        self.registers = [bytearray(self.frames) for _ in range(14)]
        self.timers = [[None] * self.frames for _ in range(3)]
        cursor = [0] * STREAM_COUNT
        held = [0] * 14
        effect, prescaler, counter, sample = [0] * 3, [0] * 3, [0] * 3, [0] * 3
        popped = [[False] * 3 for _ in range(3)]
        shape_popped = False
        frame = 0
        for command in self.entries[0]:
            if command == 0:
                if frame == self.frames:
                    raise Malformed('more end-of-frame bytes than the header asks for')
                for register in range(14):
                    self.registers[register][frame] = held[register]
                self.registers[13][frame] = held[13] if shape_popped else NO_SHAPE
                shape_popped = False
                for timer in range(3):
                    self.timers[timer][frame] = TimerFrame(
                        effect[timer], prescaler[timer], counter[timer],
                        sample[timer], *popped[timer])
                    popped[timer] = [False] * 3
                frame += 1
                continue
            if command >= 0xC0:
                raise Malformed('frame %d carries reserved command $%02X'
                                % (frame, command))
            if command >= STREAM_COUNT:
                raise Malformed('frame %d pops stream %d, past the map'
                                % (frame, command))
            width = WIDTH[command]
            at = cursor[command]
            entry = self.entries[command]
            if width > len(entry) - at:
                raise Malformed('frame %d pops stream %d, which has nothing left'
                                % (frame, command))
            cursor[command] = at + width
            if command <= LAST_REGISTER_STREAM:
                first = FIRST_REGISTER[command]
                for i in range(width):
                    held[first + i] = entry[at + i]
                shape_popped |= command == LAST_REGISTER_STREAM
                continue
            timer, which = divmod(command - FIRST_TIMER_STREAM, 3)
            popped[timer][which] = True
            if which == 0:
                effect[timer] = entry[at]
            elif which == 1:
                prescaler[timer], counter[timer] = entry[at], entry[at + 1]
            else:
                sample[timer] = entry[at]
        if frame != self.frames:
            raise Malformed('the command stream holds %d end-of-frame bytes, not %d'
                            % (frame, self.frames))

    # ------------------------------------------------- what each frame asks for

    def _walk(self):
        """What every dump frame asks of each timer, as one code byte.

        This is the dump timeline, and it stays on it: a code byte is packed
        against the frame it belongs to, so the same byte comes round again
        wherever the played timeline shows that frame again. What the effect
        stage MAKES of a run of code bytes is the played timeline's business
        and lives in the Stage below.

        A frame that pops none of a timer's three streams changes nothing.
        Popping the effect stream with something in it CONFIGURES the timer -
        which restarts a sample even when the index it gives is the one
        already playing - popping it with 0 stops the timer, popping the
        sample stream restarts the sample on a timer already running, and a
        rate pop on its own reprograms the prescaler and counter without
        disturbing anything. The held prescaler, counter and sample are read
        straight off the replay rather than tracked a second time: they can
        only differ from a running timer's own copy on frames where nothing
        is running, and then the timer owns nothing anyway.

        A timer that never ticks owns nothing, and three configurations never
        tick: an effect type the format reserves, a prescaler index of 0 (the
        MFP's stopped state) or a counter of 0, which the converter drops
        rather than run at 256, and a Sample pointing at a block
        the file does not carry. A sample that has played out gives its
        register back as well, which is why the window is recomputed at every
        trigger and the code goes quiet when it closes.
        """
        self.shape = bytearray(self.frames)
        shape = SHAPE_BEFORE_ANY_POP
        for frame in range(self.frames):
            written = self.registers[13][frame]
            if written != NO_SHAPE:
                shape = written
            self.shape[frame] = shape & 15

        self.codes = [bytearray(self.frames) for _ in range(3)]
        self.window = [[0] * self.frames for _ in range(3)]
        self.used = 0                  # the channels that ever act
        self.triggers = 0
        for channel in range(3):
            self._channel(channel)

    def _channel(self, channel):
        running = FX_NONE
        trigger = 0
        armed_to = 0
        last = 0
        for frame in range(self.frames):
            want = self.timers[channel][frame]
            configure = False
            if want.effect_pop:
                if want.effect == FX_NONE:
                    running = FX_NONE
                else:
                    configure = True
            elif running != FX_NONE and want.sample_pop:
                configure = True
            started = False
            if configure:
                running = want.effect
                started = running == FX_SAMPLE
                if started:
                    trigger ^= TRIGGER
                    self.triggers += 1
            code = self._code(channel, running, want, trigger, started, frame, armed_to)
            if code and (code & 0xC0) == KIND_PCM:
                # Every armed frame carries the window its rate would give a
                # sample starting there, not only the frame the dump timeline
                # happens to start one on: the effect stage arms on the frame
                # a code CHANGES, and the played timeline can reach a change
                # the dump timeline does not have - the first frame the split
                # rotation added is a change coming out of the song's last
                # frame rather than out of the frame before it.
                self.window[channel][frame] = self._armed(want)
                if code != last:
                    armed_to = frame + self.window[channel][frame]
            last = code
            self.codes[channel][frame] = code
            if code:
                self.used |= 1 << channel

    def _code(self, channel, running, want, trigger, started, frame, armed_to):
        """The code byte a frame hands the effect stage, or 0 for a channel
        with nothing to run. The trigger bit makes two pops of one
        sample at one rate two different codes, which is how an explicit
        re-trigger reaches a stage that acts on a code that CHANGED."""
        kind = {FX_PWM: KIND_TOGGLE, FX_SAMPLE: KIND_PCM,
                FX_RTE: KIND_RETRIGGER}.get(running)
        if kind is None:
            return 0                    # idle, or a type the format reserves
        if PRESCALE[want.prescaler & 7] == 0 or want.counter == 0:
            return 0                    # prescaler 0 stops it; counter 0 is dropped
        head = kind | ((channel + 1) << 4) | (want.prescaler & 7)
        if kind != KIND_PCM:
            return head
        if want.sample >= len(self.samples):
            return 0                    # no block behind it: nothing plays
        return head | trigger if started or frame < armed_to else 0

    def _armed(self, want):
        """How many frames a sample armed with this rate stays armed for: the
        sample plus its end marker at the timer's rate, plus a sixteenth of a
        frame for the arming phase, rounded up so the skip never lifts
        early. The skip is what this test observes, so getting the frame
        wrong here would read as the player writing a skipped register."""
        if self.samples[want.sample] is None:
            return 1 << 30              # a looped sample: the skip never
        ticks = self.samples[want.sample] + 1    # reopens on its own
        divisor = PRESCALE[want.prescaler & 7] * want.counter
        scaled = ticks * divisor * self.rate + MFP_CLOCK // 16
        return -(-scaled // MFP_CLOCK)


class Stage:
    """The effect stage, replayed frame by frame on the PLAYED timeline.

    The skip is state, not a property of a dump frame: what a code byte does
    depends on the code before it, and the played timeline is where "before"
    means anything. It matters at exactly one place - the frames the split
    rotation added, which show dump frames the walk has already been past -
    and getting it wrong there reads as the player starting a square it does
    not start. So this steps once per played frame, given the dump frame that
    frame shows, the way the compiler does.

    What it reports is the whole of what a frame's volume writes depend on:

      * SKIPPED - the voice is running a PWM or a sample, so its volume
        register belongs to that effect's timer and the frame write is muted.
        This is ymr_write_register's rule in lib_data.s, arrived at from the
        other side.
      * BUZZING - the voice is running an RTE, which drives R13 and leaves
        the volume register alone, so the frame write happens and carries
        the dump's own byte. The shape a retrigger stream
        restarts is carried in the script, and the .ymr front end reads it
        off R13, so nothing is hidden in the nibble and this is
        an ordinary open register: it is compared whole, a stricter
        check than the half-comparison a smuggled shape used to allow.
      * STARTED - a fresh square arms this frame. It restarts at phase zero,
        so the player writes the voice silent itself, after the register
        burst and past the skipped write: one write, carrying zero. A PWM
        whose prescaler merely moved is retuned instead and writes nothing,
        which keeps the square's place in the cycle.

    A .YMR binds each timer to one voice - A to A, B to B, D to C, and the
    binding is normative - so no two channels ever contend for one voice and the
    whole of the arbitration the YM dialect needs is absent here. What is
    left is a channel's own succession, which is why every branch below reads
    only its own voice.
    """

    def __init__(self, dump):
        self.dump = dump
        self.played = 0
        self.last = [0] * 3             # the code each channel ran last frame
        self.owner = [-1] * 3           # the channel a voice's sample belongs to
        self.end = [-1] * 3             # the played frame its window closes on
        self.skips = 0

    def step(self, frame):
        """Advances one played frame showing dump frame `frame`; returns
        (skipped, buzzing, started), each a voice mask."""
        played, self.played = self.played, self.played + 1
        for voice in range(3):
            if self.owner[voice] >= 0 and self.end[voice] == played:
                self.owner[voice] = -1      # the marker tick has run by now
                self.end[voice] = -1
                self.skips &= ~(1 << voice)
        started = 0
        buzzing = 0
        for channel in range(3):
            code = self.dump.codes[channel][frame]
            if code and (code & 0xC0) == KIND_RETRIGGER:
                buzzing |= 1 << channel
            old, self.last[channel] = self.last[channel], code
            if code == old:
                continue                # held: a .YMR's trigger is a pop, so
            voice = channel             # nothing re-fires on a repeated code
            if code == 0:
                # A .YMR can say stop, and every command that does programs
                # the one timer the sample was ticking on, so a sample ends
                # on the frame it is stopped rather than at a marker it will
                # now never reach - and the voice's volume comes back out of
                # this same frame's burst.
                self._drop(channel, voice)
                if (old & 0xC0) == KIND_TOGGLE and old:
                    self.skips &= ~(1 << voice)
                continue
            kind = code & 0xC0
            if kind == KIND_RETRIGGER:
                self._drop(channel, voice)
                continue                # a buzzer writes R13, never a volume
            if kind == KIND_TOGGLE:
                # The sample this channel was playing ends here too, but its
                # the skip stands: the square requires it as well.
                if self.owner[voice] == channel:
                    self.owner[voice] = -1
                    self.end[voice] = -1
                # A start and a retune differ in the top nibble of the code -
                # the kind and the voice - and only the start touches the chip.
                if old == 0 or (code ^ old) & 0xF0:
                    started |= 1 << voice
            else:
                self.owner[voice] = channel
                self.end[voice] = played + self.dump.window[channel][frame]
            self.skips |= 1 << voice
        return self.skips, buzzing, started

    def _drop(self, channel, voice):
        """The sample this channel still owns, ended because the channel was
        told to do something else: its skip lifts on this frame."""
        if self.owner[voice] == channel:
            self.owner[voice] = -1
            self.end[voice] = -1
            self.skips &= ~(1 << voice)


# ------------------------------------------------------------------ the MFP

# One row per MFP timer: its control and data registers, the interrupt enable
# and mask registers its bit lives in, and that bit. Timers C and D share
# TCDCR - C in the high nibble, D in the low - which is why a claim is checked
# as a nibble rather than as a whole byte.
TIMERS = {
    'A': (0xFFFFFA19, 0xFFFFFA1F, 0xFFFFFA07, 0xFFFFFA13, 5),
    'B': (0xFFFFFA1B, 0xFFFFFA21, 0xFFFFFA07, 0xFFFFFA13, 0),
    'C': (0xFFFFFA1D, 0xFFFFFA23, 0xFFFFFA09, 0xFFFFFA15, 5),
    'D': (0xFFFFFA1D, 0xFFFFFA25, 0xFFFFFA09, 0xFFFFFA15, 4),
}

# The spec's normative binding, as the T stream carries it: channel 0 runs on
# Timer A, 1 on B, 2 on D, and the fourth channel no .YMR fills takes the
# leftover Timer C.
CHANNEL_TIMER = ['A', 'B', 'D', 'C']

TCDCR = 0xFFFFFA1D
TCDR = 0xFFFFFA23

# The interrupt registers each timer's bit lives in, by group: A and B share
# the A group, C and D the B group. A player may touch the enable, the
# pending, the in-service and the mask register of a group it has a timer in,
# and nothing else in the MFP's page.
INTERRUPT = {'A': (0xFFFFFA07, 0xFFFFFA0B, 0xFFFFFA0F, 0xFFFFFA13),
             'B': (0xFFFFFA07, 0xFFFFFA0B, 0xFFFFFA0F, 0xFFFFFA13),
             'C': (0xFFFFFA09, 0xFFFFFA0D, 0xFFFFFA11, 0xFFFFFA15),
             'D': (0xFFFFFA09, 0xFFFFFA0D, 0xFFFFFA11, 0xFFFFFA15)}


def mfp_problem(writes, used):
    """What the MFP writes say about who claimed which timer.

    Timer C is the one that must stay untouched whatever the tune does: it is
    the operating system's own 200 Hz clock, the format reserves it, and the
    fourth timer channel - the one a .YMR never fills - is the one it is
    mapped to. Its control bits share a byte with Timer D's, so the check is
    that every write to that byte leaves the high nibble alone, and that its
    data register is never written.
    """
    allowed = set()
    for channel, timer in enumerate(CHANNEL_TIMER[:3]):
        if used & (1 << channel):
            control, data = TIMERS[timer][:2]
            allowed.update((control, data), INTERRUPT[timer])
    seen = collections.defaultdict(int)         # address -> the bits ever set
    for address, value in writes:
        seen[address] |= value
        if address == TCDR:
            return 'wrote Timer C\'s data register'
        if address == TCDCR and value & 0xF0:
            return 'programmed Timer C in TCDCR (%#04x)' % value
        if address not in allowed:
            return 'wrote %#010x, which no timer this tune uses owns' % address
    for channel, timer in enumerate(CHANNEL_TIMER):
        _, data, enable, unmask, bit = TIMERS[timer]
        claimed = bool(used & (1 << channel)) if channel < 3 else False
        for register, what in ((enable, 'enabled'), (unmask, 'unmasked')):
            live = bool(seen[register] & (1 << bit))
            if claimed and not live:
                return 'never %s Timer %s, which channel %d uses' % (
                    what, timer, channel)
            if not claimed and live:
                return '%s Timer %s for channel %d, which the tune never uses' % (
                    what, timer, channel)
        if not claimed and data in seen:
            return 'wrote Timer %s\'s data register for an idle channel' % timer
    return ''


# ----------------------------------------------------------------- the sweep

def sweep(path):
    name = os.path.basename(path)
    try:
        dump = Ymr(open(path, 'rb').read())
    except Malformed as problem:
        return 'SKIP %s: %s' % (name, problem)
    except OSError as problem:
        return 'SKIP %s: %s' % (name, problem)
    except (IndexError, struct.error):
        # A field or a block that runs off the end of the image; the parser
        # checks what it can name, and this is the rest.
        return 'SKIP %s: truncated .YMR image' % name

    with tempfile.NamedTemporaryFile(suffix='.ymx', delete=False) as handle:
        ymx = handle.name
    try:
        # -k1 so the packer inserts no padding frames: every played frame is
        # a frame the .YMR actually carries, and the expectation is exact.
        out = subprocess.run(['java', '-ea', '-cp', CLASSES, 'org.ymr.Ymr',
                              '-f', '-k1', path, ymx],
                             capture_output=True, text=True)
        if out.returncode:
            last = (out.stderr or out.stdout).strip().splitlines()[-1]
            return 'PACKFAIL %s: %s' % (name, last[:70])
        warns = [line for line in out.stdout.replace('\r', '\n').splitlines()
                 if line.startswith('Warning') or 'rotated' in line]
        return play(name, dump, open(ymx, 'rb').read(), warns)
    except AssertionError as problem:
        return 'ISSUE %s: %s' % (name, problem)
    finally:
        os.unlink(ymx)


def play(name, dump, packed, warns):
    """Runs the packed tune through the rig and compares every frame."""
    flags, = struct.unpack('>H', packed[6:8])
    played, = struct.unpack('>I', packed[8:12])
    ring, = struct.unpack('>H', packed[16:18])
    split, = struct.unpack('>I', packed[20:24])
    loops = flags & 1

    # The split rotation, from the packed header. The packer cut the loop
    # where the effect state coming from the intro and the state coming from
    # the wrap agree, which pushes the cut past the musical loop frame and
    # lengthens the played timeline by the surplus; the frames it added at
    # the end are the first frames of the loop, played a second time.
    rotation = played - dump.frames
    loop_frame = split - rotation
    if (flags >> 1) & 15 != dump.used:
        return ('ISSUE %s: the header marks timer channels %#x, the dump uses %#x'
                % (name, (flags >> 1) & 15, dump.used))

    player = T.Player(packed, T.YMX_FIXED + T.STREAMS * ring)
    if player.init() != 0:
        return 'INITFAIL %s' % name
    problem = mfp_problem(player.mfp, dump.used)
    if problem:
        return 'ISSUE %s: YMX_init %s' % (name, problem)
    claim = list(player.mfp)

    # The same budget sweep.py plays: a short tune goes right round its loop
    # and out the other side, a long one plays its first FRAME_CAP frames.
    budget = played + 200 if played <= 3000 else FRAME_CAP
    cycle = played - split
    stage = Stage(dump)
    wrapped = False
    walked = 0
    # What the walk actually got to see, so a cap that crossed nothing
    # interesting says so on its own status line rather than reading OK.
    edges = pops = buzzers = starts = 0
    was_skipped = 0
    for frame in range(budget):
        # The played timeline: the packer's rotation replayed. Frames past
        # the file's own length are the loop, and the source frame each shows
        # is the one the packer compiled it from.
        position = frame if frame < played else \
            split + (frame - split) % cycle if cycle else played - 1
        source = position if position < dump.frames \
            else loop_frame + (position - dump.frames)
        result, writes = player.frame()
        if result == -1:
            return 'ISSUE %s: ended early at frame %d/%d' % (name, frame, played)
        if result == 1:
            wrapped = True
        skipped, buzzing, started = stage.step(source)
        problem = compare(dump, frame, source, writes, skipped, started)
        if problem:
            return 'ISSUE %s: %s' % (name, problem)
        problem = mfp_problem(claim + player.mfp, dump.used)
        if problem:
            return 'ISSUE %s: frame %d %s' % (name, frame, problem)
        edges += bin(skipped ^ was_skipped).count('1')
        was_skipped = skipped
        pops += dump.registers[13][source] != NO_SHAPE
        buzzers += buzzing != 0
        starts += bin(started).count('1')
        walked = frame + 1
        if not loops and walked == played:
            break

    timers = ''.join(CHANNEL_TIMER[c] for c in range(3) if dump.used & (1 << c))
    where = 'looped' if wrapped else 'partial' if walked < played else 'once'
    crossings = ('%d skip edge%s, %d PWM start%s, %d buzzer frame%s, %d shape pop%s'
                 % (edges, '' if edges == 1 else 's', starts, '' if starts == 1 else 's',
                    buzzers, '' if buzzers == 1 else 's', pops, '' if pops == 1 else 's'))
    extra = (' [' + '; '.join(warns)[:90] + ']') if warns else ''
    return ('OK %s (%df of %d played, split %d (+%d rotated), cap %d, %s;'
            ' timers %s; %s; %d sample trigger%s in the whole dump)%s'
            % (name, walked, played, split, rotation, FRAME_CAP, where,
               timers or 'none', crossings, dump.triggers,
               '' if dump.triggers == 1 else 's', extra))


def compare(dump, frame, source, writes, skipped, started):
    """One frame's chip writes against the .YMR's own frame, with the effect
    stage's verdict on the three volume registers."""
    counted = collections.Counter(register for register, _ in writes)
    got = dict(writes)
    for register in counted:
        if register > 13:
            return 'frame %d wrote R%d, which is an I/O port' % (frame, register)

    # The periods, the noise and the envelope period: the burst writes every
    # one of them every frame, so a missing or repeated write is as wrong as
    # a wrong value.
    for register in (0, 1, 2, 3, 4, 5, 6, 11, 12):
        want = dump.registers[register][source] & MASK[register]
        if counted[register] != 1:
            return 'frame %d wrote R%d %d times' % (frame, register, counted[register])
        if got[register] != want:
            return 'frame %d R%d wrote %d, want %d' % (frame, register,
                                                       got[register], want)

    # R7 is the mixer plus the ST's port directions and nothing else. The
    # .ymr front end runs with forceMixerOnPcm off - RhYMe's engine bakes the
    # mixer a sample needs into the exported mixer stream - so a bit here
    # that the .YMR did not ask for is a bit nobody can account for.
    want = (dump.registers[7][source] & MASK[7]) | PORTS
    if counted[7] != 1:
        return 'frame %d wrote R7 %d times' % (frame, counted[7])
    if got[7] != want:
        unexplained = got[7] & ~want & 0xFF
        return ('frame %d R7 wrote %#04x, want %#04x%s' % (
            frame, got[7], want,
            ' (unexplained bits %#04x)' % unexplained if unexplained else ''))

    # The volumes, against the skips. A skipped voice's register must be absent
    # from the frame's writes - the player mutes the burst write, so nothing
    # reaches the chip for it - and an open one must be exact.
    for voice in range(3):
        register = 8 + voice
        if started & (1 << voice):
            # A fresh square starts silent, and that write comes from the
            # start action rather than from the burst: through the closed
            # skipped write, exactly once, carrying zero.
            if counted[register] != 1 or got[register] != 0:
                return ('frame %d started a PWM on voice %s and wrote R%d %r, '
                        'want one write of 0' % (frame, 'ABC'[voice], register,
                                                 [v for r, v in writes if r == register]))
            continue
        if skipped & (1 << voice):
            if register in counted:
                return ('frame %d wrote R%d that a skip covers (voice %s is '
                        'running a PWM or a sample)' % (frame, register, 'ABC'[voice]))
            continue
        if counted[register] != 1:
            return 'frame %d wrote R%d %d times' % (frame, register, counted[register])
        value = dump.registers[register][source] & MASK[register]
        # A buzzing voice is no longer a special case: an RTE drives R13 and
        # never the volume register, and v8 stopped the shape being smuggled
        # through the nibble, so the byte is the dump's own and is compared
        # like any other.
        if got[register] != value:
            return 'frame %d R%d wrote %d, want %d' % (frame, register,
                                                       got[register], value)

    # R13 is the one register a frame may decline to write, and the write is
    # an event in its own right: it restarts the hardware envelope, so a
    # frame that did not pop envelope_shape must not write it even with the
    # value it already holds, and a frame that did must write it exactly once.
    shape = dump.registers[13][source]
    if shape == NO_SHAPE:
        if 13 in counted:
            return ('frame %d wrote R13 (%d) on a frame that popped no shape'
                    % (frame, got[13]))
    elif counted[13] != 1:
        return 'frame %d wrote R13 %d times, want once' % (frame, counted[13])
    elif got[13] != shape & MASK[13]:
        return 'frame %d R13 wrote %d, want %d' % (frame, got[13], shape & MASK[13])
    return ''


if __name__ == '__main__':
    failed = 0
    for tune in TUNES:
        line = sweep(tune)
        failed |= line.startswith(('ISSUE', 'PACKFAIL', 'INITFAIL'))
        print(line, flush=True)
    sys.exit(1 if failed else 0)
