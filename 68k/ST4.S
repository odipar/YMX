; -----------------------------------------------------------------------------
; ST4.S - ST4: split-stream, unit-aligned ZX1-family decompressor (68000)
;
; By Robbert van Dalen, based on ZX1 v1.5 by Einar Saukas (data compression
; format and algorithm) and on ST1.S, which this follows instruction for
; instruction wherever the format allows. Written by Claude (Anthropic's Claude
; Code) under Robbert's direction. The decompressor can be used freely within
; your own programs, even commercially, as long as your documentation indicates
; that you used ZX1 through ST4 (see LICENSE).
;
; ST4 differs from ZX1 in three ways, and this file in one more:
;
;   1. the literal payload lives in a stream of its own, so its alignment is a
;      property of the file rather than of wherever the control bytes left it;
;   2. the offsets live in streams of their own, split by width: bytes in C,
;      words in D. That leaves stream A holding nothing but bits, so its queue
;      refills a WORD at a time - impossible while an offset byte can move the
;      same pointer by one - and it leaves a word offset stored exactly as the
;      decoder wants it, so installing one is a single move;
;   3. lengths and offsets count UNITS of ST4_UNIT bytes, so one operation
;      moves that many bytes at a time and there are that many fewer of them.
;
; ST4_UNIT is a build-time constant - 1, 2 or 4 - because a decoder that chose
; its copy width at run time would pay for the choice on every operation. Set
; it before including this file:
;
;       ST4_UNIT    equ     4
;               include "ST4.S"
;
; At ST4_UNIT = 1 this is ST1 with the literals moved out: the same parse, the
; same bits, the same speed. At 2 and 4 the copy is a move.w or move.l and the
; operation count falls with the unit size.
;
; 68000 ALIGNMENT. Streams A and D are read a word at a time, so both must
; start on an even address; the packer pads A to an even length so the last
; refill finds a whole word, and D holds whole words by construction. At ST4_UNIT 2 or 4 the destination, stream B and every offset are
; whole units, so a wide move can never land on an odd address - provided the
; caller starts the destination and stream B on a unit boundary. A real 68000
; raises an address error if they do not.
;
; -----------------------------------------------------------------------------
; THE CONTAINER. An .st4 file is twenty bytes of header and then the four
; streams, in order, each starting on a long boundary:
;
;    0  4  signature: 'S', '4', format version, ST4_UNIT
;    4  4  O, the output size in bytes
;    8  4  stream B, as a byte offset from the start of the header
;   12  4  stream C
;   16  4  stream D
;   20 ..  stream A, then B, then C, then D
;
; Nothing else is stored, because nothing else has to be. Stream A begins where
; the header ends. No length is kept: a stream runs to the next one, and this
; decoder reads no length anyway - it stops on the end marker. The signature
; packs magic, version and unit size into one long, so a build proves an asset
; matches it with a single cmp.l, and the offsets are relative to the header, so
; opening one is one adda.l per stream and no relocation:
;
;       lea     asset(pc),a3
;       cmp.l   #ST4_SIGNATURE,(a3)     ; magic, version and k in one compare
;       bne.s   wrong_asset
;       lea     20(a3),a0               ; stream A, where the header ends
;       movea.l a3,a2
;       adda.l  8(a3),a2                ; stream B
;       movea.l a3,a4
;       adda.l  12(a3),a4               ; stream C
;       movea.l a3,a5
;       adda.l  16(a3),a5               ; stream D
;
; -----------------------------------------------------------------------------
; THE STATE IS YOUR REGISTERS. ST4_init seeds it; preserve all seven between
; calls:
;
;   a0.l  position in stream A, the bit stream
;   a1.l  write pointer - where the output ends, after every call
;   a2.l  position in stream B, the literal stream
;   a4.l  position in stream C, the byte offsets
;   a5.l  position in stream D, the word offsets
;   d0.w  bit queue ($8000 is the empty sentinel)
;   d1.w  units remaining in the current operation
;   d2.w  signed byte offset: +lastOffset*UNIT during LITERALS,
;         -lastOffset*UNIT during MATCH, and zero once DONE
;
; Calling convention, in full:
;
;   in and out   a0 a1 a2 a4 a5      the state; leave it alone between calls
;                d0.w d1.w d2.w
;   resume in    d3.w                this call's budget in UNITS, spent by it
;   CLOBBERED    d3.w d4.l d5.l      no value in these survives a call
;                a3.l
;   untouched    d6 d7 a6            and the stack beyond the return address
;
; The budget and the remaining count are UNITS, not bytes: a call that asks for
; 16 with ST4_UNIT = 4 produces 64 bytes.
;
; -----------------------------------------------------------------------------
; ASSUMPTIONS (unchecked; violating them is undefined behaviour):
;
;   1. no single literal run or match is longer than 65535 units - compress
;      with st4 -l65535
;   2. no back-reference offset exceeds 32512 BYTES, which is 32512/ST4_UNIT
;      units - the st4 packer clamps -m to that. A word offset is stored as
;      -offset*UNIT, so the window is what fits the word it is kept in
;   3. at ST4_UNIT 2 or 4 the destination and stream B start on a unit boundary
;   4. streams A and D start on even addresses; A holds an even number of bytes
;   5. the budget in d3.w is 1..65535 units; zero makes no progress
;
; TRUSTED INPUT ONLY. Neither stream, nor the destination, nor the budget is
; validated. A wrong value can read or write arbitrary memory.
; -----------------------------------------------------------------------------

; The asset's signature for this build: 'S4', format version 4, and the unit
; size this decoder was assembled for. One cmp.l against a file's first long
; proves all three at once.
ST4_SIGNATURE   equ     $53340400+ST4_UNIT

; One unit copied, at the width this build was made for. Everything else in
; this file is width-independent, which is the point of keeping the counts in
; units rather than bytes.
        .macro ST4_MOVE
        .if ST4_UNIT=4
        move.l  (a3)+,(a1)+
        .endif
        .if ST4_UNIT=2
        move.w  (a3)+,(a1)+
        .endif
        .if ST4_UNIT=1
        move.b  (a3)+,(a1)+
        .endif
        .endm

; Jump table: bra.w keeps the slots 4 bytes (base+0/4/8).
                bra.w   ST4_init
                bra.w   ST4_decompress
                bra.w   ST4_resume

; ST4_init: a0 = stream A, a1 = destination, a2 = stream B, a4 = stream C,
; a5 = stream D; seeds d0/d1/d2.
ST4_init:
        moveq   #-ST4_UNIT,d2           ; -lastOffset*UNIT, INITIAL_OFFSET = 1
        moveq   #0,d1                   ; remaining = 0
        move.w  #$8000,d0               ; an empty bit queue: $8000 is the sentinel
        rts

; ST4_decompress: a0/a1/a2/a4/a5 = streams A, destination, B, C and D.
; One-shot convenience; returns with a1 = end of output.
ST4_decompress:
        bsr.s   ST4_init
.drain:
        moveq   #-1,d3                  ; budget = 65535 units; refill for more
        bsr.s   ST4_resume
        tst.w   d1
        bne.s   .drain
        rts

; ST4_resume: the state in a0/a1/a2/a4/a5/d0/d1/d2 and d3.w = this call's
; budget in units. Emits at most d3 units; d1.w = 0 means done.
ST4_resume:
        tst.w   d1                      ; active ops enter the copy body directly
        bne.s   op_body
        tst.w   d2                      ; d1 = 0: START is negative, DONE is zero
        bne.s   begin_literals
suspend:                                ; DONE or a suspended operation
        rts

; get_gamma: interlaced Elias gamma in d1.w; d4.w is the 0/1 length seed.
; Gammas are odd-length and every one of them is followed by a single flag bit,
; so a gamma always starts at an even position and the queue - which empties
; every 16 bits, also even - can only ever run out on a continuation bit. Data
; and transition reads therefore need no refill check. The argument holds for a
; word queue exactly as it did for a byte one: 16 is even, like 8.
gamma_refill:                           ; queue-empty continuations share one
        move.w  (a0)+,d0                ; refill; carry set means another pair
        addx.w  d0,d0
        bcs.s   gamma_loop
        bra.s   gamma_done

begin_literals:
        neg.w   d2                      ; positive lastOffset = State.LITERALS
gamma_seed0:
        moveq   #0,d4                   ; remaining = gamma + 0; fall through
get_gamma:
        addq.w  #1,d1
        add.w   d0,d0                   ; first continuation bit
        beq.s   gamma_refill
        bcc.s   gamma_done
gamma_loop:
        add.w   d0,d0                   ; data bit: never a refill
        addx.w  d1,d1                   ; value = value*2 + bit
        add.w   d0,d0                   ; continuation bit
        beq.s   gamma_refill
        bcs.s   gamma_loop
gamma_done:
        add.w   d4,d1                   ; +1 only for a new-offset match

resume_op:                              ; reached after parsing an op, when the
        tst.w   d3                      ; budget may already be spent
        beq.s   suspend
op_body:                                ; shared; sign(d2) = LITERALS/MATCH
        move.w  d1,d4                   ; take_budget: min(remaining, budget),
        cmp.w   d3,d4                   ; both in units
        bls.s   budget_fits
        move.w  d3,d4
budget_fits:
        tst.w   d2                      ; positive lastOffset means LITERALS
        bpl.s   literal_source
        movea.l a1,a3                   ; match: destination plus -lastOffset*UNIT
        adda.w  d2,a3                   ; which is why the offset is kept scaled
copy:                                   ; MATCH copies only reach this entry
        sub.w   d4,d1
        sub.w   d4,d3
        cmp.w   #17,d4
        bcc.s   copy_slow               ; n >= 17: the counted ladder
        add.w   d4,d4
        neg.w   d4                      ; d4 = -2n is the dispatch index itself,
        jmp     fast_end(pc,d4.w)       ; and $ffxx for byte_offset afterwards
fast_ladder:
; Sixteen unit moves: entry at fast_end-2k copies k units.
        .rept   16
        ST4_MOVE
        .endr
fast_end:                               ; no counter, no kind test: fall into
match_copied:                           ; the match epilogue
        tst.w   d1
        bne.s   resume_return           ; budget spent mid-op
        add.w   d0,d0                   ; next(), case MATCH (bare read)
        bcc.s   begin_literals          ; 0: literals; 1: falls into new offset

new_offset:                             ; beginMatchFromNewOffset() / readOffset()
; Two class bits: the first says byte or word, the second picks a byte offset's
; 256-unit bank or, for a word, marks the end of the stream. That first bit is
; the only read here that can need a refill. Every operation is an even number
; of bits, so flags and gamma data bits all land on odd positions and the queue
; - which empties every 16 bits - can never run out on one; the bit immediately
; after a flag is the one exception, and it is checked.
        add.w   d0,d0                   ; class bit 1: set means a byte offset
        beq.s   class_refill
class_read:
        bcs.s   byte_offset
        add.w   d0,d0                   ; class bit 2: set means end of stream
        bcs.s   end_marker
        move.w  (a5)+,d2                ; -lastOffset*UNIT, stored ready to use
have_offset:
        moveq   #1,d4                   ; new-offset match: remaining = gamma+1
        bra.w   get_gamma

class_refill:
        move.w  (a0)+,d0
        addx.w  d0,d0
        bra.s   class_read

byte_offset:
; The copy paths leave d4.w = $ffxx - the fast ladder's -2n dispatch index, or
; the counted terminator's $ffff - so one move.b makes $ff00 + (256-offset),
; which already IS -offset as a word. The bank bit moves it 256 units further.
        move.b  (a4)+,d4
        add.w   d0,d0                   ; class bit 2: the 256-unit bank
        bcc.s   scale_offset
        sub.w   #256,d4                 ; the far bank: 257..512 units
scale_offset:
; The offset arrives in units and is kept in bytes, so the match source is one
; adda.w away for the rest of the operation. Scaling here costs a shift once
; per new offset instead of a shift per copy.
        .if ST4_UNIT>1
        add.w   d4,d4
        .endif
        .if ST4_UNIT>2
        add.w   d4,d4
        .endif
        move.w  d4,d2                   ; install -lastOffset*UNIT
        moveq   #1,d4                   ; new-offset match: remaining = gamma+1
        bra.w   get_gamma               ; one branch, not two through have_offset

end_marker:
        clr.w   d2                      ; d1.w is already zero; normalize DONE
resume_return:
        rts                             ; a1 remains the output end

literal_source:
        movea.l a2,a3                   ; literals copy from stream B
        sub.w   d4,d1
        sub.w   d4,d3
copy_slow:                              ; any n; the subs are already done
        moveq   #15,d5                  ; d5 = n&15, partial-pass dispatch
        and.w   d4,d5
        lsr.w   #4,d4                   ; d4 = n>>4 full passes
        add.w   d5,d5
        neg.w   d5                      ; index = -2*(n&15)
        jmp     ladder_end(pc,d5.w)     ; pc-relative dispatch, no base register
ladder:
; Sixteen unit moves: entry at ladder_end-2k copies k units.
        .rept   16
        ST4_MOVE
        .endr
ladder_end:
        subq.w  #1,d4                   ; full 16-unit passes; the fall-through
        bcc.s   ladder                  ; costs 12 where dbf paid 14, and still
        tst.w   d2                      ; leaves d4.w = $ffff for byte_offset
        bmi.s   match_copied            ; an n>=17 match rejoins its epilogue
        movea.l a3,a2                   ; literals do: stream B advanced
        tst.w   d1
        bne.s   resume_return           ; budget spent mid-op

literals_transition:
        add.w   d0,d0                   ; next(), case LITERALS (bare read)
        bcs.s   new_offset              ; 1: new offset
        neg.w   d2                      ; from-last reuses the negated offset
        bra.w   gamma_seed0
