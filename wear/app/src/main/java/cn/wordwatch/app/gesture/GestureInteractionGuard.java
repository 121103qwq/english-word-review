package cn.wordwatch.app.gesture;

/** Thread-safe barrier between touchscreen/key/rotary interaction and sensor gestures. */
public final class GestureInteractionGuard {
    public static final long SUPPRESSION_NANOS = 800_000_000L;

    private long lastInteractionNanos = -1L;
    private long blockedUntilNanos;
    private boolean touchActive;

    /** Records a key or rotary interaction without changing the current touch state. */
    public synchronized void recordInteraction(long nowNanos) {
        recordInteractionLocked(nowNanos);
    }

    /** Records a touchscreen event and whether a pointer is still held afterward. */
    public synchronized void recordTouchInteraction(long nowNanos, boolean touchStillDown) {
        if (nowNanos < 0 || (lastInteractionNanos >= 0 && nowNanos < lastInteractionNanos)) return;
        recordInteractionLocked(nowNanos);
        touchActive = touchStillDown;
    }

    /**
     * Returns whether a candidate that began at candidateStartNanos may be delivered now.
     * A delayed candidate from before or during an interaction stays invalid after the quiet
     * interval; candidates must start at or after its deadline.
     */
    public synchronized boolean allows(long candidateStartNanos, long nowNanos) {
        if (candidateStartNanos < 0 || nowNanos < candidateStartNanos || touchActive) return false;
        if (lastInteractionNanos < 0) return true;
        return nowNanos >= blockedUntilNanos && candidateStartNanos >= blockedUntilNanos;
    }

    public synchronized long blockedUntilNanos() {
        return blockedUntilNanos;
    }

    /** Clears state when a caller deliberately begins a new independent interaction session. */
    public synchronized void reset() {
        lastInteractionNanos = -1L;
        blockedUntilNanos = 0L;
        touchActive = false;
    }

    private void recordInteractionLocked(long nowNanos) {
        if (nowNanos < 0 || (lastInteractionNanos >= 0 && nowNanos < lastInteractionNanos)) return;
        lastInteractionNanos = nowNanos;
        blockedUntilNanos = nowNanos > Long.MAX_VALUE - SUPPRESSION_NANOS
                ? Long.MAX_VALUE : nowNanos + SUPPRESSION_NANOS;
    }
}
