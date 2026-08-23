using System;
using System.Globalization;
using System.IO;

namespace St4
{
    /// <summary>Command-line ST4 packer, ported from org.st4.St4.</summary>
    public static class St4Cli
    {
        public static void Main(string[] args)
        {
            Console.WriteLine("ST4: aligned split-stream packer v4.0 by Robbert"
                    + " van Dalen, based on ZX1 v1.5 by Einar Saukas");

            int unit = 1;
            int offsetLimit = St4Format.MaxOffset;
            int maxOpLength = St4Format.MaxOp;
            bool forcedMode = false;
            int i = 0;
            for (; i < args.Length && args[i].StartsWith('-'); i++)
            {
                if (args[i] == "-f")
                {
                    forcedMode = true;
                }
                else if (args[i].StartsWith("-k"))
                {
                    unit = ParseNumber(args[i][2..]);
                }
                else if (args[i].StartsWith("-m"))
                {
                    offsetLimit = ParseNumber(args[i][2..]);
                }
                else if (args[i].StartsWith("-l"))
                {
                    maxOpLength = ParseNumber(args[i][2..]);
                }
                else
                {
                    throw Error("Invalid parameter " + args[i]);
                }
            }

            string outputName;
            if (args.Length == i + 1)
            {
                outputName = args[i] + ".st4";
            }
            else if (args.Length == i + 2)
            {
                outputName = args[i + 1];
            }
            else
            {
                Usage("Usage: st4 [-f] [-kK] [-mN] [-lN] input [output.st4]\n"
                        + "  -f      Force overwrite of output file\n"
                        + "  -kK     Unit size: 1, 2 or 4 bytes (default 1). Lengths and\n"
                        + "          offsets count units, so the output is padded to a\n"
                        + "          whole number of them\n"
                        + "  -mN     Limit back-references to N units\n"
                        + "  -lN     Split matches so no operation exceeds N units");
                return;
            }

            string problem = St4Format.CheckUnit(unit);
            if (problem.Length != 0)
            {
                throw Error(problem);
            }
            // A word offset is stored pre-scaled, so the window is a byte
            // figure: reaching 32512 units at k=4 would not fit its word.
            if (offsetLimit > St4Format.MaxOffsetUnits(unit))
            {
                offsetLimit = St4Format.MaxOffsetUnits(unit);
            }

            byte[] input;
            try
            {
                input = File.ReadAllBytes(args[i]);
            }
            catch (IOException)
            {
                throw Error("Cannot access input file " + args[i]);
            }
            if (input.Length == 0)
            {
                throw Error("Empty input file " + args[i]);
            }

            if (!forcedMode && File.Exists(outputName))
            {
                throw Error("Already existing output file " + outputName);
            }

            int[] units = Units.Split(input, unit);
            St4Compressor.Result result = St4Compressor.Compress(
                    St4EventOptimizer.Optimize(units, unit, offsetLimit, true),
                    units, unit, maxOpLength);

            try
            {
                File.WriteAllBytes(outputName, Container(result));
            }
            catch (IOException)
            {
                throw Error("Cannot write output file " + outputName);
            }

            int padded = Units.PaddedLength(input.Length, unit);
            Console.WriteLine(string.Format(CultureInfo.InvariantCulture,
                    "Packed {0} bytes{1} into {2} ({3:F1}%): A {4}, B {5}, C {6},"
                    + " D {7}, {8} operations",
                    input.Length, padded == input.Length ? "" : " padded to " + padded,
                    result.PackedSize(), 100.0 * result.PackedSize() / input.Length,
                    result.Control.Length, result.Literal.Length,
                    result.ByteOffsets.Length, result.WordOffsets.Length,
                    result.Operations));
            if (result.LongestOp > maxOpLength)
            {
                Console.WriteLine($"Warning: longest operation is {result.LongestOp}"
                        + $" units, over the -l{maxOpLength} limit: a literal run,"
                        + " which the format cannot split");
            }
        }

        /// <summary>Twenty bytes of header, then A, B, C and D in order, each
        /// starting on a long boundary; a container is also how other formats
        /// embed an ST4 stream.</summary>
        public static byte[] Container(St4Compressor.Result result)
        {
            int controlAt = St4Format.HeaderSize;   // already a multiple of 4
            int literalAt = Align(controlAt + result.Control.Length);
            int byteAt = Align(literalAt + result.Literal.Length);
            int wordAt = Align(byteAt + result.ByteOffsets.Length);
            byte[] file = new byte[wordAt + result.WordOffsets.Length];

            PutLong(file, St4Format.OffsetSignature, St4Format.Signature(result.Unit));
            PutLong(file, St4Format.OffsetSize, result.PaddedSize);
            PutLong(file, St4Format.OffsetLiteral, literalAt);
            PutLong(file, St4Format.OffsetByteOffsets, byteAt);
            PutLong(file, St4Format.OffsetWordOffsets, wordAt);
            Array.Copy(result.Control, 0, file, controlAt, result.Control.Length);
            Array.Copy(result.Literal, 0, file, literalAt, result.Literal.Length);
            Array.Copy(result.ByteOffsets, 0, file, byteAt, result.ByteOffsets.Length);
            Array.Copy(result.WordOffsets, 0, file, wordAt, result.WordOffsets.Length);
            return file;
        }

        private static int Align(int at)
        {
            return at + ((-at) & 3);
        }

        private static void PutWord(byte[] file, int at, int value)
        {
            file[at] = (byte) (value >>> 8);
            file[at + 1] = (byte) value;
        }

        private static void PutLong(byte[] file, int at, int value)
        {
            PutWord(file, at, value >>> 16);
            PutWord(file, at + 2, value);
        }

        internal static Exception Error(string message)
        {
            Console.Error.WriteLine("Error: " + message);
            Environment.Exit(1);
            throw new AssertionException("unreachable");
        }

        internal static void Usage(string text)
        {
            Console.Error.WriteLine(text);
            Environment.Exit(1);
        }

        internal static int ParseNumber(string argument)
        {
            if (!int.TryParse(argument, NumberStyles.Integer,
                    CultureInfo.InvariantCulture, out int value) || value <= 0)
            {
                throw Error("Invalid parameter value " + argument);
            }
            return value;
        }
    }

    /// <summary>Command-line ST4 unpacker, ported from org.st4.Dst4.</summary>
    public static class Dst4Cli
    {
        public static void Main(string[] args)
        {
            Console.WriteLine("DST4: aligned split-stream unpacker v4.0 by Robbert"
                    + " van Dalen, based on ZX1 v1.5 by Einar Saukas");

            bool forcedMode = false;
            int i = 0;
            for (; i < args.Length && args[i].StartsWith('-'); i++)
            {
                if (args[i] == "-f")
                {
                    forcedMode = true;
                }
                else
                {
                    throw St4Cli.Error("Invalid parameter " + args[i]);
                }
            }

            string inputName;
            string outputName;
            if (args.Length == i + 1)
            {
                inputName = args[i];
                if (inputName.Length > 4 && inputName.EndsWith(".st4"))
                {
                    outputName = inputName[..^4];
                }
                else
                {
                    throw St4Cli.Error("Cannot infer output filename");
                }
            }
            else if (args.Length == i + 2)
            {
                inputName = args[i];
                outputName = args[i + 1];
            }
            else
            {
                St4Cli.Usage("Usage: dst4 [-f] input.st4 [output]\n"
                        + "  -f      Force overwrite of output file\n"
                        + "The output is padded to a whole number of units, as the format stores it.");
                return;
            }

            byte[] file;
            try
            {
                file = File.ReadAllBytes(inputName);
            }
            catch (IOException)
            {
                throw St4Cli.Error("Cannot access input file " + inputName);
            }

            if (!forcedMode && File.Exists(outputName))
            {
                throw St4Cli.Error("Already existing output file " + outputName);
            }

            St4Format.Container container;
            try
            {
                container = St4Format.Read(file);
            }
            catch (ArgumentException e)
            {
                throw St4Cli.Error(e.Message + ": " + inputName);
            }

            byte[] output;
            try
            {
                output = St4Decompressor.Decompress(container.Control,
                        container.Literal, container.ByteOffsets,
                        container.WordOffsets, container.Unit, container.Size);
            }
            catch (Exception e) when (e is AssertionException
                    || e is IndexOutOfRangeException)
            {
                // A malformed stream trips a descriptive check; the decoder
                // does not validate its input, so report rather than continue
                // on corrupt data.
                throw St4Cli.Error("Corrupted or truncated ST4 data in " + inputName
                        + ": " + e.Message);
            }

            try
            {
                File.WriteAllBytes(outputName, output);
            }
            catch (IOException)
            {
                throw St4Cli.Error("Cannot write output file " + outputName);
            }

            Console.WriteLine($"File decompressed from {file.Length} to"
                    + $" {output.Length} bytes, k={container.Unit}"
                    + (container.Unit == 1 ? "" : " (a whole number of units)") + "!");
        }
    }
}
