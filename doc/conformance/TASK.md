# Implement a YMX reader from the specification

`SPEC.md` in this directory is the format specification. Implement a
reader for the `.ymx` files in `tunes/`.

## What to produce

`decode.py` in this directory. Run as:

    python3 decode.py <file.ymx> <calls>

It prints one line of JSON:

    {"frames":[{"result":R,"w":{"0":V,"1":V,...}}, ...]}

One entry per player call, in order, at most `<calls>` of them.

- `result` is the value that call reports.
- `w` maps sound-chip register number to the value written to it on that
  call. **Include only registers actually written** - a register the call
  leaves alone is absent, not repeated.
- Registers 0 to 13. Values are what a YM2149 would receive.

Only the call's own writes. Do not model what a timer interrupt would
write between frames: the specification's §9.4 puts those outside the
work, and this is the reader it describes.

## The tunes

Each is a `.ymx`. Their shapes differ on purpose; the specification tells
you how to read each one.

| file | calls to produce |
|---|---:|
| `stored_tiny.ymx` | 40 |
| `plain_packed.ymx` | 400 |
| `ring_form.ymx` | 2,200 |
| `cut_form.ymx` | 6,000 |
| `plays_once.ymx` | 4,100 |
| `retrigger.ymx` | 9,985 |
| `resume_model.ymx` | 5,379 |
| `unit1.ymx` | 400 |
| `unit4.ymx` | 400 |
| `wide_ring.ymx` | 7,585 |

Produce at most that many entries for each. A call reporting -1 is one
entry, and the record ends with it: produce no entry for any later call.

## Rules

- Work **only** from `SPEC.md`.
- **Do not read anything under `/Users/rapido/git/YMX`,** and do not read
  any implementation of this format or of the compression it uses,
  anywhere - not in that repository, not on the web. This is a test of
  whether the specification alone is enough.
- You have no reference output. You cannot check your answer.

## Also produce

`SOURCES.md`: every file and page you read, listed. If you read an
implementation of anything, say so plainly. An honest list is worth more
here than a clean one.

`NOTES.md`: every place the specification left you guessing. For each -
the section, what it does not say, what you assumed, and how you would
word it. Mark each entry **decides output** or **costs nothing**,
depending on whether your assumption changed a byte you emitted.
