; -----------------------------------------------------------------------------
; YMX_test.S - real-hardware validation for 68k/YMX.S.
;
; Plays an embedded tune on the actual sound chip and reads every register
; back after every frame, folding the chip's contents into a checksum that
; the host computed from the YM data alone. It plays past the end of the
; tune, so the tune starts over and the wrap count is checked too. That
; covers what emulation cannot: the player is writing to a real YM2149 through
; real bus cycles, and a wrong register number or a missed write changes the
; checksum.
;
; Then it plays the tune again, untimed by read-back, and reports the ticks it
; took - the player's cost on real hardware.
;
; Output: "SUM=OK" or "SUM=BAD", "T <frames> <ticks>", "CALIB <n> <ticks>", DONE.
; -----------------------------------------------------------------------------

GEMDOS      equ     1
Pterm0      equ     0
Cconws      equ     9
Super       equ     $20
hz200       equ     $4BA

CALIB_OUTER equ     12
PSG         equ     $FFFF8800

        .text

start:
        move.l  #stack_top,-(sp)
        move.w  #Super,-(sp)
        trap    #GEMDOS
        addq.l  #6,sp
        move.l  d0,save_ssp

        lea     m_banner,a0
        bsr     puts

; ------------------------------------------------- the chip gets the right YM
        lea     m_sum,a0
        bsr     puts
        lea     song,a0
        lea     workspace,a1
        bsr     YMX_init
        tst.l   d0
        bne     .bad

        clr.w   wraps
        moveq   #0,d7                           ; the running checksum
        move.l  #YMX_PLAY,d6                    ; frames left to play
; Selecting a register and reading it back are two bus cycles, and TOS's own
; interrupt handlers use the sound chip too - the floppy motor timeout writes
; port A. With interrupts on, one landing between the two makes the harness
; read whichever register TOS selected, so the whole verification runs masked.
        move.w  sr,-(sp)
        ori.w   #$0700,sr
.frame:
        lea     workspace,a0
        bsr     YMX_play
        tst.l   d0
        bmi.s   .bad                            ; a tune that starts over
                                                ; never ends
        beq.s   .counted
        addq.w  #1,wraps                        ; that frame ended the tune
.counted:
        bsr     fold_chip
        subq.l  #1,d6
        bne.s   .frame

        move.w  (sp)+,sr                        ; interrupts back on
        cmp.w   #YMX_WRAPS,wraps
        bne.s   .bad
        cmp.l   #YMX_SUM,d7
        bne.s   .bad
        lea     m_ok,a0
        bsr     puts
        bra.s   .wrapcount
.bad:
        lea     m_bad,a0
        bsr     puts
.wrapcount:
        lea     m_wraps,a0                      ; printed either way, so a
        bsr     puts                            ; mismatch says which half failed
        moveq   #0,d0
        move.w  wraps,d0
        bsr     putdec
        lea     m_sum2,a0
        bsr     puts
        move.l  d7,d0
        bsr     putdec

; ------------------------------------------------------- what a frame costs
.timing:
        bsr     crlf
        lea     m_calib,a0
        bsr     puts
        bsr     sync
        move.w  #CALIB_OUTER,d7
        bsr     calib
        bsr     elapsed
        move.l  d0,-(sp)
        moveq   #CALIB_OUTER,d0
        bsr     putdec
        lea     m_sp,a0
        bsr     puts
        move.l  (sp)+,d0
        bsr     putdec
        bsr     crlf

        lea     workspace,a0
        bsr     YMX_stop                ; quiesce before going again
        lea     song,a0
        lea     workspace,a1
        bsr     YMX_init
        lea     m_t,a0
        bsr     puts
        move.l  #YMX_PLAY,d0
        bsr     putdec
        lea     m_sp,a0
        bsr     puts

        move.l  #YMX_PLAY,d6
        bsr     sync
.play:
        lea     workspace,a0
        bsr     YMX_play
        subq.l  #1,d6
        bne.s   .play
        bsr     elapsed
        bsr     putdec
        bsr     crlf
        lea     workspace,a0
        bsr     YMX_stop

        lea     m_done,a0
        bsr     puts
        move.l  save_ssp,-(sp)
        move.w  #Super,-(sp)
        trap    #GEMDOS
        addq.l  #6,sp
        clr.w   -(sp)
        trap    #GEMDOS

; -----------------------------------------------------------------------------
; fold_chip: read all fourteen sound registers back off the chip and fold them
; into d7. Every register is written every frame except R13 on a frame that
; leaves the envelope alone, so the read-back is defined from frame 0 on - the
; tune writes a shape in its first frame.
; -----------------------------------------------------------------------------
fold_chip:
        movem.l d0-d2/a0,-(sp)
        lea     PSG,a0
        moveq   #0,d0                           ; register number
        moveq   #13,d1
.read:
        move.b  d0,(a0)                         ; select
        moveq   #0,d2
        move.b  (a0),d2                         ; and read it back
        rol.l   #1,d7
        eor.l   d2,d7
        addq.b  #1,d0
        dbf     d1,.read
        movem.l (sp)+,d0-d2/a0
        rts

; ------------------------------------------------------------------ the library
; puts, crlf, putdec, sync, elapsed and calib come from the shared harness
; helpers, the same ones the ST1 hardware tests use.
ST4_UNIT    equ     1
        include "YMX.S"
        include "ST4_wrap.S"
        include "hatari_util.inc"

        .data
m_banner:   dc.b    "YMX TEST",13,10,0
m_sum:      dc.b    "SUM=",0
m_ok:       dc.b    "OK ",0
m_bad:      dc.b    "BAD ",0
m_calib:    dc.b    "CALIB ",0
m_t:        dc.b    "T ",0
m_wraps:    dc.b    "wraps=",0
m_sum2:     dc.b    " sum=",0
m_sp:       dc.b    " ",0
m_done:     dc.b    "DONE",13,10,0
        even

        include "ymxdata.inc"

        .bss
        even
save_ssp:   ds.l    1
wraps:      ds.w    1
; The parentheses matter: rmac evaluates expressions strictly left to right.
workspace:  ds.b    YMX_FIXED+(YMX_STREAMS*RING_SIZE)
        even
            ds.b    4096
stack_top:
