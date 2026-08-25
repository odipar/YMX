using System;
using System.Collections.Generic;

namespace Rig
{
    /// <summary>
    /// The effect stage, frame by frame, against the compiled script, ported
    /// from the Java rig's Effects: a SID held, reloaded and retuned on slot
    /// 1, drums triggered and retriggered on slot 2, a buzzer, the
    /// same-voice arbitration - asserted at this format's frame-aligned
    /// edges, with the tick handlers then driven by direct invocation.
    /// </summary>
    public static class Effects
    {
        private sealed record W(ulong Address, int Value);

        private static bool MfpIs(Player player, params W[] want)
        {
            if (player.Mfp.Count != want.Length)
            {
                return false;
            }
            for (int i = 0; i < want.Length; i++)
            {
                if (player.Mfp[i].Address != want[i].Address
                        || player.Mfp[i].Value != want[i].Value)
                {
                    return false;
                }
            }
            return true;
        }

        private static string ShowMfp(Player player)
        {
            var text = new System.Text.StringBuilder("[");
            foreach (Player.Write write in player.Mfp)
            {
                text.Append(text.Length == 1 ? "" : ", ").Append('(')
                        .Append(write.Address.ToString("x")).Append(", ")
                        .Append(write.Value).Append(')');
            }
            return text.Append(']').ToString();
        }

        private static Dictionary<int, int> RegisterMap(List<Player.Pair> writes)
        {
            var map = new Dictionary<int, int>();
            foreach (Player.Pair pair in writes)
            {
                map[pair.Register] = pair.Value;
            }
            return map;
        }

        private static int Get(Dictionary<int, int> map, int register)
        {
            return map.TryGetValue(register, out int value) ? value : -1;
        }

        public static string RunEffects(bool perf)
        {
            int frames = 72;
            byte[][] values = PlayerTests.NewValues(frames);
            for (int frame = 0; frame < frames; frame++)
            {
                values[7][frame] = 0x38;            // tone on, noise off
                values[8][frame] = 10;              // steady volumes
                values[9][frame] = 11;
                values[10][frame] = 12;
                values[13][frame] = (byte) GenYm.NoEnvelopeChange;
            }
            for (int frame = 5; frame < 21; frame++)
            {                                       // SID voice A on slot 1
                values[1][frame] |= 0x10;
                values[6][frame] |= 1 << 5;
                values[14][frame] = (byte) (frame >= 15 ? 80 : 100);
            }
            for (int frame = 6; frame < 15; frame++)
            {                                       // ...whose volume SLIDES:
                values[8][frame] = (byte) (10 - (frame - 4) / 2);   // the
            }                                       // script must track it
            // The retune scene: the same SID slides across a prescaler
            // boundary - E $11 to $12 at frame 25 - as wobbling basses do
            // many times a second.
            for (int frame = 22; frame < 27; frame++)
            {
                values[1][frame] |= 0x10;
                values[6][frame] |= (byte) ((frame < 25 ? 1 : 2) << 5);
                values[14][frame] = 90;
            }
            values[3][30] = 0x70;                   // drum voice C on slot 2
            values[8][30] |= 1 << 5;                // its prescaler rides R8
            values[15][30] = 122;                   // ...at 5036 Hz
            // Real dumps trigger drums on back-to-back frames, so frame 31
            // codes the same drum again with a different number underneath.
            values[3][31] = 0x70;
            values[8][31] |= 1 << 5;
            values[15][31] = 122;
            // The ring-integrity trap: an 11-byte R10 pattern spanning the
            // drum frame, repeated 30 frames later - the packer emits a
            // match that copies those ring positions, so the trap proves the
            // drum number travels in the ring unharmed.
            int[] pattern = {3, 4, 5, 6, 7, 1, 0, 6, 5, 4, 3};
            for (int i = 0; i < pattern.Length; i++)
            {
                values[10][25 + i] = (byte) pattern[i];
                values[10][55 + i] = (byte) pattern[i];
            }
            for (int frame = 40; frame < 43; frame++)
            {                                       // sync-buzzer voice B
                values[1][frame] = 0xE0;
                values[6][frame] |= 6 << 5;
                values[14][frame] = 200;
            }
            // The arbitration scene: a SID runs on voice B from slot 2, and
            // at frame 48 a drum fires on the SAME voice from slot 1.
            for (int frame = 45; frame < 53; frame++)
            {
                values[3][frame] |= 0x20;
                values[8][frame] |= 1 << 5;
                values[15][frame] = 90;
            }
            values[1][48] = 0x60;
            values[6][48] |= 1 << 5;
            values[14][48] = 60;
            values[9][48] = 0;                      // its number: sample 0
            byte[] drum0 = {0x80, 0x40};
            byte[] drum1 = {0x10, 0xF0, 0x50};

            byte[] packed = Rig.Pack(GenYm.Ym6File(frames, values, drum0, drum1),
                    960, 24, true, 1);
            var player = new Player(packed, 1, perf);
            if (player.Init() != 0)
            {
                return "effects: YMX_init rejected the file";
            }

            // The mechanism itself: stream T's byte, decoded into the
            // channel descriptors. Driven directly, since no YM tune moves
            // the map.
            string problem = CheckAssignment(player);
            if (problem.Length != 0)
            {
                return problem;
            }
            if (player.Init() != 0)
            {                                       // put the tune's own map
                return "effects: YMX_init rejected the file on the second pass";
            }

            // Every tick-handler block must be byte-congruent with channel
            // 1's: the action handlers reach every patched operand through
            // offsets measured there, and ymx_link walks the blocks at a
            // fixed stride.
            string[] timers = {"a", "b", "c", "d"};
            foreach (string shape in new[] {"ymx_toggle_{0}_on",
                    "ymx_toggle_{0}_off", "ymx_retrigger_{0}", "ymx_park_{0}"})
            {
                int want = player.Symbol(string.Format(shape, "a"))
                        - player.Symbol("ymx_pcm_a");
                for (int i = 1; i < timers.Length; i++)
                {
                    string label = string.Format(shape, timers[i]);
                    if (player.Symbol(label)
                            - player.Symbol("ymx_pcm_" + timers[i]) != want)
                    {
                        return "effects: " + label
                                + " broke the ISR block congruence";
                    }
                }
            }
            int stride = player.Symbol("ymx_pcm_b") - player.Symbol("ymx_pcm_a");
            for (int i = 0; i < timers.Length; i++)
            {
                if (player.Symbol("ymx_pcm_" + timers[i])
                        - player.Symbol("ymx_pcm_a") != stride * i)
                {
                    return "effects: block " + timers[i]
                            + " is not one stride along";
                }
            }

            // Channel 0 runs on Timer A and channel 1 on Timer D, which is
            // what the packer's default map says; the blocks are the timers'.
            ulong drumD = Rig.Code + (ulong) player.Symbol("ymx_pcm_d");
            ulong sidOn = Rig.Code + (ulong) player.Symbol("ymx_toggle_a_on");
            ulong sidOff = Rig.Code + (ulong) player.Symbol("ymx_toggle_a_off");

            // A burst write is ten bytes and ends in the movep that sends
            // it; a skip replaces that instruction with two nops.
            const int writeSize = 10;
            const int writeMovep = 6;
            long movepOpcode = player.Uc.Value(
                    Rig.Code + (ulong) player.Symbol("ymx_movep"), 2);

            const ulong enableA = 0xFFFFFA07;
            const ulong enableB = 0xFFFFFA09;
            for (int frame = 0; frame < 72; frame++)
            {
                if (frame == 25)
                {                           // flip the square to its quiet
                    PlayerTests.InvokeIsr(player, sidOn);   // half: the
                }                           // retune below must preserve it
                Player.Frame played = player.PlayFrame();
                Dictionary<int, int> registers = RegisterMap(played.Writes);
                if (frame == 5)
                {                           // SID start: stop, count, run,
                    if (!MfpIs(player, new W(PlayerTests.Tacr, 0),  // enabled
                            new W(PlayerTests.Tadr, 100),
                            new W(PlayerTests.Tacr, 1), new W(enableA, 0x20)))
                    {
                        return "effects: frame 5 programmed " + ShowMfp(player);
                    }
                    if (Skipped(player, movepOpcode, writeMovep, writeSize, 0) != 0)
                    {
                        return "effects: frame 5 left the SID voice open";
                    }
                }
                else if (frame >= 6 && frame <= 20 && frame != 15)
                {
                    if (player.Mfp.Count != 0)
                    {                       // held: the slide patches the
                        return "effects: frame " + frame + " wrote "
                                + ShowMfp(player);      // tick's immediate,
                    }                                   // never the MFP
                    if (registers.ContainsKey(8))
                    {
                        return "effects: frame " + frame
                                + " wrote the SID voice volume";
                    }
                    int want = frame <= 14 ? 10 - (frame - 4) / 2 : 10;
                    long vol = player.Uc.Value(Rig.Code
                            + (ulong) player.Symbol("ymx_pcm_a")
                            + (ulong) player.Symbol("ISR_TOGGLE_VOL"), 1);
                    if (vol != want)
                    {
                        return "effects: frame " + frame + " tick volume " + vol
                                + ", the slide says " + want;
                    }
                }
                else if (frame == 15)
                {                           // the count changed: a HELD
                    if (!MfpIs(player, new W(PlayerTests.Tadr, 80)))
                    {                       // reload, data only
                        return "effects: frame 15 wrote " + ShowMfp(player);
                    }
                }
                else if (frame == 21)
                {                           // released: stopped, and the
                    if (!MfpIs(player, new W(PlayerTests.Tacr, 0)))
                    {                       // skip lifts with the frame
                        return "effects: frame 21 wrote " + ShowMfp(player);
                    }
                    if (!registers.ContainsKey(8))
                    {
                        return "effects: frame 21 kept skipping the SID volume";
                    }
                }
                else if (frame == 22)
                {                           // a re-start is a FULL start at
                    if (!MfpIs(player, new W(PlayerTests.Tacr, 0),  // phase
                            new W(PlayerTests.Tadr, 90),            // zero...
                            new W(PlayerTests.Tacr, 1), new W(enableA, 0x20)))
                    {
                        return "effects: frame 22 programmed " + ShowMfp(player);
                    }
                    if (Get(registers, 8) != 0)
                    {                       // ...the voice silenced for its
                        return "effects: frame 22 volume " + Get(registers, 8)
                                + ", not silent";       // first period
                    }
                    long vector = player.Uc.Value(0x134, 4);
                    if ((ulong) vector != sidOn)
                    {
                        return "effects: the start did not install the loud"
                                + " half: " + vector.ToString("x");
                    }
                }
                else if (frame == 23 || frame == 24)
                {
                    if (player.Mfp.Count != 0)
                    {
                        return "effects: frame " + frame + " wrote "
                                + ShowMfp(player);
                    }
                }
                else if (frame == 25)
                {                           // prescaler-only change: full
                    if (!MfpIs(player, new W(PlayerTests.Tacr, 0),  // timer
                            new W(PlayerTests.Tadr, 90),        // reprogram...
                            new W(PlayerTests.Tacr, 2), new W(enableA, 0x20)))
                    {
                        return "effects: frame 25 programmed " + ShowMfp(player);
                    }
                    long vector = player.Uc.Value(0x134, 4);
                    if ((ulong) vector != sidOff)
                    {                       // ...but the phase lives
                        return "effects: the retune reset the square to "
                                + vector.ToString("x")
                                + ", not the installed quiet half";
                    }
                }
                else if (frame == 26)
                {
                    if (player.Mfp.Count != 0)
                    {
                        return "effects: frame 26 wrote " + ShowMfp(player);
                    }
                }
                else if (frame == 27)
                {                           // the scene's release
                    if (!MfpIs(player, new W(PlayerTests.Tacr, 0)))
                    {
                        return "effects: frame 27 wrote " + ShowMfp(player);
                    }
                }
                else if (frame == 30)
                {                           // the drum start, slot 2
                    if (!MfpIs(player, new W(PlayerTests.Tcdcr, 0),
                            new W(PlayerTests.Tddr, 122),
                            new W(PlayerTests.Tcdcr, 1), new W(enableB, 0x10)))
                    {
                        return "effects: frame 30 programmed " + ShowMfp(player);
                    }
                    if (registers.ContainsKey(10))
                    {                       // the drum owns R10 now
                        return "effects: frame 30 wrote the drummed volume";
                    }
                    if (Get(registers, 7) != (0x38 | 0xC0 | 0x24))
                    {                       // baked, not forced
                        return "effects: frame 30 mixer " + Get(registers, 7);
                    }
                    long position = player.Uc.Value(
                            drumD + (ulong) player.Symbol("ISR_PCM_PTR"), 4);
                    long drum = player.Uc.Value(player.File
                            + (ulong) PlayerTests.DrumTable(player) + 8, 4);
                    if (position != (long) player.File + drum)
                    {
                        return "effects: the trigger points at the wrong sample";
                    }
                }
                else if (frame == 31)
                {                           // the same code again: a fresh
                    if (!MfpIs(player, new W(PlayerTests.Tcdcr, 0),     // DRUM
                            new W(PlayerTests.Tddr, 122),
                            new W(PlayerTests.Tcdcr, 1), new W(enableB, 0x10)))
                    {
                        return "effects: frame 31 programmed " + ShowMfp(player);
                    }
                    long position = player.Uc.Value(
                            drumD + (ulong) player.Symbol("ISR_PCM_PTR"), 4);
                    long drum = player.Uc.Value(player.File
                            + (ulong) PlayerTests.DrumTable(player), 4);
                    if (position != (long) player.File + drum)
                    {
                        return "effects: the retrigger points at the wrong sample";
                    }
                }
                else if (frame == 32)
                {                           // the computed end, frame
                    if (player.Mfp.Count != 0)  // aligned: skip and mixer
                    {                           // come back as one
                        return "effects: frame 32 wrote " + ShowMfp(player);
                    }
                    if (Get(registers, 7) != (0x38 | 0xC0))
                    {
                        return "effects: frame 32 mixer still forced";
                    }
                    if (!registers.ContainsKey(10))
                    {
                        return "effects: frame 32 kept skipping the drum voice";
                    }
                }
                else if (frame == 40)
                {                           // buzzer start on slot 1
                    if (!MfpIs(player, new W(PlayerTests.Tacr, 0),
                            new W(PlayerTests.Tadr, 200),
                            new W(PlayerTests.Tacr, 6), new W(enableA, 0x20)))
                    {
                        return "effects: frame 40 programmed " + ShowMfp(player);
                    }
                }
                else if (frame == 41 || frame == 42)
                {                           // held, nothing changed
                    if (player.Mfp.Count != 0)
                    {
                        return "effects: frame " + frame + " wrote "
                                + ShowMfp(player);
                    }
                }
                else if (frame == 43)
                {
                    if (!MfpIs(player, new W(PlayerTests.Tacr, 0)))
                    {
                        return "effects: frame 43 wrote " + ShowMfp(player);
                    }
                }
                else if (frame == 45)
                {                           // the scene's SID, slot 2
                    if (!MfpIs(player, new W(PlayerTests.Tcdcr, 0),
                            new W(PlayerTests.Tddr, 90),
                            new W(PlayerTests.Tcdcr, 1), new W(enableB, 0x10)))
                    {
                        return "effects: frame 45 programmed " + ShowMfp(player);
                    }
                }
                else if (frame == 46 || frame == 47)
                {                           // held: its volume is skipped
                    if (registers.ContainsKey(9))
                    {
                        return "effects: frame " + frame
                                + " wrote the skipped volume";
                    }
                }
                else if (frame == 48)
                {                           // START_PCM_PREEMPT: the SID's
                    if (!MfpIs(player, new W(PlayerTests.Tcdcr, 0),     // timer
                            new W(PlayerTests.Tacr, 0),     // stops FIRST,
                            new W(PlayerTests.Tadr, 60),    // then the drum
                            new W(PlayerTests.Tacr, 1),     // arms
                            new W(enableA, 0x20)))
                    {
                        return "effects: frame 48 programmed " + ShowMfp(player);
                    }
                    if (registers.ContainsKey(9))
                    {
                        return "effects: frame 48 wrote the drummed volume";
                    }
                    if (Get(registers, 7) != (0x38 | 0xC0 | 0x12))
                    {
                        return "effects: frame 48 mixer " + Get(registers, 7);
                    }
                }
                else if (frame == 49)
                {                           // the computed end: the SID
                    if (!MfpIs(player, new W(PlayerTests.Tcdcr, 0), // re-STARTS
                            new W(PlayerTests.Tddr, 90),    // - phase 0
                            new W(PlayerTests.Tcdcr, 1), new W(enableB, 0x10)))
                    {
                        return "effects: frame 49 programmed " + ShowMfp(player);
                    }
                    if (Get(registers, 9) != 0)
                    {                       // the start's own silence
                        return "effects: frame 49 volume " + Get(registers, 9)
                                + ", not the silent first half";
                    }
                    if (Get(registers, 7) != (0x38 | 0xC0))
                    {
                        return "effects: frame 49 mixer " + Get(registers, 7);
                    }
                }
                else if (frame >= 50 && frame <= 52)
                {
                    if (player.Mfp.Count != 0)
                    {
                        return "effects: frame " + frame + " wrote "
                                + ShowMfp(player);
                    }
                }
                else if (frame == 53)
                {                           // the restarted SID releases
                    if (!MfpIs(player, new W(PlayerTests.Tcdcr, 0)))
                    {
                        return "effects: frame 53 wrote " + ShowMfp(player);
                    }
                    if (!registers.ContainsKey(9))
                    {
                        return "effects: frame 53 kept skipping the voice";
                    }
                }
                else if (frame == 71)
                {                           // the tune starts over: both
                    if (!MfpIs(player, new W(PlayerTests.Tacr, 0),  // claimed
                            new W(0xFFFFFA0B, 0), new W(enableA, 0x20), // timers
                            new W(PlayerTests.Tcdcr, 0),    // stop, nothing
                            new W(0xFFFFFA0D, 0),           // pending, enabled
                            new W(enableB, 0x10)))          // again
                    {
                        return "effects: the wrap wrote " + ShowMfp(player);
                    }
                    foreach (ulong vector in new ulong[] {0x134, 0x110})
                    {
                        long parked = player.Uc.Value(vector, 4);
                        if ((ulong) parked != Rig.Code
                                        + (ulong) player.Symbol("ymx_park_a")
                                && (ulong) parked != Rig.Code
                                        + (ulong) player.Symbol("ymx_park_d"))
                        {
                            return "effects: the wrap left the vector at "
                                    + vector.ToString("x") + " pointing at "
                                    + parked.ToString("x") + ", not a park entry";
                        }
                    }
                }
                else if (player.Mfp.Count != 0)
                {
                    return "effects: frame " + frame + " unexpectedly wrote "
                            + ShowMfp(player);
                }
                if (frame == 60 && Get(registers, 10) != 1)
                {
                    return "effects: frame 60 played " + Get(registers, 10)
                            + " - the ring did not keep the byte the drum"
                            + " number rode in on";
                }
            }

            // Both drums, tick by tick, by direct invocation: frame 31's on
            // Timer D (voice C), frame 48's on Timer A (voice B).
            problem = DrumTicks(player, drumD, 10, PlayerTests.Tcdcr,
                    0xFFFFFA11, 0xEF);
            if (problem.Length != 0)
            {
                return problem;
            }
            problem = DrumTicks(player,
                    Rig.Code + (ulong) player.Symbol("ymx_pcm_a"), 9,
                    PlayerTests.Tacr, 0xFFFFFA0F, 0xDF);
            if (problem.Length != 0)
            {
                return problem;
            }
            if (perf && Acc(player) != 2 * (21 + 21 + 23))
            {                               // both drums' playouts
                return "effects: the drum ticks accumulated " + Acc(player)
                        + ", not 130";
            }

            // The toggle tick: the loud half writes the volume and installs
            // the quiet half as a whole vector, and back.
            List<Player.Pair> pairs = PlayerTests.InvokeIsr(player, sidOn);
            long installed = player.Uc.Value(0x134, 4); // the A block still
            if (!PlayerTests.OnePair(pairs, 8, 10)      // holds frame 5's
                    || (ulong) installed != sidOff)     // voice A and frame
            {                                           // 25's volume
                return "effects: the loud half wrote " + PlayerTests.Show(pairs)
                        + ", vector " + installed.ToString("x");
            }
            pairs = PlayerTests.InvokeIsr(player, sidOff);
            installed = player.Uc.Value(0x134, 4);
            if (!PlayerTests.OnePair(pairs, 8, 0) || (ulong) installed != sidOn)
            {
                return "effects: the quiet half wrote " + PlayerTests.Show(pairs)
                        + ", vector " + installed.ToString("x");
            }

            // And the buzzer from frame 40: every tick rewrites the shape
            // to R13.
            pairs = PlayerTests.InvokeIsr(player,
                    Rig.Code + (ulong) player.Symbol("ymx_retrigger_a"));
            if (!PlayerTests.OnePair(pairs, 13, 11))
            {
                return "effects: the retrigger tick wrote "
                        + PlayerTests.Show(pairs);
            }
            if (perf && Acc(player) != 130 + 15 + 15 + 12)
            {
                return "effects: the ticks accumulated " + Acc(player);
            }

            // One more frame clears the raster monitor's accumulator.
            player.PlayFrame();
            if (perf && Acc(player) != 0)
            {
                return "effects: the frame did not clear the perf accumulator";
            }

            // The library's stop contract: it quiesces its claim - timers
            // stopped, their interrupt bits disabled and masked, no voice
            // skipped - and restores nothing; the host owns the machine
            // state (assumption 5).
            player.Stop();
            if ((player.Uc.Value(0xFFFFFA19, 1) & 0x0F) != 0
                    || (player.Uc.Value(0xFFFFFA1D, 1) & 0x0F) != 0)
            {
                return "effects: stop left a timer running";
            }
            if ((player.Uc.Value(0xFFFFFA07, 1) & 0x20) != 0
                    || (player.Uc.Value(0xFFFFFA13, 1) & 0x20) != 0
                    || (player.Uc.Value(0xFFFFFA09, 1) & 0x10) != 0
                    || (player.Uc.Value(0xFFFFFA15, 1) & 0x10) != 0)
            {
                return "effects: stop left its claim enabled";
            }
            for (int voice = 0; voice < 3; voice++)
            {
                if (Skipped(player, movepOpcode, writeMovep, writeSize, voice) != 2)
                {
                    return "effects: stop left voice " + voice + " muted";
                }
            }

            // Claiming is per timer channel, and a second YMX_init must hand
            // back what the first one took.
            byte[] quiet = GenYm.Ym6File(40, PlayerTests.NewValues(40));
            var reused = new Player(packed, 1, perf);
            if (reused.Init() != 0)
            {
                return "effects: init rejected the two-channel pack";
            }
            for (int i = 0; i < 32; i++)
            {                               // far enough in to be running
                reused.PlayFrame();
            }
            reused.Uc.Write(reused.File, Rig.Pack(quiet, 960, 24, true, 1));
            if (reused.Init() != 0)
            {
                return "effects: init rejected the effect-free pack";
            }
            if ((reused.Uc.Value(0xFFFFFA19, 1) & 0x0F) != 0
                    || (reused.Uc.Value(0xFFFFFA1D, 1) & 0x0F) != 0)
            {
                return "effects: re-init left an unclaimed timer running";
            }
            if ((reused.Uc.Value(0xFFFFFA07, 1) & 0x20) != 0
                    || (reused.Uc.Value(0xFFFFFA13, 1) & 0x20) != 0
                    || (reused.Uc.Value(0xFFFFFA09, 1) & 0x10) != 0
                    || (reused.Uc.Value(0xFFFFFA15, 1) & 0x10) != 0)
            {
                return "effects: re-init left an unclaimed channel enabled";
            }
            for (int i = 0; i < 20; i++)
            {
                Player.Frame frame = reused.PlayFrame();
                if (frame.Result != 0)
                {
                    return "effects: the re-inited tune returned " + frame.Result;
                }
            }

            // The -sidresume gap model, on the same tune: a fresh player
            // walks to the release and resume and must see the mask, the
            // counting-on timer, and the reload-only comeback.
            var resumed = new Player(Rig.Pack(
                    GenYm.Ym6File(frames, values, drum0, drum1), 960, 24, true, 1,
                    "-sidresume"), 1, perf);
            if (resumed.Init() != 0)
            {
                return "effects: init rejected the -sidresume pack";
            }
            for (int frame = 0; frame < 30; frame++)
            {
                Player.Frame played = resumed.PlayFrame();
                Dictionary<int, int> registers = RegisterMap(played.Writes);
                if (frame == 21)
                {
                    if (!MfpIs(resumed, new W(enableA, 0x00)))
                    {                       // masked: IER bit
                        return "effects: resume-model frame 21 wrote "
                                + ShowMfp(resumed);
                    }
                    if (!registers.ContainsKey(8))
                    {
                        return "effects: resume-model frame 21 kept the voice"
                                + " skipped";
                    }
                }
                else if (frame == 22)
                {
                    if (!MfpIs(resumed, new W(PlayerTests.Tadr, 90),
                            new W(enableA, 0x20)))
                    {
                        return "effects: resume-model frame 22 programmed "
                                + ShowMfp(resumed);
                    }
                    if (Get(registers, 8) == 0)
                    {
                        return "effects: the resume silenced a running square";
                    }
                }
                else if (frame == 27)
                {
                    if (!MfpIs(resumed, new W(enableA, 0x00)))
                    {
                        return "effects: resume-model frame 27 wrote "
                                + ShowMfp(resumed);
                    }
                }
            }

            // The monitor's color protocol: every frame paints the yellow
            // timer bar, then its own red, then puts the original back;
            // every tick paints its timer's color and restores.
            if (perf)
            {
                List<int> seen = player.Palette;
                var distinct = new HashSet<int>(seen);
                if (!distinct.SetEquals(new[] {0x770, 0x700, 0x070, 0x007, 0}))
                {
                    return "effects: the monitor painted "
                            + string.Join(",", distinct);
                }
                int bars = Count(seen, 0x770);
                if (bars != Count(seen, 0x700))
                {
                    return "effects: a timer bar without its frame band";
                }
                if (seen[^1] != 0 || Count(seen, 0)
                        != seen.Count - 2 * bars - Count(seen, 0x070)
                                - Count(seen, 0x007))
                {
                    return "effects: the monitor did not restore the background";
                }
            }
            else if (player.Palette.Count != 0)
            {
                return "effects: the monitor painted in a build without it";
            }
            return "";
        }

        /// <summary>2 when the voice's burst write is open, 0 when it is
        /// muted - the two values the old displacement trick reported.</summary>
        private static int Skipped(Player player, long movepOpcode,
                int writeMovep, int writeSize, int voice)
        {
            ulong at = Rig.Code + (ulong) player.Symbol("ymx_wB")
                    + (ulong) (writeMovep + writeSize * voice);
            long word = player.Uc.Value(at, 2);
            return word == movepOpcode ? 2 : word == 0x4E71 ? 0 : -1;
        }

        private static int Acc(Player player)
        {
            return (int) player.Uc.Value(
                    Rig.Code + (ulong) player.Symbol("ymx_perf_acc"), 2);
        }

        private static int Count(List<int> values, int wanted)
        {
            int count = 0;
            foreach (int value in values)
            {
                if (value == wanted)
                {
                    count++;
                }
            }
            return count;
        }

        /// <summary>Plays a patched drum out by direct invocation: two
        /// sample nibbles, then the marker - which parks the volume and
        /// stops the timer, nothing more. Sample 0 is 0x80, 0x40 -> nibbles
        /// 8, 4.</summary>
        public static string DrumTicks(Player player, ulong code, int register,
                ulong ctrl, ulong eoiRegister, int eoiValue)
        {
            int[] nibbles = {8, 4};
            for (int tick = 0; tick < nibbles.Length; tick++)
            {
                List<Player.Pair> pairs = PlayerTests.InvokeIsr(player, code);
                if (!PlayerTests.OnePair(pairs, register, nibbles[tick]))
                {
                    return "effects: PCM tick " + tick + " wrote "
                            + PlayerTests.Show(pairs);
                }
            }
            List<Player.Pair> marker = PlayerTests.InvokeIsr(player, code);
            if (marker.Count != 2 || marker[0].Register != register
                    || marker[0].Value != 0x80 || marker[1].Register != register
                    || marker[1].Value != 0x0D)
            {
                return "effects: the marker tick wrote " + PlayerTests.Show(marker);
            }
            int from = Math.Max(0, player.Mfp.Count - 2);
            if (player.Mfp.Count - from != 2 || player.Mfp[from].Address != ctrl
                    || player.Mfp[from].Value != 0
                    || player.Mfp[from + 1].Address != eoiRegister
                    || player.Mfp[from + 1].Value != eoiValue)
            {
                return "effects: the marker tick programmed the wrong tail";
            }
            return "";
        }

        /// <summary>ymx_assign, driven directly: every map the T stream can
        /// express must put the right timer's row into the right channel's
        /// descriptor.</summary>
        public static string CheckAssignment(Player player)
        {
            byte[][] rows = new byte[4][];
            string[] timers = {"a", "b", "c", "d"};
            for (int i = 0; i < 4; i++)
            {
                rows[i] = player.Uc.Read(
                        Rig.Code + (ulong) player.Symbol("ymx_timer_" + timers[i]),
                        18);
            }
            int[] maps = {0x1B,             // 0->D 1->C 2->B 3->A: reversed
                    0x00,                   // every channel on Timer A, which
                                            // the packer never emits but the
                    0xE4,                   // copy must still do; straight
                    Rig.YmxDefaultMap};
            foreach (int assignments in maps)
            {
                ulong stack = Rig.StackTop - 512;
                player.Uc.Write(stack, new byte[] {0x00, 0x0A, 0x00, 0x00});
                player.Uc.Set(Unicorn.SR, 0x2700);
                player.Uc.Set(Unicorn.A7, (long) stack);
                player.Uc.Set(Unicorn.D0, assignments);
                int code = player.Uc.Start(
                        Rig.Code + (ulong) player.Symbol("ymx_assign"),
                        Rig.Magic, 100_000);
                if (code != 0)
                {
                    return "assign: " + Unicorn.Error(code);
                }
                for (int channel = 0; channel < 4; channel++)
                {
                    int timer = (assignments >> (2 * channel)) & 3;
                    byte[] got = player.Uc.Read(Rig.Code
                            + (ulong) player.Symbol("ymx_desc_" + channel), 18);
                    if (!got.AsSpan().SequenceEqual(rows[timer]))
                    {
                        return string.Format("assign: map 0x{0:x2} put the wrong"
                                + " row in channel {1} (wanted timer {2})",
                                assignments, channel, timer);
                    }
                }
            }
            return "";
        }
    }
}
