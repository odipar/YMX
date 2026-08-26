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
;                              YMXDONE.MRK on exit, bit 1 = tick from the
;                              VBL because the set claims Timer C
;   18  .w  50                 the tune rate in Hz, patched by the tool
;                              from the SNDH file's own timer tag
;
; Nothing in here is absolute, so the PRG's relocation table is empty. The
; session: takeover, play driven from Timer C at the system's own 200 Hz
; with the rate divided out - the SNDH convention, doc/BINARIES.md section
; 5 - or from the VBL where the set claims Timer C for an effect channel.
; A stop on SPACE or when a patched frame count runs out, the number keys
; 1-9 switching subtunes. Flag bit 0 is for scripted runs - ym/play.sh
; watches for the marker file so it can close the emulator by itself.
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
; The operating system's own Timer C - the 200 Hz tick it measures a
; double click, a key repeat and the time of day in: a /64 prescaler and
; 192 counts, since 2457600 / 64 / 192 is 200. This count is written back
; rather than read back, because a running timer's data register reads as
; the count it has reached and not as the count it restarts from.
TIMERC_SYSTEM_DATA equ  192
TIMERD_SYSTEM_DATA equ  2       ; and Timer D's, the serial clock's divider

TIMER_D_VEC equ     $110            ; the MFP timer vectors, in MFP order
TIMER_C_VEC equ     $114
TIMER_B_VEC equ     $120
TIMER_A_VEC equ     $134
ACIA_DATA   equ     $FFFFFC02
SPACE_MAKE  equ     $39             ; the space bar's make code
ESC_MAKE    equ     $01             ; and Escape's

; What play saves and the handback restores, as offsets into `saved` - the
; two longs first, so every field sits at its natural alignment.
SV_VBL      equ     0               ; long  the VBL vector
SV_USP      equ     4               ; long  the USP
SV_VEC_D    equ     8               ; long  the four MFP timer vectors: the
SV_VEC_C    equ     12              ; long  player parks the vector of every
SV_VEC_B    equ     16              ; long  timer it claims and restores none
SV_VEC_A    equ     20              ; long  of them (YMX.S assumption 5)
SV_IERA     equ     24              ; 4 interrupt bytes
SV_IERB     equ     25
SV_IMRA     equ     26
SV_IMRB     equ     27
SV_TACR     equ     28              ; 7 timer bytes
SV_TBCR     equ     29
SV_TCDCR    equ     30
                                ; no data register: see the restore

; The session state, as offsets into `state`.
ST_DONE     equ     0               ; word  nonzero once the tune ended
ST_PLAYED   equ     2               ; long  frames played since init
ST_MOUSE    equ     6               ; word  mouse packet bytes to skip
ST_ACCUM    equ     8               ; word  the rate, accumulated mod 200
ST_BUSY     equ     10              ; word  nonzero while a call is running

stub:
        bra.w   main
        dc.b    'YMXP'
stub_version:
        dc.w    2
stub_tunes:
        dc.w    1
stub_frames:
        dc.l    0
stub_flags:
        dc.w    0
stub_rate:
        dc.w    50

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
; names. No data register is saved: one reads as the count the timer has
; reached, not the value it reloads from, so there is nothing there to
; put back. The restore below writes the two counts the system runs.
; The timer vectors. Stream T may put a channel on any of the four, so all
; four are saved: a tune on Timer C leaves the system's 200 Hz clock parked
; on the player's own do-nothing entry, and the desktop counts nothing
; afterwards - a double click is timed off that counter.
        move.l  TIMER_D_VEC.w,SV_VEC_D(a5)
        move.l  TIMER_C_VEC.w,SV_VEC_C(a5)
        move.l  TIMER_B_VEC.w,SV_VEC_B(a5)
        move.l  TIMER_A_VEC.w,SV_VEC_A(a5)

        move.b  $FFFFFA19.w,SV_TACR(a5)
        move.b  $FFFFFA1B.w,SV_TBCR(a5)
        move.b  $FFFFFA1D.w,SV_TCDCR(a5)
        clr.b   $FFFFFA19.w                     ; Timer A
        clr.b   $FFFFFA1B.w                     ; Timer B
        clr.b   $FFFFFA1D.w                     ; Timers C and D share this

        lea     state(pc),a6
        clr.w   ST_DONE(a6)
        clr.l   ST_PLAYED(a6)
        clr.w   ST_MOUSE(a6)
        clr.w   ST_ACCUM(a6)
        clr.w   ST_BUSY(a6)
        moveq   #1,d0                           ; subtune 1
        bsr     sndh_call_init
        lea     vbl_idle(pc),a0                 ; the VBL is parked; the tick
        btst    #1,stub_flags+1(pc)             ; source below carries play
        bne     .tick_vbl
; Timer C is free of the tune, so it carries the calls - the SNDH host
; convention. The system's own 200 Hz (/64, 192 counts), this handler on
; its vector, and the rate accumulated against 200, so a 50 Hz tune plays
; every fourth tick and a 60 Hz one lands 60 calls in every 200, exactly:
; the accumulator is integer arithmetic and loses no call.
;
; Timer C counts the MFP's own 2.4576 MHz crystal, which the video clock
; does not track. 200 Hz against a 50.0527 Hz PAL frame walks the calls
; a third of a scanline a frame, measured at 169 cycles, and a -perf
; build paints that walk. A host that wants the calls at a fixed place
; on the screen drives them from the VBL below. The handback below
; already restores everything this touches.
        lea     tc_handler(pc),a1
        move.l  a1,TIMER_C_VEC.w
        move.b  #TIMERC_SYSTEM_DATA,$FFFFFA23.w
        or.b    #$50,$FFFFFA1D.w                ; TCDCR's C half: /64, run
        bset    #5,$FFFFFA09.w                  ; its interrupt enabled and
        bset    #5,$FFFFFA15.w                  ; unmasked
        bra     .tick_go
.tick_vbl:
        lea     handler(pc),a0                  ; the set claims Timer C for
.tick_go:                                       ; itself: the VBL calls play
        move.l  a0,VBL_VECTOR.w
        move.w  #$2300,sr                       ; the tick and the file's timers

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
        cmp.b   #SPACE_MAKE,d0                  ; space stops, and so does
        beq.s   .stop                           ; Escape
        cmp.b   #ESC_MAKE,d0
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
; A timer's data register reads as the count it has reached, so what a
; takeover reads back is wherever the count happened to be, and writing
; that back leaves the timer running at some rate of its own. That is what
; left the desktop unable to time a double click. The two the system runs
; go back as the values it runs them at - Timer C's 200 Hz clock, and
; Timer D's serial divider - and the counts come back before the controls,
; so a timer restarts on the value it will keep. The prescalers come back
; with TCDCR below, which does read as what was written to it.
;
; Timers A and B are the host's: their controls go back as they were, and
; a tune that claimed one leaves its count changed, which is assumption 5
; in YMX.S and the reason that assumption names no data register.
        move.b  #TIMERC_SYSTEM_DATA,$FFFFFA23.w
        move.b  #TIMERD_SYSTEM_DATA,$FFFFFA25.w
        move.b  SV_TACR(a5),$FFFFFA19.w         ; TOS's timers running again
        move.b  SV_TBCR(a5),$FFFFFA1B.w
        move.b  SV_TCDCR(a5),$FFFFFA1D.w
        move.b  SV_IERA(a5),$FFFFFA07.w         ; and TOS's interrupts revived
        move.b  SV_IERB(a5),$FFFFFA09.w
        move.b  SV_IMRA(a5),$FFFFFA13.w
        move.b  SV_IMRB(a5),$FFFFFA15.w
        move.l  SV_VEC_D(a5),TIMER_D_VEC.w      ; and the vectors TOS gave us,
        move.l  SV_VEC_C(a5),TIMER_C_VEC.w      ; so Timer C counts the 200 Hz
        move.l  SV_VEC_B(a5),TIMER_B_VEC.w      ; the desktop times a double
        move.l  SV_VEC_A(a5),TIMER_A_VEC.w      ; click off again
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

; One play call, and for a play-once tune the frame count the tool patched
; in marks when the music has ended. Both tick sources come through here.
tick:
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
        rts

; The Timer C tick, 200 a second. The in-service bit is cleared before the
; play call, and the level dropped to the session's, so the file's own
; timers - Timer D sits below Timer C in the MFP's order - preempt the
; frame write here exactly as they preempt it under a VBL host.
;
; That leaves Timer C free to fire again inside the call it is running.
; The next tick is 5 ms out and a call is a fraction of that, so it takes
; a tune whose own timers eat most of the machine - dense digidrums at a
; high rate - to stretch one call past the next tick. The player holds
; one tune's state and reads it again from the top, so a second call
; inside the first would decode from a ring the first is still filling.
;
; ST_BUSY marks a call in progress. A tick that finds it set returns
; without playing and without accumulating, so the tune plays that frame
; late rather than twice: a machine that cannot keep up loses frames at
; the rate it is short by. The flag is set at level 6, before the level
; drops, and cleared with the level raised again, so a tick reads it
; either side of a call and never in the middle of one.
tc_handler:
        movem.l d1/a6,-(sp)
        move.b  #$DF,$FFFFFA11.w                ; Timer C served (SEI mode)
        lea     state(pc),a6
        tst.w   ST_BUSY(a6)                     ; a call is still running:
        bne.s   .running                        ; this tick goes
        move.w  ST_ACCUM(a6),d1
        add.w   stub_rate(pc),d1
        cmp.w   #200,d1
        blo.s   .keep
        move.w  #1,ST_BUSY(a6)                  ; still at level 6 here
        move.w  #$2300,sr                       ; the file's timers preempt
.calls:
        sub.w   #200,d1
        bsr.s   tick
        cmp.w   #200,d1                         ; a rate above 200 lands more
        bhs.s   .calls                          ; than one call on a tick
        move.w  #$2700,sr                       ; the flag drops masked
        clr.w   ST_BUSY(a6)
.keep:
        move.w  d1,ST_ACCUM(a6)
.running:
        movem.l (sp)+,d1/a6
        rte

; The VBL: one call per frame where the set claims Timer C, and parked
; where it does not.
handler:
        bsr     tick
vbl_idle:
        rte                                     ; ours alone: no TOS behind us

puts:
        move.l  a0,-(sp)
        move.w  #Cconws,-(sp)
        trap    #GEMDOS
        addq.l  #6,sp
        rts

m_banner:   dc.b    "YMX player - streaming through ST4",13,10,0
m_playing:  dc.b    "Playing. SPACE stops.",13,10,0
m_tunes:    dc.b    "Playing. SPACE stops.",13,10      ; a line of its own: the
            dc.b    "1-"                               ; console is 40 columns
m_count:    dc.b    "X picks a subtune.",13,10,0
m_marker:   dc.b    "YMXDONE.MRK",0
m_bye:      dc.b    "Done.",13,10,0
        even

saved:      ds.b    31                          ; the SV_ fields
        even
state:      ds.b    12                          ; the ST_ fields
        even
            ds.b    $800
stack_top:
sndh:                                           ; the SNDH file begins here
