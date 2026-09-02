; -----------------------------------------------------------------------------
; ST4_ring.S - the ST4 ring decoder for any budget
;
; By Robbert van Dalen, based on ZX1 v1.5 by Einar Saukas and on ST1_ring.S,
; which this follows instruction for instruction where the format allows.
; Written by Claude (Anthropic's Claude Code) under Robbert's direction. Use it
; freely, including commercially, as long as your documentation says you used
; ZX1 through ST4 (see LICENSE).
;
; ST4_ring decodes through a ring and clamps each call to the ring end, wrapping
; the write pointer on the next entry, so the caller asks for any budget and
; counts nothing. d1.w = 0 means done; a stream packed with st4 -r is never
; done: its end marker installs one last offset and the decoder matches it
; forever, re-arming 65535 units at a time. Literals come from stream B in a2
; and offsets from stream C in a4 (bytes) or D in a5 (words); stream A holds
; the bits, so the queue refills a word at a time. Set the unit size before
; including:
;
;       ST4_UNIT    equ     4
;               include "ST4_ring.S"
;
; ST4_WINDOW, set to 1, builds the copy code. A stream packed with st4 -c for
; the ring writes a copy from the literal stream as an offset beyond the ring:
; it copies offset-M literal units from behind the stream B read pointer,
; without moving it, and without the ring's wrap arithmetic. The window is
; the ring size, so ST4_init has it already: it writes the size into the two
; instructions that use it, the compare that tells a copy from a match and
; the lea that finds a copy's source. So the decoder is code in RAM, and a
; 68030 caller flushes the instruction cache after ST4_init. A build without
; ST4_WINDOW, or with 0, has no copy code.
;
;       ST4_UNIT    equ     4
;       ST4_WINDOW  equ     1
;               include "ST4_ring.S"
;
; A stream with copies was packed with -m equal to the ring in units, which
; its header records at byte 24; a stream without copies decodes through any
; ring at least its window.
;
; Two copy ladders: match segments of at most sixteen units take a
; counter-free ladder that falls into the match epilogue; literals and longer
; segments take the counted ladder. ST4_wrap.S has the measurement behind the
; split.
;
; The container is ST4.S's, and opening one is shown there. So is the rewind:
; a loop longer than the window is replayed by saving the state registers but
; a1 at the rewind point and restoring them at O, every pass, which this
; decoder needs no code for.
;
; THE STATE, seeded by ST4_init and kept between calls:
;
;   a0.l  position in stream A          a1.l  write pointer
;   a2.l  position in stream B          a4.l  stream C, the byte offsets
;   a5.l  stream D, the word offsets    d0.w  bit queue ($8000 empty,
;                                            zero once a repeat is armed)
;   d1.l  -ring_start.low : units remaining
;   d2.l  ring_end.low : signed byte offset (+lastOffset*UNIT during literals,
;         -lastOffset*UNIT during a match, zero once done)
;
;   init in      a0/a1/a2/a4/a5, d3.l = one past the last ring byte
;   resume in    d3.w   this call's budget in units
;   out          d1.w   zero when the stream is done
;   clobbered    d3.w d4.l d5.l a3.l
;   untouched    d6 d7 a6
;
; ASSUMPTIONS, unchecked:
;
;   1. the ring size N is a multiple of ST4_UNIT, and the ring starts on a
;      unit boundary: the room the decoder clamps to, the unit it holds back
;      when it wraps, and the wide move that faults on an odd address all
;      depend on it
;   2. no offset exceeds 32512 bytes: the packer clamps -m to that
;   3. no operation is longer than 65535 units: pack with -l65535
;   4. the budget in d3.w is 1..65535 units
;   5. streams A and D start on even addresses, and A holds an even number of
;      bytes: the bit queue is refilled with a move.w, and so is a word offset
;   6. a stream with copies was packed with -m equal to the ring in units,
;      and every copy is shorter than its distance, so the offset never
;      reaches zero: the packer keeps both
;
; Pack with st4 -k<UNIT> -m<ring/UNIT> -l65535, and -c for copies from the
; literal stream on a build with ST4_WINDOW.
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

; One unit copied at this build's width.
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

; Jump table: bra.w keeps 4-byte slots (base+0/4). A bounded ring has no
; one-shot entry; the caller drains it through ST4_resume.
                bra.w   ST4_init
                bra.w   ST4_resume

; ST4_init: a0 = A, a1 = first ring byte, a2 = B, a4 = C, a5 = D,
; d3 = one past the last ring byte. Packs -start.low and end.low above the
; common state; writes no memory.
ST4_init:
        moveq   #0,d1
        sub.w   a1,d1                   ; -start.low, exact modulo 65536
        swap    d1                      ; pack it high; remaining = 0
; The initial offset lands in the low word after the swap, so it starts in the
; high half: moveq would put -1 there whatever the unit size.
        move.l  #(-ST4_UNIT)<<16,d2
        move.w  d3,d2                   ; end.low lives below it for now
        swap    d2                      ; end.low above, -INITIAL_OFFSET*UNIT below
        move.w  #$8000,d0               ; an empty bit queue: $8000 is the sentinel
        .if ST4_WINDOW
        move.l  d3,d4
        sub.l   a1,d4                   ; N, the ring in bytes: the window
        lea     window_lea+2(pc),a3
        move.w  d4,(a3)                 ; the copy source's displacement: N
        neg.w   d4
        lea     window_cmp+2(pc),a3
        move.w  d4,(a3)                 ; the compare's immediate: -N
        .endif
        rts

short_room:                             ; rare: destination wrap or budget clamp
        tst.w   d5
        beq.s   wrap_a1
        move.w  d5,d3                   ; positive room is smaller than budget
        bra.s   budget_set
wrap_a1:
        move.l  d2,d5                   ; N = end.low + (-start.low)
        clr.w   d5
        add.l   d1,d5                   ; low words cannot carry into N
        clr.w   d5
        swap    d5                      ; zero-extend N, in bytes
        suba.l  d5,a1                   ; wrap a1; leave room = N
        .if ST4_UNIT=2
        lsr.l   #1,d5
        .endif
        .if ST4_UNIT=4
        lsr.l   #2,d5              ; one shift, not two: 10 cycles, not 16
        .endif
; Leave room one unit short of the ring. A call that wraps and then fills the
; whole ring would return a1 where it started, and the caller reads its output
; as [old a1 .. new a1), so a full ring would read as nothing. A one-unit ring
; cannot spare the unit; there the span stays ambiguous unless the caller
; wraps a1 itself.
        subq.w  #1,d5
        bne.s   have_room
        addq.w  #1,d5
        bra.s   have_room               ; room = N-1 units, or 1 for a tiny ring

source_wrapped:
        move.l  d2,d5                   ; N = end.low + (-start.low)
        clr.w   d5
        add.l   d1,d5                   ; low words cannot carry into N
        clr.w   d5
        swap    d5                      ; zero-extend N at any 32-bit address
        adda.l  d5,a3
        bra.s   copy

; ST4_resume: the state and d3.w = this call's budget in units. Emits no more
; than the budget or the room to the ring end. d1.w = 0 means done;
; [old a1 .. new a1) is new output. After a call that ends at the ring end,
; either wrap a1 or let the next entry wrap it.
ST4_resume:
        move.l  d2,d5
        swap    d5                      ; room = end.low - destination.low, bytes
        sub.w   a1,d5                   ; exact because the room is <= 65535
; Budgets and segments count units, so the room joins them. The ring size and
; every position are whole units, so the shift is exact.
        .if ST4_UNIT=2
        lsr.w   #1,d5
        .endif
        .if ST4_UNIT=4
        lsr.w   #2,d5              ; one shift, not two: 10 cycles, not 16
        .endif
have_room:                              ; room in units from here on
        cmp.w   d3,d5                   ; budget = min(budget, room)
        bcs.s   short_room               ; zero room means wrap; otherwise clamp
budget_set:                             ; word clamp ignores upper d3/d5 junk
        tst.w   d1                      ; zero identifies START or DONE
        beq.s   entry_special           ; START/DONE; active ops have remaining
        move.w  d3,d4                   ; seed n from the nonzero budget
segment:                                ; d1.w remaining >= 1, d3 budget >= 1
        cmp.w   d1,d4                   ; n = min(budget, remaining)
        bls.s   budget_fits
        move.w  d1,d4
budget_fits:
        tst.w   d2                      ; positive lastOffset means LITERALS
        bpl.s   literal_source
        .if ST4_WINDOW
window_cmp:
        cmp.w   #-32768,d2              ; patched by ST4_init to -M*UNIT: an
        blt.s   copy_source             ; offset beyond the window is a copy
        .endif
; MATCH: derive the source and clamp a segment that crosses the ring end.
        movea.l a1,a3                   ; raw source = dst + -lastOffset*UNIT
        adda.w  d2,a3                   ; the offset is kept scaled for this
        move.l  d1,d5
        swap    d5                      ; d5.w = -start.low
        add.w   a1,d5                   ; d5.w = destination position
        add.w   d2,d5                   ; add -offset; carry means unwrapped
        bcs.s   copy                    ; unwrapped source cannot cross before dst
        neg.w   d5                      ; wrapped: room = offset - position, bytes
        .if ST4_UNIT=2
        lsr.w   #1,d5
        .endif
        .if ST4_UNIT=4
        lsr.w   #2,d5              ; one shift, not two: 10 cycles, not 16
        .endif
        cmp.w   d4,d5                   ; units, as above: the room is at most N
        bcc.s   source_wrapped
        move.w  d5,d4                   ; this segment stops at the wrap
        bra.s   source_wrapped
copy:                                   ; MATCH copies only reach this entry
        sub.w   d4,d1                   ; consume n from the operation
        sub.w   d4,d3                   ; and from this call's budget
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
        beq.s   match_transition
resume_fresh:                           ; gamma or a split match may continue
        move.w  d3,d4                   ; test budget and seed the next segment
        bne.s   segment                 ; budget remains: start/continue a segment

resume_return:                          ; return the current d1.w result
        rts                             ; zero = done; nonzero = more output

entry_special:                          ; d1.w = 0: the start, or done
        tst.w   d2                      ; the start has -1; done is zero
        beq.s   resume_return
        bra.w   begin_literals          ; the start: once per stream

        .if ST4_WINDOW
copy_source:                            ; the offset is beyond the window, so
; the source is stream B, offset-M units behind the read pointer, which stays
; where it is. Stream B never wraps, so none of the ring arithmetic applies.
; The offset advances by what this segment copies: a copy the budget or the
; ring end cuts continues where it stopped, and a rep after it starts past
; it. It stays beyond the window, since a copy is shorter than its distance.
window_lea:
        lea     1024(a2),a3             ; patched by ST4_init to M*UNIT(a2),a3
        adda.w  d2,a3                   ; + -offset*UNIT
        .rept   ST4_UNIT
        add.w   d4,d2                   ; -offset*UNIT += n*UNIT
        .endr
        bra.s   copy
        .endif

literal_source:
        movea.l a2,a3                   ; literals copy from stream B
        sub.w   d4,d1                   ; consume n from the operation
        sub.w   d4,d3                   ; and from this call's budget
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
        bne.s   resume_return           ; literals stop only on the budget

literals_transition:
        add.w   d0,d0                   ; the flag after literals, no refill
        bcs.s   new_offset              ; 1: new offset
        neg.w   d2                      ; a rep reuses the negated offset
        bra.w   gamma_seed0

match_transition:                       ; remaining == 0: parse after a match
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
        bra.s   get_gamma

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
; adda.w away for the rest of the operation.
        .if ST4_UNIT>1
        add.w   d4,d4
        .endif
        .if ST4_UNIT>2
        add.w   d4,d4
        .endif
        move.w  d4,d2                   ; install -lastOffset*UNIT, keep d2.high
        moveq   #1,d4                   ; new-offset match: remaining = gamma+1
        bra.s   get_gamma               ; one branch, not two through have_offset

end_marker:
; One more bit: 0 ends the stream, 1 repeats it. It sits at an even position,
; where the queue can run out, so this is the other checked read.
        add.w   d0,d0
        beq.s   end_refill
end_read:
        bcs.s   arm_repeat
        clr.w   d2                      ; done: d1.w is zero, high words stay
        rts
end_refill:
        move.w  (a0)+,d0
        addx.w  d0,d0
        bra.s   end_read

arm_repeat:
; The repeat: install one last word offset and match it forever. The queue
; pinned to zero, a value no read leaves it at, makes every transition that
; would parse the next block re-arm the match instead.
        move.w  (a5)+,d2                ; -(O-R)*UNIT; end.low stays above
        moveq   #0,d0
        subq.w  #1,d1                   ; remaining = 65535, from zero
        move.w  d3,d4                   ; the resume_fresh tail: the budget
        bne.w   segment                 ; may already be spent
        rts

match_next:                             ; a 0 flag after a match means literals
        bne.s   begin_literals          ; - unless the queue reads zero, which
        subq.w  #1,d1                   ; only a pinned one does: the repeat
        bra.w   resume_fresh            ; re-arms 65535 units, budget willing

begin_literals:
        neg.w   d2                      ; positive lastOffset = literals
gamma_seed0:
        moveq   #0,d4                   ; remaining = gamma + 0; fall through

; get_gamma: interlaced Elias gamma in d1.w; d4.w is the 0/1 length seed.
; A gamma has an odd number of bits and one flag bit follows it, so a gamma
; starts at an even position and the queue, which empties every 16 bits, runs
; out only on a continuation bit. Data and flag reads need no refill check.
; The first continuation is peeled and bcs is the loop edge.
get_gamma:
        addq.w  #1,d1
        add.w   d0,d0                   ; first continuation bit
        beq.s   gamma_refill            ; the queue empties on one bit in eight,
        bcc.s   gamma_done              ; so the refill is out of line and this
gamma_loop:                             ; branch is the one normally not taken
        add.w   d0,d0                   ; data bit: never a refill
        addx.w  d1,d1                   ; value = value*2 + bit
        add.w   d0,d0                   ; continuation bit
        beq.s   gamma_refill
        bcs.s   gamma_loop              ; 1: another pair; the continue branch
gamma_done:                             ; doubles as the loop jump
        add.w   d4,d1                   ; +1 only for a new-offset match
        move.w  d3,d4                   ; the resume_fresh tail, duplicated so
        bne.w   segment                 ; the parse exit takes one branch
        rts                             ; budget spent: the d1.w result stands
gamma_refill:                           ; queue-empty continuations share one
        move.w  (a0)+,d0                ; refill; carry set means another pair
        addx.w  d0,d0                   ; follows, otherwise finish the gamma
        bcs.s   gamma_loop
        bra.s   gamma_done
