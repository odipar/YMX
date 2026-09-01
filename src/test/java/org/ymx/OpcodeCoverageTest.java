package org.ymx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.ym6.Ym6Reader;
import org.ym6.YmEffects;

/**
 * Which of SPEC.md §3's eight opcodes the pinned tunes compile to.
 *
 * <p>Three audits found the same hole by hand: an opcode the format defines
 * and no tune exercises. This holds the answer in the build instead. Every
 * opcode a pinned tune reaches must keep being reached, and the ones no
 * tune reaches are named here with the reason, so the list shrinks when
 * someone adds a fixture rather than being rediscovered. All eight are
 * reached now, two of them by dumps {@link org.ymx.rig.BuiltTunes} builds.
 *
 * <p>Each tune is compiled twice, under the default gap model and under the
 * resume model {@code -sidresume} selects, because both are packings a user
 * can ask for and the format carries either.
 */
final class OpcodeCoverageTest {

    /** SPEC.md §3, in opcode order. */
    private static final List<String> OPCODES = List.of(
            "RESUME", "HOLD", "RELEASE", "START_TOGGLE",
            "RETUNE", "START_RETRIGGER", "START_PCM", "START_PCM_PREEMPT");

    /**
     * The opcodes no pinned tune compiles to, and why. The list is empty:
     * it held {@code START_RETRIGGER} and {@code START_PCM_PREEMPT}, which
     * no recorded file in the collection reaches, until two built dumps
     * carried them. It is kept so a new opcode has somewhere to be named
     * while nothing exercises it.
     */
    private static final Set<String> UNREACHED = Set.of();

    @Test
    void everyOpcodeTheTunesReachKeepsBeingReached() throws IOException {
        Set<String> reached = new LinkedHashSet<>();
        List<Path> tunes = new ArrayList<>();
        try (Stream<Path> listing = Files.list(Path.of("ym", "test"))) {
            listing.filter(p -> p.toString().endsWith(".ym")).sorted()
                    .forEach(tunes::add);
        }
        assertTrue(!tunes.isEmpty(), "no pinned tunes under ym/test");

        for (Path each : tunes) {
            Tune tune = YmEffects.tune(Ym6Reader.read(Files.readAllBytes(each)));
            reached.addAll(opcodesOf(tune));
            reached.addAll(opcodesOf(tune.under(new EffectScript.Semantics(
                    true, true, false, true, false))));
        }

        List<String> missing = new ArrayList<>();
        for (String opcode : OPCODES) {
            if (!reached.contains(opcode) && !UNREACHED.contains(opcode)) {
                missing.add(opcode);
            }
        }
        assertTrue(missing.isEmpty(), "the pinned tunes no longer reach "
                + missing + ". An opcode that was covered has stopped being"
                + " covered, which is how a fixture goes missing without"
                + " anyone noticing");

        List<String> nowReached = new ArrayList<>(UNREACHED);
        nowReached.retainAll(reached);
        assertTrue(nowReached.isEmpty(), nowReached + " is reached by a pinned"
                + " tune now and is still listed as unreached. Take it out of"
                + " UNREACHED: the list is what the corpus cannot do, and it"
                + " should only shrink");

        assertEquals(OPCODES.size(), reached.size() + UNREACHED.size(),
                "the opcodes SPEC.md §3 names, those the tunes reach and those"
                        + " listed as unreached no longer account for each"
                        + " other");
    }

    /** The three opcodes SPEC.md gives a second form, discriminated by
     * voice 3: RETUNE (§3.1), START_RETRIGGER (§3.4), RESUME (§3.5). */
    private static final List<String> VOICE_THREE = List.of(
            "RESUME", "RETUNE", "START_RETRIGGER");

    /**
     * The voice-3 forms no pinned tune compiles to, and why. Reaching an
     * opcode says nothing about them: a form is that opcode with a
     * different voice field, and the check above counts one where the
     * player dispatches two.
     *
     * <p>The list is empty. It held RETUNE and START_RETRIGGER until the
     * tunes were compiled under a source that signals a live retune as
     * well: no YM file asks for one, since it records a code sitting in a
     * register and not the moment a player reprogrammed anything, but the
     * packing is one the format carries and the packer emits, so the check
     * below compiles for it the way it compiles for the resume gap model.
     */
    private static final Set<String> UNREACHED_FORMS = Set.of();

    @Test
    void everyVoiceThreeFormTheTunesReachKeepsBeingReached() throws IOException {
        Set<String> reached = new LinkedHashSet<>();
        List<Path> tunes = new ArrayList<>();
        try (Stream<Path> listing = Files.list(Path.of("ym", "test"))) {
            listing.filter(p -> p.toString().endsWith(".ym")).sorted()
                    .forEach(tunes::add);
        }
        for (Path each : tunes) {
            Tune tune = YmEffects.tune(Ym6Reader.read(Files.readAllBytes(each)));
            reached.addAll(voiceThreeOf(tune));
            reached.addAll(voiceThreeOf(tune.under(new EffectScript.Semantics(
                    true, true, false, true, false))));
            reached.addAll(voiceThreeOf(tune.under(new EffectScript.Semantics(
                    true, true, false, false, true))));
        }

        List<String> missing = new ArrayList<>(VOICE_THREE);
        missing.removeAll(reached);
        missing.removeAll(UNREACHED_FORMS);
        assertTrue(missing.isEmpty(), "no pinned tune compiles to " + missing
                + " at voice 3 any more. A form that was covered has stopped"
                + " being covered, and the opcode check above cannot see it");

        List<String> nowReached = new ArrayList<>(UNREACHED_FORMS);
        nowReached.retainAll(reached);
        assertTrue(nowReached.isEmpty(), nowReached + " is reached at voice 3"
                + " now and is still listed as unreached. Take it out of"
                + " UNREACHED_FORMS: the list is what the corpus cannot do,"
                + " and it should only shrink");
    }

    /** The opcodes one tune's script carries at voice 3. */
    private static Set<String> voiceThreeOf(Tune tune) {
        Set<String> found = new LinkedHashSet<>();
        for (byte[] actions : EffectScript.compile(tune).actions()) {
            for (byte action : actions) {
                if (action != 0
                        && ((action >> 3) & 3) == EffectScript.VOICELESS) {
                    found.add(OPCODES.get((action & 0xFF) >> 5));
                }
            }
        }
        return found;
    }

    /** The opcodes one tune's compiled script carries. */
    private static Set<String> opcodesOf(Tune tune) {
        Set<String> found = new LinkedHashSet<>();
        for (byte[] actions : EffectScript.compile(tune).actions()) {
            for (byte action : actions) {
                if (action != 0) {
                    found.add(OPCODES.get((action & 0xFF) >> 5));
                }
            }
        }
        return found;
    }
}
