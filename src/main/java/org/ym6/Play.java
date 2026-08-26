package org.ym6;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.ymx.MkPrg;
import org.ymx.Tools;
import org.ymx.YmxFormat;

/**
 * Test drive: pack a tune, build a player around it, run it under Hatari.
 *
 * <p>Several tunes become one program's subtunes, packed with one
 * configuration and switched with the number keys. Everything it builds is
 * kept next to the first tune, so two ring sizes can be compared by ear and
 * both kept.
 *
 * <p>The program is built with the exit marker, which is how this detects the
 * tune has stopped: the emulator has no other way to report that SPACE was
 * pressed inside it. Whichever comes first - the marker or the window being
 * closed by hand - ends the wait.
 */
public final class Play {

    private Play() {}

    private static final String USAGE = """
            play.sh - test drive a YM tune: pack it, build a player, run it under Hatari.

              ym/play.sh song.ym                  # 960-byte rings, 24 values per call
              ym/play.sh -n256 song.ym            # smaller rings: less RAM, worse ratio
              ym/play.sh -n2048 -c32 song.ym      # longer calls: cheaper on average
              ym/play.sh -o song.ym               # play once and stop, instead of
                                                   # starting over at the end
              ym/play.sh -min13 -sec52 song.ym    # trim: start deep in a long tune
              ym/play.sh -startframe41403 -frames1729 song.ym
              ym/play.sh one.ym two.ym            # a set: subtunes, number keys pick
              ym/play.sh -perf song.ym            # the raster monitor: the frame step
                                                   # works in red, timer ticks in green
                                                   # (A) and blue (D), and a yellow bar
                                                   # estimates the ticks' scanlines
              ym/play.sh -vbl song.ym             # tick from the VBL instead of Timer
                                                   # C, so the -perf bars hold one place
                                                   # on the screen. 50 Hz tunes only
              ym/play.sh -nomask song.ym          # drop the interrupt mask around the
                                                   # frame write, which the writes do
                                                   # not need: ticks then interleave
                                                   # with it instead of waiting ~500
                                                   # cycles behind it

            Press SPACE in the Hatari window to stop. Everything it builds lands in a
            work directory next to the first tune. The trim flags take one tune.

              HATARI=/path/to/hatari TOS=/path/to/tos.img ym/play.sh song.ym""";

    public static void main(String[] args) {
        int ring = YmxFormat.DEFAULT_RING_SIZE;
        int chunk = YmxFormat.DEFAULT_CHUNK;
        String unit = "";
        String once = "";
        boolean perf = false;
        boolean maskBurst = true;
        boolean vbl = false;
        List<String> extra = new ArrayList<>();
        int i = 0;
        for (; i < args.length && args[i].startsWith("-"); i++) {
            String a = args[i];
            if (a.equals("-perf")) {
                perf = true;
            } else if (a.equals("-nomask")) {
                maskBurst = false;
            } else if (a.equals("-vbl")) {
                vbl = true;
            } else if (a.equals("-o")) {
                once = "-o";
            } else if (a.equals("-h") || a.equals("--help")) {
                System.out.println(USAGE);
                return;
            } else if (a.startsWith("-n")) {
                ring = number(a.substring(2));
            } else if (a.startsWith("-c")) {
                chunk = number(a.substring(2));
            } else if (a.startsWith("-k")) {
                unit = "-k" + a.substring(2);
            } else {
                extra.add(a);           // the packer's: trim, -drumhz, whatever
            }                           // it reads next
        }
        List<Path> yms = new ArrayList<>();
        for (; i < args.length; i++) {
            yms.add(Path.of(args[i]));
        }
        if (yms.isEmpty()) {
            throw Tools.fail("usage: play.sh [-perf] [-nomask] [-vbl] [-nRING]"
                    + " [-cCHUNK] [-kUNIT] [-o] song.ym...");
        }
        for (Path ym : yms) {
            if (!Files.isRegularFile(ym)) {
                throw Tools.fail("play.sh: no such file: " + ym);
            }
        }
        String hatari = env("HATARI", "hatari");
        Path tos = Path.of(env("TOS", System.getProperty("user.home")
                + "/hatari-2.6.1_macos/tos-2.06.rom"));
        if (!Files.isRegularFile(tos)) {
            throw Tools.fail("play.sh: no TOS image at " + tos
                    + " - set TOS=/path/to/tos.img");
        }

        // One directory per run, named after the first tune and the shape, so
        // a second run with a different ring size does not overwrite the
        // first. A set says how many more it carries, so it cannot collide
        // with a run of the first tune alone.
        Path first = yms.get(0).toAbsolutePath();
        String name = TuneSet.stem(first) + (yms.size() > 1 ? "+" + (yms.size() - 1) : "")
                + "-n" + ring + "-c" + chunk + (unit.isEmpty() ? "" : "-" + unit.substring(1));
        Path work = Tools.directoryOf(first).resolve(name);

        List<String> flags = new ArrayList<>(List.of("-n" + ring, "-c" + chunk));
        if (!unit.isEmpty()) {
            flags.add(unit);
        }
        if (!once.isEmpty()) {
            flags.add(once);
        }
        flags.addAll(extra);

        TuneSet set = TuneSet.of(yms);
        System.out.println("play.sh: packing " + join(yms));
        List<Path> packed = Packing.pack(yms, work, flags);
        MkPrg.build(new MkPrg.Options(work.resolve("PLAY.PRG"), packed, set.title(),
                set.composer(), set.names(), perf, maskBurst, true, vbl));

        Path marker = work.resolve("YMXDONE.MRK");
        Packing.deleteQuietly(marker);
        System.out.println("play.sh: starting Hatari - press SPACE in its window to stop");
        run(hatari, tos, work.resolve("PLAY.PRG"), marker);
        Packing.deleteQuietly(marker);
        System.out.println("play.sh: stopped. The tune and the program are in " + work);
    }

    /**
     * Sound on, real speed, a window: this is a listening test, not a
     * measurement. Nothing here asks the user to confirm anything - closing
     * the window is as good an answer as pressing SPACE.
     */
    private static void run(String hatari, Path tos, Path program, Path marker) {
        List<String> command = List.of(hatari,
                "--tos", tos.toString(), "--machine", "st", "--cpuclock", "8",
                "--memsize", "4", "--sound", "44100", "--ym-mixing", "model",
                "--window", "--zoom", "2", "--confirm-quit", "off",
                "--log-level", "fatal", program.toString());
        Process emulator;
        try {
            emulator = new ProcessBuilder(command).inheritIO().start();
        } catch (IOException e) {
            throw Tools.fail("play.sh: cannot start " + hatari + ": " + e.getMessage());
        }
        try {
            while (emulator.isAlive()) {
                if (Files.exists(marker)) {
                    emulator.destroy();
                    if (!emulator.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                        emulator.destroyForcibly();
                    }
                    break;
                }
                Thread.sleep(200);
            }
            emulator.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emulator.destroyForcibly();
        }
    }

    private static String join(List<Path> paths) {
        StringBuilder out = new StringBuilder();
        for (Path p : paths) {
            out.append(out.isEmpty() ? "" : " ").append(p);
        }
        return out.toString();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static int number(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw Tools.fail("play.sh: not a number: " + text);
        }
    }
}
