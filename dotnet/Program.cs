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
        if (args.Length == 0)
        {
            Console.Error.WriteLine("usage: ymx <tool> [arguments...]\n"
                    + "tools: st4 dst4 ymx ymr mksndh mkprg mkcores mkrelease"
                    + " ymsndh play ymrplay rig sweep ymrsweep gendata");
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
            case "ymx":
                Ym6.YmxCli.Main(rest);
                return 0;
            case "ymr":
                Ymr.YmrCli.Main(rest);
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
            case "ymsndh":
                Ym6.YmSndh.Main(rest);
                return 0;
            case "play":
                Ym6.Play.Main(rest);
                return 0;
            case "ymrplay":
                Ymr.YmrPlay.Main(rest);
                return 0;
            case "rig":
                Rig.PlayerTests.Main(rest);
                return 0;
            case "sweep":
                Rig.Sweep.Main(rest);
                return 0;
            case "ymrsweep":
                Rig.YmrSweep.Main(rest);
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
