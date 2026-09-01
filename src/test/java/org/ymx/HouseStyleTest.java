package org.ymx;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The documentation against the house style's ban list.
 *
 * <p>{@code AGENTS.md} states the rules - nothing acts on its own, and no
 * flourish - and this test holds the phrases struck in review under them.
 * Each entry is one struck phrase or the stem of one; a hit names the file
 * and line. A phrase that is legitimate in a new context comes off the list
 * in the same change that uses it, so the exception is deliberate.
 */
final class HouseStyleTest {

    /** The two documents that state the rules, and so quote what they
     * strike. Every other Markdown file in the tree is held. */
    private static final List<String> STATES_THE_RULES =
            List.of("AGENTS.md", "CLAUDE.md");

    /** The width AGENTS.md's Shape rule is held at. */
    private static final int WIDTH = 78;

    /**
     * Every document, found rather than listed. A list is a place a new
     * document is not: {@code go/README.md} and
     * {@code ym/examples/README.md} were outside the one that stood here,
     * so nothing held them.
     */
    private static List<Path> documents() throws IOException {
        try (Stream<Path> tree = Files.walk(Path.of("."))) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".md"))
                    .filter(path -> !path.toString().contains("/target/"))
                    .filter(path -> !STATES_THE_RULES
                            .contains(path.getFileName().toString()))
                    .sorted()
                    .toList();
        }
    }

    /**
     * AGENTS.md's Shape rule: one wrap width a document, held. A table row,
     * an indented block, a fenced block and a line carrying a link are not
     * prose and set their own width, so none is measured.
     *
     * <p>Four lines had drifted past the width across three merged changes
     * with every test passing, each one a paragraph edited and not
     * rewrapped whole. Nothing read them until this did.
     */
    @Test
    void everyDocumentHoldsOneWrapWidth() throws IOException {
        List<String> wide = new ArrayList<>();
        for (Path doc : documents()) {
            List<String> lines = Files.readAllLines(doc);
            boolean fenced = false;
            for (int at = 0; at < lines.size(); at++) {
                String line = lines.get(at);
                if (line.stripLeading().startsWith("```")) {
                    fenced = !fenced;
                    continue;
                }
                if (fenced || line.startsWith("|") || line.startsWith("    ")
                        || line.startsWith("#") || line.contains("](")) {
                    continue;
                }
                if (line.length() > WIDTH) {
                    wide.add(doc + ":" + (at + 1) + " runs to " + line.length());
                }
            }
        }
        assertTrue(wide.isEmpty(), () -> String.join("\n", wide)
                + "\nAGENTS.md asks one width, held, and the paragraph a"
                + " change touches rewrapped whole.");
    }

    /** Struck in review, lowercase; matched as substrings. */
    private static final List<String> STRUCK = List.of(
            // roles and abstractions acting: a writer promising, a verb
            // consuming, bits standing as they were
            "promise",
            "guarantee",
            // "consumer" is a role SPEC.md §1.6 defines, as "caller" and
            // "owner" are roles: only the verb is struck
            "consume ",
            "consumes",
            "consumed",
            "consuming",
            "stand as they were",
            // a consumer does not understand a stream, it implements it:
            // the agentive verb and a second vocabulary for one idea
            "understand",
            "refuse",
            // the sweep: a trailing clause generalising the sentence
            "whatever",
            "whichever way",
            "where it sits",
            "stood still",
            // the metaphor in place of the operation
            " a tail ",
            "sliver",
            "literally",
            "smear",
            "bears it out",
            "pressure point",
            "door left open",
            "cover version",
            "smuggl",
            "catastroph",
            // shape: no em dash construct anywhere - a dash that must
            // stay is a single '-'; the list strikes the en dash and the
            // minus sign too
            "—",
            "–",
            "−",
            // a noun pressed into service as a verb: a repository carries a
            // copy of a library, it does not vendor one
            "vendor",
            // the verdict: the sentence grading itself or its subject
            "is deliberate",
            "by design",
            "on purpose",
            "asked properly",
            "not a shrug",
            "most of the point",
            "the answer to that",
            "worth reading",
            "the ones that matter",
            "the whole point",
            // filler: cut unless the word carries the meaning. "at all"
            // is not here: as a substring it hits "format allows"
            "actually");

    @Test
    void theDocumentationCarriesNoStruckPhrase() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path doc : documents()) {
            List<String> lines = Files.readAllLines(doc);
            for (int at = 0; at < lines.size(); at++) {
                // a space in front, so an entry that leads
                // with one matches a word at the start of a
                // line as well as inside one
                String line = " " + lines.get(at).toLowerCase();
                for (String struck : STRUCK) {
                    if (line.contains(struck)) {
                        hits.add(doc + ":" + (at + 1) + " carries \"" + struck + '"');
                    }
                }
            }
            hits.addAll(wrappedHits(doc, lines));
        }
        assertTrue(hits.isEmpty(), () -> String.join("\n", hits)
                + "\nAGENTS.md has the rule each phrase was struck under;"
                + " reword the line, or take the entry off this list in the"
                + " same change.");
    }

    /**
     * The hits a line wrap hides. A phrase broken across two lines stands in
     * neither of them, so every paragraph is read joined as well, and what
     * the joined text holds beyond what its own lines hold is reported at
     * the line the paragraph begins on. A table row, an indented block and a
     * fence break a paragraph: joining those would put words side by side
     * that no sentence puts there.
     */
    private static List<String> wrappedHits(Path doc, List<String> lines) {
        List<String> hits = new ArrayList<>();
        int from = 0;
        for (int at = 0; at <= lines.size(); at++) {
            boolean breaks = at == lines.size() || lines.get(at).isBlank()
                    || lines.get(at).startsWith("|")
                    || lines.get(at).startsWith("    ")
                    || lines.get(at).startsWith("```");
            if (!breaks) {
                continue;
            }
            if (at > from) {
                List<String> paragraph = lines.subList(from, at);
                String joined = " " + String.join(" ", paragraph).toLowerCase();
                for (String struck : STRUCK) {
                    int whole = occurrences(joined, struck);
                    int apart = 0;
                    for (String line : paragraph) {
                        apart += occurrences(" " + line.toLowerCase(), struck);
                    }
                    for (int n = apart; n < whole; n++) {
                        hits.add(doc + ":" + (from + 1) + " carries \""
                                + struck + "\", broken by a line wrap");
                    }
                }
            }
            from = at + 1;
        }
        return hits;
    }

    /** How many times a struck phrase stands in a run of text. */
    private static int occurrences(String text, String struck) {
        int found = 0;
        for (int at = text.indexOf(struck); at >= 0;
                at = text.indexOf(struck, at + 1)) {
            found++;
        }
        return found;
    }
}
