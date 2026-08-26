using System;
using System.Collections.Generic;
using System.IO;
using System.Text.RegularExpressions;

namespace Ymx
{
    /// <summary>
    /// ymx/setversion.sh -dotnet - rewrite the version at every site
    /// that carries it, the same eight sites org.ymx.SetVersion patches:
    /// the Java, C# and 68k format constants, SPEC.md's three mentions,
    /// and the two patch constants. A site must match exactly once, so a
    /// reworded sentence fails loudly instead of being skipped, and
    /// nothing is written unless every site matched. The prose around the
    /// two constants names no version, so these eight are the whole list.
    /// </summary>
    public static class SetVersion
    {
        /// <summary>One pattern in one file; the whole match is replaced
        /// by the template, formatted with the version word and its
        /// name.</summary>
        private sealed record Site(string File, string Pattern, string Template);

        /// <summary>The release's own version: three numbers, in both
        /// trees.</summary>
        private static readonly List<Site> ReleaseSites = new()
        {
            new Site("src/main/java/org/ymx/YmxFormat.java",
                    "public static final int RELEASE_MAJOR = \\d+;",
                    "public static final int RELEASE_MAJOR = {3};"),
            new Site("src/main/java/org/ymx/YmxFormat.java",
                    "public static final int RELEASE_MINOR = \\d+;",
                    "public static final int RELEASE_MINOR = {4};"),
            new Site("src/main/java/org/ymx/YmxFormat.java",
                    "public static final int PATCH = \\d+;",
                    "public static final int PATCH = {2};"),
            new Site("dotnet/ymx/YmxFormat.cs",
                    "public const int ReleaseMajor = \\d+;",
                    "public const int ReleaseMajor = {3};"),
            new Site("dotnet/ymx/YmxFormat.cs",
                    "public const int ReleaseMinor = \\d+;",
                    "public const int ReleaseMinor = {4};"),
            new Site("dotnet/ymx/YmxFormat.cs",
                    "public const int Patch = \\d+;",
                    "public const int Patch = {2};"),
        };

        /// <summary>The format version: the word a header carries and a
        /// player checks.</summary>
        private static readonly List<Site> Sites = new()
        {
            new Site("src/main/java/org/ymx/YmxFormat.java",
                    "public static final int VERSION = 0x[0-9A-Fa-f]{4};",
                    "public static final int VERSION = 0x{0:X4};"),
            new Site("dotnet/ymx/YmxFormat.cs",
                    "public const int Version = 0x[0-9A-Fa-f]{4};",
                    "public const int Version = 0x{0:X4};"),
            new Site("68k/YMX.S",
                    "YMX_VERSION     equ     \\$[0-9A-F]{4}",
                    "YMX_VERSION     equ     ${0:X4}"),
            new Site("doc/SPEC.md",
                    "Version \\d+\\.\\d+\\. Big-endian throughout\\.",
                    "Version {1}. Big-endian throughout."),
            new Site("doc/SPEC.md",
                    "format version, the major byte then the minor"
                    + " - \\*\\*\\$[0-9A-F]{4}\\*\\*, version \\d+\\.\\d+",
                    "format version, the major byte then the minor"
                    + " - **${0:X4}**, version {1}"),
            new Site("doc/SPEC.md",
                    "the version is \\$[0-9A-F]{4} - \\d+\\.\\d+;",
                    "the version is ${0:X4} - {1};"),
        };

        internal const string UsageText =
                "usage: setversion.sh -format MAJOR.MINOR\n"
                + "       setversion.sh -release MAJOR.MINOR[.PATCH]";

        public static void Main(string[] args)
        {
            if (args.Length != 2)
            {
                throw Tools.Fail(UsageText);
            }
            bool format = args[0] == "-format";
            bool release = args[0] == "-release";
            if (!format && !release)
            {
                throw Tools.Fail(UsageText);
            }
            try
            {
                Set(Tools.Repo(), args[1], format);
            }
            catch (ArgumentException e)
            {
                throw Tools.Fail(e.Message);
            }
        }

        /// <summary>
        /// Rewrites one version under the repo, given as
        /// MAJOR.MINOR[.PATCH] - the patch defaults to 0. The flag picks
        /// which: the format version a header carries and a player checks,
        /// or the binaries' own. The two are set apart because moving the
        /// format one is a break for every tune already packed, and moving
        /// the release one is not. Every site is matched before the first
        /// write, so a refusal leaves every file as it was.
        /// </summary>
        public static void Set(string repo, string version, bool format)
        {
            if (!Regex.IsMatch(version,
                    "^[0-9]{1,3}\\.[0-9]{1,3}(\\.[0-9]{1,4})?\\z"))
            {
                throw new ArgumentException(UsageText);
            }
            string[] parts = version.Split('.');
            int major = int.Parse(parts[0]);
            int minor = int.Parse(parts[1]);
            int patch = parts.Length > 2 ? int.Parse(parts[2]) : 0;
            if (format && parts.Length > 2)
            {
                throw new ArgumentException("setversion: the format version"
                        + " is MAJOR.MINOR - a patch is the release's, and"
                        + " -release sets it");
            }
            if (major > 255 || minor > 255)
            {
                throw new ArgumentException("setversion: each half of a"
                        + " version is a byte, 0 to 255");
            }
            int word = (major << 8) | minor;
            string name = major + "." + minor;

            var texts = new Dictionary<string, string>();
            var order = new List<string>();
            foreach (Site site in (format ? Sites : ReleaseSites))
            {
                if (!texts.TryGetValue(site.File, out string? text))
                {
                    text = Read(Path.Combine(repo, site.File));
                    texts[site.File] = text;
                    order.Add(site.File);
                }
                MatchCollection found = Regex.Matches(text, site.Pattern);
                if (found.Count != 1)
                {
                    throw new ArgumentException("setversion: " + site.File
                            + " does not carry exactly one match of \""
                            + site.Pattern + "\" - the site has moved");
                }
                texts[site.File] = text.Replace(found[0].Value,
                        string.Format(site.Template, word, name, patch,
                                major, minor));
            }
            foreach (string file in order)
            {
                try
                {
                    File.WriteAllText(Path.Combine(repo, file), texts[file]);
                }
                catch (IOException e)
                {
                    throw new ArgumentException("setversion: " + file + ": "
                            + e.Message);
                }
                Console.WriteLine(file + ": " + (format
                        ? "format version " + name
                        : "release " + name + "." + patch));
            }
        }

        private static string Read(string path)
        {
            try
            {
                return File.ReadAllText(path);
            }
            catch (IOException e)
            {
                throw new ArgumentException("setversion: " + path + ": "
                        + e.Message);
            }
        }
    }
}
