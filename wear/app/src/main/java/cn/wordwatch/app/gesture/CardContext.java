package cn.wordwatch.app.gesture;

import java.util.Objects;

/** Immutable identity of the learning card that may receive a gesture. */
public final class CardContext {
    public final String sessionId;
    public final String wordId;
    public final int position;
    public final long generation;

    public CardContext(String sessionId, String wordId, int position, long generation) {
        if (sessionId == null || sessionId.trim().isEmpty()) throw new IllegalArgumentException("sessionId is required");
        if (wordId == null || wordId.trim().isEmpty()) throw new IllegalArgumentException("wordId is required");
        if (position < 0) throw new IllegalArgumentException("position must be non-negative");
        if (generation < 0) throw new IllegalArgumentException("generation must be non-negative");
        this.sessionId = sessionId;
        this.wordId = wordId;
        this.position = position;
        this.generation = generation;
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof CardContext)) return false;
        CardContext value = (CardContext) other;
        return position == value.position && generation == value.generation
                && sessionId.equals(value.sessionId) && wordId.equals(value.wordId);
    }

    @Override public int hashCode() { return Objects.hash(sessionId, wordId, position, generation); }
}
