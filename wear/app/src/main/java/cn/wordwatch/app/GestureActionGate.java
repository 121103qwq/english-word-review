package cn.wordwatch.app;

import cn.wordwatch.app.gesture.CardContext;
import cn.wordwatch.app.gesture.core.GestureKind;

/** Last boundary before any study mutation. Timestamps use the sensor's monotonic clock. */
final class GestureActionGate {
    enum Action { IGNORE, REVEAL, REMEMBER, HARD, REVEAL_FIRST }
    private long lastEndNanos;

    Action accept(GestureKind kind, CardContext captured, CardContext current,
                  long endedAt, long emittedAt, long now, boolean revealed) {
        if (captured == null || current == null || !captured.equals(current)) return Action.IGNORE;
        if (endedAt <= 0 || endedAt <= lastEndNanos || emittedAt < endedAt || now < emittedAt
                || now - endedAt > 1_000_000_000L) return Action.IGNORE;
        Action action;
        if (kind == GestureKind.DOUBLE_PINCH_MIDDLE) action = revealed ? Action.REMEMBER : Action.REVEAL;
        else if (kind == GestureKind.RUB_INDEX) action = revealed ? Action.HARD : Action.REVEAL_FIRST;
        else return Action.IGNORE;
        lastEndNanos = endedAt;
        return action;
    }
}
