package org.ymx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * A runnable TOS program around one or more packed tunes.
 *
 * <p>The tunes go into an SNDH container first - the canonical form - and the
 * program is a thin shell around those same bytes: takeover, one play call
 * per VBL, SPACE to quit, number keys to switch subtunes. Nothing is
 * duplicated between the two forms but the shell itself.
 */
public final class MkPrg {

    private MkPrg() {}

    public record Options(Path output, List<Path> tunes, @Nullable String title,
                          @Nullable String composer, @Nullable List<String> names,
                          boolean perf, boolean maskBurst, boolean marker) {}

    public static Path build(Options options) {
        Path output = options.output().toAbsolutePath();
        Path work = Tools.directoryOf(output).resolve(".prg_work");
        try {
            Files.createDirectories(work);
        } catch (IOException e) {
            throw Tools.fail("mkprg: cannot make " + work);
        }

        @Nullable String title = options.title();
        if (title == null || title.isEmpty()) {
            title = output.getFileName().toString().replaceAll("(?i)\\.prg$", "");
        }
        MkSndh.build(new MkSndh.Options(work.resolve("tune.sndh"), options.tunes(),
                title, options.composer(), options.names(), options.perf(),
                options.maskBurst()));

        // The subtune count and, for a lone tune that ends, its frame count:
        // the shell needs one to bind the number keys and the other to notice
        // the tune is over. A set is endless as far as the shell cares.
        int tunes = options.tunes().size();
        int frames = 0;
        try {
            YmxHeader first = YmxHeader.read(options.tunes().get(0));
            if (!first.loops() && tunes == 1) {
                frames = first.frames();
            }
        } catch (IOException e) {
            throw Tools.fail("mkprg: " + e.getMessage());
        }

        String wrapper = """
                YMX_TUNES       equ     %d
                YMX_FRAMES      equ     %d
                YMX_EXIT_MARKER equ     %d
                        include "YMX_player.S"
                        .data
                        even
                sndh:   incbin  "tune.sndh"
                        even
                """.formatted(tunes, frames, options.marker() ? 1 : 0);
        try {
            Files.writeString(work.resolve("wrapper.S"), wrapper, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw Tools.fail("mkprg: cannot write the wrapper");
        }
        Tools.assemble(work, "wrapper.S", output,
                List.of("-p", "-i" + Tools.asmDir()));

        System.out.println(options.output() + ": " + Tools.size(output) + " bytes, "
                + Tools.plural(tunes, "subtune"));
        return output;
    }

    private static final String USAGE =
            "usage: mkprg.sh [-m] [-perf] [-nomask] [-tTitle] [-cComposer] [-Nnamesfile]"
            + " output.prg tunes...";

    public static void main(String[] args) {
        boolean marker = false;
        boolean perf = false;
        boolean maskBurst = true;
        @Nullable String title = null;
        @Nullable String composer = null;
        @Nullable List<String> names = null;
        int i = 0;
        for (; i < args.length; i++) {
            String a = args[i];
            if (a.equals("-m")) {
                marker = true;
            } else if (a.equals("-perf")) {
                perf = true;
            } else if (a.equals("-nomask")) {
                maskBurst = false;
            } else if (a.startsWith("-t")) {
                title = a.substring(2);
            } else if (a.startsWith("-c")) {
                composer = a.substring(2);
            } else if (a.startsWith("-N")) {
                names = MkSndh.readNames(Path.of(a.substring(2)));
            } else {
                break;
            }
        }
        if (i >= args.length) {
            throw Tools.fail(USAGE);
        }

        // Both argument orders: the .prg names the output wherever it stands,
        // so `mkprg.sh song.ymx SONG.PRG` keeps working.
        Path output;
        List<Path> tunes = new ArrayList<>();
        if (args[i].toLowerCase(java.util.Locale.ROOT).endsWith(".prg")) {
            output = Path.of(args[i++]);
            for (; i < args.length; i++) {
                tunes.add(Path.of(args[i]));
            }
        } else if (args.length - i == 2) {
            tunes.add(Path.of(args[i]));
            output = Path.of(args[i + 1]);
        } else {
            throw Tools.fail(USAGE);
        }
        if (tunes.isEmpty()) {
            throw Tools.fail(USAGE);
        }
        build(new Options(output, tunes, title, composer, names, perf, maskBurst,
                marker));
    }
}
