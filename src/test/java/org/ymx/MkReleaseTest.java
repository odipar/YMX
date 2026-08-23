package org.ymx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The release stager's own checks, on synthetic cores. */
final class MkReleaseTest {

    @Test
    void theMatrixCoversEveryVariantOnce() {
        Set<String> names = new HashSet<>();
        for (MkRelease.Variant variant : MkRelease.matrix()) {
            assertTrue(names.add(variant.name()), variant.name() + " twice");
        }
        assertEquals(12, names.size(), "three units by four flag combinations");
        assertTrue(names.contains("ymxsndh-k2.bin"));
        assertTrue(names.contains("ymxsndh-k4-perf-nomask.bin"));
    }

    @Test
    void aCoreIsVerifiedAgainstTheVariantItIsNamedFor() {
        MkRelease.Variant plain = new MkRelease.Variant(1, false, false);
        byte[] core = MkSndhTest.core(1);
        MkRelease.verifyCore(core, plain);

        RuntimeException wrongUnit = assertThrows(RuntimeException.class,
                () -> MkRelease.verifyCore(core, new MkRelease.Variant(2, false, false)));
        assertTrue(String.valueOf(wrongUnit.getMessage()).contains("unit"));

        RuntimeException wrongFlags = assertThrows(RuntimeException.class,
                () -> MkRelease.verifyCore(core, new MkRelease.Variant(1, true, false)));
        assertTrue(String.valueOf(wrongFlags.getMessage()).contains("flags"));

        byte[] wrongFormat = core.clone();
        wrongFormat[MkSndh.CORE_FORMAT + 1] = 99;
        RuntimeException stale = assertThrows(RuntimeException.class,
                () -> MkRelease.verifyCore(wrongFormat, plain));
        assertTrue(String.valueOf(stale.getMessage()).contains("format version"));
    }
}
