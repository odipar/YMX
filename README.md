# YMX - a streaming YM format for the Atari ST

## Read this first

**AI wrote most of YMX.** Claude wrote the Java and C# tools, the 68000
player and its SNDH core, the tests, the emulation rigs and most of what is
written here, under direction. The attribution section below says who did
what. If you would rather not use software written that way, this is not
the repository for you, and nothing here is meant to talk you out of that.

What it is built on is human, and older. The Atari ST chiptune scene comes
first: the musicians and coders who worked out what three voices and a noise
generator could be made to do, and who are the reason there is anything here
worth streaming. Arnaud Carré's YM format and ST-Sound recorded those tunes
and gave every file in the collection its shape. Grazey's long work got the
chiptunes into the open and keeps them there, which is why there is a
collection here to measure against. GwEm's maxYMiser is still the most
advanced tracker and player the ST has, and this player's timer code is
close to maxYMiser's. Tat's MinYMiser is what the rest is modelled on. Every
measurement in this repository was taken on Hatari. Behind all of it is the
wider body of Atari work. YMX rearranges what those established, and could
not exist without them.

**It is a tool for the people who make the music.** It was written by
odipar to put that tool in their hands: a musician writes the tune with a
tracker, the tracker writes the format, the player plays it on an Atari
ST with the RAM a real machine has, and the rest of us get to hear what
they made. Everything below is in service of that.

## The format

YMX extends the YM family - its packer reads YM5 and YM6 - into a format a
68000 plays without ever holding the tune in memory. A `.ymx` file carries
twenty-five independently compressed streams: fourteen for the sound
registers of the YM2149, the ST's sound chip, one value per frame, and
eleven carrying a **compiled effect script** that drives the timers of the
MFP, the ST's timer chip. Each stream decodes through its own small ring,
refilled one stream per frame, so a tune's cost in RAM follows the player's
configuration, not the tune's length.

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
| [doc/BINARIES.md](doc/BINARIES.md) | the prebuilt binaries, and how a tool combines them without an assembler |
| [doc/tools.md](doc/tools.md) | every tool's usage, flags and environment |
| [doc/terminology.md](doc/terminology.md) | the vocabulary all of these use |
| [doc/performance.md](doc/performance.md) | what a play call costs, in cycles, on real songs |
| [doc/experiments.md](doc/experiments.md) | ideas measured against the real corpus, and what the measurements said |
| [doc/RELEASES.md](doc/RELEASES.md) | what changed in each published set of binaries |

## From a YM dump, with nothing installed

Each release carries **`ym-to-ymx`**, one standalone executable per
platform, at
[github.com/odipar/YMX/releases](https://github.com/odipar/YMX/releases).
No JVM, no .NET, no checkout: the player binaries travel inside it.

```sh
ym-to-ymx tune.prg song.ym          # a TOS program that plays the tune
ym-to-ymx tune.sndh song.ym         # an SNDH file any host plays
ym-to-ymx tune.ymx song.ym          # just the packed tune
ym-to-ymx -h                        # every option
./ymxplay.sh song.ym                # the same, then Hatari plays it
```

The output's extension picks what is written. `ymxplay.sh` and
`ymxplay.cmd` travel beside it; `HATARI` names the emulator and `TOS` its
ROM image.

## Hearing a tune from the repository

Four tunes to try are under [ym/examples](ym/examples), chosen to be heard
rather than to cover a format feature:

```
ym/play.sh "ym/examples/Cuddly - main menu.ym"
```

```sh
mvn -q compile
ym/play.sh song.ym                  # pack a YM tune, build a player, run it
ym/play.sh -n2048 -c32 song.ym      # bigger rings and refills: cheaper on average
ym/play.sh -h                       # every flag
```

Both need `rmac`, and `hatari` with a TOS image. Press SPACE in the Hatari
window to stop.

To pack without playing:

```sh
mvn -q compile exec:exec@ymx -Dargs="song.ym song.ymx"
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

How big is the workspace? The tune's own header says: the packer records
the ring size it used, which can be larger than the `-n` it was
asked for when one pass of the tune needs a longer ring. So a program either
reads that header word, or reserves enough for the format's maximum and
stops caring. [68k/YMX.S](68k/YMX.S) gives both forms.

<!-- The two byte counts below are measured by the rig (ymx/test/rig.sh),
     which reads them back out of this sentence: keep the shape of it. -->
[68k/YMX.S](68k/YMX.S) is the player, 3,434 bytes at the `ST4_UNIT` 2 below,
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
| [`org.ymx.Tune`](src/main/java/org/ymx/Tune.java) | what a front end produces and the engine works on - no format anywhere in it |
| [`org.ymx.EffectScript`](src/main/java/org/ymx/EffectScript.java) | the script compiler: a `Tune` in, prepared actions out |
| [68k/YMX.S](68k/YMX.S) | the player |
| [68k/YMX_sndh.S](68k/YMX_sndh.S), [68k/YMX_player.S](68k/YMX_player.S) | the SNDH core and the PRG stub, prebuilt by [ymx/mkcores.sh](ymx/mkcores.sh) |
| [`org.st4`](src/main/java/org/st4) | the ST4 compressor, a copy carried here |
| [68k/](68k) | all the 68000 sources: the player, its wrappers, the ST4 decoders |
| [dotnet/](dotnet) | the C# tree: every tool and rig again, producing the same bytes |
| [go/](go) | the Go tree: the tools again, producing the same bytes |

The front end stops at a `Tune`, and no field past that point records what
format a tune came out of: the engine works on the `Tune` alone.

## Tests

```sh
mvn test                              # the packer, 41 pinned tunes, a rig slice
ymx/test/rig.sh                       # the player, under emulation
ymx/test/sweep.sh songs/*.ym          # a YM collection, differentially
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

The Go tree in [go/](go) is the third, carrying the tools rather than the
rigs. [ymx/parity.sh](ymx/parity.sh) runs one command line through all three
and compares everything it leaves.

The sweep is the broadest of these. It replays a converted tune on the
real player under emulation and compares every write it makes to the sound
chip - and which MFP timers it claimed - against an independent model of the
source file. A disagreement is reported exactly where it happened, which is
how most of the bugs in this player were found.

## Where this came from

YMX began as the `.yx6` container from the
[ST4](https://github.com/odipar/ST4) repository, adopted whole and
renumbered. ST4 is the compression format underneath, and stays there; this
repository keeps a copy of the parts it needs, and ST4 goes on being
developed in its own.

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

The YMX format and its additions are © 2026 Robbert van Dalen. Claude
(Anthropic's Claude Code) wrote the Java and C# tools, the 68000 player and
its SNDH core, the tests and the emulation rigs, under Robbert's direction.

The player was inspired by Steven Tattersall's MinYMiser.

Special thanks to Sandor Drieënhuizen and Wietze Spijkerman for their support,
proofreading, and ideas.
