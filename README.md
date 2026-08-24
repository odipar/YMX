# YMX - a streaming YM format for the plain 68000

YMX extends the YM family - its packer reads YM5 and YM6 - into a format a
68000 plays without ever holding the tune in memory. A `.ymx` file carries
twenty-five independently compressed streams: fourteen for the YM2149's sound
registers, one value per frame, and eleven carrying a **compiled effect
script** that drives the MFP's timers. Each stream decodes through its own
small ring, refilled one stream per frame, so a tune's cost in RAM follows the
player's configuration, not the tune's length.

YMX changes one thing about the YM lineage: where the work happens.
What those formats call a "special effect" - a SID voice, a digidrum, a
sync-buzzer - is carried in spare register bits, and the player has to
re-derive on every frame what it means. YMX resolves that at pack time and
writes down the outcome, so the player compares nothing and every frame
costs the same.

[**doc/SPEC.md**](doc/SPEC.md) is the format: the container, the streams, the
opcodes and the frame contract. The rest of the documentation is beside it.

| | |
|---|---|
| [doc/SPEC.md](doc/SPEC.md) | the format specification |
| [ym/CONVERSION.md](ym/CONVERSION.md) | what a YM file loses on the way in |
| [ymr/CONVERSION.md](ymr/CONVERSION.md) | what a `.YMR` loses on the way in |
| [doc/BINARIES.md](doc/BINARIES.md) | the prebuilt binaries, and how a tool combines them without an assembler |
| [doc/tools.md](doc/tools.md) | every tool's usage, flags and environment |
| [doc/terminology.md](doc/terminology.md) | the vocabulary all of these use |
| [doc/experiments.md](doc/experiments.md) | ideas measured against the real corpus, and what the measurements said |
| [doc/RELEASES.md](doc/RELEASES.md) | what changed in each published set of binaries |

## Test driving one

```sh
mvn -q compile
ym/play.sh song.ym                  # pack a YM tune, build a player, run it
ymr/ymr.sh song.ymr                 # the same for a .YMR register dump
ym/play.sh -n2048 -c32 song.ym      # longer calls: cheaper on average
ym/play.sh -h                       # every flag
```

Both need `rmac`, and `hatari` with a TOS image. Press SPACE in the Hatari
window to stop.

To pack without playing:

```sh
mvn -q compile exec:exec@ymx -Dargs="song.ym song.ymx"
mvn -q compile exec:exec@ymr -Dargs="song.ymr song.ymx"
```

## Building a tune into something runnable

```sh
ymx/mkcores.sh                           # assemble the player binaries, once
ymx/mksndh.sh MY.SNDH build/*.ymx        # an SNDH v2.2 file: the canonical build
ymx/mkprg.sh MY.PRG build/*.ymx          # a TOS program around those same bytes
ym/ym_sndh.sh -t"My Set" my.sndh *.ym    # pack and combine in one
```

The combiners run no assembler: `mkcores.sh` assembles the binaries, and
`mksndh.sh` runs it for you the first time. Combining is byte appending and
patching - a tracker or another build system does it without a 68000
toolchain; [doc/BINARIES.md](doc/BINARIES.md) is the contract, and
`ymx/mkrelease.sh -publish` puts every prebuilt variant in a GitHub release
for systems without the repository.

SNDH is the Atari ST scene's shared music container, and where the player
lives: the `.PRG` is a thin stub in front of the same bytes, so the two share
the player byte for byte.

## Using the player

```
        lea     song,a0                 ; the .ymx file, loaded anywhere
        lea     workspace,a1            ; even address, YMX_SIZE bytes
        bsr     YMX_init                ; d0 = 0 when the file was accepted
   vbl:                                 ; once per frame, in supervisor mode
        lea     workspace,a0
        bsr     YMX_play                ; d0 = 0 played, 1 wrapped, -1 ended
        ...
        lea     workspace,a0
        bsr     YMX_stop                ; chip quiet, timers stopped
```

`YMX_SIZE` follows the ring size the tune's own header gives, and the packer
raises that above the `-n` it was asked for where one pass of a tune needs a
longer ring: a program reads the header word rather than the flag the tune
was packed with, or reserves for the format's cap. [68k/YMX.S](68k/YMX.S)
gives both forms.

<!-- The two byte counts below are measured by the rig (ymx/test/rig.sh),
     which reads them back out of this sentence: keep the shape of it. -->
[68k/YMX.S](68k/YMX.S) is the player, 3,394 bytes at the `ST4_UNIT` 2 below,
plus the 288 of [68k/ST4_wrap.S](68k/ST4_wrap.S), the stream decoder it is
built on. Include both, with the unit size defined first:

```
ST4_UNIT    equ     2
        include "YMX.S"
        include "ST4_wrap.S"
```

## What's here

| | |
|---|---|
| [`org.ym6.Ymx`](src/main/java/org/ym6/Ymx.java) | the packer: `YM5!`/`YM6!` in, `.ymx` out |
| [`org.ymr.Ymr`](src/main/java/org/ymr/Ymr.java) | the other packer: `YMR!` in, the same `.ymx` out |
| [`org.ymx.Tune`](src/main/java/org/ymx/Tune.java) | what a front end produces and the engine works on - no format anywhere in it |
| [`org.ymx.EffectScript`](src/main/java/org/ymx/EffectScript.java) | the script compiler: a `Tune` in, prepared actions out |
| [68k/YMX.S](68k/YMX.S) | the player |
| [68k/YMX_sndh.S](68k/YMX_sndh.S), [68k/YMX_player.S](68k/YMX_player.S) | the SNDH core and the PRG stub, prebuilt by [ymx/mkcores.sh](ymx/mkcores.sh) |
| [`org.st4`](src/main/java/org/st4) | the ST4 compressor, vendored |
| [68k/](68k) | all the 68000 sources: the player, its wrappers, the ST4 decoders |
| [`org.jx1`](src/main/java/org/jx1) | the ZX1 decoder a `.YMR`'s own streams need, vendored |
| [dotnet/](dotnet) | the C# tree: every tool and rig again, producing the same bytes |

The two front ends are peers. Neither is downstream of the other: both read
their own format and produce a `Tune`, and no field past that point records
which format a tune came out of.

## Tests

```sh
mvn test                              # packers, 39 pinned tunes, a rig slice
ymx/test/rig.sh                       # the player, under emulation
ymx/test/sweep.sh songs/*.ym          # a YM collection, differentially
ymx/test/ymr_sweep.sh songs/*.ymr     # the same for .YMR
```

The three player tests run the 68000 player under emulation and need rmac
and libunicorn (`brew install unicorn`, or `UNICORN_LIB` names the
library).

Two tests read the documents' figures back against the YM collection they
count, which is not in the tree. `YM_CORPUS` says which directory holds
it, and `mvn test` skips those two without it:

```sh
YM_CORPUS=/path/to/ym_collection mvn test
```

Every shell script also takes `-dotnet` as its first argument, which runs
the C# tree in [dotnet/](dotnet) instead of the Java one - the same tools
and rigs, producing the same bytes, built by the .NET SDK on first use.

The two sweeps are the broadest of these. Each replays a converted tune on the
real player under emulation and compares every write it makes to the sound
chip - and which MFP timers it claimed - against an independent model of the
source file. A disagreement is reported exactly where it happened, which is
how most of the bugs in this player were found.

## Where this came from

YMX began as the `.yx6` container from the
[ST4](https://github.com/odipar/ST4) repository, adopted whole and
renumbered. ST4 is the compression format underneath, and stays there; this
repository vendors the parts it needs and has its own life-cycle.

The results of the experiments that shaped the player came across too, in
[doc/experiments.md](doc/experiments.md). What stayed behind is their full
logs - the false trails and the instrument readings - and the
version-by-version argument for a container that is now what
[doc/SPEC.md](doc/SPEC.md) says. Both are in ST4's history if anyone wants
them.

## License and attribution

ST4 is built on [ZX1](https://github.com/einar-saukas/ZX1) by Einar Saukas,
through [ST1](https://github.com/odipar/ST1). Use it freely, including
commercially, as long as you indicate somehow in your documentation that you
have used ZX1, via ST4 or YMX. See [LICENSE](LICENSE).

The player was inspired by Steven Tattersall's MinYMiser. Sinus-SID is the one
YM effect this player leaves unplayed. ST-Sound, the format author's own
player, reads the effect code and runs an empty handler.
