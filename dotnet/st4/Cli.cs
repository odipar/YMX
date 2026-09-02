// ZX1 by Einar Saukas; ST4 and this C# port by Claude (Anthropic's Claude
// Code) under Robbert van Dalen's direction. See LICENSE for the terms.

using System.Globalization;

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;

namespace St4;

/// <summary>Shared command-line parsing and error-reporting helpers.</summary>
internal static class Cli
{
    /// <summary>Writes an error message as the Java tools do and returns the failure exit code.</summary>
    internal static int Error(string message)
    {
        Console.Error.WriteLine($"Error: {message}");
        return 1;
    }

    /// <summary>Writes usage text and returns the failure exit code.</summary>
    internal static int Usage(string text)
    {
        Console.Error.WriteLine(text);
        return 1;
    }

    /// <summary>
    /// Parses a signed decimal integer. An invalid or overflowing value
    /// becomes zero, which callers reject.
    /// </summary>
    internal static int ParseNumber(string value) =>
        int.TryParse(value, NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture,
            out int number) ? number : 0;

    /// <summary>
    /// Parses an unsigned decimal index, where zero is a value: -r0 loops the
    /// whole stream. An invalid value becomes -1, which callers reject.
    /// </summary>
    internal static int ParseIndex(string value) =>
        int.TryParse(value, NumberStyles.None, CultureInfo.InvariantCulture,
            out int number) ? number : -1;

    /// <summary>Whether an exception is a file error, reported without a stack trace.</summary>
    internal static bool IsFileException(Exception exception) =>
        exception is IOException or UnauthorizedAccessException or ArgumentException
            or NotSupportedException;
}
