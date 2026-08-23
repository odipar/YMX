using System;

/// <summary>
/// The Java tree's assertions, always on: the tools run with -ea there, so
/// malformed-input validation is part of their behaviour, and the C# tree
/// keeps it without a build flag.
/// </summary>
public sealed class AssertionException : Exception
{
    public AssertionException(string message) : base(message) { }
}

public static class Check
{
    public static void That(bool condition, string message)
    {
        if (!condition)
        {
            throw new AssertionException(message);
        }
    }
}
