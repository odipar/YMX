using System;
using System.Collections.Generic;
using System.IO;
using System.Text.RegularExpressions;

namespace Ymx
{
    /// <summary>
    /// ymx/setversion.sh -dotnet - rewrite the format version at every
    /// site that carries it, the same sites org.ymx.SetVersion patches:
    /// the Java, C# and 68k constants and SPEC.md's three mentions. A
    /// site must match exactly once, so a reworded sentence fails loudly
    /// instead of being skipped - and nothing is written unless every
    /// site matched.
    /// </summary>
    public static class SetVersion
    {
        /// <summary>One pattern in one file; the whole match is replaced
        /// by the template, formatted with the version word and its
        /// name.</summary>
        private sealed record Site(string File, string Pattern, string Template);

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

        public static void Main(string[] args)
        {
            if (args.Length != 1)
            {
                throw Tools.Fail("usage: setversion.sh MAJOR.MINOR");
            }
            try
            {
                Set(Tools.Repo(), args[0]);
            }
            catch (ArgumentException e)
            {
                throw Tools.Fail(e.Message);
            }
        }

        /// <summary>Rewrites every site under the repo to the version,
        /// given as MAJOR.MINOR. Every site is matched before the first
        /// write, so a refusal leaves every file as it was.</summary>
        public static void Set(string repo, string version)
        {
            if (!Regex.IsMatch(version, "^[0-9]{1,3}\\.[0-9]{1,3}\\z"))
            {
                throw new ArgumentException("usage: setversion.sh MAJOR.MINOR");
            }
            string[] halves = version.Split('.');
            int major = int.Parse(halves[0]);
            int minor = int.Parse(halves[1]);
            if (major > 255 || minor > 255)
            {
                throw new ArgumentException(
                        "setversion: each half is a byte, 0 to 255");
            }
            int word = (major << 8) | minor;
            string name = major + "." + minor;

            var texts = new Dictionary<string, string>();
            var order = new List<string>();
            foreach (Site site in Sites)
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
                        string.Format(site.Template, word, name));
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
                Console.WriteLine(file + ": version " + name);
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
