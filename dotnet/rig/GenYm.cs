using System;
using System.Collections.Generic;
using System.IO;
using System.Text;

namespace Rig
{
    /// <summary>
    /// Synthetic YM6 tunes for the rig, ported from the Java rig's GenYm:
    /// registers that behave like real chip data, every YM6 effect bit set
    /// in the registers that carry them, and the expectations computed
    /// independently of both the packer and the player.
    /// </summary>
    public static class GenYm
    {
        public const int YmRegisters = 16;          // R0..R15 in the file
        public const int PlayRegisters = 14;        // R0..R13 reach the chip
        public const int NoEnvelopeChange = 0xFF;   // R13: leave the envelope
        public const int PortBits = 0xC0;           // R7 bits the ST forces on

        // Bits the YM2149 uses; everything else is effect data.
        public static readonly int[] Mask = {0xFF, 0x0F, 0xFF, 0x0F, 0xFF, 0x0F,
                0x1F, 0x3F, 0x1F, 0x1F, 0x1F, 0xFF, 0xFF, 0x0F};

        /// <summary>A tiny LCG, the rig's own: one sequence on every host.</summary>
        private sealed class Random
        {
            private int state = 12345;

            internal int Next(int bound)
            {
                state = unchecked(state * 1103515245 + 12345) & 0x7FFFFFFF;
                return (state >>> 8) % bound;
            }
        }

        /// <summary>Raw YM6 register vectors, effect bits and all:
        /// registers[r][frame].</summary>
        public static byte[][] Registers(int frames)
        {
            var random = new Random();
            byte[][] values = new byte[YmRegisters][];
            for (int r = 0; r < YmRegisters; r++)
            {
                values[r] = new byte[frames];
            }
            int[] period = {0, 0, 0};
            int[] volume = {15, 12, 9};
            for (int frame = 0; frame < frames; frame++)
            {
                for (int voice = 0; voice < 3; voice++)
                {
                    if (frame % (7 + voice * 3) == 0)
                    {
                        period[voice] = 40 + random.Next(3000);
                        volume[voice] = 15;
                    }
                    else if (volume[voice] > 0 && frame % 4 == 0)
                    {
                        volume[voice]--;
                    }
                    values[voice * 2][frame] = (byte) period[voice];
                    values[voice * 2 + 1][frame] = (byte) (period[voice] >> 8);
                    values[8 + voice][frame] = (byte) volume[voice];
                }
                values[6][frame] = (byte) (frame % 32);
                values[7][frame] = (byte) (0x38 | (frame % 8));
                values[11][frame] = (byte) (frame * 3);
                values[12][frame] = (byte) (frame / 64);
                values[13][frame] = (byte) (frame % 50 == 0 ? 0x0A
                        : NoEnvelopeChange);

                values[1][frame] |= 0x30;       // effect 1: voice set, TP=0 -
                values[3][frame] |= 0xC0;       // inert, dropped at pack time,
                values[7][frame] |= 0xC0;       // so the checksum stays exact
                values[8][frame] |= 0x20;       // per-voice effect flags
                values[9][frame] |= 0x40;
                values[10][frame] |= 0x80;
                values[14][frame] = (byte) random.Next(256);
                values[15][frame] = (byte) random.Next(256);
            }
            return values;
        }

        /// <summary>What a plain YM2149 receives: the fourteen streams the
        /// packer writes.</summary>
        public static int[][] Masked(int frames, byte[][] source)
        {
            int[][] masked = new int[PlayRegisters][];
            for (int register = 0; register < PlayRegisters; register++)
            {
                masked[register] = new int[frames];
                for (int frame = 0; frame < frames; frame++)
                {
                    int value = source[register][frame];
                    masked[register][frame] =
                            register == 13 && value == NoEnvelopeChange
                            ? NoEnvelopeChange : value & Mask[register];
                }
            }
            return masked;
        }

        /// <summary>Which frame of the tune each played frame shows: a tune
        /// that starts over runs 0..O-1 once and then L..O-1 again and again,
        /// one that plays once stops at O-1.</summary>
        public static int[] FrameOrder(int frames, int loopFrame, bool loops,
                int count)
        {
            var order = new List<int>();
            int frame = 0;
            for (int i = 0; i < count; i++)
            {
                order.Add(frame);
                frame++;
                if (frame >= frames)
                {
                    if (!loops)
                    {
                        break;
                    }
                    frame = loopFrame;
                }
            }
            return order.ToArray();
        }

        /// <summary>One played frame's outcome: the chip's fourteen
        /// registers after it, and whether R13 was written.</summary>
        public sealed record ChipState(int[] Registers, bool EnvelopeWritten);

        /// <summary>What the sound chip must hold after each played frame. A
        /// player may skip a register whose value has not changed, so state,
        /// not the write sequence, has to match.</summary>
        public static List<ChipState> ChipStates(int frames, byte[][] source,
                bool loops, int loopFrame, int count)
        {
            int[][] vectors = Masked(frames, source);
            int[] state = new int[PlayRegisters];
            var history = new List<ChipState>();
            foreach (int frame in FrameOrder(frames, loopFrame, loops, count))
            {
                bool envelopeWritten = false;
                for (int register = 0; register < PlayRegisters; register++)
                {
                    int value = vectors[register][frame];
                    if (register == 7)
                    {
                        value |= PortBits;
                    }
                    if (register == 13)
                    {
                        if (value == NoEnvelopeChange)
                        {
                            continue;
                        }
                        envelopeWritten = true;
                    }
                    state[register] = value;
                }
                history.Add(new ChipState((int[]) state.Clone(), envelopeWritten));
            }
            return history;
        }

        /// <summary>A complete, unpacked YM6! file - what the YMX packer
        /// takes as input; the drums are 8-bit digidrum samples stored the
        /// way a YM6 file stores them.</summary>
        public static byte[] Ym6File(int frames, byte[][] source,
                params byte[][] drums)
        {
            return Ym6File(frames, 0, source, drums);
        }

        /// <summary>The same, with the frame the header sends its own player
        /// back to - what the packer answers for when it decides the file's
        /// L.</summary>
        public static byte[] Ym6File(int frames, int loopFrame, byte[][] source,
                params byte[][] drums)
        {
            var file = new MemoryStream();
            file.Write(Encoding.ASCII.GetBytes("YM6!LeOnArD!"));
            WriteLong(file, frames);
            WriteLong(file, 1);                 // interleaved
            WriteWord(file, drums.Length);      // digidrums
            WriteLong(file, 2000000);           // master clock
            WriteWord(file, 50);                // player rate
            WriteLong(file, loopFrame);         // loop frame
            WriteWord(file, 0);                 // additional data size
            foreach (byte[] drum in drums)
            {
                WriteLong(file, drum.Length);
                file.Write(drum);
            }
            file.Write(Encoding.ASCII.GetBytes(
                    "Synthetic\0Test\0Generated by the rig\0"));
            foreach (byte[] vector in source)
            {
                file.Write(vector, 0, frames);
            }
            file.Write(Encoding.ASCII.GetBytes("End!"));
            return file.ToArray();
        }

        private static void WriteWord(MemoryStream file, int value)
        {
            file.WriteByte((byte) (value >>> 8));
            file.WriteByte((byte) value);
        }

        private static void WriteLong(MemoryStream file, int value)
        {
            WriteWord(file, value >>> 16);
            WriteWord(file, value);
        }
    }
}
