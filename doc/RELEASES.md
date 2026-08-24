# The releases

What changed in each published set of binaries, newest first.
`ymx/mkrelease.sh -publish` reads the section whose heading matches the
release it is staging and publishes it as the release notes, so the
release page says why the release exists. A release without a section
here is not published.

A release version is the format version the binaries read, then the
patch number the binaries carry of their own. The format version is the
compatibility gate: tunes packed at one format version play on every
patch of it.

## 0.4.1

The player, optimized. Every tune packed at format 0.4 plays unchanged -
the format did not move.

- 3,256 bytes at unit size 2, where 0.4 carried 3,324.
- Cheaper by about 94 cycles a frame, and the measurement resolves to
  within about 24 of that: `ymx/test/run.sh` under Hatari 2.6.1 on a
  cycle-exact 8 MHz ST with TOS 2.06 played 1,700 frames of one tune in
  89 ticks of the 200 Hz clock where 0.4 took 93, three runs each, with
  the same chip-write checksum.
- The frame write counts its register selects through one step register,
  the channel-to-timer map is read through a displacement the init
  patches, the sample tick steps its pointer through its own patched
  address, and init sets up its streams in registers.
- `YMX_SUPER_HOST` is gone: the sample tick no longer borrows an address
  register, so the flag chose nothing. A build line that defines it
  still assembles.

## 0.4

The first release of format 0.4, renumbered from 1 while the format is
short of the stability 1.0 states. The binaries' names carry the release
version, so files from different releases tell apart on sight.

- A sample's loop point resolves as an unsigned word: a loop point of
  `$8000` through `$FFFE` addressed 65,536 bytes below the sample.
- Every timer a claim takes is handed back as it was found.
