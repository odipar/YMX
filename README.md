# YMX — a streaming YM format for the plain 68000

YMX extends the YM family — YM3, YM4, YM5, YM6 — into a format a 68000 plays
without ever holding the tune in memory. A `.ymx` file carries twenty-five
independently compressed streams: fourteen for the YM2149's sound registers,
one value per frame, and eleven carrying a **compiled effect script** that
drives the MFP's timers. Each stream decodes through its own small ring,
refilled one stream per frame, so what a tune costs in RAM is a property of
the player's configuration rather than of the tune's length.

The one thing YMX changes about the YM lineage is where the work happens.
What those formats call a "special effect" — a SID voice, a digidrum, a
sync-buzzer — is carried in spare register bits, and the player has to
re-derive on every frame what it means. YMX resolves all of that when the
file is packed and writes down the outcome, so the player compares nothing
and every frame costs the same.

[**doc/SPEC.md**](doc/SPEC.md) is the format: the container, the streams, the
verbs and the frame contract. Everything else worth reading is beside it.

| | |
|---|---|
| [doc/SPEC.md](doc/SPEC.md) | the format specification |
| [ym/CONVERSION.md](ym/CONVERSION.md) | what a YM file loses on the way in |
| [ymr/CONVERSION.md](ymr/CONVERSION.md) | what a `.YMR` loses on the way in |
| [doc/terminology.md](doc/terminology.md) | the vocabulary all of these use |
| [doc/experiments.md](doc/experiments.md) | ideas measured against the real corpus, and what the measurements said |

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
mvn -q exec:exec@ymx -Dargs="song.ym song.ymx"
mvn -q exec:exec@ymr -Dargs="song.ymr song.ymx"
```

## Building a tune into something runnable

```sh
ymx/mksndh.sh MY.SNDH build/*.ymx        # an SNDH v2.2 file: the canonical build
ymx/mkprg.sh MY.PRG build/*.ymx          # a TOS program around those same bytes
ym/ym_sndh.sh -t"My Set" my.sndh *.ym    # both steps in one
```

SNDH is the Atari ST's standard music container, and it is where the player
really lives: the `.PRG` is a thin shell around the same blob, so the two
share every byte that matters.

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

[68k/YMX.S](68k/YMX.S) is the player, 3,180 bytes at the `ST4_UNIT` 2 below,
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
| [`org.ymx.Tune`](src/main/java/org/ymx/Tune.java) | what a front end produces and the engine works on — no format anywhere in it |
| [`org.ymx.EffectScript`](src/main/java/org/ymx/EffectScript.java) | the script compiler: a `Tune` in, prepared actions out |
| [68k/YMX.S](68k/YMX.S) | the player |
| [68k/YMX_sndh.S](68k/YMX_sndh.S), [68k/YMX_player.S](68k/YMX_player.S) | the SNDH and `.PRG` wrappers |
| [`org.st4`](src/main/java/org/st4) | the ST4 compressor, vendored |
| [68k/](68k) | all the 68000 sources: the player, its wrappers, the ST4 decoders |
| [`org.jx1`](src/main/java/org/jx1) | the ZX1 decoder a `.YMR`'s own streams need, vendored |

The two front ends are peers. Neither is downstream of the other: both read
their own format and produce a `Tune`, and nothing past that point can ask
which format a tune came out of.

## Tests

```sh
mvn test                                   # the packers: formats, effects, shapes
python3 ymx/test/emu/test_ymx.py           # the player, under emulation
python3 ymx/test/sweep.py songs/*.ym       # a YM collection, differentially
python3 ymx/test/ymr_sweep.py songs/*.ymr  # the same for .YMR
```

The two sweeps are the ones that matter. Each replays a converted tune on the
real player under emulation and compares every write it makes to the sound
chip — and which MFP timers it claimed — against an independent model of the
source file. A disagreement is reported exactly where it happened, which is
how most of the bugs in this player were found.

## Where this came from

YMX is the `.yx6` container from the
[ST4](https://github.com/odipar/ST4) repository, adopted whole and renumbered
to version 1. ST4 is the compression format underneath, and stays there; this
repository vendors the parts it needs and has its own life-cycle.

The results of the experiments that shaped the player came across too, in
[doc/experiments.md](doc/experiments.md). What stayed behind is their full
logs — the false trails and the instrument readings — and the
version-by-version argument for a container that now simply is what
[doc/SPEC.md](doc/SPEC.md) says. Both are in ST4's history if anyone wants
them.

## License and attribution

ST4 is built on [ZX1](https://github.com/einar-saukas/ZX1) by Einar Saukas,
through [ST1](https://github.com/odipar/ST1). Use it freely, including
commercially, as long as your documentation says you used ZX1 through ST4.
See [LICENSE](LICENSE).

The player was inspired by Steve Clarets' MinYMiser. Sinus-SID is the one YM
effect deliberately left unplayed, as it is in every other player, including
the format author's.
