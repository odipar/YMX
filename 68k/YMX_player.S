; -----------------------------------------------------------------------------
; YMX_player.S - the PRG stub: a TOS program prepended to an SNDH file.
;
; This assembles to a raw, position-independent binary with no tunes inside:
; a STUB that a tool prepends to an SNDH file, with a 28-byte PRG header in
; front and an empty relocation table behind, into a runnable program without
; an assembler - doc/BINARIES.md is the byte-exact contract, and
; org.ymx.MkPrg the tool. The SNDH file begins where this binary ends, and
; the stub drives it through its three entries: +0 init (subtune in d0.w,
; 1-based), +4 exit, +8 play.
;
; The stub's fixed layout, offsets from its start:
;
;    0  bra.w  main
;    4  'YMXP'                 stub descriptor
;    8  .w  1                  descriptor version
;   10  .w  1                  subtunes, patched by the tool
;   12  .l  0                  frames of subtune 1 when it plays once and
;                              stands alone, patched by the tool; 0 = play on
;   16  .w  0                  flags, patched by the tool: bit 0 = drop
;                              YMXDONE.MRK on exit
;
; Nothing in here is absolute, so the PRG's relocation table is empty. The
; session: takeover, one play call per VBL, a stop on SPACE or when a
; patched frame count runs out, the number keys 1-9 switching subtunes.
; Flag bit 0 is for scripted runs - ym/play.sh watches for the marker file
; so it can close the emulator by itself.
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

; What play saves and the handback restores, as offsets into `saved` - the
; two longs first, so every field sits at its natural alignment.
SV_VBL      equ     0               ; long  the VBL vector
SV_USP      equ     4               ; long  the USP
SV_IERA     equ     8               ; 4 interrupt bytes
SV_IERB     equ     9
SV_IMRA     equ     10
SV_IMRB     equ     11
SV_TACR     equ     12              ; 7 timer bytes
SV_TBCR     equ     13
SV_TCDCR    equ     14
SV_TADR     equ     15
SV_TBDR     equ     16
SV_TCDR     equ     17
SV_TDDR     equ     18

; The session state, as offsets into `state`.
ST_DONE     equ     0               ; word  nonzero once the tune ended
ST_PLAYED   equ     2               ; long  frames played since init
ST_MOUSE    equ     6               ; word  mouse packet bytes to skip

stub:
        bra.w   main
        dc.b    'YMXP'
stub_version:
        dc.w    1
stub_tunes:
        dc.w    1
stub_frames:
        dc.l    0
stub_flags:
        dc.w    0

main:
        move.l  4(sp),a5                        ; the basepage TOS gave us
        lea     stack_top(pc),sp                ; our own stack, inside the blob
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

        lea     m_banner(pc),a0
        bsr     puts
        move.w  stub_tunes(pc),d0               ; the subtune line's count
        cmp.w   #1,d0                           ; digit, poked at run time -
        beq.s   .one_tune                       ; the stub runs from RAM
        cmp.w   #9,d0                           ; the number keys reach 9
        bls.s   .digit
        moveq   #9,d0
.digit:
        add.b   #'0',d0
        lea     m_count(pc),a0
        move.b  d0,(a0)
        lea     m_tunes(pc),a0                  ; printed while TOS still owns
        bsr     puts                            ; the machine - see below
        bra.s   .printed
.one_tune:
        lea     m_playing(pc),a0
        bsr     puts
.printed:

; From here to the handback the machine is ours alone: every TOS interrupt
; silenced, the VBL unshared, the keyboard read straight off the ACIA. The
; SNDH file does its own save/restore of the timers it claims - two,
; for a YM tune - redundant under this takeover and exactly right
; when the same bytes run under a jukebox.
        pea     play(pc)
        move.w  #Supexec,-(sp)
        trap    #XBIOS
        addq.l  #6,sp

        lea     m_bye(pc),a0
        bsr     puts
        btst    #0,stub_flags+1(pc)             ; a scripted run - ym/play.sh -
        beq.s   .no_marker                      ; watches for this file so it
        clr.w   -(sp)                           ; can close the emulator when
        pea     m_marker(pc)                    ; the program stops
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
        clr.w   -(sp)                           ; Pterm0
        trap    #GEMDOS

; -----------------------------------------------------------------------------
; play, under Supexec: takeover, the session, handback.
; -----------------------------------------------------------------------------
play:
        move.w  sr,-(sp)
        move.w  #$2700,sr
        lea     saved(pc),a5
        move.l  VBL_VECTOR.w,SV_VBL(a5)
        move    usp,a0                          ; ours to keep, per the
        move.l  a0,SV_USP(a5)                   ; host-state contract
        move.b  $FFFFFA07.w,SV_IERA(a5)
        move.b  $FFFFFA09.w,SV_IERB(a5)
        move.b  $FFFFFA13.w,SV_IMRA(a5)
        move.b  $FFFFFA15.w,SV_IMRB(a5)
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
        move.b  $FFFFFA19.w,SV_TACR(a5)
        move.b  $FFFFFA1B.w,SV_TBCR(a5)
        move.b  $FFFFFA1D.w,SV_TCDCR(a5)
        move.b  $FFFFFA1F.w,SV_TADR(a5)
        move.b  $FFFFFA21.w,SV_TBDR(a5)
        move.b  $FFFFFA23.w,SV_TCDR(a5)
        move.b  $FFFFFA25.w,SV_TDDR(a5)
        clr.b   $FFFFFA19.w                     ; Timer A
        clr.b   $FFFFFA1B.w                     ; Timer B
        clr.b   $FFFFFA1D.w                     ; Timers C and D share this

        lea     state(pc),a6
        clr.w   ST_DONE(a6)
        clr.l   ST_PLAYED(a6)
        clr.w   ST_MOUSE(a6)
        moveq   #1,d0                           ; subtune 1
        bsr     sndh_call_init
        lea     handler(pc),a0
        move.l  a0,VBL_VECTOR.w
        move.w  #$2300,sr                       ; VBL and the file's timers

.wait:
        tst.w   ST_DONE(a6)                     ; the handler counts the end
        bne.s   .stop
        btst    #0,ACIA_CTRL.w                  ; a byte from the keyboard?
        beq.s   .wait
        move.b  ACIA_DATA.w,d0
        tst.w   ST_MOUSE(a6)                    ; inside a mouse packet: its
        beq.s   .fresh                          ; delta bytes are not keys
        subq.w  #1,ST_MOUSE(a6)
        bra.s   .wait
.fresh:
        cmp.b   #$F8,d0                         ; $F8-$FB open a relative
        bcs.s   .key                            ; mouse packet of two deltas
        cmp.b   #$FC,d0
        bcc.s   .key
        move.w  #2,ST_MOUSE(a6)
        bra.s   .wait
.key:
        cmp.b   #SPACE_MAKE,d0                  ; space stops
        beq.s   .stop
        move.w  stub_tunes(pc),d1               ; make codes $02-$0A are the
        cmp.w   #1,d1                           ; number keys 1-9: switch,
        beq.s   .wait                           ; when there is a set
        cmp.w   #9,d1
        bls.s   .keys
        moveq   #9,d1
.keys:
        cmp.b   #2,d0
        bcs.s   .wait
        addq.b  #2,d1                           ; make codes 2 to tunes+1
        cmp.b   d1,d0
        bcc.s   .wait
        subq.b  #1,d0
        and.w   #$FF,d0                         ; the subtune, 1-based
        move.w  sr,-(sp)
        move.w  #$2700,sr                       ; no play call mid-switch
        move.w  d0,-(sp)
        bsr     sndh_call_exit
        move.w  (sp)+,d0
        bsr     sndh_call_init
        clr.l   ST_PLAYED(a6)                   ; the frame count starts over
        move.w  (sp)+,sr
        bra.s   .wait

.stop:
        move.w  #$2700,sr
        bsr     sndh_call_exit                  ; the file hands its claim back
        lea     saved(pc),a5
        move.l  SV_VBL(a5),VBL_VECTOR.w
        movea.l SV_USP(a5),a0
        move    a0,usp
        move.b  SV_TADR(a5),$FFFFFA1F.w         ; counts back before controls,
        move.b  SV_TBDR(a5),$FFFFFA21.w         ; so a timer restarts on the
        move.b  SV_TCDR(a5),$FFFFFA23.w         ; count TOS gave it
        move.b  SV_TDDR(a5),$FFFFFA25.w
        move.b  SV_TACR(a5),$FFFFFA19.w         ; TOS's timers running again
        move.b  SV_TBCR(a5),$FFFFFA1B.w
        move.b  SV_TCDCR(a5),$FFFFFA1D.w
        move.b  SV_IERA(a5),$FFFFFA07.w         ; and TOS's interrupts revived
        move.b  SV_IERB(a5),$FFFFFA09.w
        move.b  SV_IMRA(a5),$FFFFFA13.w
        move.b  SV_IMRB(a5),$FFFFFA15.w
        move.w  (sp)+,sr
        rts

; The SNDH entries are branch instructions, so a jsr INTO the triple runs
; the entry and its rts comes back here.
sndh_call_init:
        lea     sndh(pc),a0
        jsr     (a0)                            ; +0: init, subtune in d0.w
        rts
sndh_call_exit:
        lea     sndh(pc),a0
        jsr     4(a0)                           ; +4: exit
        rts

; The VBL: one play call per frame, and for a play-once tune the frame count
; the tool patched in marks when the music has ended.
handler:
        movem.l d0-d7/a0-a6,-(sp)
        lea     state(pc),a6
        tst.w   ST_DONE(a6)
        bne.s   .done
        lea     sndh(pc),a0
        jsr     8(a0)                           ; +8: play
        move.l  stub_frames(pc),d0
        beq.s   .done
        addq.l  #1,ST_PLAYED(a6)
        cmp.l   ST_PLAYED(a6),d0
        bhi.s   .done
        move.w  #1,ST_DONE(a6)
.done:
        movem.l (sp)+,d0-d7/a0-a6
        rte                                     ; ours alone: no TOS behind us

puts:
        move.l  a0,-(sp)
        move.w  #Cconws,-(sp)
        trap    #GEMDOS
        addq.l  #6,sp
        rts

m_banner:   dc.b    "YMX player v1.0 - streaming through ST4",13,10,0
m_playing:  dc.b    "Playing. SPACE stops.",13,10,0
m_tunes:    dc.b    "Playing. SPACE stops.",13,10      ; a line of its own: the
            dc.b    "1-"                               ; console is 40 columns
m_count:    dc.b    "X picks a subtune.",13,10,0
m_marker:   dc.b    "YMXDONE.MRK",0
m_bye:      dc.b    "Done.",13,10,0
        even

saved:      ds.b    20                          ; the SV_ fields
        even
state:      ds.b    8                           ; the ST_ fields
        even
            ds.b    $800
stack_top:
sndh:                                           ; the SNDH file begins here
