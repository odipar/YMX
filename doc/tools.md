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
| - (`java -cp target/classes org.ym6.Ymx`) | `org.ym6.Ymx` | pack a `.ym` into a `.ymx` |
| - (`java -cp target/classes org.ymr.Ymr`) | `org.ymr.Ymr` | pack a `.ymr` into a `.ymx` |
| `ymx/mksndh.sh` | `org.ymx.MkSndh` | combine packed tunes into an SNDH file |
| `ymx/mkprg.sh` | `org.ymx.MkPrg` | wrap an SNDH file in a runnable program |
| `ymx/mkcores.sh` | `org.ymx.MkCores` | assemble the prebuilt binaries (needs rmac) |
| `ymx/mkrelease.sh` | `org.ymx.MkRelease` | stage and publish the binaries release |
| `ymx/setversion.sh` | `org.ymx.SetVersion` | rewrite the format version at every site that carries it |
| `ym/ym_sndh.sh` | `org.ym6.YmSndh` | pack a set of `.ym` dumps and combine, in one command |
| `ym/play.sh` | `org.ym6.Play` | pack, build a program, run it under Hatari |
| `ymr/ymr.sh` | `org.ymr.YmrPlay` | the same test drive for a `.ymr` |
| `ymx/test/rig.sh` | `org.ymx.rig.PlayerTests` | the emulator test battery |
| `ymx/test/sweep.sh` | `org.ymx.rig.Sweep` | a `.ym` corpus, differentially |
| `ymx/test/ymr_sweep.sh` | `org.ymx.rig.YmrSweep` | a `.ymr` corpus, differentially |
| `ymx/test/run.sh` | `org.ymx.rig.GenData` + rmac + Hatari | the real-hardware harness |

The two packers have no wrapper of their own: the play scripts and
`ym_sndh.sh` run them, and a direct run is
`java -cp target/classes org.ym6.Ymx ...`,
`mvn -q compile exec:exec@ymx -Dargs="..."`, or
`dotnet dotnet/bin/Release/net10.0/ymx.dll ymx ...`. A wrong call prints
the tool's own usage - `mkcores.sh` and `mkrelease.sh` run with no
arguments - and this document is the same information in one place.

## The packers

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
| `-nN` | ring size per stream, bytes (default 960) |
| `-cC` | values decoded per call, the round-robin group size (default 24) |
| `-kK` | ST4 unit size 1, 2 or 4 (default 2); an odd tune length is padded with safe duplicate frames |
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

### org.ymr.Ymr

Reads a RhYMe `.YMR` version 1.3 register dump and writes the same `.ymx`.
The flags shared with the YM packer mean the same things; what a `.YMR`
does not have, the tool does not offer - no `-drumhz` (a `.YMR` sample has
no rate of its own), no `-timers` (the timer-to-voice binding is
normative), no `-sidresume` (a YM argument) and no `-meta` (a `.YMR`
carries no metadata).

    ymr [-f] [-o] [-lF] [-nN] [-cC] [-kK] input.ymr [output.ymx]
    ymr [options] one.ymr two.ymr more.ymr output-dir/

| flag | meaning |
|---|---|
| `-f` `-o` `-lF` `-nN` `-cC` `-kK` | as the YM packer's |
| `-minM` `-secS` `-startframeF` `-endframeF` `-framesN` | the trim window, as the YM packer's |
| `-script` | dump the compiled effect script instead of packing |

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

### mkcores.sh

Runs rmac to assemble the three SNDH cores for one flag combination and,
in a plain run, the PRG stub, into `dist/` or the named directory. The
combiners run no assembler: `mksndh.sh` and `mkprg.sh` call this step in
when a binary under `dist/` is missing or stale, and `mkrelease.sh` runs
it for every variant.

    ymx/mkcores.sh [-perf] [-nomask] [outdir]

### mkrelease.sh

Stages every core variant - three unit sizes by the `-perf` and `-nomask`
flags - plus the stub, verifies each descriptor, and writes MANIFEST.txt
with sizes and SHA-256 digests. Publishing reads this release's section
of `doc/RELEASES.md` and posts it as the release notes, so a release
with no account of what it changes is not published.

    ymx/mkrelease.sh [-publish] [stagedir]

| flag | meaning |
|---|---|
| `-publish` | create or update the GitHub release `binaries-v<release>` through `gh`, replacing its assets |

### setversion.sh

Rewrites the version at every site that carries it: the format constants
in `org.ymx.YmxFormat`, `dotnet/ymx/YmxFormat.cs` and `68k/YMX.S`,
SPEC.md's three mentions, and the two patch constants. The format
version word is the major in the high byte, the minor in the low, so
versions order numerically; the patch is the released binaries' own
number, which never reaches that word. A site whose surrounding text no
longer matches fails loudly, and the consistency tests read the same
sites back.

    ymx/setversion.sh MAJOR.MINOR[.PATCH]

The patch defaults to 0, so a format bump clears it. After a bump:
reassemble the cores (`mkcores.sh`), repin the corpus
(`mvn test -Dymx.pin=refresh`) and publish (`mkrelease.sh -publish`). A
patch alone - the binaries changed, the format did not - needs no repin.

## Listening

### play.sh

Test drive: pack, build a program with the exit marker, run it under
Hatari. SPACE in the emulator window stops; everything built lands in a
work directory next to the first tune, named after it and the shape.

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

### ymr.sh

`play.sh` with the `.ym` step replaced: the same flags mean the same
things, and the work directory is named the same way.

    ymr/ymr.sh [-perf] [-nomask] [-nRING] [-cCHUNK] [-kUNIT] [-o] song.ymr...

`-h` and `--help` print the usage; other `-flags` go to the `.ymr` packer.

## The tests

The three player tests run the 68000 player under emulation and need rmac
and libunicorn; `ymx/test/run.sh` needs rmac and Hatari with a TOS image.

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

### ymr_sweep.sh

The same for `.ymr`, against this rig's own decoder and replay of the
image; without arguments it sweeps `ymr/test/deeper.ymr`.

    ymx/test/ymr_sweep.sh [song.ymr ...]

`YMR_FRAME_CAP` raises the walk's frame cap (default 1200) - the only way
to reach a long tune's wrap.

### run.sh

The real-hardware harness: generates the tune and expected checksum,
assembles `YMXTEST.PRG` and runs it under Hatari. The output is the
verdict - it must reach DONE, and no line may report BAD.

    ymx/test/run.sh [-dotnet]

## The ST4 CLIs

### st4 and dst4

The vendored compressor's own command line, for packing and unpacking
plain ST4 containers outside a `.ymx`:

    st4 [-f] [-kK] [-mN] [-lN] input [output.st4]
    dst4 [-f] input.st4 [output]

`-kK` is the unit size, `-mN` limits back-references to N units, `-lN`
splits matches so no operation exceeds N units. Run them as
`dotnet dotnet/bin/Release/net10.0/ymx.dll st4 ...` or from the Java tree
with `java -cp target/classes org.st4.St4 ...`.

## The C# tool names

`-dotnet` reaches these through the wrappers; directly, the first argument
of `dotnet dotnet/bin/Release/net10.0/ymx.dll` names the tool:

| name | the wrapper it serves |
|---|---|
| `ymx`, `ymr` | the two packers |
| `st4`, `dst4` | the ST4 CLIs |
| `mksndh`, `mkprg`, `mkcores`, `mkrelease` | the combiners |
| `setversion` | `setversion.sh` |
| `ymsndh`, `play`, `ymrplay` | `ym_sndh.sh`, `play.sh`, `ymr.sh` |
| `rig`, `sweep`, `ymrsweep`, `gendata` | the test rigs and `run.sh`'s data step |

## Environment

| variable | read by | meaning |
|---|---|---|
| `HATARI`, `TOS` | play.sh, ymr.sh, run.sh | the emulator and its TOS image |
| `UNICORN_LIB` | the rigs | where libunicorn is, when the usual paths and the pip wheel fail |
| `YMX_NOMASK` | rig.sh | assemble the player with the frame write unmasked |
| `YMX_PACK_OPTIONS` | sweep.sh | extra packer options for the sweep |
| `YMR_FRAME_CAP` | ymr_sweep.sh | the .ymr sweep's frame cap |
| `ymx.repo` / `YMX_REPO` | the combiners, the play tools and the rigs | the repository root, when not derivable (the Java property, the C# variable) |
| `ymx.core` / `YMX_CORE` | mksndh.sh | a core file, overriding `dist/` |
| `ymx.stub` / `YMX_STUB` | mkprg.sh | a stub file, overriding `dist/` |
