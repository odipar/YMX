; -----------------------------------------------------------------------------
; YMX.S - streaming YM chiptune player for the Atari ST
;
; By Robbert van Dalen. Plays a .ymx file: twenty-five streams - fourteen frame
; streams, one per sound register, and eleven carrying the compiled effect
; script - every one packed as its own embedded ST4 container and decoded
; through its own small ring by ST4_wrap.S. Written by Claude (Anthropic's
; Claude Code) under Robbert's direction, based on ZX1 v1.5 by Einar Saukas
; and inspired by Steven Tattersall's MinYMiser. Use it freely, including
; commercially, as long as you indicate somehow in your documentation that you
; have used ZX1, via ST4 or YMX (see LICENSE).
;
; It plays the fourteen standard sound registers, starts over at the end, and
; plays what the YM format calls its special effects - digidrums, SID voices,
; the sync-buzzer - on FOUR TIMER CHANNELS. A timer channel is the format's
; idea; WHICH of the MFP's timers serves it is the FILE's: stream T maps
; the four channels onto the MFP's four timers, two bits each, and
; ymx_timers holds one row per timer for the map to point at. A timer is
; claimed only when a tune says it uses that channel. A channel's streams are
; TIMER STREAMS: series of values written to one register between frames,
; timer-driven, as against the FRAME STREAMS that carry the music itself.
; Sinus-SID is dropped at pack time. ST-Sound, the format author's own
; player, reads the effect code and runs an empty handler.
; doc/SPEC.md is the format and doc/terminology.md the vocabulary.
;
; REQUIRES ST4_wrap.S, built for the SAME unit size the tune was packed with
; (YMX -kK; the packer's default is 2). Include both, in either order, with
; the unit size defined first:
;
;       ST4_UNIT    equ     2
;               include "YMX.S"
;               include "ST4_wrap.S"
;
; YMX calls ST4_init and ST4_resume by label, and YMX_init checks every
; section's ST4 signature against this build's unit size - a tune packed for
; a different one is rejected, not garbled.
;
; -----------------------------------------------------------------------------
; THE DESIGN, in one paragraph. A tune is O frames long. Each of the
; twenty-five streams gets its own ring of N bytes and its own saved decoder
; state, and is decoded C values at a time (C = 24 by default). On every VBL
; the player uses one value from each of the rings and refills exactly one
; stream - stream k on the VBL where the frame number modulo C is k. With C at
; least the number of streams a tune DECODES - seventeen when it uses no
; channel, twenty-one for a YM tune - every one is refilled once per C frames,
; one refill per VBL, so the work per frame is flat, and ordered for the chip's
; benefit: the skips, then the fourteen register writes - the FRAME WRITE,
; leaving the VBL at a FIXED offset, which the frame's timer streams do not
; move - then the script's actions, then one ST4_resume of C bytes. Since C
; divides N and a call never reaches the ring end, ST4_wrap.S is the decoder
; that fits: the player counts the calls itself and wraps the write pointer
; when it lands on the ring end.
;
; THE SCRIPT. The last eleven rings are not frame streams: their bytes never
; reach a register. They carry the COMPILED EFFECT SCRIPT, in which the
; packer replayed the reference player's whole decision logic over the
; timeline - held or new, retune or restart, which stops which timer, when a
; sample's voice rejoins it - and wrote the outcomes down as prepared actions.
; Ring M says what acts this frame (almost always: nothing, one test); each
; channel's A/P pair carries its action byte and timer count, and ring X
; the operand an action byte has no room for.
; The handlers copy prepared values into the claimed timers and this blob's
; tick handlers - digidrum, SID square, sync-buzzer - and compare NOTHING
; at run time. A sample's voice is DISCONNECTED by mixing baked into the
; R7 stream, so its sample is heard alone, and no ring byte is ever edited:
; what the reference player resolved per frame, the file states.
;
; STARTING OVER. A packed stream can only be restarted from its beginning. A
; tune that repeats plays [0, O) once and [L, O) for ever after, L the
; header's loop frame, so one pass runs O - L frames and O - L against N picks
; the form. A pass of at most N frames fits a ring: no section is opened
; twice, the refills stop at O values, and at the wrap the cursor goes back by
; O - L onto the bytes of the pass just played. A longer pass is decoded
; again: a stream's section runs out mid-refill and the player opens one from
; its start - a fresh decoder writing on into the same ring - so the rings
; always hold one continuous sequence of values and nothing on the read side
; changes when it happens. Nothing requires the boundary to fall on a group
; boundary: a refill that straddles it decodes two pieces. The header's loop
; table says which section is opened: with none, the same section again, so L
; is 0 and a pass is the whole tune; with one, that stream's loop section and
; the same one on every open after it, each stream being two sections, [0, L)
; and [L, O). On the frame the tune ends, the player stops every claimed
; timer, parks its vector and clears every skip, which is the state the script
; was compiled from - so the second pass plays exactly as the first.
;
; -----------------------------------------------------------------------------
; USING IT
;
;       lea     song,a0                 ; the .ymx file, loaded anywhere
;       lea     workspace,a1            ; even address, YMX_SIZE bytes
;       bsr     YMX_init                ; d0 = 0 when the file was accepted
;       tst.l   d0
;       bne     bad_file
;   vbl:                                ; once per frame, in supervisor mode
;       lea     workspace,a0
;       bsr     YMX_play                ; d0 = 0 played, 1 wrapped, -1 ended
;       ...
;       lea     workspace,a0
;       bsr     YMX_stop                ; chip quiet, timers stopped
;
; The workspace holds the player's own fields, one decoder state per stream
; and one ring per stream, so its size depends on the ring size in the file.
; It is always sized for all twenty-five, including the streams a tune
; leaves idle:
;
;   YMX_SIZE = YMX_FIXED + 25 * N       ; 1658 + 25 * N bytes
;
; N is the file's own, the header word at YH_RING, and the packer raises it
; above the ring size it was asked for where one pass of a tune needs a
; longer ring: a file packed for 960-byte rings can carry N = 1776. A
; program sizing at run time reads that word and takes
; YMX_FIXED+(YMX_STREAMS*N) bytes. A program sizing at build time reserves
; for the format's cap of 2520 bytes a ring, the largest N a file carries:
;
;       ds.b    YMX_FIXED+(YMX_STREAMS*2520)    ; 64658 bytes
;
; where the parentheses matter: rmac evaluates expressions left to right.
; A workspace reserved for 960 holds only the files whose header says 960
; or less: the player writes a longer file's rings past the end, and init
; checks nothing (assumption 2).
;
; CALLS AND REGISTERS
;
;   YMX_init   in   a0 = .ymx file, at an even address, a1 = workspace
;              out  d0 = 0 accepted, -1 not a YMX file at YMX_VERSION with
;                        25 streams packed at this build's unit size
;              Claims a timer for each timer channel the header flags:
;              stops it, parks its vector, drops stale pendings, enables
;              and unmasks it. It saves NOTHING - the machine state is the
;              host's, see assumption 5. WHICH timer a channel gets comes
;              from stream T, not from this file: a YM tune uses channels
;              0 and 1 and maps them to Timers A and D, leaving B for
;              rasters and C for the system, but a tune may use any of
;              the four. Pair init with a stop.
;              A channel the tune does not use is left ALONE, which means
;              a timer the host had running keeps running: claiming used
;              to quiet the machine as a side effect and no longer does.
;              A host needing a quiet machine stops the timers itself
;              before init - YMX_player.S is the worked example
;   YMX_play   in   a0 = workspace
;              out  d0 = 0 played a frame
;                   d0 = 1 played a frame, and that frame ended the tune: the
;                          next one is frame L again
;                   d0 = -1 the tune is over and nothing was written, which
;                          only a tune packed with -o can report
;   YMX_stop   in   a0 = workspace
;              Quiesces: stops the claimed timers, disables their interrupts,
;              drops pendings, silences the three voices. Restores nothing
;   CLOBBERED  d0-d5, a0-a5. d6, d7 and a6 survive, as with ST4 - whose
;              decoder state spans a4 and a5, which is why they no longer do.
;
; YMX_play writes to the sound chip at $FFFF8800, so it must run in supervisor
; mode - which a VBL handler already does. Each of the fourteen writes is
; a single movep.w, which a 68000 cannot split, so the burst needs no
; interrupt mask to be correct - it takes one anyway by default, and
; YMX_MASK_BURST equ 0 (the tools' -nomask) drops it so ticks interleave
; with the frame write instead of waiting behind it. Its
; effect stage programs the timers YMX_init claimed, vectors included.
; Beyond the chip, those timers and the workspace, it writes only ITSELF: it is
; self-modifying code. The channel descriptors, the prepared tables and the
; tick handlers' operands live in this blob, init patches the burst's ring
; displacements and the run-time addresses into it, and the script's
; handlers keep patching as effects come and go. Two consequences: the
; player must run from RAM, and
; one loaded copy is one player - two workspaces cannot share it. Every
; self-reference is pc-relative or patched at run time, so the blob loads
; at any even address.
;
; ASSUMPTIONS (unchecked beyond the magic, version and stream count):
;
;   1. the file comes from the YMX packer, which enforces C >= the streams
;      a tune decodes, N mod C = 0,
;      N >= 2C, N <= 2520 - the burst reads each register's ring through an
;      assembled-in 16-bit displacement of k*N - offsets that stay inside the
;      ring, and - at ST4_UNIT above 1 - a tune length and C that are whole
;      units, so every budget this player passes to the decoder divides
;      cleanly
;   2. the workspace is at an even address and large enough for the file's N
;   3. YMX_play is called once per frame and never after it returns -1.
;      Calling it again is harmless - it returns -1 and writes nothing - which
;      keeps ST4_wrap's "never call the decoder again" rule intact
;   4. a tune that starts over plays for as long as you keep calling: d0 = 1
;      marks each time round, and YMX_init is only needed to start it again
;      from a stop
;   5. the HOST owns the machine state - the player saves and restores none
;      of it. Which timers a tune claims is stream T's, read at init, so a
;      host cannot know them beforehand and saves for all four, around
;      init..stop: the vectors $134, $120, $114 and $110; TACR, TBCR and
;      both nibbles of TCDCR; bits 5 and 0 of IERA and IMRA; bits 5 and 4
;      of IERB and IMRB. A data register is not in that list: it reads as
;      the timer's live count and writes as its reload value, so saving
;      one and writing it back sets a rate nobody asked for. A host that
;      needs a timer's rate back writes the value it knows - Timer C's is
;      the system's 200 Hz clock, 192 counts at /64. A demo that has
;      already taken the machine over saves nothing. YMX_player.S is the
;      worked example of doing that, and YMX_sndh.S is the other
; -----------------------------------------------------------------------------

; YMX_PERF equ 1 turns on the raster monitor: the frame's work paints the
; background red for exactly as long as it runs (one scanline = 512 cycles),
; each tick handler paints its own sliver - Timer A green, Timer D blue -
; wherever the beam happens to be, and because slivers are hard to read, the
; handlers also accumulate an estimated cost that the next frame burns off
; as a solid yellow bar AFTER the red one: the timers' total, in scanlines,
; burned once the frame's own work - and above all the register burst - is
; done, so the monitor never moves the chip writes. The frame step waits
; for the display to start before it paints anything - the VBL fires far
; above the visible screen, so an unsynced monitor draws its bands into
; the top border where nothing can be seen - and the wait is a constant,
; so nothing it measures is distorted. An estimate - the quanta are
; ~10-cycle loop turns - and pure debug: with the flag off (the default)
; not one cycle or byte remains.
        .if ^^defined YMX_PERF
        .else
YMX_PERF        equ     0
        .endif

; YMX_MASK_BURST masks interrupts around the frame write, and is ON by
; default. It is not needed for correctness - every write is one movep.w,
; which no interrupt can split - so setting it to 0 is safe, and what it
; buys is tick timing: with the mask on, a tick landing inside the burst
; waits about 500 cycles, which at the top of the tick range is longer
; than a whole tick period. With it off, ticks interleave with the frame
; write and wait one instruction. Everything else is identical either way,
; so an A/B on this flag isolates the mask and nothing else - the tools
; spell it -nomask.
        .if ^^defined YMX_MASK_BURST
        .else
YMX_MASK_BURST  equ     1
        .endif
PERF_PAD        equ     YMX_PERF*10     ; the color prologue's bytes, sitting
                                        ; ahead of every fixed patch offset
PERF_SYNC       equ     8000            ; the sync loop's ceiling: a whole
                                        ; frame's worth of turns, so a host
                                        ; that calls from anywhere still runs

YMX_CHANNELS    equ     4               ; timer channels, numbered 0..3. Which
                                        ; of the MFP's timers runs each one
                                        ; is the file's choice now: stream
                                        ; T carries the map, two bits each
YMX_TIMERS      equ     4               ; and this is what it may use
YMX_STREAMS     equ     25              ; R0..R13, then the compiled effect
                                        ; script: M, X, T, then each channel's
                                        ; action and count bytes - decoded
                                        ; like any register
YMX_CHAN0       equ     17              ; stream index of channel 0's action
                                        ; byte: R0..R13, then M, X and T
YMX_WRITE_READ  equ     4               ; inside a YMX_WRITE: the ring read's
                                        ; displacement, patched at init
YMX_WRITE_MOVEP equ     6               ; and the movep, which a skip swaps
                                        ; for two nops
YMX_WRITE_SIZE  equ     10              ; the whole write
YMX_STATE_SIZE  equ     64              ; per stream, so the index is a shift
                                        ; both ways: *64 in, /16 back to *4

; Workspace layout. Counters that a frame touches are kept in the form the
; frame needs them in: a pointer to walk, and a countdown to test against zero.
YMX_CURSOR      equ     0               ; long  this frame's byte in ring 0
YMX_LEFTFR      equ     4               ; long  frames before the tune ends
YMX_NEXT        equ     8               ; long  values decoded so far
YMX_RINGEND     equ     12              ; long  one past ring 0, where it wraps
YMX_RINGS       equ     16              ; long  base of the ring area
YMX_FILE        equ     20              ; long  the .ymx file
YMX_TOTAL       equ     24              ; long  O, the tune length in frames
YMX_N           equ     28              ; word  ring size N
YMX_C           equ     30              ; word  values per call C
YMX_SLOT        equ     32              ; word  refill turn, 0..C-1
YMX_RING        equ     34              ; word  nonzero when a pass fits a
                                        ; ring: O - L <= N, and the tune
                                        ; starts over. The frames of a pass
                                        ; are then all resident at the wrap,
                                        ; so O values are decoded once and
                                        ; the cursor rewinds instead
YMX_LOOPS       equ     36              ; word  nonzero when the tune starts over
YMX_EFXOFF      equ     38              ; long  14*N: the cursor's ring to M's
YMX_TMAP        equ     42              ; word  the channel-to-timer map in
                                        ; force, so stream T's byte only
                                        ; costs a compare per frame
YMX_LIVE        equ     44              ; word  streams still worth decoding:
                                        ; the channels sit last, so a tune
                                        ; that uses two leaves the others'
                                        ; pair off the end
YMX_BODY        equ     46              ; long  O - L, the frames one pass
                                        ; plays; the countdown reloads with it
YMX_TABLE       equ     50              ; long  the table ymx_reopen reads a
                                        ; section out of: the section table
                                        ; from init, and the loop table from
                                        ; the first reopen of a file that
                                        ; carries one
YMX_SECLEN      equ     54              ; long  how many values a section of
                                        ; that table carries: L while the
                                        ; section table is in force, O - L
                                        ; after the switch, and O for a file
                                        ; with no loop table. Every stream
                                        ; runs out at the same value, so the
                                        ; pair is the workspace's rather than
                                        ; each state block's
YMX_STATE       equ     58              ; the decoder states, from an even
                                        ; base. The lea that reaches them
                                        ; carries this as an 8-bit
                                        ; displacement, so it stays under 128

; A timer channel is the format's idea; which of the MFP's four serves it
; is this file's. One
; descriptor per channel says which timer implements it, and every channel
; carries the same fields, so the code that claims, programs and hands
; timers back is one loop over the table. No retained state beyond the
; claim - the script compiled every comparison at pack time. The hardware
; addresses are words: movea.w sign-extends $FAxx to $FFFFFAxx.
;
; The MFP's four interrupt registers sit at a fixed stride - enable, pending,
; in-service, mask, four bytes apart in both banks - so one address covers
; all four: IER+4 is pending, +8 in-service, +12 mask.
CH_CTRL         equ     0               ; word  timer control register
CH_DATA         equ     2               ; word  timer data register
CH_VEC          equ     4               ; word  timer vector
CH_IER          equ     6               ; word  the timer's enable register
CH_IERBIT       equ     8               ; word  and its bit: a toggle release
                                        ; only MASKS the interrupt, so the
                                        ; counter keeps counting and a
                                        ; resume is phase-continuous
CH_KEEP         equ     10              ; word  what a stop must KEEP of the
                                        ; control byte: $F0 for a timer in
                                        ; the low nibble, $0F for Timer C,
                                        ; which sits in the high one
CH_SHIFT        equ     12              ; word  and how far the prescaler
                                        ; shifts up to meet it: 0 or 4
CH_ISR          equ     14              ; long  the timer's tick handlers
CH_ROW          equ     18              ; the bytes above are the timer's, and
                                        ; an assignment copies exactly them
CH_CLAIMED      equ     18              ; word  nonzero once YMX_init took
                                        ; this channel's timer, which it
                                        ; does only for a channel the
                                        ; header flags. The channel's own,
                                        ; so it survives a re-assignment
CH_SIZE         equ     20              ; the table is indexed, so descriptors
                                        ; are a fixed stride apart
; rmac evaluates left to right, so the parentheses here are load-bearing:
; without them this reads as (YMX_STATE+YMX_STREAMS)*YMX_STATE_SIZE.
YMX_FIXED       equ     YMX_STATE+(YMX_STREAMS*YMX_STATE_SIZE)

; One saved ST4_wrap state, plus the ring bounds the wrap check needs. The
; first four longs are in movem order - d1, d2, then a0, a1 - so that much of
; the decoder state loads and stores in one instruction; the three extra
; stream pointers ST4 carries (literals, byte offsets, word offsets) follow.
YS_D1           equ     0               ; long  d1, -ring.low : remaining
YS_D2           equ     4               ; long  d2, N : signed offset
YS_INPUT        equ     8               ; long  a0, stream A position
YS_WRITE        equ     12              ; long  a1, write pointer
YS_LIT          equ     16              ; long  a2, stream B position
YS_BOFF         equ     20              ; long  a4, stream C position
YS_WOFF         equ     24              ; long  a5, stream D position
YS_QUEUE        equ     28              ; word  d0, bit queue
YS_START        equ     32              ; long  this stream's ring
YS_END          equ     36              ; long  one past its last byte
YS_LEFT         equ     40              ; long  values left in the current section
YS_STORED       equ     44              ; word  set when the section is its own
                                        ; values: fill copies them instead of
                                        ; running a decoder over them
; The movem block starts at the top of the state, so the two movems address it
; as plain (a2) - see the note where they are used.

; The .ymx header, as written by the packer.
; The extension streams a file may store past the twenty-five, and the mask
; a file that stores none carries: this build implements streams 0 to 24 and
; no extension, so any other mask names a stream it does not read.
YMX_EXT_MAX     equ     7
YMX_REQUIRED_BASE equ   $01FFFFFF

YH_MAGIC        equ     0
YH_VERSION      equ     4
YH_FLAGS        equ     6
YH_FRAMES       equ     8
YH_PLAYER_HZ    equ     12
YH_STREAM_COUNT equ     14
YH_RING         equ     16
YH_CHUNK        equ     18
YH_CLOCK        equ     20
YH_SAMPLES        equ     24              ; long: the sample table, 0 when none
YH_SAMPLECOUNT    equ     28              ; word
YH_LOOP         equ     30              ; long: L, the frame a tune that
                                        ; starts over goes back to; it has a
                                        ; meaning only where the loop flag
                                        ; is set
YH_LOOPTAB      equ     34              ; long: the loop table, 0 when none.
                                        ; One offset per stream, read like
                                        ; the section table: the frames from
                                        ; L on, which every pass after the
                                        ; first plays
YH_REQUIRED     equ     38              ; long: Q, the required-streams mask.
                                        ; Bit k requires stream k, and a
                                        ; consumer that does not implement a
                                        ; required stream rejects the file.
                                        ; This build implements the
                                        ; twenty-five and no extension, so it
                                        ; takes the one value and no other
YH_SECTIONS     equ     42              ; one offset per stream: its section,
                                        ; covering every frame of the tune,
                                        ; or the frames before L where a
                                        ; loop table covers the rest

YMX_MAGIC       equ     $594D5821       ; 'YMX!'
YMX_VERSION     equ     $0009           ; the format version: the major in
                                        ; the high byte, the minor in the
                                        ; low. A file of another version is
                                        ; rejected at init, not read: SPEC.md
                                        ; §10.5 repacks from source instead
YMX_FLAG_LOOPS  equ     1               ; header flag: the tune starts over
YMX_FLAG_CHAN   equ     2               ; and one per timer channel, from bit
                                        ; 1 up: set when the tune uses it,
                                        ; which is when a timer is claimed
YMX_PSG         equ     $FFFF8800       ; select/read at +0, write at +2
YMX_ONE_SHOT    equ     $FFFF           ; a sample table loop point meaning
                                        ; the stream stops rather than loops
YMX_NO_ENV      equ     $FF             ; R13 value meaning "leave it alone"
YMX_PORTS       equ     $C0             ; R7 bits the ST needs as outputs
YMX_MIXER       equ     7
YMX_ENVELOPE    equ     13

; -----------------------------------------------------------------------------
; YMX_WRITE: send one register's value from its ring to the sound chip.
;
; Three moves: d2 steps the select in d1's high half - R0's moveq #0 is
; written out at ymx_wA and every later write adds d2's $0100 - the ring
; byte goes to d1's low half, and one movep.w sends both. Nothing is
; tested and nothing is counted. Register k's
; ring byte sits k*N above the cursor, and N is known at init: the
; displacement is patched into the instruction there, once, so the burst
; never steps a pointer. The $7FFF placeholder is
; load-bearing - a zero displacement would let rmac's +o3 fold the extension
; word away and shift every patch site. Registers 7 and 13 need their values
; touched, so they are written out of line rather than through this macro.
;
; Skipping a register whose value has not changed was measured and dropped: a
; sound-chip access on an ST costs about 1.5 cycles more than a RAM one, so the
; compare that would save the write costs about as much as the write.
; -----------------------------------------------------------------------------
        .macro YMX_WRITE
        add.w   d2,d1                   ; the next select, in the high half
        move.b  $7FFF(a1),d1            ; the value - SMC: k*N, set at init
        movep.w d1,0(a2)                ; select and write in ONE instruction,
        .endm                           ; so no tick can land between them

; Jump table: bra.w keeps 4-byte slots for a program that loads YMX as a blob.
                bra.w   YMX_init
                bra.w   YMX_play
                bra.w   YMX_stop

; -----------------------------------------------------------------------------
; YMX_init: a0 = .ymx file, a1 = workspace. Sets up every decoder, decodes
; each stream's first group so frame 0 can be played immediately, patches the
; blob's run-time addresses and the burst's ring displacements, and claims
; a timer for each timer channel the header flags - saving nothing, per
; assumption 5.
; -----------------------------------------------------------------------------
YMX_init:
        movea.l a1,a3                   ; a3 is the workspace throughout
        move.l  a0,YMX_FILE(a3)
        cmp.l   #YMX_MAGIC,YH_MAGIC(a0)
        bne     .rejected
        cmp.w   #YMX_VERSION,YH_VERSION(a0)
        bne     .rejected
        move.w  YH_STREAM_COUNT(a0),d0  ; S is 25 to 32: a file may store
        sub.w   #YMX_STREAMS,d0         ; extension streams past the ones
        cmp.w   #YMX_EXT_MAX,d0         ; this build reads
        bhi     .rejected
        cmp.l   #YMX_REQUIRED_BASE,YH_REQUIRED(a0)
        bne     .rejected               ; a required stream this build does
                                        ; not implement

        move.l  YH_FRAMES(a0),YMX_TOTAL(a3)
        move.w  YH_RING(a0),YMX_N(a3)
        move.w  YH_CHUNK(a0),YMX_C(a3)
        move.w  YH_FLAGS(a0),d0
        and.w   #YMX_FLAG_LOOPS,d0
        move.w  d0,YMX_LOOPS(a3)

; One pass is O - L frames, and L has a meaning only under the loop flag.
; A pass that fits a ring leaves every frame of it resident at the wrap: the
; refills stop at O values and the cursor goes back by the length of a pass.
; Both fields are written on every init, not left as they were found: the
; workspace is zero-filled once and never again, so a value from the tune
; before this one would survive a switch.
        move.l  YH_FRAMES(a0),d1
        tst.w   d0
        beq.s   .body_ready
        sub.l   YH_LOOP(a0),d1
.body_ready:
        move.l  d1,YMX_BODY(a3)
        clr.w   YMX_RING(a3)
        tst.w   d0
        beq.s   .ring_ready
        moveq   #0,d0
        move.w  YH_RING(a0),d0
        cmp.l   d0,d1
        bhi.s   .ring_ready
        move.w  #1,YMX_RING(a3)
.ring_ready:

; The sections init opens are the section table's, and a file with a loop
; table cuts them at L: they carry L values each, where the sections of a
; file with no loop table carry O. ymx_reopen moves both fields on to the
; loop table's when the first stream runs out. Written on every init for
; the same reason the two above are.
        lea     YH_SECTIONS(a0),a1
        move.l  a1,YMX_TABLE(a3)
        move.l  YH_FRAMES(a0),d1
        tst.l   YH_LOOPTAB(a0)
        beq.s   .seclen_ready
        move.l  YH_LOOP(a0),d1
.seclen_ready:
        move.l  d1,YMX_SECLEN(a3)

        move.l  YH_FRAMES(a0),YMX_LEFTFR(a3)
        clr.w   YMX_SLOT(a3)
        lea     YMX_FIXED(a3),a1        ; the rings follow the decoder states
        move.l  a1,YMX_RINGS(a3)
        move.l  a1,YMX_CURSOR(a3)       ; frame 0 is the first byte of ring 0
        moveq   #0,d0
        move.w  YH_RING(a0),d0
        add.l   a1,d0
        move.l  d0,YMX_RINGEND(a3)      ; where the cursor goes back to the top

        moveq   #0,d1                   ; the first group is C values, or the
        move.w  YMX_C(a3),d1            ; whole tune when that is shorter and
        tst.w   YMX_RING(a3)            ; the O values are all that is decoded
        bne.s   .bounded_group
        tst.w   YMX_LOOPS(a3)           ; a second pass to run into
        bne.s   .first_group
.bounded_group:
        move.l  YMX_TOTAL(a3),d0
        cmp.l   d1,d0
        bcc.s   .first_group
        move.l  d0,d1
.first_group:
        move.l  d1,YMX_NEXT(a3)         ; values decoded once init is done

; How many values each stream reads out of the section it opens below.
; Every block is written before the first stream decodes anything, because
; a stream whose section is shorter than a group runs out inside its own
; first fill, and the reopen that follows moves YMX_SECLEN on to the loop
; table's length - which is not what the streams after it open with.
        lea     YMX_STATE+YS_LEFT(a3),a1
        move.l  YMX_SECLEN(a3),d0
        moveq   #YMX_STREAMS-1,d1
.section_left:
        move.l  d0,(a1)
        lea     YMX_STATE_SIZE(a1),a1
        dbra    d1,.section_left

        movem.l d6-d7/a6,-(sp)          ; the three registers a decoder call
        moveq   #0,d6                   ; leaves alone: d6 = N, d7 = the
        move.w  YMX_N(a3),d6            ; stream index, a6 = its ring - the
        moveq   #0,d7                   ; rings are consecutive, so a6 walks
        movea.l YMX_RINGS(a3),a6        ; them N at a time
.stream:
        move.w  d7,d0
        lsl.w   #6,d0                   ; * YMX_STATE_SIZE
        lea     YMX_STATE(a3,d0.w),a2
        movea.l a6,a1                   ; this stream's ring - ymx_open's
        move.l  a1,YS_START(a2)         ; ST4_init packs -ring.low from a1
        move.l  a1,YS_WRITE(a2)
        adda.l  d6,a6                   ; the next stream's ring
        move.l  a6,YS_END(a2)

        move.w  d7,d0
        lsl.w   #2,d0                   ; the table holds long offsets
        add.w   #YH_SECTIONS,d0
        movea.l YMX_FILE(a3),a0
        move.l  0(a0,d0.w),d0
        clr.w   YS_STORED(a2)           ; clr writes the flags, so the test
        tst.l   d0                      ; comes after it: the offset's top bit
        bpl.s   .container              ; marks a section stored
        bclr    #31,d0
        adda.l  d0,a0                   ; the values, read a byte at a time
        move.l  a0,YS_INPUT(a2)
        move.w  #1,YS_STORED(a2)
        bra.s   .opened
.container:
        adda.l  d0,a0                   ; the section's ST4 container
        cmp.l   #ST4_SIGNATURE,(a0)     ; magic, version and unit in one compare:
        bne     .stream_rejected        ; a tune packed for another unit size

; Open the container. The state block goes to the stack; ST4_init clobbers
; a3, so the workspace pointer does too.
        move.l  a2,-(sp)
        move.l  a3,-(sp)
        bsr     ymx_open
        movea.l (sp)+,a3

        movea.l (sp)+,a1                ; the state block; a1 is recomputed
        move.l  a0,YS_INPUT(a1)         ; every time round this loop
        move.l  a2,YS_LIT(a1)
        move.l  a4,YS_BOFF(a1)
        move.l  a5,YS_WOFF(a1)
        move.w  d0,YS_QUEUE(a1)
        move.l  d1,YS_D1(a1)
        move.l  d2,YS_D2(a1)
.opened:

        move.w  d7,d0                   ; and its first group
        lsl.w   #6,d0
        move.l  YMX_NEXT(a3),d3         ; C, or fewer
        bsr     YMX_fill
        addq.w  #1,d7
        cmp.w   #YMX_STREAMS,d7
        bne     .stream
        movem.l (sp)+,d6-d7/a6

; The cursor reaches the script rings by fixed hops: 14*N to M, computed
; once, and the three parameter offsets (8+voice)*N the handlers read the
; toggle volume and the sample number through - the shape is stream X's.
        move.w  YMX_N(a3),d0
        mulu.w  #14,d0
        move.l  d0,YMX_EFXOFF(a3)
        moveq   #0,d0
        move.w  YMX_N(a3),d0
        move.l  d0,d1
        lsl.l   #3,d1                   ; 8*N
        lea     ymx_parmoff(pc),a1
        move.l  d1,(a1)+                ; voice A
        add.l   d0,d1
        move.l  d1,(a1)+                ; voice B
        add.l   d0,d1
        move.l  d1,(a1)+                ; voice C
        move.l  d0,d1                   ; voice 3 takes voice A's offset
        lsl.l   #3,d1                   ; again: no opcode addresses it, and
        move.l  d1,(a1)                 ; a malformed file reads in the table

; The sample table becomes a table of absolute addresses, so a trigger
; is one indexed load instead of an entry walk: the DRUM action's byte is
; the sample number, pre-scaled to index these longs.
        movea.l YMX_FILE(a3),a0
        move.l  YH_SAMPLES(a0),d1
        beq.s   .no_drums
        movea.l a0,a2
        adda.l  d1,a2                   ; entries: offset.l, length.w, loop.w
        lea     ymx_samples(pc),a1
        move.w  YH_SAMPLECOUNT(a0),d0
        subq.w  #1,d0
        bmi.s   .no_drums
.drum_addr:
        move.l  (a2),d1
        add.l   a0,d1                   ; file base + entry offset
        move.l  d1,(a1)+                ; the sample's own address, then where
        moveq   #0,d2                   ; a stream that runs out goes back to -
        move.w  6(a2),d2                ; resolved here so the tick that meets
        cmp.w   #YMX_ONE_SHOT,d2        ; the end has one long to load and no
        beq.s   .one_shot               ; arithmetic to do. The loop word is
        add.l   d1,d2                   ; unsigned: $8000..$FFFE are points too
        move.l  d2,(a1)+
        bra.s   .next_drum
.one_shot:
        clr.l   (a1)+                   ; zero: the tick stops instead
.next_drum:
        addq.l  #8,a2
        dbra    d0,.drum_addr
.no_drums:

; The burst reads register k's ring byte through an assembled-in k*N
; displacement - patch all fourteen now that N is known. The packer keeps
; N at 2520 or less, so 13*N fits the signed word. The displacement sits
; at YMX_WRITE_READ inside each write; R7 and R13 read out of line.
        move.w  YMX_N(a3),d2
        moveq   #0,d1                   ; k*N, accumulated
        lea     ymx_wA+YMX_WRITE_READ(pc),a0
        moveq   #6,d0                   ; R0..R6
.wpatchA:
        move.w  d1,(a0)
        add.w   d2,d1
        lea     YMX_WRITE_SIZE(a0),a0
        dbra    d0,.wpatchA
        lea     ymx_w7+2(pc),a0         ; R7 reads through d1
        move.w  d1,(a0)
        add.w   d2,d1
        lea     ymx_wB+YMX_WRITE_READ(pc),a0
        moveq   #4,d0                   ; R8..R12
.wpatchB:
        move.w  d1,(a0)
        add.w   d2,d1
        lea     YMX_WRITE_SIZE(a0),a0
        dbra    d0,.wpatchB
        lea     ymx_w13+2(pc),a0
        move.w  d1,(a0)
        move.w  d2,d1
        add.w   d1,d1                   ; 2N: ring T's byte, read off ring M
        lea     ymx_wT+2(pc),a0
        move.w  d1,(a0)
        bsr     ymx_link                ; no skips, handlers linked

; Read the map before claiming anything: stream T's first byte says which
; timer each channel runs on, and the rings are primed by now, so it is
; the byte under the cursor.
        movea.l YMX_CURSOR(a3),a0
        adda.l  YMX_EFXOFF(a3),a0       ; ring M
        moveq   #0,d1
        move.w  YMX_N(a3),d1
        adda.l  d1,a0                   ; ring X
        adda.l  d1,a0                   ; ring T
        moveq   #0,d0
        move.b  (a0),d0
        move.w  d0,YMX_TMAP(a3)         ; kept, so a change shows up

; An earlier init's claims go back before the new map lands, whether or not
; this tune uses the same channels: init twice without a stop and no timer
; of ours is left running. The order is load-bearing. ymx_handback reaches
; a timer through the descriptor's row, and ymx_assign overwrites exactly
; those fields, so a channel released afterwards would hand back the timer
; it moved TO and leave the one it moved FROM counting, enabled, its vector
; still in this blob.
        move.w  sr,-(sp)
        ori.w   #$0700,sr
        lea     ymx_desc_0(pc),a5
        moveq   #YMX_CHANNELS-1,d2
.release:
        bsr     ymx_handback
        lea     CH_SIZE(a5),a5
        dbra    d2,.release
        move.w  (sp)+,sr

        bsr     ymx_assign

; Claim a timer for each timer channel the header flags: park our handler
; on its vector and leave the timer stopped but enabled and unmasked. A
; channel the tune does not use is left alone, timer and all, so
; a tune that uses two leaves the other timers to the host untouched.
; Nothing is saved - the machine state is the host's, see assumption 5 in
; the header. bset/bclr only: Timer C's bits, Timer D's and the ACIA's all
; live in registers they share with something else.
        movea.l YMX_FILE(a3),a0         ; ymx_link had a0
        move.w  YH_FLAGS(a0),d0
        lsr.w   #1,d0                   ; flag bit 1+c: channel c is used
        lea     ymx_desc_0(pc),a5
        moveq   #YMX_CHANNELS-1,d2
        move.w  #YMX_CHAN0,YMX_LIVE(a3) ; no channel: no A or P worth decoding
        move.w  sr,-(sp)
        ori.w   #$0700,sr
.claim:
        moveq   #0,d1
        lsr.w   #1,d0
        bcc.s   .idle
        moveq   #1,d1
.idle:
        move.w  d1,CH_CLAIMED(a5)       ; what YMX_stop hands back
        beq.s   .next_channel
        move.w  #YMX_CHANNELS,d1        ; and the last claimed channel's pair
        sub.w   d2,d1                   ; is the last stream worth decoding
        add.w   d1,d1
        add.w   #YMX_CHAN0,d1
        move.w  d1,YMX_LIVE(a3)
        movea.w CH_CTRL(a5),a1
        move.w  CH_KEEP(a5),d1
        and.b   d1,(a1)                 ; stopped; the other nibble kept
        movea.l CH_ISR(a5),a2
        lea     ISR_PARK(a2),a2         ; the park entry, until an effect
        movea.w CH_VEC(a5),a1           ; aims the vector somewhere
        move.l  a2,(a1)
        move.w  CH_IERBIT(a5),d1
        movea.w CH_IER(a5),a1
        bclr    d1,4(a1)                ; drop a stale pending
        bset    d1,(a1)                 ; enabled
        bset    d1,12(a1)               ; and unmasked
.next_channel:
        lea     CH_SIZE(a5),a5
        dbra    d2,.claim
        move.w  (sp)+,sr

        moveq   #0,d0
        rts
.stream_rejected:
        movem.l (sp)+,d6-d7/a6          ; the stream loop's saved registers
.rejected:
        moveq   #-1,d0
        rts

; -----------------------------------------------------------------------------
; YMX_fill: d0.w = stream index * YMX_STATE_SIZE, d3.l = values needed.
; Produces exactly that many values for one register, opening its section
; again - and again - as often as it takes. Straddling matters even for one
; group: nothing says the end of the tune falls on a group boundary.
; -----------------------------------------------------------------------------
YMX_fill:
        movem.l d6-d7/a6,-(sp)          ; three registers that survive a decoder
        lea     YMX_STATE(a3,d0.w),a2   ; call, which its own state needs
        movea.l a3,a6                   ; ST4 clobbers a3: a6 keeps the workspace
        move.l  a2,d6
        move.l  d3,d7                   ; values still needed
.piece:
        move.l  YS_LEFT(a2),d0
        bne.s   .section
        bsr     ymx_reopen              ; this section ran out: go round again
        move.l  YS_LEFT(a2),d0
        beq.s   .done                   ; nothing to go round to; do not hang
.section:
        cmp.l   d7,d0
        bcs.s   .piece_size
        move.l  d7,d0                   ; the section covers all that is needed
.piece_size:
        sub.l   d0,YS_LEFT(a2)
        sub.l   d0,d7
        move.w  d0,d3                   ; a piece is at most C, so it fits a word
        tst.w   YS_STORED(a2)
        bne.s   .copy                   ; stored: the section is the values
; The decoder's budget counts units of ST4_UNIT bytes. The packer enforces
; that every piece boundary - C and the tune length - is a whole number of
; units, so this shift never drops a remainder.
        .if ST4_UNIT=2
        lsr.w   #1,d3
        .endif
        .if ST4_UNIT=4
        lsr.w   #2,d3
        .endif

; (a2) written out rather than YS_D1(a2): with +o3 rmac folds a zero
; displacement away on movem but keeps the addressing mode, and emits the
; instruction without its extension word. The next word becomes the
; displacement and the code runs off into nothing.
        movem.l (a2),d1-d2/a0-a1
        movea.l YS_BOFF(a2),a4
        movea.l YS_WOFF(a2),a5
        move.w  YS_QUEUE(a2),d0
        movea.l YS_LIT(a2),a2           ; loaded last: a2 was the base
        bsr     ST4_resume
        movea.l a6,a3                   ; the decoder clobbered the workspace
        exg     a2,d6                   ; a2 = the block again, d6 = stream B
        cmpa.l  YS_END(a2),a1           ; YMX_save, inlined: this is the hot path
        bne.s   .stored
        movea.l YS_START(a2),a1         ; the call landed on the ring end
.stored:
        movem.l d1-d2/a0-a1,(a2)
        move.l  d6,YS_LIT(a2)
        move.l  a4,YS_BOFF(a2)
        move.l  a5,YS_WOFF(a2)
        move.w  d0,YS_QUEUE(a2)
        move.l  a2,d6                   ; the block pointer, back where fill keeps it
        tst.l   d7
        bne.s   .piece
.done:
        movem.l (sp)+,d6-d7/a6
        rts

; A stored section: the file holds the values, so a piece is a copy. The ring
; end is handled the way a decode's is - C divides N, so a piece lands on the
; end or short of it, never across.
.copy:
        movea.l YS_INPUT(a2),a0
        movea.l YS_WRITE(a2),a1
        subq.w  #1,d3                   ; a piece is never empty
.byte:
        move.b  (a0)+,(a1)+
        dbra    d3,.byte
        move.l  a0,YS_INPUT(a2)
        cmpa.l  YS_END(a2),a1
        bne.s   .in_ring
        movea.l YS_START(a2),a1         ; the copy landed on the ring end
.in_ring:
        move.l  a1,YS_WRITE(a2)
        tst.l   d7
        bne     .piece                  ; the copy sits past .done, so the two
        bra     .done                   ; ways back are word displacements

; -----------------------------------------------------------------------------
; ymx_reopen: a2 = state block. Opens a section for this stream - a fresh
; decoder, reading from the top of the section, still writing where the last
; one stopped. ST4_init needs the ring start so that its packed metadata comes
; out right; the write pointer is restored from the block, which is why it is
; stored rather than held in a register.
;
; Which section that is comes out of YMX_TABLE, and how many values it
; carries out of YMX_SECLEN. A file with a loop table moves both on to it
; here, and every reopen after this one finds them there: the entries are
; the same two values every time, so the write costs less than the test
; that would skip it. Every first section runs out at value L, so every
; stream crosses over on its own refill turn.
; -----------------------------------------------------------------------------
; It runs inside YMX_fill, which guarantees d6 = the state block and a6 = the
; workspace - the two anchors that survive the decoder call.
ymx_reopen:
        tst.w   YMX_LOOPS(a3)
        beq.s   .done                   ; a tune that plays once stops here
        movea.l YMX_FILE(a3),a0
        move.l  YH_LOOPTAB(a0),d0
        beq.s   .table_ready            ; one table: its sections carry O
        add.l   a0,d0
        move.l  d0,YMX_TABLE(a3)        ; the loop table, from here on
        move.l  YMX_BODY(a3),YMX_SECLEN(a3)     ; O - L values a section
.table_ready:
        move.l  a2,d0
        sub.l   a3,d0
        sub.w   #YMX_STATE,d0
        lsr.w   #4,d0                   ; k * 64 becomes k * 4, a table index
        movea.l YMX_TABLE(a3),a1        ; the table holds long offsets
        move.l  0(a1,d0.w),d0
        beq.s   .done                   ; this stream has no section
        bpl.s   .container              ; the offset's top bit: stored values
        bclr    #31,d0
        adda.l  d0,a0
        move.l  a0,YS_INPUT(a2)         ; the values, read a byte at a time
        move.w  #1,YS_STORED(a2)
        move.l  YMX_SECLEN(a6),YS_LEFT(a2)
        rts
.container:
        clr.w   YS_STORED(a2)
        adda.l  d0,a0                   ; the section's ST4 container
        movea.l YS_START(a2),a1         ; read before a2 becomes stream B
        bsr.s   ymx_open
        movea.l a6,a3                   ; the decoder clobbered the workspace
        exg     a2,d6                   ; a2 = the block again, d6 = stream B
        move.l  a0,YS_INPUT(a2)
        move.l  d6,YS_LIT(a2)
        move.l  a4,YS_BOFF(a2)
        move.l  a5,YS_WOFF(a2)
        move.w  d0,YS_QUEUE(a2)
        move.l  d1,YS_D1(a2)
        move.l  d2,YS_D2(a2)
        move.l  YMX_SECLEN(a3),YS_LEFT(a2)
        move.l  a2,d6                   ; the block pointer, as fill keeps it
.done:
        rts

; -----------------------------------------------------------------------------
; ymx_open: a0 = an ST4 container, a1 = the stream's ring, which ST4_init
; packs -ring.low from. Points the four stream registers at its
; streams - A begins where the header ends, B, C and D sit at the recorded
; header-relative offsets, exactly as ST4.S documents - and tail-calls
; ST4_init: its rts returns to ymx_open's caller with the decoder registers
; as ST4_init leaves them. ST4_init clobbers a3; callers restore it
; themselves, init from the stack and ymx_reopen from a6.
; -----------------------------------------------------------------------------
ymx_open:
        movea.l a0,a2
        adda.l  8(a0),a2                ; stream B, the literals
        movea.l a0,a4
        adda.l  12(a0),a4               ; stream C, the byte offsets
        movea.l a0,a5
        adda.l  16(a0),a5               ; stream D, the word offsets
        lea     28(a0),a0               ; stream A, where the header ends
        moveq   #0,d3
        move.w  YMX_N(a3),d3            ; N, ST4_init's argument
        bra     ST4_init

; -----------------------------------------------------------------------------
; YMX_play: a0 = workspace. One frame: skips, write fourteen registers, run
; the script's actions, refill one stream. No ring byte is ever edited.
; -----------------------------------------------------------------------------
YMX_play:
        movea.l a0,a3
        tst.l   YMX_LEFTFR(a3)
        bne.s   .frame
        moveq   #-1,d0                  ; past the last frame of a tune that
        rts                             ; plays once: write nothing

.frame:
        .if YMX_PERF
        move.w  $FFFF8240.w,-(sp)       ; the raster monitor. The background
        move.b  $FFFF8209.w,d0          ; goes on the stack first, before any
        move.w  #PERF_SYNC,d1           ; tick can paint over it; then wait
.perf_sync:                             ; for the beam. The VBL fires in the
        cmp.b   $FFFF8209.w,d0          ; blanking, dozens of lines above the
        dbne    d1,.perf_sync           ; screen, and only the video counter
                                        ; marks when the display starts: it
                                        ; moves when, and only when, the chip
                                        ; is fetching pixels. Bounded, so a
                                        ; host that plays mid-screen - or a
                                        ; machine whose counter never moves -
                                        ; stops rather than hanging.
                                        ;
                                        ; The wait cannot be skipped for a
                                        ; host that ticks from a timer, which
                                        ; is where it pins the bar: the player
                                        ; is not told which clock calls it,
                                        ; and reading the counter cannot tell
                                        ; a timer host in a border from a VBL
                                        ; host, whose bar is invisible without
                                        ; the wait. -vbl picks the clock
                                        ; instead, and the bar then holds.
        move.w  #$700,$FFFF8240.w       ; this frame's work, red - first,
        .endif                          ; so the monitor never delays the
                                        ; burst; the timers' bar follows

; One channel's turn in the frame's action stage: \bit of the master byte
; says whether it acts, \rings is how far its action stream sits above M
; (channel c's pair is 3+2c and 4+2c streams up), \here is the block
; and \next the one after it, so the channels chain without a loop's
; bookkeeping in the common case of nothing acting.
        .macro YMX_ACT chan, bit, rings, here, next
\here:
        move.b  (a0),d0                 ; a0 survives ymx_act
        btst    #\bit,d0
        beq.s   \next
        lea     ymx_desc\{chan}(pc),a5
        movea.l a0,a4
        .rept \rings
        adda.l  d3,a4                   ; its ring sits \rings above M
        .endr
        bsr     ymx_act
        .endm

; The skips first, then the burst, then everything else: the fourteen chip
; writes leave the VBL at a FIXED offset, which the frame's effects do not
; move. Only the skip bits must precede the burst (a trigger frame's voice
; must be skipped before the burst would write its sample number); the
; actions - timer programs, handler patches - happen after, where their
; varying cost cannot jitter the writes. Skip changes are rare, so the
; burst's offset moves only on those frames, and by a fixed amount.
        movea.l YMX_CURSOR(a3),a0
        adda.l  YMX_EFXOFF(a3),a0       ; ring M, at this frame's offset
        move.b  (a0),d0                 ; a0 survives everything below
        btst    #4,d0                   ; new skip bits this frame?
        beq.s   .burst                  ; bits 7-5 hold it
        lsr.b   #5,d0
        bsr     ymx_skips
        move.b  (a0),d0                 ; the master byte again
.burst:
        movea.l YMX_CURSOR(a3),a1       ; the cursor: every register's ring
        lea     YMX_PSG.w,a2            ; byte is a patched k*N above it
        move.w  #$0100,d2               ; the select step - loaded after
                                        ; ymx_skips, which clobbers d2

; Selecting a register and writing it are two bus cycles, and an interrupt
; between them would send the value to whatever register the interrupt
; selected. The burst used to mask interrupts for its whole run to prevent
; that, which delayed every tick that fell inside it - at 25,000 ticks a
; second the mask was longer than a tick period, so a sample could lose
; its place or a tick could be coalesced away.
;
; movep.w writes both bytes from one register in ONE instruction. A 68000
; takes interrupts between instructions, so nothing can land inside a
; write and the mask below is optional: with YMX_MASK_BURST at 0 the burst
; runs unmasked and ticks interleave with it, delayed by one instruction
; at worst instead of ~500 cycles. The tick handlers are atomic the same
; way - move.l #imm,$FFFF8800.w - so the interleaving is safe in both
; directions, and a tick that lands between two register writes is
; followed by a write that selects again.
        .if YMX_MASK_BURST
        move.w  sr,-(sp)                ; YMX_MASK_BURST: the old behaviour,
        ori.w   #$0700,sr               ; for hearing what the mask costs
        .endif
ymx_wA:
        moveq   #0,d1                   ; R0: channel A period, fine - the
        move.b  $7FFF(a1),d1            ; value - SMC: k*N, set at init
        movep.w d1,0(a2)                ; select and write in ONE instruction,
                                        ; so no tick can land between them
        YMX_WRITE                       ; R1: channel A period, coarse
        YMX_WRITE                       ; R2: channel B
        YMX_WRITE                       ; R3
        YMX_WRITE                       ; R4: channel C
        YMX_WRITE                       ; R5
        YMX_WRITE                       ; R6: noise period

; R7 sits HERE ON PURPOSE, between the periods and the volumes, and moving
; it to either end of the run - which would let R0..R6 and R8..R12 fold
; into one unbroken block, for two source lines and four bytes - widens an
; audible window. Routing and volume disagree for as long as the writes
; between them take: on the frame a sample ends, R7 reconnects the voice
; while its volume register still holds the sample's parked tail value.
; That gap is one write, about 4 us, where it is. First in the run it
; becomes seven writes, 28 us; last, five writes on the other side, 20 us -
; and the same class of artefact cost a day and a half on Trapped in China.
; Nothing automated would catch it: the harness reads registers at the end
; of a frame, and the rig compares each frame's writes as a set.
        add.w   d2,d1                   ; R7 also carries the ST's I/O port
ymx_w7:
        move.b  $7FFF(a1),d1            ; directions, which port A needs kept
        or.b    #YMX_PORTS,d1           ; as outputs for the floppy select
        movep.w d1,0(a2)                ; one instruction, like every write

ymx_wB:
        YMX_WRITE                       ; R8: the three volumes
        YMX_WRITE                       ; R9
        YMX_WRITE                       ; R10
        YMX_WRITE                       ; R11: envelope period
        YMX_WRITE                       ; R12

        add.w   d2,d1                   ; R13's select, above the shape byte
ymx_w13:
        move.b  $7FFF(a1),d1            ; R13 restarts the envelope whenever it
        cmp.b   #YMX_NO_ENV,d1          ; is written, so $FF means leave it be
        beq.s   .envelope_held
        movep.w d1,0(a2)
.envelope_held:
        .if YMX_MASK_BURST
        move.w  (sp)+,sr
        .endif

; The actions, now that the chip has its frame: replay the compiled script.
; One master byte says what acts - almost always nothing. The action bytes
; were decided at pack time; the handlers copy prepared values to prepared
; places and compare nothing. a0 still points at ring M.
; The map first, if it moved. Stream T is one byte a frame and almost
; always the same one, so a tune that never re-assigns pays a compare.
ymx_wT:
        move.b  $7FFF(a0),d1            ; ring T = M + 2N - SMC: set at init
        cmp.b   YMX_TMAP+1(a3),d1
        beq.s   .map_ready
        moveq   #0,d0
        move.b  d1,d0
        move.b  d1,YMX_TMAP+1(a3)
        bsr     ymx_assign              ; leaves a0 alone
        move.b  (a0),d0                 ; the master byte again
.map_ready:
        andi.b  #(1<<YMX_CHANNELS)-1,d0 ; the channels' bits; skips are done
        beq.s   .effects_done
        moveq   #0,d3
        move.w  YMX_N(a3),d3            ; d3 survives ymx_act; d0 does not
        YMX_ACT _0,0,3,.channel0,.channel1
        YMX_ACT _1,1,5,.channel1,.channel2
        YMX_ACT _2,2,7,.channel2,.channel3
        YMX_ACT _3,3,9,.channel3,.effects_done
.effects_done:

; Refill the stream whose turn it is. Slots past the live streams exist
; when C is larger than that count; they have nothing to do - which
; is also how an unused timer channel costs no time, its pair of streams
; sitting past the end.
        move.w  YMX_SLOT(a3),d0
        cmp.w   YMX_LIVE(a3),d0
        bcc.s   .advance
        moveq   #0,d3
        move.w  YMX_C(a3),d3            ; a whole group
        tst.w   YMX_RING(a3)
        bne.s   .bounded                ; a pass that fits a ring is decoded
                                        ; once and replayed from the ring
        tst.w   YMX_LOOPS(a3)
        bne.s   .refill                 ; a tune that starts over never runs out
.bounded:
        move.l  YMX_TOTAL(a3),d1
        sub.l   YMX_NEXT(a3),d1         ; values still packed
        ble.s   .advance                ; all decoded: never call ST4 again
        cmp.l   d3,d1
        bcc.s   .refill
        move.l  d1,d3                   ; the last group is a short one
.refill:
        lsl.w   #6,d0
        bsr     YMX_fill

.advance:
        addq.l  #1,YMX_CURSOR(a3)       ; one byte along every ring
        move.l  YMX_CURSOR(a3),d0
        cmp.l   YMX_RINGEND(a3),d0
        bcs.s   .cursor_ready
        move.l  YMX_RINGS(a3),YMX_CURSOR(a3)
.cursor_ready:
        move.w  YMX_SLOT(a3),d0
        addq.w  #1,d0
        cmp.w   YMX_C(a3),d0
        bcs.s   .slot_ready
        moveq   #0,d0                   ; a cycle ended: the groups move on
        moveq   #0,d1
        move.w  YMX_C(a3),d1
        add.l   d1,YMX_NEXT(a3)
.slot_ready:
        move.w  d0,YMX_SLOT(a3)

; The rings hold the tune as one continuous sequence, so reaching the end of
; the last frame means the next values in them are frame L's again.
; The countdown follows: it reloads with the length of a pass.
        subq.l  #1,YMX_LEFTFR(a3)
        bne.s   .played
        tst.w   YMX_LOOPS(a3)
        beq.s   .played                 ; zero and it plays once: the next
                                        ; call ends
        bsr     ymx_restart             ; the tune starts over, from silence
        move.l  YMX_BODY(a3),YMX_LEFTFR(a3)

; A pass that fits a ring was decoded once, so the bytes ahead of the cursor
; are the ones just played. The cursor goes back by the length of a pass, and
; where a pass crossed the ring end that lands below the ring area: one ring
; length forward puts it back. The cursor is an absolute long, so the
; correction is a long add of a zero-extended N.
        tst.w   YMX_RING(a3)
        beq.s   .wrapped
        move.l  YMX_CURSOR(a3),d0
        sub.l   YMX_BODY(a3),d0
        cmp.l   YMX_RINGS(a3),d0
        bcc.s   .rewound
        moveq   #0,d1
        move.w  YMX_N(a3),d1
        add.l   d1,d0
.rewound:
        move.l  d0,YMX_CURSOR(a3)
.wrapped:
        .if YMX_PERF
        move.w  #$770,$FFFF8240.w       ; then the timers' bar: what the
        lea     ymx_perf_acc(pc),a0     ; ticks cost since last frame,
        move.w  (a0),d0                 ; burned AFTER the frame's work
        clr.w   (a0)
.perf_bar_w:
        dbra    d0,.perf_bar_w
        move.w  (sp)+,$FFFF8240.w
        .endif
        moveq   #1,d0                   ; played, and wrapped to frame L
        rts
.played:
        .if YMX_PERF
        move.w  #$770,$FFFF8240.w
        lea     ymx_perf_acc(pc),a0
        move.w  (a0),d0
        clr.w   (a0)
.perf_bar_p:
        dbra    d0,.perf_bar_p
        move.w  (sp)+,$FFFF8240.w
        .endif
        moveq   #0,d0
        rts

; -----------------------------------------------------------------------------
; YMX_stop: a0 = workspace. Quiesces what YMX_init claimed - every claimed
; channel's timer stopped, its interrupt disabled and masked, its pending
; dropped - then silences the three voices. A timer the tune never used is
; not touched here either. It restores NOTHING: the host that saved the
; machine state restores it itself, after this returns. Tone and envelope
; settings stay, and a fresh YMX_init starts the tune over.
; -----------------------------------------------------------------------------
YMX_stop:
        movea.l a0,a3
        move.w  sr,-(sp)
        ori.w   #$0700,sr
        lea     ymx_desc_0(pc),a5
        moveq   #YMX_CHANNELS-1,d2
.channel:
        bsr.s   ymx_handback
        lea     CH_SIZE(a5),a5
        dbra    d2,.channel
        moveq   #0,d0                   ; no voice skipped again
        bsr     ymx_skips
; The silence stays inside the mask: selecting a register and writing it are
; two bus cycles, and a host's system interrupts - about to be restored - use
; the chip too.
        lea     YMX_PSG.w,a0
        moveq   #8,d0                   ; R8, R9, R10 are the voice volumes
.silence:
        move.b  d0,(a0)
        clr.b   2(a0)
        addq.b  #1,d0
        cmp.b   #11,d0
        bne.s   .silence
        move.w  (sp)+,sr
        rts

; One channel's timer given back: stopped, disabled, masked, no pending
; tick of ours - and the claim cleared, so a second call does nothing. The
; vector is left where it points, at this blob's park entry: it cannot
; fire, and restoring vectors is the host's job (assumption 5).
; in: a5 = the descriptor; clobbers d1/a1.
ymx_handback:
        tst.w   CH_CLAIMED(a5)          ; only what YMX_init took
        beq.s   .not_ours
        clr.w   CH_CLAIMED(a5)
        movea.w CH_CTRL(a5),a1
        move.w  CH_KEEP(a5),d1
        and.b   d1,(a1)                 ; the timer stopped,
        move.w  CH_IERBIT(a5),d1
        movea.w CH_IER(a5),a1
        bclr    d1,(a1)                 ; disabled and masked - the claim
        bclr    d1,12(a1)               ; handed back bit by bit
        bclr    d1,4(a1)                ; and no pending tick of ours
.not_ours:
        rts

; -----------------------------------------------------------------------------
; The effect machinery. The per-frame stage lives inlined in YMX_play; what
; follows is everything it drives: the channel descriptors, the tick
; handlers, and the one machine that serves every channel.
; -----------------------------------------------------------------------------

; The timers, and the channels that borrow them.
;
; A timer's row holds everything that tells one timer from another: its
; registers, its vector, its interrupt bit, what a stop must keep of the
; shared control byte, how far its prescaler shifts to get there, and the
; tick-handler block that belongs to it. The handlers belong to the TIMER,
; not the channel - each bakes in its own end-of-interrupt and control
; register - so assigning a channel to a timer is a copy of this row and
; never a patch of live interrupt code.
;
; Timers C and D share TCDCR at $FA1D: C in the high nibble, D in the low.
; That is the whole reason for KEEP and SHIFT. Timer C is also the
; operating system's 200 Hz clock, so a tune that uses it stops the
; system clock and cannot be hosted from a Timer C hook - the file's
; choice, made at pack time, and the host's problem to handle.
        even
ymx_timers:
ymx_timer_a:
        dc.w    $FA19,$FA1F,$0134       ; control, data, vector
        dc.w    $FA07,5                 ; the enable register and its bit
        dc.w    $00F0,0                 ; keep, shift: the low nibble
        dc.l    0                       ; the block: ymx_link fills it in
ymx_timer_b:
        dc.w    $FA1B,$FA21,$0120
        dc.w    $FA07,0
        dc.w    $00F0,0
        dc.l    0
ymx_timer_c:
        dc.w    $FA1D,$FA23,$0114
        dc.w    $FA09,5
        dc.w    $000F,4                 ; the HIGH nibble of the shared byte
        dc.l    0
ymx_timer_d:
        dc.w    $FA1D,$FA25,$0110
        dc.w    $FA09,4
        dc.w    $00F0,0
        dc.l    0

; The channels: four rows of the same shape, each filled from a timer's
; row whenever stream T says so, plus the claim that is the channel's own.
        even
ymx_desc_0:
        dcb.b   CH_SIZE,0
ymx_desc_1:
        dcb.b   CH_SIZE,0
ymx_desc_2:
        dcb.b   CH_SIZE,0
ymx_desc_3:
        dcb.b   CH_SIZE,0

; The two prepared tables the script indexes into: the samples' absolute
; sample addresses (YMX_init resolves the file's table once, so a trigger
; is one indexed load) and the three parameter-ring offsets (8+voice)*N.
ymx_samples:
        ds.l    64                      ; a pair per sample: its address, and
                                        ; the address it loops back to or 0
ymx_parmoff:
        ds.l    4                       ; a long a voice, and a fourth for
                                        ; voice 3, which no opcode addresses:
                                        ; ymx_voice indexes at voice*4, and a
                                        ; malformed file must land inside the
                                        ; table rather than past it

; -----------------------------------------------------------------------------
; The tick handlers, in maxYMiser's shipping forms: the PSG select and write
; in as few instructions as the bus allows, patched immediates instead of
; state, software end-of-interrupt as TOS runs the MFP - written as an
; immediate byte, since the in-service registers ignore written ones. All
; MFP interrupts arrive at 68000 level 6, so these never nest, and every
; patched operand is addressed pc-relatively or through an address ymx_link
; patches at init, so the player stays loadable
; anywhere - which also means it must run from RAM, where a demo lives.
;
; The timers' handlers are the same text four times, and MUST stay
; byte-congruent: the action handlers reach every patched operand through ISR_
; offsets measured on Timer A's block, and ymx_link walks the blocks at a
; fixed stride. The rig checks both from the symbol table. The park entries -
; where YMX_init aims the vectors while no effect runs - are the handlers' own
; tails.
;
; The timers STACK: each handler drops the IPL to 5 as soon as its PSG
; select/data pair is complete, so an MFP channel of higher priority can
; nest instead of waiting a whole tick. The MFP serves its A bank before
; its B bank, which puts the player's timers in the order A, B, D. The
; nesting is strictly one-way: a higher-priority timer interrupts a lower
; one's handler, never the reverse (SEI mode admits only higher MFP
; priorities while an in-service bit is set), and nothing of the player's
; sits above Timer A. The drop must never come before the handler's LAST
; chip write: a nested tick re-selects the PSG.
;
; The patch offsets are load-bearing (all fixed ones shift by PERF_PAD when
; the raster monitor's color prologue sits at each block's top):
;   pcm:      select immediate at +3, sample address at +8; the step and the
;          end path reach it through addresses ymx_link patches, at the
;          ISR_PCM_STEP and ISR_PCM_END fields
;   toggle:   select at +2 and volume at +4 inside the imm long; the partner's
;          whole vector at +14, past the IPL drop, patched by ymx_link
;   retrigger: the shape at +4
; -----------------------------------------------------------------------------

; The channels differ only in which timer runs them, so the whole set of
; handlers is written once as a macro and invoked once per channel. \chan
; is the channel's label suffix - rmac renders a bare digit as hex, so the
; number takes a leading underscore - and the timer arrives
; as arguments: \eoi and \eoir the end-of-interrupt value and register,
; \tctrl the timer's control register, \vec its vector. \colour is the
; monitor's band. rmac's \~ gives each expansion its own local labels.
        .macro YMX_TICKS chan, colour, eoi, eoir, tctrl, keep, vec

; One PCM tick: the sample byte goes straight to the voice's volume register.
; The byte after the last has bit 7 set, so the same move answers "done": park
; the volume mid-scale against the click, stop the timer, drop the flag.
ymx_pcm\{chan}:
        .if YMX_PERF
        move.w  $FFFF8240.w,-(sp)       ; this channel's band
        move.w  #\colour,$FFFF8240.w
        .endif
        move.b  #8,$FFFF8800.w          ; SMC PERF_PAD+3: select the volume
        move.b  $12345678,$FFFF8802.w   ; SMC PERF_PAD+8: the next sample
        bmi.s   .ended\~
        move.w  #$2500,sr               ; pair done: the other timer may stack
        addq.l  #1,($12345678).l        ; SMC ISR_PCM_STEP: the operand's
                                        ; own address, patched by ymx_link
        .if YMX_PERF
        move.l  a0,-(sp)
        lea     ymx_perf_acc(pc),a0
        add.w   #18,(a0)                ; ~a PCM tick, in 10-cycle quanta -
                                        ; measured, doc/performance.md
        movea.l (sp)+,a0
        move.w  (sp)+,$FFFF8240.w
        .endif
        move.b  #\eoi,\eoir.w
        rte
.ended\~:                               ; a looping stream goes back instead
ymx_pcmend\{chan}:                       ; of stopping
        tst.l   ($12345678).l           ; SMC ISR_PCM_ENDT: the loop cell -
        beq.s   .stop\~                  ; zero marks a one-shot stream
        move.l  ($12345678).l,($12345678).l ; SMC ISR_PCM_ENDR, ISR_PCM_ENDW:
                                        ; the loop cell into the operand
        .if YMX_PERF
        move.l  a0,-(sp)
        lea     ymx_perf_acc(pc),a0
        add.w   #20,(a0)                ; the end tick: the plain one and
        movea.l (sp)+,a0                ; the loop cell's move
        move.w  (sp)+,$FFFF8240.w
        .endif
        bra.s   ymx_park\{chan}          ; the end tick has already written the
                                        ; marker as a level - one sample of
                                        ; silence at the seam, and no other
.stop\~:
        move.b  #$D,$FFFF8802.w         ; middle volume: no click
        andi.b  #\keep,\tctrl.w         ; the sample ran out: stop the timer -
        .if YMX_PERF                    ; the script compiled this frame in
        move.l  a0,-(sp)                ; advance: skip and mixer follow at
        lea     ymx_perf_acc(pc),a0     ; the next frame boundary
        add.w   #20,(a0)                ; and the stop: the park and the
        movea.l (sp)+,a0                ; timer's control byte
        move.w  (sp)+,$FFFF8240.w
        .endif
ymx_park\{chan}:
        move.b  #\eoi,\eoir.w
        rte
ymx_pcmloop\{chan}:
        dc.l    0                       ; where this timer's PCM stream loops
                                        ; back to, or 0 for one that stops.
                                        ; Inside the block, so the trigger
                                        ; reaches it at a fixed offset; past
                                        ; the rte, so nothing falls into it

; The toggle tick: each tick writes one of two values - the volume or
; silence - and installs the other half as the next tick, one word into the
; vector. Select and data land in one move.l: the immediate's bytes hit
; $8800 (select) and $8802 (data) by bus layout.
ymx_toggle\{chan}_on:
        .if YMX_PERF
        move.w  $FFFF8240.w,-(sp)
        move.w  #\colour,$FFFF8240.w
        .endif
        move.l  #$08000F00,$FFFF8800.w  ; SMC PERF_PAD+2 select, +4 volume
        move.w  #$2500,sr
        move.l  #0,\vec.w                ; SMC PERF_PAD+14: the quiet half is
        .if YMX_PERF                    ; next - the whole vector, so the
        move.l  a0,-(sp)                ; blob may straddle a 64K boundary
        lea     ymx_perf_acc(pc),a0
        add.w   #15,(a0)                ; ~a toggle tick, in 10-cycle quanta
        movea.l (sp)+,a0
        move.w  (sp)+,$FFFF8240.w
        .endif
        move.b  #\eoi,\eoir.w
        rte
ymx_toggle\{chan}_off:
        .if YMX_PERF
        move.w  $FFFF8240.w,-(sp)
        move.w  #\colour,$FFFF8240.w
        .endif
        move.l  #$08000000,$FFFF8800.w  ; SMC PERF_PAD+2 select; silence
        move.w  #$2500,sr
        move.l  #0,\vec.w                ; SMC PERF_PAD+14: the loud half next
        .if YMX_PERF
        move.l  a0,-(sp)
        lea     ymx_perf_acc(pc),a0
        add.w   #15,(a0)
        movea.l (sp)+,a0
        move.w  (sp)+,$FFFF8240.w
        .endif
        move.b  #\eoi,\eoir.w
        rte

; The retrigger tick: writing the shape to R13 restarts the envelope, and
; that restart at the timer's rate is the whole effect.
ymx_retrigger\{chan}:
        .if YMX_PERF
        move.w  $FFFF8240.w,-(sp)
        move.w  #\colour,$FFFF8240.w
        .endif
        move.l  #$0D000000,$FFFF8800.w  ; SMC PERF_PAD+4: the shape
        move.w  #$2500,sr
        .if YMX_PERF
        move.l  a0,-(sp)
        lea     ymx_perf_acc(pc),a0
        add.w   #12,(a0)                ; ~a retrigger tick, in 10-cycle quanta
        movea.l (sp)+,a0
        move.w  (sp)+,$FFFF8240.w
        .endif
        move.b  #\eoi,\eoir.w
        rte
        .endm

; One block per timer, in MFP priority order: the A bank is served before
; the B bank, so it runs Timer A, Timer B, Timer C, Timer D. Timer D is
; last, which makes it the one nothing can nest into.
;                 timer colour  eoi  in-service  control    keep  vector
        YMX_TICKS _a,    $070,   $DF, $FFFFFA0F, $FFFFFA19,  $F0,  $134
        YMX_TICKS _b,    $700,   $FE, $FFFFFA0F, $FFFFFA1B,  $F0,  $120
        YMX_TICKS _c,    $077,   $DF, $FFFFFA11, $FFFFFA1D,  $0F,  $114
        YMX_TICKS _d,    $007,   $EF, $FFFFFA11, $FFFFFA1D,  $F0,  $110

YMX_BLOCK       equ     ymx_pcm_b-ymx_pcm_a   ; the blocks are congruent, so
                                        ; one stride walks them

        .if YMX_PERF
ymx_perf_acc:
        dc.w    0                       ; the ticks' estimated cost since the
        .endif                          ; last frame, in 10-cycle quanta

; Where the patched operands sit, measured on Timer A's block; the other
; blocks are byte-congruent, so the same offsets serve all of them through
; CH_ISR.
ISR_PCM_SEL    equ     PERF_PAD+3
ISR_PCM_PTR    equ     PERF_PAD+8
ISR_PCM_LOOP   equ     ymx_pcmloop_a-ymx_pcm_a
ISR_TOGGLE_ON       equ     ymx_toggle_a_on-ymx_pcm_a
ISR_TOGGLE_ON_SEL   equ     ISR_TOGGLE_ON+PERF_PAD+2
ISR_TOGGLE_VOL     equ     ISR_TOGGLE_ON+PERF_PAD+4
ISR_TOGGLE_ON_NEXT  equ     ISR_TOGGLE_ON+PERF_PAD+14
ISR_TOGGLE_OFF      equ     ymx_toggle_a_off-ymx_pcm_a
ISR_TOGGLE_OFF_SEL  equ     ISR_TOGGLE_OFF+PERF_PAD+2
ISR_TOGGLE_OFF_NEXT equ     ISR_TOGGLE_OFF+PERF_PAD+14
ISR_RETRIGGER        equ     ymx_retrigger_a-ymx_pcm_a
ISR_PARK             equ     ymx_park_a-ymx_pcm_a
ISR_RETRIGGER_SHAPE  equ     ISR_RETRIGGER+PERF_PAD+4
ISR_PCM_STEP   equ     PERF_PAD+22
ISR_PCM_ENDT   equ     (ymx_pcmend_a-ymx_pcm_a)+2
ISR_PCM_ENDR   equ     (ymx_pcmend_a-ymx_pcm_a)+10
ISR_PCM_ENDW   equ     (ymx_pcmend_a-ymx_pcm_a)+14

; -----------------------------------------------------------------------------
; ymx_link, from YMX_init: the run-time addresses that exist only once the
; player is loaded. Every skip lifts (a previous session's must not leak),
; each descriptor gets its ISR block, and the toggle halves get each
; other's whole vector - it never changes, so it is patched once here rather
; than on every toggle start. One pass over the channel table does all of it.
; -----------------------------------------------------------------------------
ymx_link:
        moveq   #0,d0
        bsr     ymx_skips
        lea     ymx_timers(pc),a1
        lea     ymx_pcm_a(pc),a2
        moveq   #YMX_TIMERS-1,d1
.timer:
        move.l  a2,CH_ISR(a1)               ; the block that belongs to it
        lea     ISR_TOGGLE_OFF(a2),a0       ; each half installs the other,
        move.l  a0,ISR_TOGGLE_ON_NEXT(a2)   ; as a whole vector
        lea     ISR_TOGGLE_ON(a2),a0
        move.l  a0,ISR_TOGGLE_OFF_NEXT(a2)
        lea     ISR_PCM_PTR(a2),a0          ; the PCM tick steps its operand
        move.l  a0,ISR_PCM_STEP(a2)         ; through this patched address
        move.l  a0,ISR_PCM_ENDW(a2)         ; and the loop rewinds it
        lea     ISR_PCM_LOOP(a2),a0
        move.l  a0,ISR_PCM_ENDT(a2)
        move.l  a0,ISR_PCM_ENDR(a2)
        lea     CH_ROW(a1),a1
        lea     YMX_BLOCK(a2),a2
        dbra    d1,.timer
        rts

; -----------------------------------------------------------------------------
; ymx_assign: stream T's byte in d0, two bits per channel. Copies each
; chosen timer's row into that channel's descriptor - registers, vector,
; enable bit, keep mask, shift and handler block, everything that tells
; one timer from another. The claim stays where it is, because it belongs
; to the channel and not to the timer under it.
;
; A channel whose timer changes while it runs is not stopped here: SPEC
; 2.3 makes moving only idle channels the writer's promise.
; Clobbers d0-d2, a1-a2; a0 survives, and the frame's caller uses that.
; -----------------------------------------------------------------------------
ymx_assign:
        lea     ymx_desc_0(pc),a1
        moveq   #YMX_CHANNELS-1,d2
.channel:
        move.w  d0,d1
        and.w   #3,d1                   ; this channel's two bits
        move.b  .rowoff(pc,d1.w),d1     ; the two bits become a row offset
        lea     ymx_timers(pc),a2
        adda.w  d1,a2
        move.l  (a2)+,(a1)+             ; the row, eighteen bytes of it
        move.l  (a2)+,(a1)+
        move.l  (a2)+,(a1)+
        move.l  (a2)+,(a1)+
        move.w  (a2),(a1)+              ; a1 now stands on CH_CLAIMED, which
        addq.l  #CH_SIZE-CH_ROW,a1      ; is the channel's and stays put
        lsr.w   #2,d0                   ; the next channel's bits
        dbra    d2,.channel
        rts
.rowoff:
        dc.b    0,CH_ROW,CH_ROW*2,CH_ROW*3
        even

; -----------------------------------------------------------------------------
; The action handlers: the script's opcodes, one straight line each. The
; packer resolved everything - which channel acts, which voice, restart or
; retune, which stops which timer - so a handler only moves prepared bytes
; into prepared places and programs its timer in the contract's order.
;
; in: a4 = the channel's action ring byte (its count byte is one ring above),
;     a5 = the channel's descriptor, a3 = workspace, d3.l = N.
; preserves d3/a3/a0; clobbers d0-d2/d4-d5, a1-a2/a4-a5.
; -----------------------------------------------------------------------------
ymx_act:
        move.b  (a4),d0                 ; opcode 7-5, voice 4-3, low bits 2-0
        move.w  d0,d1
        and.w   #$F8,d1
        lsr.w   #2,d1                   ; (opcode * 4 + voice) * 2
        move.w  ymx_opcodes(pc,d1.w),d1
        jmp     ymx_opcodes(pc,d1.w)

; Opcode AND voice select the handler, so the three forms that turn on the
; voice field cost no test. A voice no opcode addresses repeats that
; opcode's own entry: §2.4 leaves those bytes undefined and §9.1 leaves a
; malformed file undefined, and this keeps one landing where its opcode
; does rather than anywhere.
ymx_opcodes:
        dc.w    ymx_resume-ymx_opcodes  ; opcode 0: a masked toggle returns
        dc.w    ymx_resume-ymx_opcodes
        dc.w    ymx_resume-ymx_opcodes
        dc.w    ymx_resume_retune-ymx_opcodes   ; voice 3: with a new rate
        dc.w    ymx_hold-ymx_opcodes
        dc.w    ymx_hold-ymx_opcodes
        dc.w    ymx_hold-ymx_opcodes
        dc.w    ymx_hold-ymx_opcodes
        dc.w    ymx_release-ymx_opcodes
        dc.w    ymx_release-ymx_opcodes
        dc.w    ymx_release-ymx_opcodes
        dc.w    ymx_release-ymx_opcodes
        dc.w    ymx_toggle_start-ymx_opcodes
        dc.w    ymx_toggle_start-ymx_opcodes
        dc.w    ymx_toggle_start-ymx_opcodes
        dc.w    ymx_toggle_start-ymx_opcodes
        dc.w    ymx_retune-ymx_opcodes  ; opcode 4: a voice, so a repatch
        dc.w    ymx_retune-ymx_opcodes
        dc.w    ymx_retune-ymx_opcodes
        dc.w    ymx_live-ymx_opcodes    ; voice 3: no voice, no repatch
        dc.w    ymx_retrigger_start-ymx_opcodes
        dc.w    ymx_retrigger_start-ymx_opcodes
        dc.w    ymx_retrigger_start-ymx_opcodes
        dc.w    ymx_retrigger_retune-ymx_opcodes    ; voice 3: shape, live
        dc.w    ymx_pcm-ymx_opcodes
        dc.w    ymx_pcm-ymx_opcodes
        dc.w    ymx_pcm-ymx_opcodes
        dc.w    ymx_pcm-ymx_opcodes
        dc.w    ymx_pcm_preempt-ymx_opcodes
        dc.w    ymx_pcm_preempt-ymx_opcodes
        dc.w    ymx_pcm_preempt-ymx_opcodes
        dc.w    ymx_pcm_preempt-ymx_opcodes

; The two operands the opcodes read out of the rings: a voice's parameter
; byte off its own register stream, and the shape off stream X.
;
; ymx_shape: the envelope shape a retrigger stream must restart. A toggle
; stream's volume and a PCM stream's number are read off the voice's own
; ring, because that is where they live - they belong to the voice the
; effect took over. A shape belongs to the one envelope generator instead,
; and different formats file it in different places, so it is not looked
; for: the packer resolved it and X carries it, high nibble. The
; player is therefore independent of where a tune came from.
; in: a0 = ring M, d3.l = N; out: d1 = the shape, 0..15.
ymx_shape:
        move.b  0(a0,d3.l),d1           ; ring X, one stream above M
        lsr.b   #4,d1
        rts

; in: d0 = the action byte; out: a1 = the byte, d5 = voice*4. Clobbers d1.
ymx_voice:
        moveq   #$18,d5
        and.b   d0,d5
        lsr.w   #1,d5                   ; voice * 4
        lea     ymx_parmoff(pc),a1
        move.l  0(a1,d5.w),d1
        movea.l YMX_CURSOR(a3),a1
        adda.l  d1,a1                   ; this frame's byte
        rts

ymx_release:                               ; STOP: low bit clear = stop the
        btst    #0,d0                   ; timer (a retrigger release); set =
        bne.s   .mask                   ; MASK only - a toggle release: the
        movea.w CH_CTRL(a5),a1          ; counter keeps counting and the
        move.w  CH_KEEP(a5),d1          ; square's half stays frozen. RMW
        and.b   d1,(a1)
        rts                             ; forms throughout: Timer C's nibble
.mask:                                  ; and the other enable bits belong
        movea.w CH_IER(a5),a1           ; to the machine
        move.w  CH_IERBIT(a5),d1
        bclr    d1,(a1)                 ; disabling drops any pending too
        rts

ymx_resume:                             ; unmask; reload the count if it
        btst    #0,d0                   ; moved, retrack the volume if it
        beq.s   .kept                   ; moved - the phase ran on through
        move.b  0(a4,d3.l),d1           ; the gap, exactly as a masked
        movea.w CH_DATA(a5),a2          ; hardware timer does
        move.b  d1,(a2)                 ; a running timer loads a changed
.kept:                                  ; count at its next reload
        btst    #1,d0
        beq.s   .same
        bsr     ymx_voice
        move.b  (a1),d1
        and.w   #15,d1
        movea.l CH_ISR(a5),a2
        move.b  d1,ISR_TOGGLE_VOL(a2)
.same:
        movea.w CH_IER(a5),a1
        move.w  CH_IERBIT(a5),d1
        bset    d1,(a1)
        rts

; RESUME at voice 3: the re-arrival brings a new rate. The low bits are the
; prescaler, so no flag is left to name the voice and none names the volume
; either - both are implied. The voice comes back out of the PSG select byte
; ymx_toggle_start patched, which holds voice+8, and the timer is PROGRAMMED
; rather than reloaded: after a gap the counter holds an arbitrary count, so
; a live reload would defer the new rate by up to one old period. No vector
; is written, so the half the release left standing is the half that returns.
ymx_resume_retune:
        movea.l CH_ISR(a5),a2
        moveq   #0,d5
        move.b  ISR_TOGGLE_ON_SEL(a2),d5
        subq.w  #8,d5                   ; the voice
        lsl.w   #2,d5                   ; voice * 4, the index ymx_voice builds
        lea     ymx_parmoff(pc),a1
        move.l  0(a1,d5.w),d1
        movea.l YMX_CURSOR(a3),a1
        adda.l  d1,a1                   ; this frame's byte of R8+v
        move.b  (a1),d1
        and.w   #15,d1
        move.b  d1,ISR_TOGGLE_VOL(a2)
        moveq   #0,d4                   ; no vector: the installed half stands
        bra     ymx_program             ; stop, load, run - and ends enabled

ymx_hold:                               ; flags: 1 = reload the count, 2 =
        btst    #0,d0                   ; track the toggle volume, 4 =
        beq.s   .tracks                 ; track the retrigger shape - only
        move.b  0(a4,d3.l),d1           ; where the value changed
        movea.w CH_DATA(a5),a2
        move.b  d1,(a2)                 ; a running timer loads a changed
.tracks:                                ; count at its next reload
        moveq   #6,d1
        and.b   d0,d1
        beq.s   .done
        movea.l CH_ISR(a5),a2           ; ymx_voice leaves a2 alone
        btst    #2,d0
        bne.s   .shape
        bsr     ymx_voice               ; the blob outgrew a short branch
        move.b  (a1),d1
        and.w   #15,d1
        move.b  d1,ISR_TOGGLE_VOL(a2)
        rts
.shape:                                 ; a shape follows the file's source,
        bsr     ymx_shape               ; the same as an arm does
        move.b  d1,ISR_RETRIGGER_SHAPE(a2)
.done:
        rts

ymx_toggle_start:                          ; a fresh square restarts at phase
        bsr     ymx_voice               ; zero: the voice goes silent NOW,
        movea.l CH_ISR(a5),a2           ; and the first tick - one timer
        move.w  d5,d1                   ; period out - plays the loud half.
        lsr.w   #2,d1                   ; Retunes and resumes never come
        addq.w  #8,d1                   ; here: a held or masked square's
        move.b  d1,ISR_TOGGLE_ON_SEL(a2)    ; phase free-runs
        move.b  d1,ISR_TOGGLE_OFF_SEL(a2)
        move.w  sr,-(sp)                ; select and write are two bus
        ori.w   #$0700,sr               ; cycles: no tick in between
        move.b  d1,(YMX_PSG).w
        clr.b   (YMX_PSG+2).w           ; the silent first half
        move.w  (sp)+,sr
        move.b  (a1),d1                 ; a1 still holds the voice's byte
        and.w   #15,d1
        move.b  d1,ISR_TOGGLE_VOL(a2)
        lea     ISR_TOGGLE_ON(a2),a1
        move.l  a1,d4
        bra     ymx_program

ymx_retune:                         ; the vector is NEVER touched: the
        bsr     ymx_voice               ; square's phase persists through every
        move.b  (a1),d1                 ; retune, and through a resume after
        and.w   #15,d1                  ; a PCM stream took the voice. The
        movea.l CH_ISR(a5),a2           ; parameter is repatched and then the
        move.b  d1,ISR_TOGGLE_VOL(a2)   ; timer moves LIVE: falls into
                                        ; ymx_live below

; The prescaler moves under a RUNNING timer. Nothing is stopped, so the
; period already in flight runs to its own end and the reload lands at the
; next underflow: a square keeps its phase AND its place inside the half it
; is in, which is what a source that reprograms live sounds like.
;
; Three paths arrive here. A voiced RETUNE falls in from above, having
; repatched the parameter first. A RETUNE at voice 3 enters from the table,
; repatching nothing - the packer picks that encoding only on a frame where
; the parameter byte stood still. A retrigger retune enters below, having
; patched the shape. The timer must already be running: the control write
; comes before the data write, so on a stopped timer the first period would
; run at the previous count.
ymx_live:
        move.b  0(a4,d3.l),d2           ; the count
        and.b   #7,d0
        move.w  CH_SHIFT(a5),d1         ; Timer C's nibble is the high one
        lsl.b   d1,d0
        movea.w CH_CTRL(a5),a1
        movea.w CH_DATA(a5),a2
        move.w  CH_KEEP(a5),d1
        move.w  sr,-(sp)
        ori.w   #$0700,sr
        and.b   (a1),d1                 ; our nibble out, the other's kept
        or.b    d0,d1
        move.b  d1,(a1)                 ; ONE write: never a stopped timer
        move.b  d2,(a2)
        move.w  (sp)+,sr
        movea.w CH_IER(a5),a1           ; and enabled, whatever a mask left
        move.w  CH_IERBIT(a5),d1
        bset    d1,(a1)
        rts

ymx_retrigger_retune:               ; voice 3: a running retrigger stream
        bsr     ymx_shape               ; whose shape AND rate move on one
        movea.l CH_ISR(a5),a2           ; frame. The vector is already the
        move.b  d1,ISR_RETRIGGER_SHAPE(a2)  ; retrigger tick, so it stays,
        bra     ymx_live                ; and the period in flight survives

ymx_retrigger_start:
        bsr     ymx_shape
        movea.l CH_ISR(a5),a2
        move.b  d1,ISR_RETRIGGER_SHAPE(a2)
        lea     ISR_RETRIGGER(a2),a1
        move.l  a1,d4
        bra     ymx_program

ymx_pcm_preempt:                        ; arbitration, compiled: the stopped
        move.b  0(a0,d3.l),d2           ; timers go FIRST, then the sample
                                        ; arms its own - the reference order.
                                        ; A channel has no fixed voice, so
                                        ; ring X - one stream above M - gives
                                        ; what to stop, a bit per channel
        lea     ymx_desc_0(pc),a1
        moveq   #YMX_CHANNELS-1,d1      ; the table bounds the walk: a bit
.stop:                                  ; past the last channel marks nothing
        lsr.b   #1,d2
        bcc.s   .keep
        movea.w CH_CTRL(a1),a2
        move.w  CH_KEEP(a1),d4
        and.b   d4,(a2)
.keep:
        lea     CH_SIZE(a1),a1
        dbra    d1,.stop
ymx_pcm:
        bsr     ymx_voice
        moveq   #0,d1
        move.b  (a1),d1                 ; the sample number, 0..31
        lsl.w   #3,d1                   ; a PAIR each: the sample's
        movea.l CH_ISR(a5),a2           ; address, then where it loops back to
        lea     ymx_samples(pc),a1
        move.l  0(a1,d1.w),ISR_PCM_PTR(a2)  ; both resolved at init, so a
        move.l  4(a1,d1.w),ISR_PCM_LOOP(a2) ; trigger is two moves and no test
        move.w  d5,d1
        lsr.w   #2,d1
        addq.w  #8,d1
        move.b  d1,ISR_PCM_SEL(a2)
        move.l  a2,d4                   ; the tick handler is the block base
        ; falls through: a trigger and a retrigger program alike

; The timer program, in the order the reference sets: stop, vector,
; count, run - under the mask, since a tick between the stop and the run
; would fire into a half-programmed timer. A retune passes no vector.
; in: d0 = the action byte (prescaler in bits 2-0), d4 = the vector or 0,
;     a4 = the action ring byte (the count is one ring above).
; clobbers d0-d2 and a1-a2; d3-d5 and a3-a5 stand.
ymx_program:
        move.b  0(a4,d3.l),d2           ; the count
        and.b   #7,d0
        move.w  CH_SHIFT(a5),d1         ; Timer C's nibble is the high one
        lsl.b   d1,d0
        movea.w CH_CTRL(a5),a1
        move.w  CH_KEEP(a5),d1
        move.w  sr,-(sp)
        ori.w   #$0700,sr
        and.b   d1,(a1)                 ; stop, keeping the other nibble
        tst.l   d4
        beq.s   .keep
        movea.w CH_VEC(a5),a2
        move.l  d4,(a2)                 ; the vector; a retune keeps it
.keep:
        movea.w CH_DATA(a5),a2
        move.b  d2,(a2)                 ; count
        or.b    d0,(a1)                 ; run
        move.w  (sp)+,sr
        movea.w CH_IER(a5),a1           ; and enabled, whatever a mask
        move.w  CH_IERBIT(a5),d1        ; left: every program ends live
        bset    d1,(a1)
        rts

; -----------------------------------------------------------------------------
; ymx_restart: the tune begins again, so nothing may still be running from its
; end. Every claimed timer stops and its vector goes back to the park entry,
; and every skip opens - the state the script's first frame is compiled for.
; The rings are not touched: each stream re-opened its own section on its own
; refill turn, one a frame, so the values for the new pass are already there.
; in: a3 = workspace; clobbers d0-d2/a1-a2/a5.
; -----------------------------------------------------------------------------
ymx_restart:
        lea     ymx_desc_0(pc),a5
        moveq   #YMX_CHANNELS-1,d2
        move.w  sr,-(sp)                ; a tick between the stop and the park
        ori.w   #$0700,sr               ; would run the old handler
.channel:
        tst.w   CH_CLAIMED(a5)
        beq.s   .next
        movea.w CH_CTRL(a5),a1
        move.w  CH_KEEP(a5),d1
        and.b   d1,(a1)                 ; stopped; the other nibble kept
        movea.l CH_ISR(a5),a2
        lea     ISR_PARK(a2),a2         ; nothing to tick until an arm
        movea.w CH_VEC(a5),a1
        move.l  a2,(a1)
        move.w  CH_IERBIT(a5),d1        ; a released toggle left this clear
        movea.w CH_IER(a5),a1
        bclr    d1,4(a1)                ; drop a stale pending
        bset    d1,(a1)                 ; enabled, as init leaves it
.next:
        lea     CH_SIZE(a5),a5
        dbra    d2,.channel
        move.w  (sp)+,sr
        moveq   #0,d0                   ; no voice skipped again
        bra     ymx_skips

; -----------------------------------------------------------------------------
; ymx_skips: apply the skip bits. Bit v of d0 skips voice v: its volume write
; is overwritten with two nops, so the byte never reaches the chip and no
; branch ever enters the burst. Restoring it copies the movep back from a
; template. The script computed every edge at pack time.
; in: d0 bits 2-0 = the state, voices A/B/C; clobbers d0-d2/a1.
; -----------------------------------------------------------------------------
ymx_skips:
        lea     ymx_wB+YMX_WRITE_MOVEP(pc),a1   ; R8's movep, then R9's, R10's
        moveq   #3-1,d1
.skip:
        move.l  ymx_movep(pc),d2        ; kept: the instruction itself, copied
        lsr.b   #1,d0                   ; from a template so no hand-encoded
        bcc.s   .set                    ; opcode can drift
        move.l  #$4E714E71,d2           ; skipped: two nops - the displacement
.set:                                   ; word is part of the instruction
        move.l  d2,(a1)
        lea     YMX_WRITE_SIZE(a1),a1
        dbra    d1,.skip
        rts

; The template, never executed: ymx_skips copies these two words back over
; a skipped write to restore it.
ymx_movep:
        movep.w d1,0(a2)
