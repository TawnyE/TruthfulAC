package ret.tawny.truthful.sync;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TeleportQueue regression tests, focused on the disabler surface: the
 * non-consuming near-pending test, the flood counter, and that match/confirm
 * still consume exactly one entry.
 */
class TeleportQueueTest {

    private static TeleportQueue queue() {
        return new TeleportQueue(50, 5000L);
    }

    @Test
    void nearPendingIsNonConsuming() {
        TeleportQueue q = queue();
        q.add(1, new Location(null, 100, 64, 100), false);

        assertTrue(q.isNearPending(100, 64, 100, 4.0));
        assertTrue(q.isNearPending(101.9, 64, 100, 4.0), "2-block landing radius");
        assertFalse(q.isNearPending(100 + 2.1, 64, 100, 4.0));

        // Repeated checks must not consume: the entry stays available for
        // the real match/consume in the movement path.
        assertTrue(q.isNearPending(100, 64, 100, 4.0));
        assertEquals(1, q.getPendingCount());

        assertFalse(q.isNearPending(10_000, 64, 10_000, 4.0), "ghost positions match nothing");
    }

    @Test
    void matchConsumesExactlyOneEntry() {
        TeleportQueue q = queue();
        q.add(1, new Location(null, 100, 64, 100), false);
        q.add(2, new Location(null, 200, 64, 200), false);

        assertNotNull(q.match(100, 64, 100));
        assertEquals(1, q.getPendingCount());
        assertNull(q.match(100, 64, 100), "matched entry is gone");
        assertNotNull(q.match(200, 64, 200));
        assertEquals(0, q.getPendingCount());
    }

    @Test
    void confirmRemovesById() {
        TeleportQueue q = queue();
        q.add(7, new Location(null, 100, 64, 100), false);

        assertNotNull(q.confirm(7));
        assertNull(q.confirm(7), "confirm is one-shot");
        assertEquals(0, q.getPendingCount());
        assertFalse(q.isNearPending(100, 64, 100, 4.0));
    }

    @Test
    void floodDetectorCountsServerCorrectionsOnly() {
        TeleportQueue q = queue();

        // Legit pace: a handful of teleports in the window.
        for (int i = 0; i < 12; i++) {
            q.add(i, new Location(null, i, 64, 0), false);
        }
        assertFalse(q.isFlooding(), "12 corrections / 5s is aggressive but not disabler pace");

        // Disabler pace: one correction per far packet.
        q.add(99, new Location(null, 0, 64, 99), false);
        assertTrue(q.isFlooding(), "13+ server corrections / 5s is disabler churn");

        // Lagbacks (our own setbacks) must NOT feed the flood counter:
        // otherwise a punished cheater could weaponize their own lagbacks.
        TeleportQueue q2 = queue();
        for (int i = 0; i < 30; i++) {
            q2.add(i, new Location(null, i, 64, 0), true);
        }
        assertFalse(q2.isFlooding(), "lagback teleports are exempt from the flood count");
    }
}
