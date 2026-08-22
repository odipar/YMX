# The burst's mask: what it costs, and how to do without it

(The filename says "unmasked" because that is where the work started.
It ended with the mask optional rather than gone - see What shipped.)

The frame write used to run with interrupts off. Selecting a register and
writing it are two bus cycles, and an interrupt landing between them
would send the value to whatever register the interrupt had selected - so
the burst masked, wrote its fourteen registers, and unmasked. The comment
called it cheap: "about 24 cycles for the whole frame", counting only the
two `sr` instructions.

What it actually cost was every tick that fell inside the window.

## The window, measured against the tick periods

The masked span is the fourteen writes:

| part | cycles |
|---|---|
| 12 plain writes: `move.b #k,(a2)` 12 + `move.b $7FFF(a1),2(a2)` 20 | 384 |
| R7: select, read, `or.b`, write | 44 |
| R13: read, compare, branch, select, write | 52 |
| the `ori.w #$0700,sr` that opens it | 20 |

**About 500 cycles, 60 microseconds**, and more on real hardware where
bus contention adds 7 to 10 per cent.

| tick rate | period | the mask, as a share |
|---|---|---|
| 25,600 a second, the ceiling | 312 cycles | **160%** |
| 6,000, a typical drum | 1,333 | 38% |
| 3,000, a SID | 2,666 | 19% |

The top row is the one that matters. At the ceiling the mask is longer
than a whole tick period, and the MFP keeps **one** pending bit per
channel: a second tick arriving inside the window is not delayed, it is
gone. Below that, a tick landing in the window is displaced by up to a
third of its period - jitter on a sample, at the frame rate, which is
exactly where an ear finds it.

## The fix is atomicity, not a shorter mask

Masking per write instead of per burst would cost more than it saves: a
save/mask/restore around each of fourteen writes is several hundred
cycles a frame.

The 68000 takes interrupts **between** instructions. So a write that is
one instruction cannot be split, and needs no mask at all. `movep.w`
writes two bytes from one register to alternate addresses - which is what
the PSG's select at $8800 and data at $8802 are:

    move.w  #k<<8,d1        ; the select byte, in the high half
    move.b  $7FFF(a1),d1    ; the value, from the ring
    movep.w d1,0(a2)        ; both bytes, one instruction

The tick handlers were already atomic, for two different reasons, and
neither needed changing:

* the toggle and retrigger ticks write `move.l #$rr00vv00,$FFFF8800.w` -
  select and data in one immediate;
* the PCM tick uses two instructions, but they run at **IPL 6**, where no
  other MFP interrupt can preempt them, and main-line code can never
  preempt a handler. Its `move.w #$2500,sr` sits after the pair for
  exactly that reason.

So interrupt against interrupt is settled by priority, and interrupt
against burst by the burst being atomic. A tick landing between two burst
writes is harmless: the next write selects again.

## What it cost, measured

| | masked burst | unmasked |
|---|---|---|
| player | 2,786 bytes | **2,820** |
| harness, 1700 frames | 93 ticks | **91** |
| longest interrupt-free span in a frame | ~500 cycles | **one instruction** |

The frame got *cheaper* - the old masked build measured against the new
unmasked one. A wider write costs a few cycles, and the save/mask/restore
it replaced cost more. The build that shipped keeps the mask over the
wider writes, and that one costs a tick more than the old build rather
than less - 94 against 93, in the table below. The atomic write is what
the tick bought. The chip checksum is unchanged -
`sum=2941391492` - so the same values reach the same registers in the
same order; only the timing of what happens between them changed.

## What had to move with it

**The burst gate.** Muting a voice used to flip the destination
displacement of its volume write from 2 to 0, so the value landed on the
select register and was overridden. With one instruction per write there
is no displacement to flip, so a muted write is replaced by **two nops** -
the movep and its displacement word - and reopened by copying the
instruction back from a template (`ymx_movep`) rather than from a
hand-encoded opcode.

**Three layout constants.** A write is twelve bytes now rather than ten,
with the ring displacement at +6 and the movep at +8: `YMX_WRITE_READ`,
`YMX_WRITE_MOVEP`, `YMX_WRITE_SIZE`, so the init patch loops and the gate
cannot drift apart.

**R7 and R13.** Both write select and data as a pair of their own, and
both are converted. Leaving them would have kept a tearing window open in
the one place the burst is unmasked - the point of the exercise.

## What shipped

Both halves, but only one of them by default.

The atomic write stayed: every register write is a `movep.w`, so tearing
is impossible whatever the interrupt state. The mask became **optional**
rather than necessary - `YMX_MASK_BURST`, on by default, and the tools'
`-nomask` turns it off. So the shipping player still holds ticks off for
the burst, and dropping the mask is one flag away for anyone who wants
the tick timing instead.

That leaves the numbers as a menu rather than a verdict:

| | masked, atomic writes (default) | -nomask |
|---|---|---|
| player | 2,828 bytes | 2,820 |
| harness, 1700 frames | 94 ticks | 91 |
| longest interrupt-free span | ~500 cycles | one instruction |

Same chip traffic either way, byte for byte: 16,156 PSG writes over 900
VBLs of the same tune, identical. The flag moves when ticks run, not what
reaches the chip.

## What to keep

1. **"Cheap" was measured on the wrong thing.** The comment counted the
   two `sr` instructions and called the mask cheap. The cost was never
   those instructions; it was the 500 cycles of interrupt latency they
   guarded, paid by whichever tick fell inside.
2. **Atomicity beats exclusion.** Where hardware offers an instruction
   that does the whole job - `movep.w`, or an immediate `move.l` - it
   removes the race instead of scheduling around it, and usually costs
   less than the mask it replaces.
3. **An interrupt-free span is a number worth writing down.** Any player
   driving audio-rate interrupts should know its longest one, and compare
   it against the shortest tick period it allows.
