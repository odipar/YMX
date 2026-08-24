# org.jx1 - the vendored ZX1 decoder

ST4's own format is not [ZX1](https://github.com/einar-saukas/ZX1), so nothing
here is on ST4's path - this is a single file kept for the one job ST4's own
decoders cannot do: reading somebody else's ZX1.

RhYMe's `.YMR` register dumps are ZX1, one stream at a time, each packed
against the ring its player will decode it through. [`org.ymr.Zx1`](../ymr/Zx1.java)
reads them, and it reads them with the ZX1 implementation that already exists
rather than with a second one written to match it: a decoder is only worth
having if it is the same decoder, and two ports of one format drift the moment
either is touched.

`Decompressor.java` is `org.jx1.Decompressor` from
[jx1](https://github.com/odipar/jx1), which is itself a port of `dzx1.c`. It
streams its output through a caller-supplied ring, which is exactly the
contract `.YMR` is packed for - the ring is at once the decoder's window and
its output queue, and the packer never looks further back than it.

## What was changed

One thing, and only because of what it drags in:

- `lastOffset = Optimizer.INITIAL_OFFSET` became `lastOffset = INITIAL_OFFSET`,
  a constant declared here. Upstream reads the 1 out of the optimal parser,
  which is 350 lines of packer this repository has no use for.

Nothing else. Fix a bug here by fixing it in jx1 and copying the file again.
