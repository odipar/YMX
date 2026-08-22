package org.ym6;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import org.ymx.Tools;

/**
 * What a set of YM dumps says about itself, for the SNDH tags: each tune's
 * name, a composer they all share, and a title made of the lot.
 *
 * <p>The dumps are wildly inconsistent about their own metadata, so a name
 * that says nothing - empty, "unknown", "untitled" - gives way to the file
 * stem, which at least came from someone who knew what the tune was. A
 * composer is only claimed when every tune in the set agrees on one; a set
 * assembled from different musicians has no single COMM to declare.
 */
public record TuneSet(List<String> names, @Nullable String composer, String title) {

    public static TuneSet of(List<Path> tunes) {
        List<String> names = new ArrayList<>();
        @Nullable String composer = null;
        boolean agree = true;
        for (Path tune : tunes) {
            Ym6Reader.Song song = read(tune);
            String name = song.name().strip();
            if (saysNothing(name)) {
                name = stem(tune);
            }
            names.add(name);
            String author = song.author().strip();
            if (composer == null && !author.isEmpty()) {
                composer = author;
            } else if (!author.equals(composer == null ? "" : composer)) {
                agree = false;
            }
        }
        return new TuneSet(names, agree ? composer : null, String.join(" / ", names));
    }

    /** The rate every tune must share: one SNDH declares one. */
    public static int playerHz(Path tune) {
        return read(tune).playerHz();
    }

    private static Ym6Reader.Song read(Path tune) {
        try {
            return Ym6Reader.read(Files.readAllBytes(tune));
        } catch (IOException | Ym6Reader.FormatException e) {
            throw Tools.fail(tune + ": " + e.getMessage());
        }
    }

    static String stem(Path tune) {
        return tune.getFileName().toString().replaceAll("(?i)\\.ym$", "");
    }

    private static boolean saysNothing(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.isEmpty() || lower.equals("unknown") || lower.equals("untitled")
                || lower.equals("<unknown>");
    }
}
