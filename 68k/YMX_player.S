; -----------------------------------------------------------------------------
; YMX_player.S - a complete TOS program around an SNDH container.
;
; This is what ymx/mkprg.sh assembles: a thin shell that takes the machine
; over, then drives the incbin'd SNDH blob - the canonical build of the
; player - through its three entries: +0 init (subtune in d0.w, 1-based),
; +4 exit, +8 play. All the player code lives in the SNDH; the PRG adds
; only this shell, so the two containers share every byte that matters.
;
; It is not a standalone assembly: the includer supplies four things, then
; includes this file.
;
;       YMX_TUNES       equ     1       ; subtunes in the blob
;       YMX_FRAMES      equ     0       ; frames of subtune 1; 0 = loops
;       YMX_EXIT_MARKER equ     0       ; 1 to drop a file when it stops
;               include "YMX_player.S"
;       sndh:   incbin  "tune.sndh"     ; in .data, at an even address
;
; ymx/mkprg.sh writes exactly that wrapper. SPACE quits; with more than one
; subtune, the number keys 1-9 switch.
; -----------------------------------------------------------------------------

GEMDOS      equ     1
Fcreate     equ     $3C
Fclose      equ     $3E
Cconws      equ     9
Mshrink     equ     $4A
XBIOS       equ     14
Supexec     equ     38
VBL_VECTOR  equ     $70
ACIA_CTRL   equ     $FFFFFC00       ; the keyboard ACIA, polled directly
ACIA_DATA   equ     $FFFFFC02
SPACE_MAKE  equ     $39             ; the space bar's make code

        .text

start:
        move.l  4(sp),a5                        ; the basepage TOS gave us
        lea     stack_top,sp                    ; our own stack, inside our bss
        move.l  12(a5),d0                       ; give back everything past
        add.l   20(a5),d0                       ; text + data + bss
        add.l   28(a5),d0
        add.l   #$100,d0                        ; plus the basepage itself
        move.l  d0,-(sp)
        move.l  a5,-(sp)
        clr.w   -(sp)
        move.w  #Mshrink,-(sp)
        trap    #GEMDOS
        lea     12(sp),sp

        lea     m_banner,a0
        bsr     puts
        lea     m_playing,a0                    ; printed while TOS still owns
        bsr     puts                            ; the machine - see below

; From here to the handback the machine is ours alone: every TOS interrupt
; silenced, the VBL unshared, the keyboard read straight off the ACIA. The
; SNDH blob does its own save/restore of the timers it claims - two,
; for a YM tune - redundant under this takeover and exactly right
; when the same bytes run under a jukebox.
        move.l  #play,-(sp)
        move.w  #Supexec,-(sp)
        trap    #XBIOS
        addq.l  #6,sp

        lea     m_bye,a0
        bsr     puts
        .if YMX_EXIT_MARKER
; A scripted run - ymx/play.sh - watches for this file so it can close the
; emulator when the program stops, instead of leaving a window to be quit by
; hand.
        clr.w   -(sp)                           ; ordinary attributes
        pea     m_marker
        move.w  #Fcreate,-(sp)
        trap    #GEMDOS
        lea     8(sp),sp
        tst.w   d0
        bmi.s   .no_marker
        move.w  d0,-(sp)
        move.w  #Fclose,-(sp)
        trap    #GEMDOS
        addq.l  #4,sp
.no_marker:
        .endif
        clr.w   -(sp)                           ; Pterm0
        trap    #GEMDOS

; -----------------------------------------------------------------------------
; play, under Supexec: takeover, the session, handback.
; -----------------------------------------------------------------------------
play:
        move.w  sr,-(sp)
        move.w  #$2700,sr
        move.b  $FFFFFA07.w,old_iera
        move.b  $FFFFFA09.w,old_ierb
        move.b  $FFFFFA13.w,old_imra
        move.b  $FFFFFA15.w,old_imrb
        clr.b   $FFFFFA07.w                     ; every MFP interrupt dead
        clr.b   $FFFFFA09.w
        clr.b   $FFFFFA13.w
        clr.b   $FFFFFA15.w
; And every timer STOPPED, not merely silenced. Clearing the enable bits
; leaves TOS's timers counting underneath - Timer D is its RS232 baud
; generator and keeps ticking at a few hundred kHz - machine state
; the tune did not name. A host that takes the machine over should give
; the player a quiet MFP; the player itself claims only what its tune
; names. Data registers are saved too: a timer reloads from them.
        move.b  $FFFFFA19.w,old_tacr
        move.b  $FFFFFA1B.w,old_tbcr
        move.b  $FFFFFA1D.w,old_tcdcr
        move.b  $FFFFFA1F.w,old_tadr
        move.b  $FFFFFA21.w,old_tbdr
        move.b  $FFFFFA23.w,old_tcdr
        move.b  $FFFFFA25.w,old_tddr
        clr.b   $FFFFFA19.w                     ; Timer A
        clr.b   $FFFFFA1B.w                     ; Timer B
        clr.b   $FFFFFA1D.w                     ; Timers C and D share this
        move    usp,a0                          ; ours to keep, per the
        move.l  a0,old_usp                      ; host-state contract

        moveq   #1,d0                           ; subtune 1
        bsr     sndh_call_init
        move.l  VBL_VECTOR,old_vbl
        move.l  #handler,VBL_VECTOR
        move.w  #$2300,sr                       ; VBL and the blob's timers

        clr.w   mouse_skip
.wait:
        tst.w   finished                        ; the handler counts the end
        bne.s   .stop
        btst    #0,ACIA_CTRL.w                  ; a byte from the keyboard?
        beq.s   .wait
        move.b  ACIA_DATA.w,d0
        tst.w   mouse_skip                      ; inside a mouse packet: its
        beq.s   .fresh                          ; delta bytes are not keys
        subq.w  #1,mouse_skip
        bra.s   .wait
.fresh:
        cmp.b   #$F8,d0                         ; $F8-$FB open a relative
        bcs.s   .key                            ; mouse packet of two deltas
        cmp.b   #$FC,d0
        bcc.s   .key
        move.w  #2,mouse_skip
        bra.s   .wait
.key:
        cmp.b   #SPACE_MAKE,d0                  ; space stops
        beq.s   .stop
        .if YMX_TUNES-1
        cmp.b   #2,d0                           ; make codes $02-$0A are the
        bcs.s   .wait                           ; number keys 1-9: switch
        cmp.b   #2+YMX_TUNES,d0
        bcc.s   .wait
        subq.b  #1,d0
        and.w   #$FF,d0                         ; the subtune, 1-based
        move.w  sr,-(sp)
        move.w  #$2700,sr                       ; no play call mid-switch
        move.w  d0,-(sp)
        bsr     sndh_exit_entry
        move.w  (sp)+,d0
        bsr     sndh_call_init
        clr.w   frame_count
        move.w  (sp)+,sr
        .endif
        bra.s   .wait

.stop:
        move.w  #$2700,sr
        bsr     sndh_exit_entry                 ; the blob hands its claim back
        move.l  old_vbl,VBL_VECTOR
        movea.l old_usp,a0
        move    a0,usp
        move.b  old_tadr,$FFFFFA1F.w            ; counts back before controls,
        move.b  old_tbdr,$FFFFFA21.w            ; so a timer restarts on the
        move.b  old_tcdr,$FFFFFA23.w            ; count TOS gave it
        move.b  old_tddr,$FFFFFA25.w
        move.b  old_tacr,$FFFFFA19.w            ; TOS's timers running again
        move.b  old_tbcr,$FFFFFA1B.w
        move.b  old_tcdcr,$FFFFFA1D.w
        move.b  old_iera,$FFFFFA07.w            ; and TOS's interrupts revived
        move.b  old_ierb,$FFFFFA09.w
        move.b  old_imra,$FFFFFA13.w
        move.b  old_imrb,$FFFFFA15.w
        move.w  (sp)+,sr
        rts

sndh_call_init:
        lea     sndh,a0
        jsr     (a0)                            ; +0: init, subtune in d0.w
        rts
sndh_exit_entry:
        lea     sndh+4,a0
        jsr     (a0)
        rts

; The VBL: one play call per frame, and for a play-once tune the frame count
; the builder measured marks when the music has ended.
handler:
        movem.l d0-d7/a0-a6,-(sp)
        tst.w   finished
        bne.s   .done
        lea     sndh+8,a0
        jsr     (a0)
        .if YMX_FRAMES
        addq.w  #1,frame_count
        cmp.w   #YMX_FRAMES,frame_count
        bcs.s   .done
        move.w  #1,finished
        .endif
.done:
        movem.l (sp)+,d0-d7/a0-a6
        rte                                     ; ours alone: no TOS behind us

puts:
        move.l  a0,-(sp)
        move.w  #Cconws,-(sp)
        trap    #GEMDOS
        addq.l  #6,sp
        rts

        .data
m_banner:   dc.b    "YMX player v1.0 - streaming through ST4",13,10,0
m_playing:  dc.b    "Playing. SPACE stops."
        .if YMX_TUNES-1                 ; a line of its own: the console is
            dc.b    13,10               ; 40 columns and one line ran over
            dc.b    "1-",'0'+YMX_TUNES," picks a subtune."
        .endif
            dc.b    13,10,0
        .if YMX_EXIT_MARKER
m_marker:   dc.b    "YMXDONE.MRK",0
        .endif
m_bye:      dc.b    "Done.",13,10,0
        even

        .bss
        even
old_vbl:     ds.l    1
old_usp:     ds.l    1
finished:    ds.w    1
frame_count: ds.w    1
old_iera:    ds.b    1
old_ierb:    ds.b    1
old_imra:    ds.b    1
old_imrb:    ds.b    1
old_tacr:    ds.b    1
old_tbcr:    ds.b    1
old_tcdcr:   ds.b    1
old_tadr:    ds.b    1
old_tbdr:    ds.b    1
old_tcdr:    ds.b    1
old_tddr:    ds.b    1
        even
mouse_skip:  ds.w    1
        even
            ds.b    $800
stack_top:
