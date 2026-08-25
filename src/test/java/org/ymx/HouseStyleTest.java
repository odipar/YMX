package org.ymx;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    /** Every documentation file the ban list covers. */
    private static final List<Path> DOCS = List.of(
            Path.of("doc", "SPEC.md"),
            Path.of("doc", "BINARIES.md"),
            Path.of("doc", "tools.md"),
            Path.of("doc", "terminology.md"),
            Path.of("doc", "experiments.md"),
            Path.of("doc", "RELEASES.md"),
            Path.of("README.md"),
            Path.of("ym", "CONVERSION.md"),
            Path.of("ym", "test", "README.md"),
            Path.of("doc", "conformance", "README.md"),
            Path.of("doc", "conformance", "TASK.md"),
            Path.of("doc", "conformance", "TASK-player.md"),
            Path.of("doc", "conformance", "SOURCES.md"));

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
        for (Path doc : DOCS) {
            List<String> lines = Files.readAllLines(doc);
            for (int at = 0; at < lines.size(); at++) {
                String line = lines.get(at).toLowerCase();
                for (String struck : STRUCK) {
                    if (line.contains(struck)) {
                        hits.add(doc + ":" + (at + 1) + " carries \"" + struck + '"');
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(), () -> String.join("\n", hits)
                + "\nAGENTS.md has the rule each phrase was struck under;"
                + " reword the line, or take the entry off this list in the"
                + " same change.");
    }
}
