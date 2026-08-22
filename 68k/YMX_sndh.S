; -----------------------------------------------------------------------------
; YMX_sndh.S - the YMX player as an SNDH container (SNDH v2.2).
;
; This is the canonical build of the player: a position-independent blob a
; jukebox loads anywhere and drives through the three classic entries -
; INIT (subtune in d0.w, 1-based), EXIT, PLAY - with 'SNDH' at offset 12,
; the tag header, and the tunes inside. ymx/mksndh.sh assembles it around
; one or more .ymx files (packed with one configuration) and generates the
; two includes:
;
;   sndh_tags.inc    ST4_UNIT/RING_SIZE/YMX_TUNES equates and the tag bytes
;                    (TITL, CONV, '##', TC50, FLAG, FRMS)
;   sndh_tunes.inc   the subtune table and the incbin'd tunes
;
; The contract this file honors (researched against the v2.2 spec, psgplay,
; sc68 and native-player practice):
;   - every entry preserves d0-a6 and returns with rts, supervisor assumed;
;   - the host's own interrupts keep running: nothing is disabled beyond the
;     two MFP timers the player claims, and everything it touches - both
;     timer vectors, TACR, TCDCR's Timer D nibble, TADR, TDDR, the four
;     enable/mask bits - is saved at INIT and handed back at EXIT. Two is
;     what a YM tune needs: its frames start at most two effects, so it
;     names timer channels 0 and 1, which the packer's map puts on Timers
;     A and D. A file whose T stream named Timer B or C would need those
;     saved here too, and a Timer C tune could not be hosted from a Timer
;     C hook at all;
;   - INIT is defensive: called twice without an EXIT, it restores first;
;   - PLAY before INIT is a no-op; PLAY is driven at the TC50 tag's 50 Hz;
;   - the USP is never touched (YMX_SUPER_HOST stays 0), and every buffer
;     lives inside the blob.
;
; One real limitation: a native host that dispatches PLAY from its Timer C
; hook holds the MFP's Timer C in-service for the length of the call, which
; delays Timer D ticks (timer channel 1's) by up to that length - pending, not
; lost. VBL- and emulator-hosted playback has no such window.
; -----------------------------------------------------------------------------

sndh_start:
        bra.w   sndh_init
        bra.w   sndh_exit
        bra.w   sndh_play
        dc.b    'SNDH'
        include "sndh_tags.inc"
        even
        dc.b    'HDNS'

; ---------------------------------------------------------------- the glue
; INIT: d0.w = subtune, 1-based (out of range plays subtune 1).
sndh_init:
        movem.l d0-a6,-(sp)
        lea     sndh_live(pc),a6
        tst.b   (a6)
        beq.s   .fresh
        bsr     sndh_stop               ; init without exit: clean up first
.fresh:

; Save what the player will touch - the list YMX.S assumption 5 names. The
; four enable/mask bits are kept as bits, so EXIT can restore them without
; clobbering whatever the host changed in the same registers meanwhile.
        lea     sndh_saved(pc),a5
        move.l  $134.w,(a5)+
        move.l  $110.w,(a5)+
        move.b  $FFFFFA19.w,(a5)+
        move.b  $FFFFFA1D.w,(a5)+
        move.b  $FFFFFA1F.w,(a5)+
        move.b  $FFFFFA25.w,(a5)+
        moveq   #0,d1
        btst    #5,$FFFFFA07.w
        beq.s   .iera_off
        addq.b  #1,d1
.iera_off:
        btst    #5,$FFFFFA13.w
        beq.s   .imra_off
        addq.b  #2,d1
.imra_off:
        btst    #4,$FFFFFA09.w
        beq.s   .ierb_off
        addq.b  #4,d1
.ierb_off:
        btst    #4,$FFFFFA15.w
        beq.s   .imrb_off
        addq.b  #8,d1
.imrb_off:
        move.b  d1,(a5)

; The subtune table holds offsets from sndh_start; d0 is 1-based.
        subq.w  #1,d0
        cmp.w   #YMX_TUNES,d0
        bcs.s   .tune_ok
        moveq   #0,d0
.tune_ok:
        add.w   d0,d0
        add.w   d0,d0
        lea     sndh_table(pc),a0
        move.l  0(a0,d0.w),d0
        lea     sndh_start(pc),a0
        adda.l  d0,a0                   ; the subtune's .ymx file
        lea     sndh_work(pc),a1
        bsr     YMX_init
        tst.l   d0
        bne.s   .rejected               ; a foreign file: stay silent
        st      (a6)                    ; live
.rejected:
        movem.l (sp)+,d0-a6
        rts

; EXIT: quiesce the player, hand the machine back exactly as INIT found it.
sndh_exit:
        movem.l d0-a6,-(sp)
        lea     sndh_live(pc),a6
        tst.b   (a6)
        beq.s   .dead
        bsr     sndh_stop
.dead:
        movem.l (sp)+,d0-a6
        rts

sndh_stop:
        clr.b   (a6)                    ; a6 = sndh_live, set by both callers
        lea     sndh_work(pc),a0
        bsr     YMX_stop                ; timers stopped, claim disabled,
        lea     sndh_saved(pc),a5       ; voices silent - now the handback
        move.w  sr,-(sp)
        ori.w   #$0700,sr
        move.l  (a5)+,$134.w
        move.l  (a5)+,$110.w
        move.b  (a5)+,$FFFFFA19.w
        move.b  (a5)+,d1                ; only Timer D's nibble is ours
        and.b   #$0F,d1
        or.b    d1,$FFFFFA1D.w
        move.b  (a5)+,$FFFFFA1F.w
        move.b  (a5)+,$FFFFFA25.w
        move.b  (a5),d1                 ; re-set the bits INIT found set -
        btst    #0,d1                   ; YMX_stop cleared all four
        beq.s   .iera_clear
        bset    #5,$FFFFFA07.w
.iera_clear:
        btst    #1,d1
        beq.s   .imra_clear
        bset    #5,$FFFFFA13.w
.imra_clear:
        btst    #2,d1
        beq.s   .ierb_clear
        bset    #4,$FFFFFA09.w
.ierb_clear:
        btst    #3,d1
        beq.s   .imrb_clear
        bset    #4,$FFFFFA15.w
.imrb_clear:
        move.w  (sp)+,sr
        rts

; PLAY: once per tick, from whatever interrupt the host hung on TC50.
sndh_play:
        movem.l d0-a6,-(sp)
        lea     sndh_live(pc),a0
        tst.b   (a0)
        beq.s   .silent
        lea     sndh_work(pc),a0
        bsr     YMX_play                ; -1 past a play-once end: harmless,
.silent:                                ; the FRMS tag told the host when
        movem.l (sp)+,d0-a6
        rts

; ---------------------------------------------------------------- the player
        include "YMX.S"
        include "ST4_wrap.S"

; ---------------------------------------------------------------- the state
sndh_live:
        dc.b    0
        even
sndh_saved:
        ds.b    16                      ; 2 vectors, 4 timer bytes, the bits
        even
sndh_work:
        ds.b    YMX_FIXED+(YMX_STREAMS*RING_SIZE)
        even

; ---------------------------------------------------------------- the tunes
sndh_table:
        include "sndh_tunes.inc"
        even
sndh_end:
