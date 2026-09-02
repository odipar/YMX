package org.ym6;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.ymx.MkPrg;
import org.ymx.MkSndh;
import org.ymx.Tools;

/**
 * One command from a YM dump to something that plays: a {@code .ymx}, an
 * SNDH file, or a runnable TOS program. The output's extension picks which.
 *
 * <p>The C# and Go trees ship this as a standalone executable, carrying the
 * SNDH cores and the PRG stub inside it so it combines with no repository
 * beside it. This one does not: it resolves them out of {@code dist/} the
 * way the combiners always have, so it runs inside this tree and is not a
 * binary to hand anyone. What it is for is the third reading of the same
 * command line, which {@code ymx/parity.sh} holds against the other two.
 */
public final class YmToYmx {

    private YmToYmx() {}

    private static final String USAGE =
            "usage: ym-to-ymx [options] output.{ymx|sndh|prg} tune.ym [more.ym ...]\n"
                    + "\n"
                    + "  The output's extension picks what is written:\n"
                    + "    .ymx    the packed tune, one input only\n"
                    + "    .sndh   an SNDH v2.2 file any SNDH host plays\n"
                    + "    .prg    a TOS program that plays it\n"
                    + "\n"
                    + "packing\n"
                    + "  -f              overwrite the output\n"
                    + "  -o              play once: stop at the end instead of starting over\n"
                    + "  -lF             start over from frame F; -l0 from the beginning\n"
                    + "  -nN             ring size per stream, bytes (default 960)\n"
                    + "  -cC             values decoded per call (default 24)\n"
                    + "  -kK             ST4 unit size 1, 2 or 4 (default: the tune's own shape)\n"
                    + "  -minM -secS     trim: drop everything before M:S\n"
                    + "  -startframeF -endframeF -framesN\n"
                    + "                  the same window in frames\n"
                    + "  -drumhzH        the drum rate ceiling (default 25600)\n"
                    + "  -timersT        which MFP timer each channel runs on (default AD)\n"
                    + "  -sidresume      the resume gap model, for maxYMiser tunes\n"
                    + "\n"
                    + "the SNDH file and the program\n"
                    + "  -perf           build with the raster monitor\n"
                    + "  -nomask         build with the frame write unmasked\n"
                    + "  -tTitle         the SNDH TITL tag (default: the dump's own)\n"
                    + "  -cComposer      the COMM tag - note -c is the chunk size when\n"
                    + "                  it is followed by digits\n"
                    + "  -Nnamesfile     subtune names, one per line\n"
                    + "  -m              drop YMXDONE.MRK on exit, for scripted runs\n"
                    + "\n"
                    + "  -h, --help      this text";

    public static void main(String[] args) {
        if (args.length == 0 || args[0].equals("-h")
                || args[0].equals("--help")) {
            System.out.println(USAGE);
            return;
        }

        List<String> packerFlags = new ArrayList<>();
        @Nullable String title = null;
        @Nullable String composer = null;
        @Nullable List<String> names = null;
        boolean perf = false;
        boolean maskBurst = true;
        boolean marker = false;

        int i = 0;
        for (; i < args.length && args[i].startsWith("-"); i++) {
            String flag = args[i];
            if (flag.equals("-perf")) {
                perf = true;
            } else if (flag.equals("-nomask")) {
                maskBurst = false;
            } else if (flag.equals("-m")) {
                marker = true;
            } else if (flag.startsWith("-timers") || flag.startsWith("-copies")) {
                packerFlags.add(flag);          // the packer's, not a title or
                                                // a composer
            } else if (flag.startsWith("-t") && flag.length() > 2) {
                title = flag.substring(2);
            } else if (flag.startsWith("-N") && flag.length() > 2) {
                names = MkSndh.readNames(Path.of(flag.substring(2)));
            } else if (flag.startsWith("-c") && flag.length() > 2
                    && !Character.isDigit(flag.charAt(2))) {
                composer = flag.substring(2);   // -c with digits is the packer's
            } else {
                packerFlags.add(flag);
            }
        }

        if (args.length - i < 2) {
            throw Tools.fail(USAGE);
        }
        Path output = Path.of(args[i++]).toAbsolutePath();
        List<Path> yms = new ArrayList<>();
        for (; i < args.length; i++) {
            yms.add(Path.of(args[i]));
        }

        String name = output.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String kind = dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
        if (!kind.equals(".ymx") && !kind.equals(".sndh")
                && !kind.equals(".prg")) {
            throw Tools.fail("ym-to-ymx: the output's extension says what to"
                    + " write, and '" + kind + "' is not one of .ymx, .sndh"
                    + " or .prg");
        }
        if (kind.equals(".ymx") && yms.size() > 1) {
            throw Tools.fail("ym-to-ymx: a .ymx holds one tune. Name a .sndh"
                    + " or a .prg output to combine " + yms.size() + " of them");
        }

        if (kind.equals(".ymx")) {
            List<String> argv = new ArrayList<>(packerFlags);
            argv.add(yms.get(0).toString());
            argv.add(output.toString());
            Ymx.main(argv.toArray(new String[0]));
            return;
        }
        // The packer guards the .ymx it writes; the SNDH file and the
        // program are written here, so the guard is here too.
        if (!packerFlags.contains("-f") && Files.exists(output)) {
            throw Tools.fail("ym-to-ymx: already existing output file "
                    + output);
        }

        Path work = Tools.directoryOf(output).resolve(".ym_work");
        TuneSet set = TuneSet.of(yms);
        List<Path> packed = Packing.pack(yms, work, packerFlags, true);

        if (kind.equals(".sndh")) {
            MkSndh.build(new MkSndh.Options(output, packed,
                    title != null ? title : set.title(),
                    composer != null ? composer : set.composer(),
                    names != null ? names : set.names(), perf, maskBurst));
        } else {
            MkPrg.build(new MkPrg.Options(output, packed,
                    title != null ? title : set.title(),
                    composer != null ? composer : set.composer(),
                    names != null ? names : set.names(), perf, maskBurst,
                    marker));
        }
    }
}
