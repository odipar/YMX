using System;

/// <summary>
/// The one entry point: the first argument names the tool, the rest are its
/// arguments - what `java -cp target/classes <class>` is to the Java tree,
/// `dotnet ymx.dll <tool>` is to this one. The shell scripts' -dotnet flag
/// lands here.
/// </summary>
public static class Program
{
    public static int Main(string[] args)
    {
        // Published under a tool's own name, the executable is that tool and
        // every argument is its own: `ym-to-ymx -f out.prg tune.ym` rather
        // than `ymx ym-to-ymx -f ...`. Built as the one assembly, the first
        // argument names the tool, which is what the shell scripts pass.
        string? self = Environment.ProcessPath;
        if (self != null && System.IO.Path.GetFileNameWithoutExtension(self)
                == "ym-to-ymx")
        {
            Ym6.YmToYmx.Main(args);
            return 0;
        }
        if (args.Length == 0)
        {
            Console.Error.WriteLine("usage: ymx <tool> [arguments...]\n"
                    + "tools: ym-to-ymx st4 dst4 ymx mksndh mkprg mkcores"
                    + " mkrelease setversion ymsndh play rig sweep gendata");
            return 1;
        }
        string[] rest = args[1..];
        switch (args[0])
        {
            case "st4":
                St4.St4Cli.Main(rest);
                return 0;
            case "dst4":
                St4.Dst4Cli.Main(rest);
                return 0;
            case "ym-to-ymx":
                Ym6.YmToYmx.Main(rest);
                return 0;
            case "ymx":
                Ym6.YmxCli.Main(rest);
                return 0;
            case "mksndh":
                Ymx.MkSndh.Main(rest);
                return 0;
            case "mkprg":
                Ymx.MkPrg.Main(rest);
                return 0;
            case "mkcores":
                Ymx.MkCores.Main(rest);
                return 0;
            case "mkrelease":
                Ymx.MkRelease.Main(rest);
                return 0;
            case "setversion":
                Ymx.SetVersion.Main(rest);
                return 0;
            case "ymsndh":
                Ym6.YmSndh.Main(rest);
                return 0;
            case "play":
                Ym6.Play.Main(rest);
                return 0;
            case "rig":
                Rig.PlayerTests.Main(rest);
                return 0;
            case "sweep":
                Rig.Sweep.Main(rest);
                return 0;
            case "gendata":
                Rig.GenData.Main(rest);
                return 0;
            default:
                Console.Error.WriteLine("ymx: unknown tool " + args[0]);
                return 1;
        }
    }
}
