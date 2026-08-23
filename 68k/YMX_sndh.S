; -----------------------------------------------------------------------------
; YMX_sndh.S - the YMX player as a prebuilt SNDH core.
;
; This assembles to a position-independent binary with no tunes, no tags and
; no workspace inside: a CORE that a tool combines with packed tunes into a
; complete SNDH file without an assembler - doc/BINARIES.md is the byte-exact
; contract, and org.ymx.MkSndh the tool. ymx/mkcores.sh assembles one core
; per ST4 unit size and flag combination; everything else the player needs -
; ring size, chunk, frame count - is read from each tune's own header at
; init.
;
; The core's fixed layout, offsets from sndh_core:
;
;    0  bra.w  init            the three classic SNDH entries, which the
;    4  bra.w  exit            combined file's outer header branches into
;    8  bra.w  play
;   12  'YMXC'                 core descriptor
;   16  .w  1                  descriptor version
;   18  .w  ST4_UNIT           the unit size this build decodes
;   20  .w  flags              bit 0 = the raster monitor is built in,
;                              bit 1 = the frame write runs unmasked
;   22  .w  YMX_VERSION        the format version this build reads: a tool
;                              combines only tunes of the same version
;   24  .w  YMX_FIXED          workspace bytes before the rings: a tool
;                              sizes the workspace as this + 25 x max N
;   26  .l  0                  table offset, patched by the tool: a word
;                              subtune count, then a long offset per tune
;   30  .l  0                  workspace offset, patched by the tool
;
; Every patched offset is relative to sndh_core and even. The combined file
; is loaded anywhere even; nothing in here is absolute.
;
; The contract the entries honor (researched against the v2.2 spec, psgplay,
; sc68 and native-player practice):
;   - INIT (subtune in d0.w, 1-based; out of range plays subtune 1), EXIT
;     and PLAY each preserve d0-a6 and return with rts, supervisor assumed;
;   - the host's own interrupts keep running: nothing is disabled beyond the
;     two MFP timers a YM tune claims, and everything the player touches -
;     both timer vectors, TACR, TCDCR's Timer D nibble, TADR, TDDR, the four
;     enable/mask bits - is saved at INIT and handed back at EXIT. A file
;     whose T stream names Timer B or C would need those saved here too, and
;     a Timer C tune could not be hosted from a Timer C hook;
;   - INIT called twice without an EXIT restores first;
;   - PLAY before INIT is a no-op; PLAY is driven at the TC tag's rate;
;   - the USP is never touched (YMX_SUPER_HOST stays 0), and every buffer
;     lives inside the combined file.
;
; One real limitation: a native host that dispatches PLAY from its Timer C
; hook holds the MFP's Timer C in-service for the length of the call, which
; delays Timer D ticks (timer channel 1's) by up to that length - pending,
; not lost. VBL- and emulator-hosted playback has no such window.
; -----------------------------------------------------------------------------

sndh_core:
        bra.w   sndh_init
        bra.w   sndh_exit
        bra.w   sndh_play
        dc.b    'YMXC'
        dc.w    1
        dc.w    ST4_UNIT
        dc.w    YMX_PERF+((1-YMX_MASK_BURST)*2)
        dc.w    YMX_VERSION
        dc.w    YMX_FIXED
sndh_table_off:
        dc.l    0
sndh_work_off:
        dc.l    0

; ---------------------------------------------------------------- the glue
; INIT: d0.w = subtune, 1-based (out of range plays subtune 1).
sndh_init:
        movem.l d0-a6,-(sp)
        lea     sndh_live(pc),a6
        tst.b   (a6)
        beq.s   .fresh
        bsr     sndh_stop               ; init without exit: clean up first
.fresh:

; Save what the player will touch - the list YMX.S assumption 5 gives. The
; four enable/mask bits are kept as bits, so EXIT can restore them without
; clobbering the host's own later changes to the same registers.
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

; The subtune table the tool appended: a word count, then a long offset per
; tune, each offset from sndh_core; d0 is 1-based.
        lea     sndh_core(pc),a1
        movea.l a1,a0
        adda.l  sndh_table_off(pc),a1
        subq.w  #1,d0
        cmp.w   (a1)+,d0
        bcs.s   .tune_ok
        moveq   #0,d0
.tune_ok:
        add.w   d0,d0
        add.w   d0,d0
        move.l  0(a1,d0.w),d0
        adda.l  d0,a0                   ; the subtune's .ymx file
        lea     sndh_core(pc),a1
        adda.l  sndh_work_off(pc),a1    ; the workspace the tool appended
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
        lea     sndh_core(pc),a0
        adda.l  sndh_work_off(pc),a0
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

; PLAY: once per tick, from the interrupt the host hung on the TC tag.
sndh_play:
        movem.l d0-a6,-(sp)
        lea     sndh_live(pc),a0
        tst.b   (a0)
        beq.s   .silent
        lea     sndh_core(pc),a0
        adda.l  sndh_work_off(pc),a0
        bsr     YMX_play                ; -1 past a play-once end: harmless,
.silent:                                ; the FRMS tag gave the host the count
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
