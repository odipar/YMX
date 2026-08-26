using System;
using System.IO;

namespace St4
{
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
                    throw Error("Invalid parameter " + args[i]);
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
                    throw Error("Cannot infer output filename");
                }
            }
            else if (args.Length == i + 2)
            {
                inputName = args[i];
                outputName = args[i + 1];
            }
            else
            {
                Usage("Usage: dst4 [-f] input.st4 [output]\n"
                        + "  -f      Force overwrite of output file\n"
                        + "The output is padded to a whole number of units, as the format stores it.");
                return;
            }

            byte[] file;
            try
            {
                file = File.ReadAllBytes(inputName);
            }
            catch (Exception e) when (e is IOException
                    || e is UnauthorizedAccessException)
            {
                throw Error("Cannot access input file " + inputName);
            }

            if (!forcedMode && File.Exists(outputName))
            {
                throw Error("Already existing output file " + outputName);
            }

            St4Format.Container container;
            try
            {
                container = St4Format.Read(file);
            }
            catch (ArgumentException e)
            {
                throw Error(e.Message + ": " + inputName);
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
                throw Error("Corrupted or truncated ST4 data in " + inputName
                        + ": " + e.Message);
            }

            try
            {
                File.WriteAllBytes(outputName, output);
            }
            catch (Exception e) when (e is IOException
                    || e is UnauthorizedAccessException)
            {
                throw Error("Cannot write output file " + outputName);
            }

            Console.WriteLine($"File decompressed from {file.Length} to"
                    + $" {output.Length} bytes, k={container.Unit}"
                    + (container.Unit == 1 ? "" : " (a whole number of units)") + "!");
        }

        private static Exception Error(string message)
        {
            Console.Error.WriteLine("Error: " + message);
            Environment.Exit(1);
            throw new AssertionException("unreachable");
        }

        private static void Usage(string text)
        {
            Console.Error.WriteLine(text);
            Environment.Exit(1);
        }
    }
}
