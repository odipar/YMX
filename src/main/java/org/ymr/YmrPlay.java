package org.ymr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.ymx.MkPrg;
import org.ymx.Tools;
import org.ymx.YmxFormat;

/**
 * Test drive a RhYMe tune: pack it, build a player around it, run it under
 * Hatari.
 *
 * <p>It is {@link org.ym6.Play} with the .ym step replaced, and deliberately
 * so: the same flags mean the same things, the work directory is named the
 * same way, and SPACE in the emulator window still ends the run. The two
 * front ends drive the same player, and someone who has test driven a .ym
 * should not have to learn a second set of habits to hear a .ymr.
 *
 * <p>Everything downstream of the packed files is format-blind and is called
 * rather than copied: {@link MkPrg#build} makes the program, {@link Tools}
 * finds the repo. Two pieces refused to be borrowed, and for the same reason:
 * {@code Packing.pack} shells into {@link org.ym6.Ymx} and {@code TuneSet.of}
 * reads a YM header with {@link org.ym6.Ym6Reader}, so both are .ym to the
 * bone and have small .ymr equivalents here. The Hatari invocation and the
 * marker wait are Play's own private methods, and stand here unchanged - the
 * emulator does not care what the tune was converted from, so neither should
 * they diverge.
 *
 * <p>A .YMR carries no title, no author and no comment: the format stores
 * streams and a command stream, not credits. So a set is titled and its
 * subtunes named from the file stems - the only name a .YMR has - and the
 * SNDH's composer is left absent rather than invented.
 *
 * <p>The program is built with the exit marker, which is how this knows the
 * tune has stopped: the emulator has no other way to tell us that SPACE was
 * pressed inside it. Whichever comes first - the marker or the window being
 * closed by hand - ends the wait.
 */
public final class YmrPlay {

    private YmrPlay() {}

    private static final String USAGE = """
            ymr.sh - test drive a .YMR tune: pack it, build a player, run it under Hatari.

              ymr/ymr.sh song.ymr                # 960-byte rings, 24 values per call
              ymr/ymr.sh -n2048 -c32 song.ymr    # longer calls: cheaper on average
              ymr/ymr.sh -l0 song.ymr            # loop from the start, whatever the
                                                   # .ymr header says
              ymr/ymr.sh -o song.ymr             # play once and stop
              ymr/ymr.sh -min13 -sec52 song.ymr  # trim: start deep in a long tune
              ymr/ymr.sh -startframe41403 -frames1729 song.ymr
              ymr/ymr.sh one.ymr two.ymr         # a set: subtunes, number keys pick
              ymr/ymr.sh -perf song.ymr          # the raster monitor: the frame step
                                                   # works in red, the ticks paint green
                                                   # (A), red (B) and blue (D), and a
                                                   # yellow bar estimates their scanlines
              ymr/ymr.sh -nomask song.ymr        # drop the interrupt mask around the
                                                   # frame write, which the writes do
                                                   # not need: ticks then interleave
                                                   # with it instead of waiting ~500
                                                   # cycles behind it

            Press SPACE in the Hatari window to stop. Everything it builds lands in a
            work directory next to the first tune. The trim flags take one tune.

            A .ymr fills three timer channels and never the fourth, so the player decodes
            23 of the format's 25 streams and C must cover them: the default 24 clears it
            by one slot, and C above that buys headroom no .ymr can use.

              HATARI=/path/to/hatari TOS=/path/to/tos.img ymr/ymr.sh song.ymr""";

    public static void main(String[] args) {
        int ring = YmxFormat.DEFAULT_RING_SIZE;
        int chunk = YmxFormat.DEFAULT_CHUNK;
        String unit = "";
        String loop = "";
        boolean perf = false;
        boolean maskBurst = true;
        List<String> extra = new ArrayList<>();
        int i = 0;
        for (; i < args.length && args[i].startsWith("-"); i++) {
            String a = args[i];
            if (a.equals("-perf")) {
                perf = true;
            } else if (a.equals("-nomask")) {
                maskBurst = false;
            } else if (a.equals("-o")) {
                loop = "-o";
            } else if (a.equals("-h") || a.equals("--help")) {
                System.out.println(USAGE);
                return;
            } else if (a.startsWith("-n")) {
                ring = number(a.substring(2));
            } else if (a.startsWith("-c")) {
                chunk = number(a.substring(2));
            } else if (a.startsWith("-k")) {
                unit = "-k" + a.substring(2);
            } else if (a.startsWith("-l")) {
                loop = "-l" + a.substring(2);
            } else {
                extra.add(a);           // the packer's: the trim window, and
            }                           // whatever it learns next
        }
        List<Path> ymrs = new ArrayList<>();
        for (; i < args.length; i++) {
            ymrs.add(Path.of(args[i]));
        }
        if (ymrs.isEmpty()) {
            throw Tools.fail("usage: ymr.sh [-perf] [-nomask] [-nRING] [-cCHUNK]"
                    + " [-kUNIT] [-lFRAME|-o] song.ymr...");
        }
        for (Path ymr : ymrs) {
            if (!Files.isRegularFile(ymr)) {
                throw Tools.fail("ymr.sh: no such file: " + ymr);
            }
        }
        String hatari = env("HATARI", "hatari");
        Path tos = Path.of(env("TOS", System.getProperty("user.home")
                + "/hatari-2.6.1_macos/tos-2.06.rom"));
        if (!Files.isRegularFile(tos)) {
            throw Tools.fail("ymr.sh: no TOS image at " + tos
                    + " - set TOS=/path/to/tos.img");
        }
        checkOneRate(ymrs);

        // One directory per run, named after the first tune and the shape, so
        // a second run with a different ring size does not overwrite the
        // first. A set says how many more it carries, so it cannot collide
        // with a run of the first tune alone.
        Path first = ymrs.get(0).toAbsolutePath();
        String name = stem(first) + (ymrs.size() > 1 ? "+" + (ymrs.size() - 1) : "")
                + "-n" + ring + "-c" + chunk + (unit.isEmpty() ? "" : "-" + unit.substring(1));
        Path work = Tools.directoryOf(first).resolve(name);

        List<String> flags = new ArrayList<>(List.of("-n" + ring, "-c" + chunk));
        if (!unit.isEmpty()) {
            flags.add(unit);
        }
        if (!loop.isEmpty()) {
            flags.add(loop);
        }
        flags.addAll(extra);

        List<String> names = new ArrayList<>();
        for (Path ymr : ymrs) {
            names.add(stem(ymr));
        }
        System.out.println("ymr.sh: packing " + join(ymrs));
        List<Path> packed = pack(ymrs, work, flags);
        // No composer: a .YMR has no author field, and MkPrg takes a @Nullable
        // one precisely so a converter with nothing to say can say nothing
        // rather than stamp the SNDH with a guess.
        MkPrg.build(new MkPrg.Options(work.resolve("PLAY.PRG"), packed,
                String.join(" / ", names), null, names, perf, maskBurst, true));

        Path marker = work.resolve("YMXDONE.MRK");
        clear(marker);
        System.out.println("ymr.sh: starting Hatari - press SPACE in its window to stop");
        run(hatari, tos, work.resolve("PLAY.PRG"), marker);
        clear(marker);
        System.out.println("ymr.sh: stopped. The tune and the program are in " + work);
    }

    /**
     * Every input through the .ymr packer with one configuration, into one
     * directory - {@link org.ym6.Packing} with the .ym taken out of it.
     *
     * <p>A lone tune goes through the packer's single-file form, where the
     * trim options still mean something. A set goes through its trailing-
     * directory form, which is what pins them all to one unit size and one
     * workspace - the shape a single player build can hold as subtunes - and
     * which is why the directory has to exist before the packer sees it.
     *
     * <p>The packer's per-stream table is left where it lands. The .ym front
     * ends filter it out because a build script on its way to an SNDH does not
     * want a screen of ratios per tune; a test drive is the one moment you do.
     */
    private static List<Path> pack(List<Path> ymrs, Path work, List<String> flags) {
        try {
            Files.createDirectories(work);
        } catch (IOException e) {
            throw Tools.fail("cannot make " + work + ": " + e.getMessage());
        }
        List<String> argv = new ArrayList<>();
        argv.add("-f");
        argv.addAll(flags);
        List<Path> packed = new ArrayList<>();
        for (Path ymr : ymrs) {
            packed.add(work.resolve(stem(ymr) + ".ymx"));
        }
        if (ymrs.size() == 1) {
            argv.add(ymrs.get(0).toString());
            argv.add(packed.get(0).toString());
        } else {
            for (Path ymr : ymrs) {
                argv.add(ymr.toString());
            }
            argv.add(work.toString());          // the trailing directory: a set
        }
        Ymr.main(argv.toArray(new String[0]));
        return packed;
    }

    /**
     * The rate every tune in a set must share: one player build is called at
     * one rate, and the SNDH it wraps declares one.
     *
     * <p>Answering this costs a full read of each dump - a .YMR has no random
     * access into it, so the reader replays the command stream from the start
     * whatever you ask it - which is why a lone tune is not asked at all. It
     * trivially agrees with itself, and the packer is about to read it anyway.
     */
    private static void checkOneRate(List<Path> ymrs) {
        if (ymrs.size() < 2) {
            return;
        }
        int set = 0;
        for (Path ymr : ymrs) {
            int rate = read(ymr).frameRate();
            if (set == 0) {
                set = rate;
            } else if (rate != set) {
                throw Tools.fail("ymr.sh: " + ymr + " plays at " + rate
                        + " Hz, the set at " + set + " Hz - one player build"
                        + " is driven at one rate");
            }
        }
    }

    private static YmrReader.Song read(Path ymr) {
        try {
            return YmrReader.read(Files.readAllBytes(ymr));
        } catch (IOException | YmrReader.FormatException e) {
            throw Tools.fail(ymr + ": " + e.getMessage());
        }
    }

    /** The file's own name, which is the only name a .YMR has: it becomes the
     * work directory, the packed file, and the subtune the number keys pick. */
    private static String stem(Path ymr) {
        return ymr.getFileName().toString().replaceAll("(?i)\\.ymr$", "");
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
            throw Tools.fail("ymr.sh: cannot start " + hatari + ": " + e.getMessage());
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

    /** The exit marker, cleared on the way in as well as out: one left behind
     * by an interrupted run would close the emulator before a note is heard. */
    private static void clear(Path marker) {
        try {
            Files.deleteIfExists(marker);
        } catch (IOException e) {
            throw Tools.fail("ymr.sh: cannot remove " + marker);
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
            throw Tools.fail("ymr.sh: not a number: " + text);
        }
    }
}
