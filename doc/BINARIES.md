# The prebuilt binaries — combine contract

How a system without a 68000 assembler builds a playable SNDH file, and a
runnable TOS program, from binaries this repository assembles once.
`ymx/mkcores.sh` assembles them into `dist/`; `org.ymx.MkSndh` and
`org.ymx.MkPrg` are the reference combiners, and any tool that follows this
document produces the same files. Big-endian throughout; every offset and
size in bytes.

Two kinds of binary:

| file | contents |
|---|---|
| `ymxsndh-k1.bin`, `-k2`, `-k4` | an **SNDH core**: the player and its SNDH glue, one per ST4 unit size |
| `ymxprg.bin` | the **PRG stub**: a TOS program that drives an appended SNDH file |

A `-perf` or `-nomask` suffix marks a core assembled with the raster monitor
in, or with the frame write unmasked; the flags word below says which, so a
combiner verifies rather than parses names.

Every variant is published as a GitHub release tagged
`binaries-v<format version>`, staged by `ymx/mkrelease.sh`: twelve cores -
three unit sizes by the four flag combinations - the stub, and a
`MANIFEST.txt` of sizes and SHA-256 digests with the source commit. A new
format version is a new release; an unchanged one updates in place.

## The stack

```
+----------------------------------------------+
| PRG header, 28 bytes                         |
| PRG stub: ymxprg.bin, patched (§3)           |
|  +--------------------------------------+    |
|  | entry triple and tags (§2)           |    |
|  | SNDH core: ymxsndh-k<u>.bin, the     |    |
|  |   assembly options in its flags (§1) |    |
|  | subtune table                        |    |
|  | tune 1, .ymx                         |    |
|  | tune 2, .ymx                         |    |
|  | ...                                  |    |
|  | tune n, .ymx                         |    |
|  | workspace, zero bytes                |    |
|  +--------------------------------------+    |
| relocation table, one zero long              |
+----------------------------------------------+
```

The inner box is the SNDH file `org.ymx.MkSndh` writes; any SNDH host plays
it as it stands. `org.ymx.MkPrg` adds the outer box. The assembler produced
only the two named binaries; every other byte is the combiners' or the
tunes' own.

---

## 1. The SNDH core

Position-independent. Fixed layout at its start:

| offset | size | field |
|---:|---:|---|
| 0 | 4 | `bra.w` to init — the entry a combined file's outer header reaches |
| 4 | 4 | `bra.w` to exit |
| 8 | 4 | `bra.w` to play |
| 12 | 4 | `'YMXC'` |
| 16 | 2 | descriptor version — **1** |
| 18 | 2 | the ST4 unit size this core decodes: 1, 2 or 4 |
| 20 | 2 | flags: bit 0 = raster monitor built in, bit 1 = frame write unmasked |
| 22 | 2 | the format version the core reads — a combiner combines only tunes of the same version |
| 24 | 2 | `F`, the workspace bytes before the rings |
| 26 | 4 | table offset — written 0, patched by the combiner |
| 30 | 4 | workspace offset — written 0, patched by the combiner |

Both patched offsets are relative to the core's first byte and must be even.

The **subtune table** the table offset reaches: a word count `N`, then `N`
long offsets, each the position of one packed tune relative to the core's
first byte, each even. Init with subtune `s` (1-based, in `d0.w`) plays the
tune at entry `s`; out of range plays entry 1.

The **workspace** the workspace offset reaches: at least
`F + 25 · max(ring size)` zero bytes, where the maximum is over the combined
tunes' `N` header fields, and even-aligned. The core reads each tune's own
ring size, chunk and frame count from the tune's header at init, so tunes
with different rings and chunks combine into one file.

---

## 2. An SNDH file from a core

In order, with every part after the tag block even-aligned:

1. **The entry triple**, 12 bytes: three `bra.w` instructions
   (`$60 $00`, then a word displacement). Each branches to the same entry
   of the core's own triple, so with the header `H` bytes long — the triple
   plus the tag block, padded even — all three displacements are `H − 2`.
2. **The tag block**: `'SNDH'`, then the tags, then `'HDNS'`, padded even.
   The reference combiner writes, in order: `TITL` (NUL-terminated text),
   `COMM` (when there is a composer), `CONV`, `'##'` plus two ASCII digits
   of the subtune count plus NUL, `TC` plus the frame rate in ASCII plus
   NUL, `FLAG~ady` plus NUL, an even pad, `FRMS` with one long frame count
   per subtune (0 for a tune that starts over), `!#SN` with one word offset
   per subtune — each relative to the `!#SN` bytes — followed by the
   NUL-terminated names, an even pad, `HDNS`.
3. **The core**, with its two offsets patched.
4. **The subtune table** (§1).
5. **The tunes**, each even-aligned.
6. **The workspace** (§1), last.

Rules the combiner keeps: every tune's unit size equals the core's — a tune
whose sections are all stored reads the same at any unit size and combines
with any core — one frame rate across the set for the `TC` tag, and at most
99 subtunes, the `'##'` tag's two digits.

---

## 3. The PRG stub

Position-independent, raw, even-sized. Fixed layout at its start:

| offset | size | field |
|---:|---:|---|
| 0 | 4 | `bra.w` to the program |
| 4 | 4 | `'YMXP'` |
| 8 | 2 | descriptor version — **1** |
| 10 | 2 | subtunes — patched by the combiner |
| 12 | 4 | frames of subtune 1 when it plays once and stands alone — patched; 0 = play on |
| 16 | 2 | flags — patched: bit 0 = drop `YMXDONE.MRK` on exit |

The stub reaches the SNDH file at its own last byte: the file is appended
directly after it, at an even position — the reason the stub must be
even-sized.

## 4. A program from the stub

In order:

1. **The PRG header**, 28 bytes: `$601A`, text size (stub plus SNDH),
   then data, bss, symbol, reserved and flag longs, all 0, and a zero
   `absflag` word.
2. **The stub**, with its descriptor patched. The subtune count and frame
   count come out of the SNDH file's own `'##'` and `FRMS` tags; the frame
   count is 0 when the file holds more than one subtune.
3. **The SNDH file.**
4. **The relocation table**: one zero long — nothing in the stub or a
   position-independent SNDH file is relocated.

The program takes the machine over, calls play once per VBL, stops on
SPACE, and switches subtunes on the number keys 1-9.
