package cn.wordwatch.app.gesture;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class GestureInteractionGuardTest {
    private static final long INTERACTION = 10_000_000_000L;
    private static final long QUIET = GestureInteractionGuard.SUPPRESSION_NANOS;

    @Test public void queuedCandidateFromBeforeTouchStaysInvalidAfterDeliveryDelay() {
        GestureInteractionGuard guard = new GestureInteractionGuard();
        guard.recordTouchInteraction(INTERACTION, true);
        guard.recordTouchInteraction(INTERACTION + 180_000_000L, true);
        guard.recordTouchInteraction(INTERACTION + 260_000_000L, false);

        long delayedDelivery = INTERACTION + 2 * QUIET;
        assertFalse(guard.allows(INTERACTION - 10_000_000L, delayedDelivery));
        assertFalse(guard.allows(INTERACTION + 100_000_000L, delayedDelivery));
        assertTrue(guard.allows(INTERACTION + 260_000_000L + QUIET, delayedDelivery + 1));
    }

    @Test public void heldTouchBlocksRecognitionUntilReleaseAndFullQuietInterval() {
        GestureInteractionGuard guard = new GestureInteractionGuard();
        guard.recordTouchInteraction(INTERACTION, true);

        long heldFor = INTERACTION + 3 * QUIET;
        assertFalse(guard.allows(heldFor, heldFor));

        guard.recordTouchInteraction(heldFor, false);
        long releaseDeadline = heldFor + QUIET;
        assertEquals(releaseDeadline, guard.blockedUntilNanos());
        assertFalse(guard.allows(releaseDeadline - 1, releaseDeadline + 2 * QUIET));
        assertFalse(guard.allows(releaseDeadline, releaseDeadline - 1));
        assertTrue(guard.allows(releaseDeadline, releaseDeadline));
    }

    @Test public void repeatedKeyOrCrownInteractionsExtendDeadlineFromLatestEvent() {
        GestureInteractionGuard guard = new GestureInteractionGuard();
        guard.recordInteraction(INTERACTION);
        long firstDeadline = INTERACTION + QUIET;
        guard.recordInteraction(INTERACTION + 400_000_000L);
        long latestDeadline = INTERACTION + 400_000_000L + QUIET;

        assertEquals(latestDeadline, guard.blockedUntilNanos());
        assertFalse(guard.allows(firstDeadline, latestDeadline));
        assertFalse(guard.allows(latestDeadline, latestDeadline - 1));
        assertTrue(guard.allows(latestDeadline, latestDeadline));
    }

    @Test public void outOfOrderTimestampCannotShortenBarrierOrReleaseTouch() {
        GestureInteractionGuard guard = new GestureInteractionGuard();
        guard.recordTouchInteraction(INTERACTION + 100_000_000L, true);
        long deadline = guard.blockedUntilNanos();
        guard.recordTouchInteraction(INTERACTION, false);

        assertEquals(deadline, guard.blockedUntilNanos());
        assertFalse(guard.allows(deadline + QUIET, deadline + QUIET));
    }
}
