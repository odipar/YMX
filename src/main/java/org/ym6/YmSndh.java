package org.ym6;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import org.ymx.MkSndh;
import org.ymx.Tools;

/**
 * From .ym dumps to one SNDH file, in one command: the packer over every
 * input with one configuration, then {@link MkSndh} around the results.
 *
 * <p>A lone tune is packed through the single-file path so the trim options
 * mean something; a set goes through the packer's own set mode, which is what
 * forces the one shared configuration the player needs.
 */
public final class YmSndh {

    private YmSndh() {}

    private static final String USAGE =
            "usage: ym_sndh.sh [-perf] [-tTitle] [packer flags] output.sndh tunes.ym...";

    public static void main(String[] args) {
        @Nullable String title = null;
        boolean perf = false;
        List<String> packerFlags = new ArrayList<>();
        int i = 0;
        for (; i < args.length && args[i].startsWith("-"); i++) {
            if (args[i].equals("-perf")) {
                perf = true;
            } else if (args[i].startsWith("-t")) {
                title = args[i].substring(2);
            } else {
                packerFlags.add(args[i]);
            }
        }
        if (args.length - i < 2) {
            throw Tools.fail(USAGE);
        }
        Path output = Path.of(args[i++]);
        List<Path> yms = new ArrayList<>();
        for (; i < args.length; i++) {
            yms.add(Path.of(args[i]));
        }

        Path work = Tools.directoryOf(output).resolve(".ym_work");
        TuneSet set = TuneSet.of(yms);
        // a fresh work directory each run: yesterday's leftovers are not
        // this set's subtunes
        List<Path> packed = Packing.pack(yms, work, packerFlags, true);
        MkSndh.build(new MkSndh.Options(output, packed,
                title != null && !title.isEmpty() ? title : set.title(),
                set.composer(), set.names(), perf, true));
    }
}
