package cn.wordwatch.app;

import cn.wordwatch.app.gesture.CardContext;
import cn.wordwatch.app.gesture.core.GestureKind;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static cn.wordwatch.app.GestureActionGate.Action.*;

public final class GestureActionGateTest {
    private final CardContext card = new CardContext("session", "word", 0, 1);

    @Test public void exactGesturesFollowRevealAndSelfReportMapping() {
        GestureActionGate gate = new GestureActionGate();
        assertEquals(REVEAL, accept(gate, GestureKind.DOUBLE_PINCH_MIDDLE, 100, false));
        assertEquals(REMEMBER, accept(gate, GestureKind.DOUBLE_PINCH_MIDDLE, 200, true));
        assertEquals(HARD, accept(gate, GestureKind.RUB_INDEX, 300, true));
        assertEquals(REVEAL_FIRST, accept(gate, GestureKind.RUB_INDEX, 400, false));
        assertEquals(IGNORE, accept(gate, GestureKind.UNKNOWN, 500, true));
    }

    @Test public void oneDetectionCannotRevealAndThenGrade() {
        GestureActionGate gate = new GestureActionGate();
        assertEquals(REVEAL, accept(gate, GestureKind.DOUBLE_PINCH_MIDDLE, 100, false));
        assertEquals(IGNORE, accept(gate, GestureKind.DOUBLE_PINCH_MIDDLE, 100, true));
        assertEquals(IGNORE, accept(gate, GestureKind.RUB_INDEX, 99, true));
    }

    @Test public void crossCardAndPreviousGenerationAreIgnored() {
        GestureActionGate gate = new GestureActionGate();
        CardContext[] stale = {new CardContext("other", "word", 0, 1),
                new CardContext("session", "other", 0, 1), new CardContext("session", "word", 1, 1),
                new CardContext("session", "word", 0, 0), null};
        for (CardContext captured : stale)
            assertEquals(IGNORE, gate.accept(GestureKind.DOUBLE_PINCH_MIDDLE, captured, card, 100, 110, 120, true));
        assertEquals(REMEMBER, accept(gate, GestureKind.DOUBLE_PINCH_MIDDLE, 100, true));
    }

    @Test public void expiredFutureAndReversedTimesNeverGrade() {
        GestureActionGate gate = new GestureActionGate();
        long[][] invalid = {{0, 10, 20}, {100, 90, 120}, {100, 110, 105}, {100, 110, 1_000_000_101L}};
        for (long[] times : invalid)
            assertEquals(IGNORE, gate.accept(GestureKind.RUB_INDEX, card, card, times[0], times[1], times[2], true));
        assertEquals(HARD, accept(gate, GestureKind.RUB_INDEX, 100, true));
    }

    private GestureActionGate.Action accept(GestureActionGate gate, GestureKind kind, long end, boolean revealed) {
        return gate.accept(kind, card, card, end, end + 10, end + 20, revealed);
    }
}
