# The Go tools

A third implementation of the tools, beside `src/` (Java) and `dotnet/`
(C#). It exists for one reason the other two cannot give: `go build`
cross-compiles to every target from any host, with no toolchain to
install, so one machine builds the standalone executables for Windows,
macOS and Linux on both architectures.

It is held to the same bar as the other two: byte-identical output.
`go/test/against.sh` runs a tune through this tree and through the C#
tree and compares the bytes, and nothing here is finished until that
passes over the corpus.

## The commands

| command | what it does |
|---|---|
| `st4` | compress a file into an ST4 container |
| `dst4` | take one apart again |
| `ymx` | pack a YM dump into a .ymx |
| `ym-to-ymx` | a YM dump to a .ymx, an SNDH file or a TOS program |
| `mksndh` | combine packed tunes into an SNDH file |
| `mkprg` | wrap packed tunes, or an SNDH file, in a program |
| `ymsndh` | pack a set of dumps and combine them in one step |
| `play` | pack a tune, build a program and run it under Hatari |
| `ymxcheck` | read a packed tune back against SPEC.md §9.3 |

`mkrelease`, `setversion`, `rig`, `sweep` and `gendata` stay in the other
two trees, and `mkcores` in the Java one alone. The published standalone
`ym-to-ymx` is built from the C# tree; this one and the Java one carry the
same command without carrying the cores. They assemble the 68000
sources, cut releases and run the test rig, which is the repository's own
work rather than a user's, and a third copy of them would be three places
to keep a figure in step.
