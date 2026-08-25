# Implement a YMX player from the specification

`SPEC.md` in this directory is the format specification. Implement a
**player** for the `.ymx` files in `tunes/`: a reader that also runs the
timer streams.

`TASK.md` asks for a reader, which produces the values a frame writes and
drives no chip. This asks for the rest: §3's operations, §5's rates and
§6's samples, which is what a timer writes between one call and the next.

## What to produce

`decode.py` in this directory. Run as:

    python3 decode.py <file.ymx> <calls>

It prints one line of JSON:

    {"frames":[{"result":R,"w":{"0":V,...},"t":[{"n":"A","w":[[R,V],...]}]}]}

One entry per player call, in order, at most `<calls>` of them.

- `result` is the value that call reports.
- `w` is the call's own writes: register number to value, only the
  registers the call writes, in ascending register order.
- `t` is every tick that falls after that call and before the next, in
  time order. `n` is the timer, `A` to `D`. A tick's `w` is a list of
  `[register, value]` pairs **in the order it writes them**, because one
  tick may write a register twice.
- Registers 0 to 13. Values are what a YM2149 would receive.

## The five conventions this record fixes

The specification states a timer's rate and what each tick carries. It
does not say where a tick falls against a call, because that is the
host's. These five settle it, and they are this task's, not the format's:

1. A call's own writes come first, then the ticks falling before the next
   call. No tick lands inside a call. A tick due at the exact instant a
   call begins belongs to that call's window, not the one before it, and
   runs against the state that call's own writes and actions left.
2. One call lasts `2457600 / rate` of the MFP clock's cycles, where
   `rate` is the header's frame rate.
3. A timer started by a call has its first tick one period after the
   instant that call begins, not one period after it ends. A timer already
   running when a call reprograms it keeps the count in flight and takes
   the new period after that (§3.1). A timer stopped and started again
   begins its count over.
4. A timer whose interrupt is disabled lands no tick, and its count
   keeps running (§3, `RELEASE` bit 0).
5. Two ticks due in one cycle are listed in the order an MFP ranks their
   timers: A, then B, then C, then D.

## The tunes

| file | calls to produce |
|---|---:|
| `stored_tiny.ymx` | 40 |
| `plain_packed.ymx` | 400 |
| `ring_form.ymx` | 2,200 |
| `cut_form.ymx` | 6,000 |
| `plays_once.ymx` | 4,001 |
| `retrigger.ymx` | 3,001 |
| `resume_model.ymx` | 5,379 |
| `unit1.ymx` | 400 |
| `unit4.ymx` | 400 |
| `wide_ring.ymx` | 7,585 |

Produce at most that many entries for each. A call reporting -1 is one
entry, and the record ends with it: produce no entry for any later call.
Three of the ten start no timer stream, so every `t` in them is empty.

## Rules

- Work **only** from `SPEC.md`.
- **Do not read anything under `/Users/rapido/git/YMX`,** and do not read
  any implementation of this format or of the compression it uses,
  anywhere - not in that repository, not on the web. This is a test of
  whether the specification alone is enough.
- You have no reference output. You cannot check your answer.

## Also produce

`SOURCES.md`: every file and page you read, listed. Include anything that
reached you without your opening it. If you read an implementation of
anything, say so plainly. An honest list is worth more here than a clean
one.

`NOTES.md`: every place the specification left you guessing. For each -
the section, what it does not say, what you assumed, and how you would
word it. Mark each entry **decides output** or **costs nothing**,
depending on whether your assumption changed a byte you emitted.
