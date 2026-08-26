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
