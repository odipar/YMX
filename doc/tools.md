# The tools

Every tool is a shell wrapper around one class: the wrapper finds the
repository and the classes, compiles them when they are missing, and hands
the arguments over. Each wrapper takes `-dotnet` as its FIRST argument to
run the C# tree in `dotnet/` instead of the Java one - the same tools,
producing the same bytes. `ToolsDocTest` reads this document back against
the sources: a flag a tool parses must appear in its section here, and the
defaults quoted below are the constants' own values.

| script | class | one line |
|---|---|---|
| `ym-to-ymx` (a standalone executable) | `Ym6.YmToYmx`, `org.ym6.YmToYmx` | a `.ym` in, a `.ymx`, SNDH file or TOS program out |
| `ymx/ymxplay.sh`, `ymx/ymxplay.cmd` | - | the same, then Hatari plays it |
| `ymx/publish.sh` | `go build` | build `ym-to-ymx` for each platform |
| - (`java -cp target/classes org.ym6.Ymx`) | `org.ym6.Ymx` | pack a `.ym` into a `.ymx` |
| `ymx/mksndh.sh` | `org.ymx.MkSndh` | combine packed tunes into an SNDH file |
| `ymx/mkprg.sh` | `org.ymx.MkPrg` | wrap an SNDH file in a runnable program |
| `ymx/mkcores.sh` | `org.ymx.MkCores` | assemble the prebuilt binaries (needs rmac) |
| `ymx/mkrelease.sh` | `org.ymx.MkRelease` | stage and publish the binaries release |
| `ymx/setversion.sh` | `org.ymx.SetVersion` | rewrite the format version, or the release version, at every site it reaches |
| `ym/ym_sndh.sh` | `org.ym6.YmSndh` | pack a set of `.ym` dumps and combine, in one command |
| `ym/play.sh` | `org.ym6.Play` | pack, build a program, run it under Hatari |
| `ymx/test/rig.sh` | `org.ymx.rig.PlayerTests` | the emulator test battery |
| `ymx/test/sweep.sh` | `org.ymx.rig.Sweep` | a `.ym` corpus, differentially |
| `ymx/test/run.sh` | `org.ymx.rig.GenData` + rmac + Hatari | the real-hardware harness |
| `ymx/test/ticks.sh` | `org.ymx.rig.TickDump` + Hatari | the tick reference against a real MFP |
| `ymx/test/cost.sh` | `ymx/test/cost.py` + Hatari | what a play call costs, in cycles |
| `ymx/test/damage.sh` | `ymx/test/damage.py` + the three readers | a tune read back with its bytes changed |

The packer has no wrapper of its own: the play script and `ym_sndh.sh`
run it, and a direct run is
`java -cp target/classes org.ym6.Ymx ...`,
`mvn -q compile exec:exec@ymx -Dargs="..."`, or
`dotnet dotnet/bin/Release/net10.0/ymx.dll ymx ...`. A wrong call prints
the tool's own usage (`mkcores.sh` and `mkrelease.sh` take no arguments,
so a bare call runs them), and this document collects the same
information in one place.

## From a YM dump, without a repository

### ym-to-ymx

One command from a YM dump to something that plays. Each release carries
it as a standalone executable for Windows, macOS and Linux: it needs no
JVM, no .NET SDK and no checkout, because the SNDH cores and the PRG stub
travel inside it. `publish.sh` builds that executable from the Go tree,
which cross-compiles to all six platforms from one machine and takes the
cores through `//go:embed`. The C# tree carries the same command and
embeds them as assembly resources. `org.ym6.YmToYmx` is a third reading of
the command line for `ymx/parity.sh` to hold the other two against, and
resolves the cores out of `dist/` rather than carrying them, so it runs
inside this repository alone.

    ym-to-ymx [options] output.{ymx|sndh|prg} tune.ym [more.ym ...]

The output's extension says what to write: a `.ymx` is the packed tune and
takes one input, a `.sndh` is a file any SNDH host plays, and a `.prg` is a
TOS program. Every option the packer and the combiners take is here, so
`-h` is the whole list. Where two tools spelled a flag the same way, the
packer keeps it: `-c24` is the chunk size and `-cName` the composer, told
apart by the digit.

### ymxplay.sh, ymxplay.cmd

Hear a tune: build a program from it with `ym-to-ymx`, then play it under
Hatari. `ymxplay.sh` is macOS and Linux, `ymxplay.cmd` Windows, and each
travels beside the executable in a release.

    ./ymxplay.sh [ym-to-ymx options] tune.ym [more.ym ...]

`HATARI` names the emulator and `TOS` its ROM image. Every argument goes
to `ym-to-ymx`, so `HATARI_OPTS` is where the emulator's own options go.

### publish.sh

Builds `ym-to-ymx` for each platform, one static file each with this
release's cores embedded, and zips each one with its launcher for
`mkrelease.sh` to attach. It stages the release's binaries first when
`dist/release` is not there yet, copies them where `//go:embed` takes
them, and removes every zip of this release at the start of a run and
each platform's directory before its build, so what a run leaves behind
is what that run built.

    ymx/publish.sh [outdir]
    TARGETS="linux-x64" ymx/publish.sh

| platform | |
|---|---|
| `win-x64` `win-arm64` | Windows |
| `osx-x64` `osx-arm64` | macOS |
| `linux-x64` `linux-arm64` | Linux |

It builds them from `go/`, and needs Go. That is why there are six: `go
build` cross-compiles to any target from any host with nothing installed
for it, so one machine covers all of them. The C# tree writes the same
bytes and is the reference the Go tree is held to, but it reaches three
of these from one machine at ten times the size: NativeAOT does not
compile across operating systems, and a self-contained single file
carries a runtime.

## The packer

### org.ym6.Ymx

Reads a YM5!/YM6! register dump - LHA-archived or unpacked, the reader
tells them apart - and writes a `.ymx`.

    ymx [-f] [-o] [-lF] [-nN] [-cC] [-kK] input.ym [output.ymx]
    ymx [options] one.ym two.ym more.ym output-dir/

| flag | meaning |
|---|---|
| `-f` | overwrite the output file |
| `-o` | play once: stop at the end instead of starting over |
| `-lF` | start over from frame F rather than from the frame the header gives; `-l0` from the beginning. Where the wrap cannot enter F the packer takes the next frame it can - `ym/CONVERSION.md` states how far it looks |
| `-nN` | ring size per stream, bytes (default 960). The packer raises it, up to the cap of 2520, where one pass of a tune needs a longer ring, and says so; the ring size the file carries is the header's, not this flag's |
| `-cC` | values decoded per call, the round-robin group size (default 24) |
| `-kK` | ST4 unit size 1, 2 or 4 (default 2); an odd tune length is padded with safe duplicate frames |
| `-copies` | let a match beyond the ring copy from the literal stream (SPEC.md Appendix A.5); the file sets flag bit 5 and plays only on a player built with `ST4_WINDOW` = N/K, which `mksndh` and `mkprg` take from the `-copies` cores |
| `-copiesS` | the same, searching S seconds a stream for a better parse |
| `-minM` `-secS` | trim: drop everything before M:S |
| `-startframeF` `-endframeF` `-framesN` | the same window in frames: start, end, or a length cap |
| `-drumhzH` | the drum rate ceiling (default 25600): a faster drum is resampled to fit, with a warning |
| `-timersT` | which MFP timer each channel runs on, one letter per channel from 0 up (`-timersBC` puts channel 0 on Timer B); the default is AD |
| `-sidresume` | the resume gap model (SPEC.md §3.3): a released toggle stream's timer keeps counting and a re-arrival resumes its phase - the model tunes written in maxYMiser need |
| `-meta` | print the header's title, author and frame rate, one per line, and pack nothing |
| `-script` | dump the compiled effect script instead of packing |

With a trailing DIRECTORY, every argument before it is an input, each
packed with the identical configuration into `<dir>/<stem>.ymx` - the set
one player build can hold as subtunes. The trim options take one tune.

## The combiners

### mksndh.sh

Combines a prebuilt SNDH core with packed tunes into an SNDH v2.2 file.
No assembler runs: the core comes from `dist/`, reassembled on the spot
when missing or stale. `doc/BINARIES.md` is the byte contract.

    ymx/mksndh.sh [-perf] [-nomask] [-tTitle] [-cComposer] [-Nnamesfile]
                  [-Pcorefile] output.sndh tune1.ymx [tune2.ymx ...]

| flag | meaning |
|---|---|
| `-perf` | combine with the raster-monitor core |
| `-nomask` | combine with the unmasked-frame-write core |
| `-tTitle` | the SNDH `TITL` tag (default: the output's stem) |
| `-cComposer` | the `COMM` tag; absent when not given |
| `-Nnamesfile` | subtune names, one per line (default: the tunes' stems) |
| `-Pcorefile` | a core file, instead of resolving one from `dist/` |

### mkprg.sh

A runnable TOS program around an SNDH file: the prebuilt stub, patched and
concatenated. Takes packed tunes (combined into an SNDH file first) or a
ready `.sndh`; both argument orders work, and the `.prg` argument names
the output.

    ymx/mkprg.sh [-m] [-perf] [-nomask] [-tTitle] [-cComposer] [-Nnamesfile]
                 output.prg tunes.ymx...|set.sndh

| flag | meaning |
|---|---|
| `-m` | drop `YMXDONE.MRK` on exit, for scripted runs |
| `-perf` `-nomask` `-tTitle` `-cComposer` `-Nnamesfile` | as mksndh.sh's |

The program picks its own clock when it runs, off the machine it finds:

| the machine refreshes at | the tune's rate | the calls come from |
|---|---|---|
| the tune's rate | any | the VBL |
| any other rate | any | Timer C |

The VBL is the screen's own, so a call on it holds one place on the
screen and nothing walks: a demo drawing to the music needs that, and a
`-perf` bar stands still on it. Timer C counts the MFP's
crystal, which the video clock does not track, so it carries any rate at
a third of a scanline a frame of drift. A 50 Hz tune on a PAL machine
takes the VBL; the same tune on a 60 Hz machine takes Timer C. A set that
claims Timer C for an effect channel leaves none to tick from and takes
the VBL either way.

### mkcores.sh

Runs rmac to assemble the three SNDH cores for one flag combination and,
in a plain run, the PRG stub, into `dist/` or the named directory. The
combiners run no assembler: `mksndh.sh` and `mkprg.sh` call this step in
when a binary under `dist/` is missing or stale, and `mkrelease.sh` runs
it for every variant.

    ymx/mkcores.sh [-perf] [-nomask] [-copies] [outdir]

### mkrelease.sh

Stages every core variant - three unit sizes by the `-perf` and `-nomask`
flags - plus the stub, verifies each descriptor, and writes MANIFEST.txt
with sizes and SHA-256 digests. It also carries in the standalone
`ym-to-ymx` zips `publish.sh` left in `dist/standalone`, digests them
into the same manifest and attaches them, so `publish.sh` runs after a
staging run and before the publishing one. Publishing reads this
release's section of `doc/RELEASES.md` and posts it as the release
notes, so a release with no account of what it changes is not
published.

    ymx/mkrelease.sh [-publish] [stagedir]
    ymx/mkrelease.sh -notes [release]

| flag | meaning |
|---|---|
| `-publish` | create or update the GitHub release `binaries-v<release>` through `gh`, replacing its assets |
| `-notes [release]` | rewrite a published page's notes and nothing else. Without a version it is this build's release; with one, any release still published. The commit the page names is the tag's own, not HEAD |

A new release is tagged at the staged commit, the one its notes name. An
existing tag stays where it is, so a run from another commit stops rather
than posting notes naming a commit the tag does not reach. A patch is
published beside the patch before it, and the superseded release is
deleted by hand: the tool removes no published release.

### setversion.sh

Rewrites one of the two versions at every site it reaches.

    ymx/setversion.sh -format MAJOR.MINOR
    ymx/setversion.sh -release MAJOR.MINOR[.PATCH]

| flag | what it moves |
|---|---|
| `-format` | the compatibility gate: the constants in `org.ymx.YmxFormat`, `dotnet/ymx/YmxFormat.cs`, `go/internal/ymx/format.go` and `68k/YMX.S`, and SPEC.md's four mentions |
| `-release` | the binaries' own version: three numbers in each of the three trees |

The format version word is the major in the high byte, the minor in the
low, so versions order numerically. It is in every header and the player
checks it, so moving it stops every tune already packed from playing. The
release version reaches no file and moving it breaks nothing. A site
whose surrounding text no longer matches fails loudly, and the
consistency tests read the same sites back.

A release patch defaults to 0, so `-release 0.9` clears it. After
`-format`: write this release's section in `doc/RELEASES.md`, reassemble
the cores (`mkcores.sh`), repin the corpus (`mvn test
-Dymx.pin=refresh`) and publish (`mkrelease.sh -publish`). `-release`
needs no repin, the tunes being untouched.

## Listening

### play.sh

Pack, build a program with the exit marker, and run it under Hatari. SPACE
in the emulator window stops; everything built lands in a work directory
next to the first tune, named after it and the shape.

    ym/play.sh [-perf] [-nomask] [-nRING] [-cCHUNK] [-kUNIT] [-o] song.ym...

| flag | meaning |
|---|---|
| `-perf` | build with the raster monitor |
| `-nomask` | build with the frame write unmasked |
| `-nN` `-cC` `-kK` `-o` | passed to the packer, as its own |
| `-h`, `--help` | print the usage and stop |

Any other `-flag` goes to the packer unread - the trim window and
`-drumhz` among them. `HATARI=` and `TOS=` point at your own install.

### ym_sndh.sh

From `.ym` dumps to one SNDH file in one command: the packer over every
input, then `mksndh.sh` around the results, titled and named from the
dumps' own headers.

    ym/ym_sndh.sh [-perf] [-tTitle] [packer flags] output.sndh tunes.ym...

Flags other than `-perf` and `-tTitle` go to the packer unread.

## The tests

The three player tests run the 68000 player under emulation and need rmac
and libunicorn; `ymx/test/run.sh` needs rmac and Hatari with a TOS image.
`ymx/test/check.sh` reads a file and drives nothing, so it needs neither.

### check.sh

A packed tune against SPEC.md §9.3 - the rules a player does not check. A
file that breaks one is undefined behaviour: the player reads it, drives
the chip from it and reports nothing, so a writer other than the packer
reads its output back with this.

    ymx/test/check.sh tune.ymx [more.ymx ...]
    ymx/test/check.sh -go tune.ymx [more.ymx ...]
    ymx/test/check.sh -dotnet tune.ymx [more.ymx ...]

`-go` and `-dotnet` as the first argument read the file with the Go tree
(`go/cmd/ymxcheck`) or the C# one instead of the Java one. All three read
the same rules off the same streams and report the same faults, over the
collection and over a file whose bytes have been changed one at a time.

One line per file - `within §9.3`, or a count and one line per place the
file leaves them, each naming the frame and the rule. A non-zero exit
where any file reports one.

What it reads:

| | the rules |
|---|---|
| the shape | `O`, `N` and `S` within §1.3 and §1.5; every section-table entry nonzero; `L` below `O`; the loop table's form against `O - L` and the ring, and its entries |
| the values | each register within the mask §2 gives it; M marks only channels §1.2 flags; a voice's skip set while a timer stream owns its volume register and clear otherwise, a sample's against §6's rejoin bound; stream T's map, its distinct timers at frame 0 and the bounds on a change |
| the actions | a programming opcode's prescaler index; `RELEASE`'s voice field; `RETUNE` over a running stream of the same voice; `RESUME` after a disabling release on a toggle stream; `HOLD`'s flags 2 and 4; one timer stream to a voice; `START_PCM_PREEMPT`'s X nibble against the channels it stops |

A voice a sample owns is read against §6's rejoin bound: a one-shot's
earliest end is the length, the prescaler, the count and the rate, and a
skip cleared before it claims an end that cannot have happened.

Two rules of §9.3 are outside it: the sample table's own bounds, which it
reads without checking, and R13's `$FF` on every frame that must not
restart the envelope, a marker whose absence is a value the file is free
to carry.

### damage.sh

A packed tune with its bytes changed one at a time, read back by every tree
that is built.

    ymx/test/damage.sh [tune.ymx]

A reader that stops the run on one damaged file reports nothing about that
file, and nothing about any file after it. Four inputs did that, and none of
the other batteries reached one of them: a negative sample-table offset, a
sample table near the int ceiling whose entry offset wraps, a loop table past
the file's end, and a malformed container. Each was one changed byte.

Every header byte is changed, and a spread through the body; each is changed
three times, by `$01`, `$80` and `$FF`, so a copy reaches a low bit, a high
bit and the whole byte. The default tune is
`doc/conformance/tunes/plain_packed.ymx`. A tree whose tools are absent is
named and left out.

The report counts what agreed and what differed, over every file. A non-zero
exit where any tree differs or ends its run, and the first fault they word
differently is printed. The generator and the comparison are
`ymx/test/damage.py`.

`DamagedFileTest` covers the Java reader alone and runs with the tests.

### rig.sh

The whole emulator battery: tune shapes, the SNDH container, the retrigger
shape, the sample loop, the stored cut, the loop-point resolve, the live
retune, the measured README and conversion numbers, and the effect stage
in two builds.

    ymx/test/rig.sh [--quick]

`--quick` leaves out the four-thousand-frame shapes. `YMX_NOMASK=1` runs
the battery against the unmasked-frame-write build.

### sweep.sh

Every `.ym` given, packed at `-k1` and replayed on the real player under
emulation, each chip write compared to the YM truth and an independent
model of the effect semantics. One status line per tune: OK, ISSUE,
PACKFAIL or SKIP; a non-zero exit on any ISSUE.

    ymx/test/sweep.sh song.ym [more.ym ...]

`YMX_PACK_OPTIONS` adds packer options for a shape no corpus tune reaches.

### cost.sh

What a play call costs. The `-perf` player paints the background red for
the length of a frame's work and each tick handler paints its own colour
inside that span, so a Hatari run tracing palette writes records every
call's duration. This builds such a program per tune, runs it, and reads
the spans back: one line per tune giving the average, the p99 and the
worst call, with the tick and timer-burn figures beside them.
`doc/performance.md` carries what it measured and the method in full.

    ymx/test/cost.sh tune.ymx [more.ymx ...]

`HATARI=` and `TOS=` point at your own install, and `VBLS=` sets how long
each tune plays.

### ticks.sh

The tick reference against a real MFP. `TickDump` models the timers,
because the unit-test emulator raises no interrupt; Hatari emulates
them. For each tune this builds a program, traces every sound-chip write
it makes and compares the two, one register at a time. One block per
tune, a line per register: how many ticks each side has, how many agree
from the start, and how many differ.

    ymx/test/ticks.sh [tune ...]

With no argument it takes the seven conformance tunes that run a timer;
the other three carry none. `HATARI=` and `TOS=` point at your own
install, and `VBLS=` sets how long each tune plays.

### run.sh

The real-hardware harness: generates the tune and expected checksum,
assembles `YMXTEST.PRG` and runs it under Hatari. The output is the
verdict - it must reach DONE, and no line may report BAD.

    ymx/test/run.sh [-dotnet]

## The ST4 CLIs

### st4 and dst4

The ST4 compressor's own command line, for packing and unpacking
plain ST4 containers outside a `.ymx`:

    st4 [-f] [-c[S]] [-kK] [-mN] [-lN] [-rR] input [output.st4]
    dst4 [-f] [-rN] input.st4 [output]

`-kK` is the unit size, `-mN` limits back-references to N units, `-lN`
splits matches so no operation exceeds N units, `-rR` loops the stream
from unit R, and `-c` lets a match beyond `-m` copy from the literal
stream, `-cS` searching S seconds for a better parse. `dst4 -rN` writes
the pass and then N - 1 repeats of the loop. Run them as
`dotnet dotnet/bin/Release/net10.0/ymx.dll st4 ...` or from the Java tree
with `java -cp target/classes org.st4.St4 ...`.

## Holding the three trees together

    ymx/parity.sh [-quick | -corpus]

Runs one command line through the Java, C# and Go trees and compares stdout,
stderr, the exit status and every file the run leaves. Each case runs three
times, once per tree, each in a directory of its own, and the directory is
hashed whole - so a file written under a different name is a difference and
not a pass.

The trees agreed on the bytes they packed long before they agreed on
anything else. What drifted was around them: a flag one tree parsed and
another took for a file name, a value that packed a file in one tree and
stopped the run in the next, a fault named in two trees and thrown as a
stack trace in the third. A sweep over output files alone sees none of it.

`YM_CORPUS` names the directory holding the `.ym` collection, and the other
two trees have to be built first. `-quick` is four tunes and the cases that
have caught something; the default is eight tunes and every case; `-corpus`
adds every tune in the collection, packed by all three trees in parallel: the
cases cover the options, and the collection covers the tunes. `ParityTest`
runs `-quick` and is skipped where `YM_CORPUS` is unset; `verify.sh -full`
runs `-corpus`.

## Every check, in one run

    ymx/verify.sh [-full]

The checks are spread over a Maven build, three trees, a file reader, an
emulator rig, a corpus sweep and two Hatari harnesses, so running one of
them answers for one of them. This runs each in turn: one line per step,
PASS, FAIL, or SKIP naming the tool or the directory that is absent. A step
that did not run is never a pass, and the exit status is non-zero where any
step failed.

Without `-full` it runs the steps that need no corpus and no Hatari: the
Maven build, the Go and C# builds, the §9.3 reader in all three trees, the
damaged-file sweep, and the player rig's quick battery. `-full` adds the
parity run, the rig in full, the corpus sweep and the tick reference.

Each step writes its own log, and the table names the log of every step that
failed.

## The C# tool names

`-dotnet` reaches these through the wrappers; directly, the first argument
of `dotnet dotnet/bin/Release/net10.0/ymx.dll` names the tool:

| name | the wrapper it serves |
|---|---|
| `ymx` | the packer |
| `st4`, `dst4` | the ST4 CLIs |
| `mksndh`, `mkprg`, `mkcores`, `mkrelease` | the combiners |
| `ymsndh`, `play` | `ym_sndh.sh`, `play.sh` |
| `rig`, `sweep`, `gendata` | the test rigs and `run.sh`'s data step |
| `check` | `check.sh`, the §9.3 reader |
| `setversion` | `setversion.sh` |

## Environment

| variable | read by | meaning |
|---|---|---|
| `HATARI`, `TOS` | play.sh, run.sh | the emulator and its TOS image |
| `UNICORN_LIB` | the rigs | where libunicorn is, when the usual paths and the pip wheel fail |
| `YMX_NOMASK` | rig.sh | assemble the player with the frame write unmasked |
| `YMX_PACK_OPTIONS` | sweep.sh | extra packer options for the sweep |
| `DAMAGE_WORK` | damage.sh | the directory the changed copies go in |
| `VERIFY_LOGS` | verify.sh | the directory the step logs go in |
| `YM_CORPUS` | mvn test, parity.sh, verify.sh | the directory holding the YM collection the documents count; without it the tests that read those figures back are skipped |
| `YMX_PLAY_FRAMES` | run.sh | how many frames the real-hardware harness plays; raise it to resolve a smaller cycle difference |
| `ymx.repo` / `YMX_REPO` | the combiners, the play tools and the rigs | the repository root, when not derivable (the Java property, the C# variable) |
| `ymx.core` / `YMX_CORE` | mksndh.sh | a core file, overriding `dist/` |
| `ymx.stub` / `YMX_STUB` | mkprg.sh | a stub file, overriding `dist/` |
