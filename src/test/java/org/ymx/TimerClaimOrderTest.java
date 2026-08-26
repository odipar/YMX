package org.ymx;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * YMX_init releases every earlier claim before it assigns the new timer
 * map. The two are not interchangeable: ymx_handback reaches a timer
 * through the descriptor's row - CH_CTRL, CH_IER, CH_IERBIT, CH_KEEP -
 * and ymx_assign copies a new row over exactly those fields. Released
 * afterwards, a channel moved from one timer to another hands back the
 * timer it moved to and leaves the one it moved from counting, enabled,
 * its vector still in the player's blob.
 *
 * <p>Neither host in this tree reaches that path, because both stop
 * before they init again, so nothing else here would notice the order
 * changing back.</p>
 */
class TimerClaimOrderTest {

    @Test
    void initReleasesEveryClaimBeforeItAssigns() throws IOException {
        String player = Files.readString(Path.of("68k", "YMX.S"));
        int init = player.indexOf("\nYMX_init:");
        assertTrue(init >= 0, "YMX_init has moved");
        int end = player.indexOf("\nymx_", init + 1);
        String body = player.substring(init, end < 0 ? player.length() : end);

        int release = body.indexOf("bsr     ymx_handback");
        int assign = body.indexOf("bsr     ymx_assign");
        assertTrue(release >= 0, "YMX_init calls no ymx_handback");
        assertTrue(assign >= 0, "YMX_init calls no ymx_assign");
        assertTrue(release < assign,
                "YMX_init assigns the new timer map before it releases the"
                        + " earlier claims, so a channel that moves between"
                        + " timers hands back the wrong one");
    }

    /** And it releases all four, not only the channels the new tune uses. */
    @Test
    void everyChannelIsReleased() throws IOException {
        String player = Files.readString(Path.of("68k", "YMX.S"));
        int init = player.indexOf("\nYMX_init:");
        int assign = player.indexOf("bsr     ymx_assign", init);
        String before = player.substring(init, assign);
        assertTrue(before.contains("YMX_CHANNELS-1"),
                "the release loop does not walk every channel");
        assertTrue(before.contains("dbra") && before.contains("CH_SIZE"),
                "the release loop does not step the descriptors");
    }
}
