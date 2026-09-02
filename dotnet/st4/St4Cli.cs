// ZX1 by Einar Saukas; ST4 and this C# port by Claude (Anthropic's Claude
// Code) under Robbert van Dalen's direction. See LICENSE for the terms.

using System.Globalization;

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;

namespace St4;

/// <summary>Command-line ST4 packer, the port of the Java <c>St4</c>.</summary>
/// <remarks>
/// <c>-k1</c> is ZX1's unit size and packs to within a few percent of jx1,
/// which a test holds; <c>-k2</c> and <c>-k4</c> trade ratio for a decoder
/// that runs half or a quarter as many operations.
/// </remarks>
public static class St4Cli
{
    /// <summary>Runs the packer command.</summary>
    /// <param name="args">
    /// Arguments after the executable name. Syntax:
    /// <c>nt4 [-f] [-c[S]] [-kK] [-mN] [-lN] [-rR] input [output.st4]</c>.
    /// </param>
    /// <returns>Zero on success; one after a user-facing argument or file error.</returns>
    /// <exception cref="ArgumentNullException"><paramref name="args"/> is null.</exception>
    /// <summary>The dispatcher's entry: Run, with its exit code handed to the process.</summary>
    public static void Main(string[] args)
    {
        int code = Run(args);
        if (code != 0)
        {
            Environment.Exit(code);
        }
    }

    public static int Run(string[] args)
    {
        ArgumentNullException.ThrowIfNull(args);
        Console.WriteLine("ST4: aligned split-stream packer v7.0 by Robbert van Dalen, "
            + "based on ZX1 v1.5 by Einar Saukas");

        int unit = 1;
        int offsetLimit = St4Format.MaxOffset;
        int maxOpLength = St4Format.MaxOp;
        int repeatIndex = -1;
        bool copies = false;
        double search = 0;
        bool forcedMode = false;
        int index = 0;
        for (; index < args.Length
            && args[index].StartsWith('-'); index++)
        {
            switch (args[index])
            {
                case "-f":
                    forcedMode = true;
                    break;
                case "-c":
                    copies = true;
                    break;
                default:
                    if (args[index].StartsWith("-c", StringComparison.Ordinal))
                    {
                        copies = true;
                        search = Cli.ParseNumber(args[index][2..]);
                        if (search <= 0)
                        {
                            return Cli.Error($"Invalid parameter value {args[index][2..]}");
                        }
                        break;
                    }
                    if (args[index].StartsWith("-r", StringComparison.Ordinal))
                    {
                        // An index, not a count: -r0 is valid and loops it all.
                        repeatIndex = Cli.ParseIndex(args[index][2..]);
                        if (repeatIndex < 0)
                        {
                            return Cli.Error($"Invalid parameter value {args[index][2..]}");
                        }
                        break;
                    }
                    int value = Cli.ParseNumber(args[index][2..]);
                    if (args[index].StartsWith("-k", StringComparison.Ordinal))
                    {
                        unit = value;
                    }
                    else if (args[index].StartsWith("-m", StringComparison.Ordinal))
                    {
                        offsetLimit = value;
                    }
                    else if (args[index].StartsWith("-l", StringComparison.Ordinal))
                    {
                        maxOpLength = value;
                    }
                    else
                    {
                        return Cli.Error($"Invalid parameter {args[index]}");
                    }
                    if (value <= 0)
                    {
                        return Cli.Error($"Invalid parameter value {args[index][2..]}");
                    }
                    break;
            }
        }

        string outputName;
        if (args.Length == index + 1)
        {
            outputName = args[index] + ".st4";
        }
        else if (args.Length == index + 2)
        {
            outputName = args[index + 1];
        }
        else
        {
            return Cli.Usage(
                "Usage: nt4 [-f] [-c[S]] [-kK] [-mN] [-lN] [-rR] input [output.st4]\n"
                + "  -f      Force overwrite of output file\n"
                + "  -c      Let a match beyond the -m window copy from the\n"
                + "          literal stream; needs a decoder built with copies\n"
                + "  -cS     The same, searching for S seconds for a better parse\n"
                + "  -kK     Unit size: 1, 2 or 4 bytes (default 1). Lengths and\n"
                + "          offsets count units, so the output is padded to a\n"
                + "          whole number of them\n"
                + "  -mN     Limit back-references to N units\n"
                + "  -lN     Split matches so no operation exceeds N units\n"
                + "  -rR     Loop: after the last unit, the output continues\n"
                + "          from unit R, forever");
        }
        string inputName = args[index];

        string problem = St4Format.CheckUnit(unit);
        if (problem.Length != 0)
        {
            return Cli.Error(problem);
        }
        // A word offset is stored scaled to bytes, so the window is a byte
        // figure: 32512 units at k=4 would not fit the word.
        if (offsetLimit > St4Format.MaxOffsetUnits(unit))
        {
            offsetLimit = St4Format.MaxOffsetUnits(unit);
        }

        byte[] input;
        try
        {
            input = File.ReadAllBytes(inputName);
        }
        catch (Exception exception) when (Cli.IsFileException(exception))
        {
            return Cli.Error($"Cannot access input file {inputName}");
        }
        if (input.Length == 0)
        {
            return Cli.Error($"Empty input file {inputName}");
        }
        if (!forcedMode && Path.Exists(outputName))
        {
            return Cli.Error($"Already existing output file {outputName}");
        }

        int[] units = Units.Split(input, unit);
        if (repeatIndex >= units.Length)
        {
            return Cli.Error($"-r{repeatIndex} is not a unit of the input, which is "
                + $"{units.Length} units");
        }
        St4Compressor.Result result;
        int window = offsetLimit;
        if (repeatIndex >= 0 && units.Length - repeatIndex > offsetLimit)
        {
            // The loop is longer than the window, so no match reaches across
            // it and the caller replays the stream from the state it saved at
            // the loop point. The loop is parsed on its own, so every pass
            // sees the same history.
            int[] intro = units[..repeatIndex];
            int[] loop = units[repeatIndex..];
            result = St4Compressor.CompressRewinding(
                intro.Length == 0 ? null
                    : Parse(intro, unit, offsetLimit, maxOpLength, copies, search),
                Parse(loop, unit, offsetLimit, maxOpLength, copies, search),
                units, unit, maxOpLength, repeatIndex, window);
        }
        else
        {
            // The loop fits the window: the end is an endless match back to
            // the loop point.
            result = St4Compressor.Compress(
                Parse(units, unit, offsetLimit, maxOpLength, copies, search), units, unit,
                maxOpLength, repeatIndex, window);
        }

        try
        {
            File.WriteAllBytes(outputName, Container(result));
        }
        catch (Exception exception) when (Cli.IsFileException(exception))
        {
            return Cli.Error($"Cannot write output file {outputName}");
        }

        int padded = Units.PaddedLength(input.Length, unit);
        Console.WriteLine(string.Create(CultureInfo.InvariantCulture,
            $"Packed {input.Length} bytes{(padded == input.Length ? "" : $" padded to {padded}")} "
            + $"into {result.PackedSize} ({100.0 * result.PackedSize / input.Length:F1}%): "
            + $"A {result.Control.Length}, B {result.Literal.Length}, "
            + $"C {result.ByteOffsets.Length}, D {result.WordOffsets.Length}, "
            + $"{result.Operations} operations"
            + $"{(result.Copies == 0 ? "" : $", {result.Copies} copies from the literal stream")}"
            + $"{(repeatIndex < 0 ? "" : $", loops from unit {repeatIndex}")}"
            + $"{(result.RewindIndex < 0 ? "" : " by rewind")}"));
        if (result.RewindIndex >= 0)
        {
            Console.WriteLine($"The loop is longer than the -m{offsetLimit} window, so the "
                + $"decoder cannot loop it alone: save its state at unit {repeatIndex} and "
                + $"restore it at unit {units.Length}, every pass");
        }
        if (result.LongestOp > maxOpLength)
        {
            Console.WriteLine(
                $"Warning: longest operation is {result.LongestOp} units, over the "
                + $"-l{maxOpLength} limit: a literal run, which the format cannot split");
        }
        return 0;
    }

    /// <summary>
    /// The parse: the event-driven optimizer, or with <c>-c</c> the opening
    /// passes of the search that copies from the literal stream, and with
    /// seconds the search from there.
    /// </summary>
    private static St4Block Parse(int[] units, int unit, int window, int maxOpLength, bool copies,
                               double seconds)
    {
        if (!copies)
        {
            return St4EventOptimizer.Optimize(units, unit, window);
        }
        return St4LiteralCopySearch.Optimize(units, unit, window, maxOpLength, seconds, seconds > 0);
    }

    /// <summary>
    /// Twenty-eight bytes of header, then A, B, C and D, each on a long
    /// boundary. No length is stored: a stream runs to the next, and the last
    /// to the end of the file.
    /// </summary>
    /// <remarks>Public because other formats embed containers, many at once.</remarks>
    /// <param name="result">The four streams to lay out.</param>
    /// <returns>The complete container, header first.</returns>
    /// <exception cref="ArgumentNullException"><paramref name="result"/> is null.</exception>
    public static byte[] Container(St4Compressor.Result result)
    {
        ArgumentNullException.ThrowIfNull(result);
        int controlAt = St4Format.HeaderSize;                  // already a multiple of 4
        int literalAt = Align(controlAt + result.Control.Length);
        int byteAt = Align(literalAt + result.Literal.Length);
        int wordAt = Align(byteAt + result.ByteOffsets.Length);
        byte[] file = new byte[wordAt + result.WordOffsets.Length];

        PutLong(file, St4Format.OffsetSignature, St4Format.Signature(result.Unit));
        PutLong(file, St4Format.OffsetSize, result.PaddedSize);
        PutLong(file, St4Format.OffsetLiteral, literalAt);
        PutLong(file, St4Format.OffsetByteOffsets, byteAt);
        PutLong(file, St4Format.OffsetWordOffsets, wordAt);
        PutLong(file, St4Format.OffsetRewind, result.RewindIndex < 0
            ? St4Format.NoRewind : result.RewindIndex * result.Unit);
        PutLong(file, St4Format.OffsetWindow, result.Window);
        result.Control.CopyTo(file, controlAt);
        result.Literal.CopyTo(file, literalAt);
        result.ByteOffsets.CopyTo(file, byteAt);
        result.WordOffsets.CopyTo(file, wordAt);
        return file;
    }

    private static int Align(int at) => at + ((-at) & 3);

    private static void PutWord(byte[] file, int at, int value)
    {
        file[at] = unchecked((byte)(value >>> 8));
        file[at + 1] = unchecked((byte)value);
    }

    private static void PutLong(byte[] file, int at, int value)
    {
        PutWord(file, at, value >>> 16);
        PutWord(file, at + 2, value);
    }
}
