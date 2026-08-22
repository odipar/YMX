# YM5/YM6 -> YMX

YM files hold **chiptunes**: a row of sound-chip settings per frame, plus
a few tricks the scene invented long ago. A chiptune is music made by
steering a sound chip register by register, not by playing back recorded
audio.

The format was written for the Atari ST's YM2149, though YM files exist
for other machines with the same chip family. The early versions - YM2,
YM3, YM3b - are bare register dumps. **YM5** added a header (title,
author, loop frame, chip clock, player rate) and stored samples, and
widened the frame to sixteen bytes so two of them could carry effect
fields. **YM6** spent the spare codes in those fields on two more
effects. All of it comes from Arnaud Carré's ST-Sound, the reference
player, which is why the format's names are that player's names - and why
a file carries `LeOnArD!` as its check string. Distributed `.ym` files
are usually LHA-compressed. YMX reads YM5 and YM6.

YM5 carries digidrum and SID voice; YM6 adds sync buzzer and sinus SID.

**YMX** is this repository's format and player: YM6's ideas, packed as
streams in a compressed container. The 6 is YM6's; the X marks the
departure. YM names stay in the code that reads YM files; everywhere else
the names are plain digital ones, so the engine can be read without
knowing the scene. This file maps one set to the other.

A word in **bold** is a term with a precise meaning here, defined where
it first appears. Words in quotes, like "digidrum" and "effect", belong
to the YM format. The new names come from digital systems: counters,
streams, rates, phases. None come from analogue synths - no carriers, no
modulators, no LFOs, because the YM2149 has none of those. A piece of
music is a **tune**; "song" is what a **tracker** - the program a
composer writes music in - calls its own file.

## The sound chip

A **YM2149**, Yamaha's AY-3-8910, running at 2 MHz on an Atari ST.

A **register** is one byte of chip state. There are sixteen: fourteen
steer the sound, two are peripheral I/O ports the ST borrowed for other
duties. Writing a register is the only way software changes anything, and
a register holds its value until written again.

| register | bits | what it holds |
|---|---|---|
| R0, R1 | 8 + 4 | voice A **tone period**, fine and coarse: one 12-bit number |
| R2, R3 | 8 + 4 | voice B tone period |
| R4, R5 | 8 + 4 | voice C tone period |
| R6 | 5 | **noise period** |
| R7 | 8 | the mixer register: **mixing** - which generators reach which voice - plus the I/O port directions |
| R8, R9, R10 | 5 | voice A, B, C **volume**: four bits of level, bit 4 meaning "follow the envelope" |
| R11, R12 | 8 + 8 | **envelope period**, fine and coarse: one 16-bit number |
| R13 | 4 | **envelope shape** |
| R14, R15 | 8 | the two I/O ports. Not sound |

A **signal** is a series of values with a rate: a square wave, a run of
noise, a sample. Five **generators** make them:

- three **tone generators**, each a counter that flips its output when it
  runs out, making a square wave;
- one **noise generator**, a shift register producing a random-sounding
  bit pattern;
- one **envelope generator**, a counter walking one of sixteen shapes.
  Normally a note's rise and fall; run fast, the same sweep is a pitch.

How long a generator takes per cycle is its **period**. Bigger period,
lower pitch:

    tone frequency     = 2,000,000 / (16 x tone period)
    noise clock        = 2,000,000 / (16 x noise period)
    envelope frequency = 2,000,000 / (256 x envelope period)

Tone period 284 is about 440 Hz. Envelope period 18 is about 434 Hz - the
same note from the envelope generator.

**Noise period** sets how bright the noise is, not how loud: five bits, 1
to 31. At 1 it is a wide hiss, around 15 a coarser rush, at 31 slow
enough to take on a pitch. One generator feeds all three voices, so only
its volume can differ between them.

**Envelope shapes** are four bits, and only four of the sixteen repeat:
two sawtooths and two triangles. The other twelve run once and hold,
which suits a decay and is useless as an oscillator.

**The envelope's pitch resolution is coarse, and worsens as pitch
rises.** The divisor is 256, so neighbouring periods are far apart:
period 18 is 434 Hz, period 17 is 460 Hz - nearly a semitone between
adjacent settings. That is the limit on a buzzer part. A sync buzzer
escapes it by taking its pitch from a timer instead, which at 440 Hz
lands within a few cents.

Three **voices**, A, B and C. Each has a **volume** and a **mixing**
setting - which generator signals reach it: tone, noise, both or neither.
The volume scales what arrives. "Follow the envelope" is a real bit, bit
4 of the volume register: with it set, the level bits are ignored and the
envelope supplies the level.

**The DAC** is a ladder of levels, close to logarithmic: about 3 dB a
step, a factor of 1.4 in amplitude. A volume register picks one of 16
steps, the envelope walks 32, and the bottom of the ladder is irregular -
hence a measured table rather than a formula (`YmEffects.CURVE`). This
matters for **samples**: a recording is linear amplitudes, the register
takes a logarithmic index, so filtering or resampling must happen on the
amplitudes and convert afterwards. The ladder spans about 54 dB top to
bottom - about 45 dB if you count 3 dB a step, the two differing because
the bottom step is a jump of 8 dB and the rest average nearer 3.3 - so
material with its peaks near the top keeps the most detail.

**Writing the envelope shape restarts the envelope.** Writing the same
shape twice is not a wasted write; it is a restart, and that is the whole
mechanism behind the sync buzzer below. A format therefore needs a way to
say "leave the shape alone" on frames that must not restart it. YM stores
255 for that.

## Streams

A **frame** is one call to the player. A tune is a list of frames,
stepped at the tune's own rate.

A **stream** is a series of values arriving at one register at a steady
speed. Three things make one: a **target**, the register the values land
in; a **source**, where they come from; and a **rate**, how often one
arrives. A period is one number over two registers, so its stream targets
that pair.

Sources differ - the packed tune, a stored sample, two numbers flipped
between, a computed waveform - and none of it shows in the write. What
arrives is a byte, and the sound is the same whatever produced it.

Not everything here is a stream. A generator has no input. A register is
where a stream ends. A stored sample is a source, not the delivery. The
per-frame instruction data YMX packs beside the music is stored like a
stream but never reaches a register.

A **tick** is one step of a stream's clock, and one register write. Every
stream is ticked; the two kinds differ in whose clock does it:

- a **frame stream** is ticked by the player's own call, one value per
  frame. This is the music: fourteen streams, one per sound register.
- a **timer stream** is ticked by a timer claimed for it alone, many
  times per frame. This is what the YM format calls an "effect".

That is not software against hardware - a frame clock is often a timer
too. The difference is that the frame clock steps the whole player at
once, and a timer steps one stream.

A **timer channel** is one place a timer stream can run: a claimable
clock, and the stream on it. A tune names the channels it uses and which
timer each runs on.

A stream's place in its own cycle is its **phase**. What becomes of that
place across a stop is its **phase policy**: the cycle keeps running
while the stream is off (**free-running**), or every start begins from
the beginning (**zero-restart**). The difference is audible.

(Trackers use "effect" for the per-row commands a composer types. Where
both readings are possible, this file says **timer stream**.)

(A warning for anyone reading a front end: a source format may use
"stream" for a change list - one entry per change, not one per frame -
advanced by an explicit "pop" rather than by a clock, and some such
streams carry timer settings rather than register values. A front end has
to keep its own format's names, so this collision cannot be settled by
choosing a word. Here a stream is the per-register value
series, and it is ticked.)

## Three clocks

| clock | speed | what runs there |
|---|---|---|
| **frame clock** | the tune's own rate | frame streams. Also the **control rate**: how often the deciding code runs |
| **timer clocks** | 48 to 25,600 a second | timer streams. Also the **audio rate**: how often a sound-shaping write lands |
| **YM2149 clock** | 2,000,000 a second | the generators. Software has no access |

    frame streams (frame clock)     timer streams (timer clocks)
                  \                          /
                   v                        v
              [ YM2149 registers  R0 - R13 ]
                            |
            +---------------+---------------+
            v               v               v
      tone counters    noise shift     envelope
         (three)         register       counter
            \               |              /
             +--------------+-------------+
                            |
                    mixing (R7) picks what reaches each voice
                            |
                            v
                  voice A    voice B    voice C
                            |
            volume (R8-R10) scales that signal, or follows the envelope
                            |
                            v
                          output

### How fast is a frame?

A property of the tune, fixed for the whole of it:

- **50 a second** usually - the screen refresh, the **VBL** or vertical
  blank, which is the PAL and SECAM rate. All 543 readable files of the
  544-file **corpus** run at 50.
- **60 a second** on NTSC machines.
- **200 a second** for four times the detail in arpeggios, volume shapes
  and pitch slides. Not the screen but a timer in the **MFP**, the ST's
  support chip - usually its Timer C.
- **Anything else** a composer set: 25, 100 and 150 all exist.

The rate belongs to the tune, not to the file carrying it: stepping the
music at another speed plays it wrong. Faster frames cost processor time.
Below, "per frame" means "per player call" at whatever that speed is.

Not here: a tempo in beats. Tempo lives in the tracker, which turns it
into rows and rows into frames.

### Where a timer tick comes from

The **MFP** (MC68901) has four timers, A to D. YMX has four timer
channels, numbered 0 to 3, and **the file says which timer each runs on**
- a stream carries the map, two bits per channel. A player claims a timer
only for a channel the tune names.

All four timers are reachable. Timer C costs more than the others: it is
the operating system's 200 Hz clock, so a tune naming it stops that clock
and cannot be hosted from a Timer C hook. A YM file never names it, since
a YM frame starts at most two effects.

The MFP's own clock runs at 2,457,600 a second, unrelated to the
YM2149's. A timer divides it twice: by a **prescaler**, one of 4, 10, 16,
50, 64, 100 or 200, then by a **timer count**, 1 to 255.

    rate = 2,457,600 / (prescaler x timer count)

The timer counts down at the divided speed and raises an interrupt at
zero. That interrupt is the tick. (Both numbers are divisors; a
generator's *counter* is a different thing.)

The slowest rate is 48 a second, the fastest 614,400. YMX rejects
anything above 25,600: on an 8 MHz 68000 the interrupt alone would take a
quarter of the machine. For scale, 69 corpus tunes play samples, mostly
between 5,000 and 6,100 a second.

### Conflicts between the two clocks

The player takes the next value from each frame stream and writes them
one after another. That burst is the **frame write**.

YMX writes all fourteen registers every frame, because a write costs
about what the comparison to avoid it would cost. Two rules bend it: the
envelope shape is left alone where a restart would be wrong, and any
voice's volume is left out while a timer stream holds it. That is the
voice's **skip**: `ymx_skips` overwrites the burst's write with two nops,
so that byte never reaches the chip - though the player can still write
the register outside the burst, and does. A tracker writes only what
changed, because its own format records which registers those are. A YM
file has no such record - a full row every frame, with nothing marking
what is new.

Most hard bugs come from a tick landing during a frame write, or both
writing the same register:

- **tearing**. A tick lands between a register's select and its value,
  and the value reaches whatever register the tick selected. Two cures:
  write select and value in one instruction, which an interrupt cannot
  split, or mask interrupts for the burst - which is safe but delays
  every tick that falls inside it.
- **contention**. Frame write and timer stream target the same register.
  Prevented by giving the register an owner and skipping it in the burst.
- **quantisation**. Something happens between frames; only the next frame
  can act on it.

## What the old names mean

A YM file starts an effect with an **effect code** in a register: a few
bits naming the kind and the voice. **An effect code is a trigger, not a
change** - the same code on two frames in a row starts the stream
*twice*.

**digidrum**. A recorded sound played through a voice's volume register,
one value per tick. Nearly always unpitched - drums, percussion - at one
rate, though nothing in the mechanism requires that.

**SID voice**. Named after the Commodore 64's chip, not for how it works
but for one sound that chip is known for: pulse-width modulation. Here
the character comes from switching a voice's volume between a set level
and off, very fast, chopping the signal already coming out of it.

**buzzer**. The envelope generator making a note rather than a volume
shape: a repeating shape and a short period, fast enough to be heard as a
pitch. No timer needed - two frame streams and a voice following the
envelope. A bass part made this way is a "hard bass", and the envelope's
coarse pitch resolution is why it stays a bass.

**sync buzzer**. A buzzer whose envelope a timer keeps restarting. The
pitch is then the timer's and the tone colour is how far the envelope
gets before restarting. Restarting one generator from another is hard
sync in synths, which is where the name comes from.

**sinus SID**. A SID voice using a calculated sine instead of two values.
In the format; no corpus tune uses it, and YMX drops it at pack time.

## The timer streams

Three of the four kinds are one idea with different sources. **A volume
stream sends volume values to one voice, one per tick** - which is all a
digidrum, a SID voice or a sinus SID is. Three things separate them:

| YM5/YM6 | YMX | the series | repeats? | the voice | rate |
|---|---|---|---|---|---|
| digidrum | **PCM stream** | a stored sample, any length | **usually not**: it plays once unless its sample carries a loop point | **disconnected** by the YM dialect: no generator reaches it, so the stream's values are all that is left. A source that mixes its own samples leaves the voice to the tune's **mixing** stream | **independent** by default: the sample's own pitch |
| SID voice | **toggle stream** | two values, a set level and off | **yes**, until stopped | **left connected**: the values chop its signal | **derived** from the note playing |
| sinus SID | **wave stream** | a stored or computed waveform | **yes**, until stopped | **left connected**: the values shape its signal | **derived** from the note playing |

The last two rows are special cases of the first: a toggle stream is a
PCM stream two values long, a wave stream one whose sample is a waveform.

Two reasons for three codes rather than one, neither about sound:

**Cost.** A PCM stream does four things per tick - hold a pointer, read a
byte, step it, test for the end - against one instruction for a two-value
flip. At 25,000 ticks a second that is tens of percent of the machine.

**Rate.** A digidrum's is **independent**: the pitch a recording plays
at, with no note to relate to, since the voice is disconnected. A SID
voice's is **derived**: its values scale the signal the voice still
makes, so the two must stay in ratio or the tone changes with every note.
One is a sound in itself; the other is a treatment applied to a note.

That is a default, not a rule. YM6 stores one rate per trigger, so a
digidrum in a YM file plays at a fixed rate; nothing in this model
forbids a source that moves a sample's rate under a melody. The split
does say when a rate may change in a given format: independent means
**set once** - and, since v10, movable under a running stream where the
source says so - derived means **per-frame**, renewed on every frame.
(Not "control-rate": the frame clock already answers to **control
rate**, and a policy and a clock should not share a word.)

**The fourth kind is not a volume stream.** It writes the envelope shape,
the same shape every tick, so the values say nothing - the point is the
restart. Do it fast enough and the envelope never finishes its shape; the
sweep repeats at the tick rate and is heard as a pitch.

It reaches a voice indirectly: there is one envelope generator, and a
voice takes its level from it only while following the envelope. Put two
voices there and both carry it, which real tunes do - 15 of the corpus's
543. This is the one timer stream not tied to a single voice.

| YM5/YM6 | YMX | the series | repeats? | the voice | rate |
|---|---|---|---|---|---|
| sync buzzer | **retrigger stream** | one shape, written again and again | **yes**, until stopped | not written directly; a voice following the envelope sounds it, and more than one can | **derived**: the rate is the pitch |

"Sync stream" would have matched YM6's word, but with three clocks about,
"sync" would read as clock syncing.

### What each rate is coupled to

**Coupling** is "derived" said exactly: what a rate is set against -
always something else on the same voice, with the ratio being what you
hear. Nothing in the hardware enforces it; it is how the music was
written, and what the **packer** - the tool that turns a source file, a
`.ym`, into a YMX file - has to preserve.

| stream | coupled to | why |
|---|---|---|
| **PCM stream** | nothing, in a YM tune | a disconnected voice has nothing to be in ratio with. A source that transposes samples couples it to the note |
| **toggle stream** | the voice's **tone period** | two square signals multiply and their ratio is the tone colour, so the rate must move with every note |
| **wave stream** | the voice's **tone period** | the waveform shapes the signal instead of chopping it; the ratio still sets the result |
| **retrigger stream** | the **envelope period** | the tick rate sets the pitch, the ratio sets how far into the shape the counter gets |

Coupling is why **retune** exists. A melody moves, so a derived rate
moves with it. If every move restarted the stream the phase would jump on
every note, so a rate change on its own is a retune: new rate, same place
in the cycle. The same rate can be built from different divisor pairs -
4 x 100 and 16 x 25 divide alike - and a melody's rate eventually leaves
one prescaler's range for the next. Re-arming instead of retuning across
that boundary loses the place in the cycle.

## What a stream can do

| action | meaning |
|---|---|
| **start** | begin from the beginning |
| **hold** | keep running, no restart: the count, the toggle level or the shape may be refreshed under it. Emitted only when one of them moved |
| **retune** | change the rate, keep the place in the cycle. The timer is stopped to reprogram it, so the period in flight is cut short |
| **live retune** | change the rate with the timer left running: control register, then data register, and the period in flight runs to its own end. Picked only where the source says a rate move was meant that way and the stream's parameter stood still |
| **release** | stop writing |
| **resume** | write again, from where it was |
| **expire** | stop because a one-shot sample ran out. PCM streams only |
| **loop** | the same end, met by a sample that carries a loop point: go back to it and carry on |
| **preempt** | take a register from a stream that was using it |
| **suppress** | fail to start because the register is taken, and retry next frame |

The code's word for an action the script hands the player is a **verb**:
three bits of a timer channel's action byte, and all eight codes are
spent - start alone takes four, one each for a toggle, a retrigger, a
sample and a sample that preempts. Not every action above is a verb. A
stream expires or loops inside its own tick handler, with nothing to hand
it; a live retune is a retune addressed to no voice rather than a code of
its own; and a suppressed start is a decision the packer took and never
emitted.

## Common techniques

Most of what a composer does needs no timer stream at all:

| technique | what it is here |
|---|---|
| digidrum kick or snare | **PCM stream**: rate set once, stops by itself, voice disconnected while it plays |
| SID lead | **toggle stream** over the tone generator's square wave |
| SID bass | the same, slow enough to need a different prescaler: the retune case |
| hard bass | no timer stream: **envelope period** and **shape** frame streams, voice following the envelope |
| sync buzzer | **retrigger stream** on the shape register, voice following the envelope |
| arpeggio | **tone period** frame stream, a new note each frame |
| vibrato, portamento | **tone period** frame stream, small steps |
| noise drums, hi-hat | **mixing** frame stream with noise on, plus a **volume** frame stream |
| a SID and a drum together | two timer channels. On one voice the PCM stream preempts and the toggle stream is suppressed until the drum ends |

A tracker effect that seems missing from the YM format is usually a frame
stream nobody named.

## The rest of the mapping

The YM format names bytes and fields; YMX names what those bytes do. The
registers hold bytes - the stream is the reading this engine puts on a
series of writes.

| YM5/YM6 | YMX |
|---|---|
| R0-R5, R6, R7 | **tone period**, **noise period**, **mixing** streams |
| R8-R10, R11-R12, R13 | **volume**, **envelope period**, **envelope shape** streams |
| effect; effect slot 1 and 2 | **timer stream**; **timer channels** 0 and 1 (four exist) |
| TP and TC, the prescaler and timer count fields | the two halves of a **rate** |
| vmax | the **toggle stream**'s set level |
| drum number, drum table | which stored sample a **PCM stream** plays, and where the samples are kept |
| player frequency | the **frame clock** |

| Atari ST | YMX |
|---|---|
| VBL, the vertical blank | the usual source of the **frame clock** |
| MFP timers A, B, C and D | what the file's map puts behind **timer channels** 0 to 3 |
| the MFP's prescaler and data register | **prescaler** and **timer count** |

A front end for another source format maps its own names onto these same
things, and keeps them on its own side of the `Tune`. What each conversion
costs is in its own account - [../ym/CONVERSION.md](../ym/CONVERSION.md)
for a YM file - and is not repeated here.

## The names in the code

| term | in the code |
|---|---|
| the four kinds | `KIND_PCM`, `KIND_TOGGLE`, `KIND_RETRIGGER`, `KIND_CURVE` - the **wave stream**, under its earlier name |
| the **verbs**, the actions a stream can be given | `VERB_START_PCM`, `VERB_START_TOGGLE`, `VERB_START_RETRIGGER`, `VERB_START_PCM_PREEMPT`, `VERB_HOLD`, `VERB_RETUNE`, `VERB_RELEASE`, `VERB_RESUME` |
| the tick handlers | `ymx_pcm_a`, `ymx_toggle_a_on`, `ymx_toggle_a_off`, `ymx_retrigger_a`, and the same per timer - they belong to the timer, not the channel |
| the timers and the map onto them | `ymx_timer_a` to `ymx_timer_d`, `ymx_desc_0` to `ymx_desc_3`, `ymx_assign` |
| the actions the script runs | `ymx_pcm`, `ymx_pcm_preempt`, `ymx_toggle_start`, `ymx_retrigger_start`, `ymx_retune`, `ymx_live` - the live retune it branches to - `ymx_resume`, `ymx_hold`, `ymx_release` |
| where a retrigger stream's shape comes from | `ymx_shape`, reading stream X's high nibble, because a shape belongs to the one envelope generator and not to a voice |
| a sample's loop point | `YMX_ONE_SHOT` for one that has none, `ymx_pcmloop` for the address the tick jumps back to |
| the frame write, the skips, the mixer | `ymx_wA`, `ymx_w7`, `ymx_wB`, `ymx_skips`, `YMX_MIXER` |
| a tune as the engine has one | `Tune` - the frame streams, the timer streams, the samples and the rate, and nothing a format would recognise |

`Ym6Reader` and `YmEffects` keep the YM names on their input side: those
are the names of the bytes. Together they are a **front end**; it stops at
a `Tune`, and everything past that point - `EffectScript`, the encoder,
the player - has no way to ask which format a tune was read out of. A
second front end for another source format is another such pair, neither
downstream of the other.

The packages say the same thing: `org.ym6` is the YM front end, and
`org.ymx` holds the engine, the format and the tools that work on a `.ymx`
file whatever made it. A front end is compiled against the engine, and the
engine against no front end - which is the layering said in a way the
compiler can check.

## If you know these ideas from elsewhere

| here | known elsewhere as |
|---|---|
| **frame clock**, **timer clocks** | **control rate** and **audio rate**; k-rate and a-rate in Csound and SuperCollider |
| **PCM stream** | **sample playback**, as a sampler does it |
| **toggle** and **wave streams** | **wavetable** oscillators. A toggle stream is the smallest one there is, two entries wide |
| **derived** rate | **key follow**, or key tracking |
| **coupling** | the **harmonicity ratio** of FM synthesis |
| **retrigger stream** | **hard sync** |
| the noise generator | an **LFSR**, a linear-feedback shift register |

**Phase policy** is the one idea without a settled name elsewhere. Synths
have free-running and retriggered LFOs, which is the same question.
Chiptune players are rarely explicit about it though each is consistent -
which is why two players can disagree audibly on the same file and both
be self-consistent.

## Where this went

The model is wider than YM5 and YM6 in two places. Format v10 spent both,
and the room was there because a stream is a source and a rate policy and
nothing else.

**A PCM stream can loop.** A sample table entry carries a loop word
beside the offset and the length: `$FFFF` means one-shot, and anything
else is an offset the tick jumps back to on meeting the end marker
instead of stopping the timer - 0 among them, the sample that repeats
whole. `YMX_init` resolves each pair to addresses once, so the tick that
meets the end has one long to load and no arithmetic to do. That makes a sample an instrument's sustain.

**A rate can move under a running timer.** The action byte's voice field
holds three voices in two bits, so 3 names none of them, and a retune
addressed to it writes the control register and then the data register
without ever stopping the timer: the period in flight runs to its own end
and the reload lands at the next underflow. That is the **live retune**,
and it is not a PCM thing - any kind's rate can move that way, keeping
its place in the cycle *and* its place inside the half it is in.

Neither reaches a YM tune. YM has no way to say a sample loops, so the YM
front end marks every one of them one-shot, and a YM rate change stays
the reference player's stop-load-run. Both are there for a source that
can express them.

Still ahead is the other half of the rate idea: a PCM stream with a
**derived** rate, a sample tracking the note the way a toggle stream
does. v10 carries a rate the source moves; nothing yet derives one from a
melody. With the loop point, that would be a wavetable in the ordinary
sense.

## Quick reference

**The sound chip**

| term | meaning |
|---|---|
| **YM2149** | the sound chip: Yamaha's AY-3-8910, at 2 MHz on an ST |
| **MFP** | the MC68901 support chip, which holds the timers |
| **register** | one byte of chip state. Sixteen; fourteen steer the sound |
| **generator** | a part of the chip that makes a signal by itself |
| **tone generator** | a counter flipping its output: a square wave. Three of them |
| **noise generator** | a shift register making a random-sounding pattern. One, shared |
| **envelope generator** | a counter walking one of sixteen shapes, four of which repeat |
| **voice** | one of the three outputs, A, B and C |
| **volume** | a voice's level: four bits, or bit 4 set for "follow the envelope" |
| **mixing** | which generators reach a voice: tone, noise, both or neither |
| **period** | how long a generator takes per cycle. Bigger period, lower pitch |
| **DAC** | the output ladder: about 3 dB a step, 16 steps from a volume register, 32 from the envelope |
| **timer** | MFP hardware counting down to an interrupt. Four exist; a tune may name any |
| **prescaler, timer count** | the two divisors that set a tick rate |

**The model**

| term | meaning |
|---|---|
| **frame** | one call to the player |
| **VBL** | the screen refresh, the usual thing a player is called from |
| **signal** | a series of values with a rate |
| **stream** | values arriving at one register at a steady speed: a target, a source, a rate |
| **frame stream** | ticked by the player's own call, one value per frame. The music |
| **timer stream** | ticked by a timer of its own, many values per frame. The YM format's "effect" |
| **tick** | one step of a stream's clock, and one register write |
| **effect code** | the YM file's way of starting one: a trigger on that frame, never "still running" |
| **rate** | how often a timer stream writes. Stored as prescaler and timer count |
| **set once** | the rate is fixed when the stream starts |
| **per-frame** | the rate is renewed on every frame |
| **independent rate** | set by nothing else: a sample's playback pitch |
| **derived rate** | set against the note playing, so it moves with the melody |
| **coupling** | what a derived rate is set against. The ratio is what you hear |
| **frame clock, control rate** | the rate the tune is stepped at. How often the deciding code runs |
| **timer clock, audio rate** | 48 to 25,600 a second. How often a sound-shaping write lands |
| **YM2149 clock** | 2,000,000 a second. Runs the generators; software has no access |
| **timer channel** | one place a timer stream can run, numbered 0 to 3. The file says which timer each gets |
| **volume stream** | a timer stream writing a voice's volume. Three of the four kinds are one |
| **phase** | where a stream is inside its own cycle |
| **phase policy** | what happens to phase across a stop: free-running, or zero-restart |
| **disconnect** | mix no generator into a voice, leaving only its volume writes |
| **frame write** | the once-a-frame round of register writes |
| **verb** | the code's name for an action the script hands the player. Three bits of an action byte, all eight spent |
| **packer** | the tool that turns a source file, a `.ym`, into a YMX file. One per front end |
| **front end** | the pair of classes that reads one source format and stops at a `Tune`. `org.ym6` for YM |
| **tracker** | the program a composer writes music in, with its own file format |
| **corpus** | the 544 YM files YMX is tested against; 543 readable |
| **script data** | per-frame instructions saying which streams start when. Stored like a stream, never written to a register |

**The timer streams**

| term | meaning |
|---|---|
| **PCM stream** | a stored sample, played once or looped from a stored loop point, rate independent. The YM front end disconnects the voice. Was: digidrum |
| **toggle stream** | a PCM stream of two values, repeating, voice connected. Cheap to run. Was: SID voice |
| **wave stream** | a PCM stream of a waveform, repeating, voice connected. Was: sinus SID |
| **retrigger stream** | not a volume stream: one shape written over and over, each write restarting the envelope. Was: sync buzzer |

**What a stream can do**

start, hold, retune, live retune, release, resume, expire, loop, preempt,
suppress - defined in the section of that name.

**Conflicts between the two clocks**

| term | meaning |
|---|---|
| **tearing** | a tick splits a select from its value; the value lands in the wrong register. One instruction per write prevents it, masking hides it |
| **contention** | frame write and timer stream target the same register |
| **skip** | a voice's volume left out of the frame write, for as long as a timer stream owns that register |
| **quantisation** | something happens between frames; only the next frame can act |
