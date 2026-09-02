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
;   16  .w  2                  descriptor version
;   18  .w  ST4_UNIT           the unit size this build decodes
;   20  .w  flags              bit 0 = the raster monitor is built in,
;                              bit 1 = the frame write runs unmasked,
;                              bit 2 = the copy code is built in: copies
;                              from the literal stream decode, at any ring
;   22  .w  YMX_VERSION        the format version this build reads: a tool
;                              combines only tunes of the same version
;   24  .w  YMX_FIXED          workspace bytes before the rings: a tool
;                              sizes the workspace as this + 25 x the
;                              largest N in the combined tunes' headers
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
;   - the host's own interrupts keep running: nothing is disabled beyond
;     the MFP timers the player claims. INIT saves all four timers' state -
;     vector, control, data, enable and mask bits - and EXIT hands back
;     exactly the claimed ones, so a tune on Timers A and D (a YM tune) or
;     A, B and D restores what it used and leaves the rest to the
;     host. A Timer C tune still cannot be hosted from a Timer C hook;
;   - INIT called twice without an EXIT restores first;
;   - PLAY before INIT is a no-op; PLAY is driven at the TC tag's rate;
;   - the USP is never touched, and every buffer lives inside the
;     combined file.
;
; One real limitation: a native host that dispatches PLAY from its Timer C
; hook holds the MFP's Timer C in-service for the length of the call, which
; delays Timer D ticks (timer channel 1's) by up to that length - pending,
; not lost. VBL- and emulator-hosted playback has no such window.
; -----------------------------------------------------------------------------

; The save area, one slot per timer in A B C D order.
SAV_VEC         equ     0               ; 4 longs: the vectors
SAV_CTRL        equ     16              ; 4 bytes: the control registers
SAV_DATA        equ     20              ; 4 bytes: the data registers
SAV_BITS        equ     24              ; byte: enable bits in the low nibble,
SAV_SIZE        equ     26              ; mask bits in the high, timer order

; The copy code switch is read in the descriptor before YMX.S defaults it.
        .if ^^defined ST4_WINDOW
        .else
ST4_WINDOW      equ     0
        .endif

sndh_core:
        bra.w   sndh_init
        bra.w   sndh_exit
        bra.w   sndh_play
        dc.b    'YMXC'
        dc.w    2
        dc.w    ST4_UNIT
        dc.w    YMX_PERF+((1-YMX_MASK_BURST)*2)+((ST4_WINDOW<>0)*4)
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

; Save every timer's state - a tune may claim any of the four, and which
; ones is the T stream's, known only inside YMX_init. The enable and mask
; bits are kept as bits, one per timer in each nibble, so EXIT can restore
; them without clobbering the host's own later changes to the same
; registers.
        lea     sndh_saved(pc),a5
        move.l  $134.w,SAV_VEC+0(a5)    ; the vectors, in timer order A B C D
        move.l  $120.w,SAV_VEC+4(a5)
        move.l  $114.w,SAV_VEC+8(a5)
        move.l  $110.w,SAV_VEC+12(a5)
        move.b  $FFFFFA19.w,SAV_CTRL+0(a5)
        move.b  $FFFFFA1B.w,SAV_CTRL+1(a5)
        move.b  $FFFFFA1D.w,SAV_CTRL+2(a5)  ; C and D share TCDCR: the byte
        move.b  $FFFFFA1D.w,SAV_CTRL+3(a5)  ; twice, each restore masks its own
        move.b  $FFFFFA1F.w,SAV_DATA+0(a5)
        move.b  $FFFFFA21.w,SAV_DATA+1(a5)
        move.b  $FFFFFA23.w,SAV_DATA+2(a5)
        move.b  $FFFFFA25.w,SAV_DATA+3(a5)
        moveq   #0,d1
        btst    #5,$FFFFFA07.w          ; the enable bits, low nibble
        beq.s   .ena
        bset    #0,d1
.ena:
        btst    #0,$FFFFFA07.w
        beq.s   .enb
        bset    #1,d1
.enb:
        btst    #5,$FFFFFA09.w
        beq.s   .enc
        bset    #2,d1
.enc:
        btst    #4,$FFFFFA09.w
        beq.s   .end
        bset    #3,d1
.end:
        btst    #5,$FFFFFA13.w          ; the mask bits, high nibble
        beq.s   .mba
        bset    #4,d1
.mba:
        btst    #0,$FFFFFA13.w
        beq.s   .mbb
        bset    #5,d1
.mbb:
        btst    #5,$FFFFFA15.w
        beq.s   .mbc
        bset    #6,d1
.mbc:
        btst    #4,$FFFFFA15.w
        beq.s   .mbd
        bset    #7,d1
.mbd:
        move.b  d1,SAV_BITS(a5)

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

; The decoder is code in RAM: ST4_init writes the ring size into it as the
; window. A 68020 or later holds instructions in a cache, and is told; the
; _CPU cookie says which processor this is, and INIT runs in supervisor
; mode, where the cookie jar and the cache control register are readable.
        .if ST4_WINDOW
        move.l  $5A0.w,d0               ; the cookie jar, or 0 without one
        beq.s   .cache_done
        movea.l d0,a0
.cookie:
        move.l  (a0)+,d0                ; the cookie's name, 0 at the end
        beq.s   .cache_done
        move.l  (a0)+,d1                ; its value
        cmp.l   #$5F435055,d0           ; '_CPU'
        bne.s   .cookie
        cmp.l   #20,d1                  ; 68000 and 68010: no cache
        blo.s   .cache_done
        cmp.l   #40,d1
        bhs.s   .push
        .68030
        movec   cacr,d0                 ; 68020 and 68030: clear the
        ori.w   #8,d0                   ; instruction cache
        movec   d0,cacr
        .68000
        bra.s   .cache_done
.push:
        dc.w    $F4F8                   ; cpusha bc: 68040 and 68060 push
.cache_done:                            ; and invalidate both caches
        .endif

; Record WHICH timers this tune claimed - YMX_stop clears the claims, so
; EXIT restores from this mask. Each claimed channel's descriptor holds a
; copy of its timer's vector address, one of the four in sndh_vecs.
        lea     ymx_desc_0(pc),a0
        moveq   #YMX_CHANNELS-1,d2
        moveq   #0,d3
.claims:
        tst.w   CH_CLAIMED(a0)
        beq.s   .unclaimed
        lea     sndh_vecs(pc),a1
        moveq   #0,d0
.which:
        move.w  CH_VEC(a0),d1
        cmp.w   (a1)+,d1
        beq.s   .mark
        addq.w  #1,d0
        bra.s   .which
.mark:
        bset    d0,d3
.unclaimed:
        lea     CH_SIZE(a0),a0
        dbra    d2,.claims
        lea     sndh_claims(pc),a0
        move.b  d3,(a0)
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
        bsr     YMX_stop                ; timers stopped, claims disabled,
                                        ; voices silent - now the handback

; Restore each timer the tune CLAIMED - the mask INIT recorded - from its
; saved slot; an unclaimed timer was never touched and keeps the host's own
; state. The rows of ymx_timers carry each timer's vector, control, data,
; keep mask and interrupt bit, in the same A B C D order as the slots.
        move.w  sr,-(sp)
        ori.w   #$0700,sr
        move.b  sndh_claims(pc),d3
        lea     ymx_timers(pc),a0
        lea     sndh_saved(pc),a5
        moveq   #0,d0                   ; the slot
.slot:
        btst    d0,d3
        beq.s   .unclaimed
        move.w  d0,d1
        add.w   d1,d1
        add.w   d1,d1
        movea.w CH_VEC(a0),a2           ; the vector back first: a restored
        move.l  SAV_VEC(a5,d1.w),(a2)   ; control byte may start the timer
        movea.w CH_DATA(a0),a2          ; the count before the control, so
        move.b  SAV_DATA(a5,d0.w),(a2)  ; a restart reloads the host's count
        movea.w CH_CTRL(a0),a2
        move.b  SAV_CTRL(a5,d0.w),d1    ; our nibble from the save, the
        move.w  CH_KEEP(a0),d4          ; other nibble as it stands now
        not.w   d4
        and.b   d4,d1
        move.b  (a2),d4
        move.w  CH_KEEP(a0),d5
        and.b   d5,d4
        or.b    d1,d4
        move.b  d4,(a2)
        move.b  SAV_BITS(a5),d1         ; the enable and mask bits INIT found
        movea.w CH_IER(a0),a2           ; set - YMX_stop cleared the claimed
        move.w  CH_IERBIT(a0),d5        ; ones
        btst    d0,d1
        beq.s   .enable_clear
        bset    d5,(a2)
.enable_clear:
        moveq   #4,d4
        add.w   d0,d4
        btst    d4,d1
        beq.s   .mask_clear
        bset    d5,12(a2)
.mask_clear:
.unclaimed:
        lea     CH_ROW(a0),a0
        addq.w  #1,d0
        cmp.w   #YMX_TIMERS,d0
        bcs.s   .slot
        move.w  (sp)+,sr
        rts

sndh_vecs:
        dc.w    $134,$120,$114,$110     ; Timer A, B, C, D

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
sndh_claims:
        dc.b    0
        even
sndh_saved:
        ds.b    SAV_SIZE
        even
