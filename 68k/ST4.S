; -----------------------------------------------------------------------------
; ST4.S - the ST4 decoder for the 68000: one buffer, in one call or resumed
;
; By Robbert van Dalen, based on ZX1 v1.5 by Einar Saukas and on ST1.S, which
; this follows instruction for instruction where the format allows. Written by
; Claude (Anthropic's Claude Code) under Robbert's direction. Use it freely,
; including commercially, as long as your documentation says you used ZX1
; through ST4 (see LICENSE).
;
; ST4 is ZX1 in four streams at a unit size of 1, 2 or 4 bytes: stream A holds
; the bits, so the queue refills a word at a time; stream B the literal units;
; stream C the byte offsets; stream D the word offsets, stored as the decoder
; keeps them. Lengths and offsets count units. A stream can loop, and an
; offset beyond the window M copies from stream B. README.md has the format.
;
; ST4_UNIT, the unit size, is a build-time constant: set it before including
; this file.
;
;       ST4_UNIT    equ     4
;               include "ST4.S"
;
; ST4_WINDOW, set to 1, builds the copy code. A stream packed with st4 -c
; writes a copy from the literal stream as an offset beyond its window M: it
; copies offset-M literal units from behind the stream B read pointer, without
; moving it. The window is a stream's, not the build's: ST4_init takes it in
; d3.w, in bytes, and writes it into the two instructions that use it, the
; compare that tells a copy from a match and the lea that finds a copy's
; source. So the decoder is code in RAM, and a 68030 caller flushes the
; instruction cache after ST4_init. A build without ST4_WINDOW, or with 0,
; has no copy code and takes no window.
;
;       ST4_UNIT    equ     4
;       ST4_WINDOW  equ     1
;               include "ST4.S"
;
; The window a stream was packed for is in its header at byte 24, in units; a
; stream without copies decodes with any window at least its own.
;
; ALIGNMENT. Streams A and D are read a word at a time, so both start on an
; even address; the packer pads A to an even length and D holds whole words.
; At ST4_UNIT 2 or 4 the caller starts the destination and stream B on a unit
; boundary, or a wide move raises an address error.
;
; -----------------------------------------------------------------------------
; THE CONTAINER. Twenty-eight bytes of header, then the four streams, each on
; a long boundary:
;
;    0  4  signature: 'S', '4', format version, ST4_UNIT
;    4  4  O, the output size in bytes
;    8  4  stream B, the literals, as a byte offset from the header
;   12  4  stream C, the byte offsets
;   16  4  stream D, the word offsets
;   20  4  the rewind point in bytes, or $FFFFFFFF when there is none
;   24  4  M, the window in units
;   28 ..  streams A, B, C and D, in that order
;
; Stream A begins where the header ends; a stream runs to the next, and the
; decoder stops on the end marker. One cmp.l checks the signature and one
; adda.l per stream opens the container:
;
;       lea     asset(pc),a3
;       cmp.l   #ST4_SIGNATURE,(a3)     ; magic, version and k in one compare
;       bne.s   wrong_asset
;       lea     28(a3),a0               ; stream A, where the header ends
;       movea.l a3,a2
;       adda.l  8(a3),a2                ; stream B, the literals
;       movea.l a3,a4
;       adda.l  12(a3),a4               ; stream C, the byte offsets
;       movea.l a3,a5
;       adda.l  16(a3),a5               ; stream D, the word offsets
;
; -----------------------------------------------------------------------------
; THE STATE. Seven registers, seeded by ST4_init and kept between calls:
;
;   a0.l  position in stream A, the bit stream
;   a1.l  write pointer: where the output ends after every call
;   a2.l  position in stream B, the literal stream
;   a4.l  position in stream C, the byte offsets
;   a5.l  position in stream D, the word offsets
;   d0.w  bit queue: $8000 is the empty sentinel, zero once a repeat is armed
;   d1.w  units remaining in the current operation
;   d2.w  signed byte offset: +lastOffset*UNIT during literals,
;         -lastOffset*UNIT during a match, zero once done
;
; A stream packed with st4 -r whose loop fits the window is never done: its
; end marker installs one last offset and the decoder matches it forever,
; re-arming 65535 units at a time. Drive it through ST4_resume with budgets
; and stop when you have enough; ST4_decompress would never return.
;
; A loop longer than the window is replayed by the caller: when the output
; reaches the rewind point, save the seven registers but a1; when it reaches
; O, restore them and carry on, every pass. The packer parsed the loop on its
; own, so nothing in it reaches before the rewind point and every pass sees
; the same history. Arrange the budgets so a call ends on both points; the
; state mid-operation is saved like any other.
;
; Calling convention:
;
;   in and out   a0 a1 a2 a4 a5      the state; keep it between calls
;                d0.w d1.w d2.w
;   init in      d3.w                the window in bytes, in a ST4_WINDOW build
;   resume in    d3.w                this call's budget in units, spent by it
;   clobbered    d3.w d4.l d5.l      no value in these survives a call
;                a3.l
;   untouched    d6 d7 a6            and the stack beyond the return address
;
; The budget and the remaining count are units, not bytes: a call that asks
; for 16 with ST4_UNIT = 4 produces 64 bytes.
;
; -----------------------------------------------------------------------------
; ASSUMPTIONS, unchecked:
;
;   1. no literal run or match is longer than 65535 units: pack with -l65535
;   2. no offset exceeds 32512 bytes, 32512/ST4_UNIT units: the packer clamps
;      -m to that. A word offset is stored as -offset*UNIT, so the window is
;      what fits the word it is kept in
;   3. at ST4_UNIT 2 or 4 the destination and stream B start on a unit
;      boundary
;   4. streams A and D start on even addresses; A holds an even number of
;      bytes
;   5. the budget in d3.w is 1..65535 units; zero makes no progress
;   6. a stream with copies was packed with -m equal to the window given to
;      ST4_init, and every copy is shorter than its distance, so the offset
;      never reaches zero: the packer keeps both
;
; TRUSTED INPUT ONLY. No stream, the destination or the budget is validated.
; A wrong value reads or writes arbitrary memory.
; -----------------------------------------------------------------------------

; 'S4', format version 7 and this build's unit size in one long: one cmp.l
; against a file's first long checks all three.
ST4_SIGNATURE   equ     $53340700+ST4_UNIT

; ST4_WINDOW is optional: a build with it set to 1 has the copy code, and
; ST4_init writes the window into it.
        .if ^^defined ST4_WINDOW
        .else
ST4_WINDOW      equ     0
        .endif

; One unit copied at this build's width; the rest of the file counts units.
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
; a5 = stream D, and in a build with ST4_WINDOW d3.w = the window in bytes,
; M*ST4_UNIT; seeds d0/d1/d2.
ST4_init:
        moveq   #-ST4_UNIT,d2           ; -lastOffset*UNIT, INITIAL_OFFSET = 1
        moveq   #0,d1                   ; remaining = 0
        move.w  #$8000,d0               ; an empty bit queue: $8000 is the sentinel
        .if ST4_WINDOW
        lea     window_lea+2(pc),a3
        move.w  d3,(a3)                 ; the copy source's displacement: M*UNIT
        neg.w   d3
        lea     window_cmp+2(pc),a3
        move.w  d3,(a3)                 ; the compare's immediate: -M*UNIT
        .endif
        rts

; ST4_decompress: a0/a1/a2/a4/a5 = streams A, destination, B, C and D, and
; d3.w = the window in bytes in a build with ST4_WINDOW. Decodes until done
; and returns with a1 at the end of the output. A repeating stream is never
; done, so this call would never return.
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
suspend:                                ; done, or a suspended operation
        rts

        .if ST4_WINDOW
copy_source:                            ; the offset is beyond the window, so
; the source is stream B, offset-M units behind the read pointer, which stays
; where it is. The offset advances by what this segment copies: a copy the
; budget cuts continues where it stopped, and a rep after it starts past it.
; It stays beyond the window, since a copy is shorter than its distance.
window_lea:
        lea     1024(a2),a3             ; patched by ST4_init to M*UNIT(a2),a3
        adda.w  d2,a3                   ; + -offset*UNIT
        .rept   ST4_UNIT
        add.w   d4,d2                   ; -offset*UNIT += n*UNIT
        .endr
        bra.s   copy
        .endif

; get_gamma: interlaced Elias gamma in d1.w; d4.w is the 0/1 length seed.
; A gamma has an odd number of bits and one flag bit follows it, so a gamma
; starts at an even position and the queue, which empties every 16 bits, runs
; out only on a continuation bit. Data and flag reads need no refill check.
gamma_refill:                           ; queue-empty continuations share one
        move.w  (a0)+,d0                ; refill; carry set means another pair
        addx.w  d0,d0
        bcs.s   gamma_loop
        bra.s   gamma_done

match_next:                             ; a 0 flag after a match means literals
        bne.s   begin_literals          ; - unless the queue reads zero, which
        subq.w  #1,d1                   ; only a pinned one does: the repeat
        bra.s   resume_op               ; re-arms 65535 units, budget willing

begin_literals:
        neg.w   d2                      ; positive lastOffset = literals
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
        .if ST4_WINDOW
window_cmp:
        cmp.w   #-32768,d2              ; patched by ST4_init to -M*UNIT: an
        blt.s   copy_source             ; offset beyond the window is a copy
        .endif
        movea.l a1,a3                   ; match: destination plus -lastOffset*UNIT
        adda.w  d2,a3                   ; the offset is kept scaled for this
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
        add.w   d0,d0                   ; the flag after a match, no refill
        bcc.s   match_next              ; 0: literals, or the repeat's re-arm

new_offset:
; Two class bits: byte or word, then a byte offset's 256-unit bank or, for a
; word, the end of the stream. The first is the one read here that can need a
; refill: every operation is an even number of bits, so flags and gamma data
; bits land on odd positions, where the queue cannot run out; the bit after a
; flag is the exception, and it is checked.
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
; The copy paths leave d4.w = $ffxx, the fast ladder's -2n dispatch index or
; the counted terminator's $ffff, so one move.b makes $ff00 + (256-offset),
; which is -offset as a word. The bank bit moves it 256 units further.
        move.b  (a4)+,d4
        add.w   d0,d0                   ; class bit 2: the 256-unit bank
        bcc.s   scale_offset
        sub.w   #256,d4                 ; the far bank: 257..512 units
scale_offset:
; The offset arrives in units and is kept in bytes, so the match source is one
; adda.w away for the rest of the operation: a shift once per new offset
; instead of a shift per copy.
        .if ST4_UNIT>1
        add.w   d4,d4
        .endif
        .if ST4_UNIT>2
        add.w   d4,d4
        .endif
        move.w  d4,d2                   ; install -lastOffset*UNIT
        moveq   #1,d4                   ; new-offset match: remaining = gamma+1
        bra.w   get_gamma               ; one branch, not two through have_offset

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
        movea.l a3,a2                   ; literals advance stream B
        tst.w   d1
        bne.s   resume_return           ; budget spent mid-op

literals_transition:
        add.w   d0,d0                   ; the flag after literals, no refill
        bcs.s   new_offset              ; 1: new offset
        neg.w   d2                      ; a rep reuses the negated offset
        bra.w   gamma_seed0

end_marker:
; One more bit: 0 ends the stream, 1 repeats it. It sits at an even position,
; where the queue can run out, so this is the other checked read.
        add.w   d0,d0
        beq.s   end_refill
end_read:
        bcs.s   arm_repeat
        clr.w   d2                      ; d1.w is already zero: done
        rts                             ; a1 remains the output end
end_refill:
        move.w  (a0)+,d0
        addx.w  d0,d0
        bra.s   end_read

arm_repeat:
; The repeat: install one last word offset and match it forever. The queue
; pinned to zero, a value no read leaves it at, makes every transition that
; would parse the next block re-arm the match instead.
        move.w  (a5)+,d2                ; -(O-R)*UNIT: back to the loop point
        moveq   #0,d0
        subq.w  #1,d1                   ; remaining = 65535, from zero
        bra.w   resume_op               ; the budget may already be spent
