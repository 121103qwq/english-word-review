import { describe, expect, it } from "vitest";
import { advanceReviewCard, REVIEW_INTERVALS_MS } from "../src/review/scheduler";
import type { HybridClock } from "../src/core/types";

const DAY = 24 * 60 * 60 * 1000;
const clock = (wallTime: number): HybridClock => ({ wallTime, logical: 0, deviceId: "test" });

describe("spaced-review-v1 scheduler", () => {
  it("creates new cards at the one-day stage and alternates after a correct first answer", () => {
    const state = advanceReviewCard({
      word: "  Take   Off ", direction: "en-to-zh", correct: true, at: 100, clock: clock(100), eventId: "a",
    });
    expect(state).toMatchObject({ wordKey: "take off", stage: 0, dueAt: 100 + DAY, nextDirection: "zh-to-en" });
  });

  it("advances all six intervals and caps correct cards at sixty days", () => {
    let state = advanceReviewCard({ word: "accept", direction: "en-to-zh", correct: true, at: 0, clock: clock(0), eventId: "new" });
    for (let stage = 1; stage <= 5; stage += 1) {
      const at = state.dueAt;
      state = advanceReviewCard({ word: "accept", previous: state, direction: state.nextDirection, correct: true, at, clock: clock(at), eventId: `${stage}` });
      expect(state.stage).toBe(stage);
      expect(state.dueAt - at).toBe(REVIEW_INTERVALS_MS[stage]);
    }
    const cappedAt = state.dueAt;
    state = advanceReviewCard({ word: "accept", previous: state, direction: state.nextDirection, correct: true, at: cappedAt, clock: clock(cappedAt), eventId: "cap" });
    expect(state.stage).toBe(5);
    expect(state.dueAt - cappedAt).toBe(60 * DAY);
  });

  it("records an early correct answer without prematurely advancing its due stage", () => {
    const first = advanceReviewCard({ word: "accept", direction: "en-to-zh", correct: true, at: 0, clock: clock(0), eventId: "a" });
    const early = advanceReviewCard({ word: "accept", previous: first, direction: "zh-to-en", correct: true, at: 1, clock: clock(1), eventId: "b" });
    expect(early.stage).toBe(0);
    expect(early.dueAt).toBe(first.dueAt);
    expect(early.nextDirection).toBe("en-to-zh");
  });

  it("resets any primary error to one day and retains its direction", () => {
    const first = advanceReviewCard({ word: "accept", direction: "en-to-zh", correct: true, at: 0, clock: clock(0), eventId: "a" });
    const due = advanceReviewCard({ word: "accept", previous: first, direction: "zh-to-en", correct: true, at: first.dueAt, clock: clock(first.dueAt), eventId: "b" });
    const wrongAt = due.dueAt - 1;
    const wrong = advanceReviewCard({ word: "accept", previous: due, direction: "en-to-zh", correct: false, at: wrongAt, clock: clock(wrongAt), eventId: "c" });
    expect(wrong).toMatchObject({ stage: 0, dueAt: wrongAt + DAY, nextDirection: "en-to-zh" });
  });
});
