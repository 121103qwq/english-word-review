import { describe, expect, it } from "vitest";
import {
  abandonReviewSession,
  buildReviewQueue,
  createReviewSession,
  getNextOldBatchOptions,
  getCurrentReviewQuestion,
  getReviewCompletionStats,
  isCorrectEnglishAnswer,
  resumeReviewSession,
  submitReviewAnswer,
} from "../src/review/session";

const now = Date.parse("2026-08-06T12:00:00.000Z");

function session(wordCount = 7) {
  const queue = buildReviewQueue({
    mode: "current", activeLibraryId: "today", now,
    libraries: [{ id: "today", date: "2026-08-06", words: Array.from({ length: wordCount }, (_, index) => ({ word: `word ${index}`, meaning: `义${index}` })) }],
  });
  return createReviewSession({ queue, activeLibraryId: "today", id: "session", now, nextDueAt: now + 1 });
}

function answerFor(question: NonNullable<ReturnType<typeof getCurrentReviewQuestion>>, correct: boolean) {
  if (question.direction === "en-to-zh") return { remembered: correct };
  return { answer: correct ? question.word.toUpperCase().replace(" ", "   ") : "not the word" };
}

describe("review session", () => {
  it("requires normalized exact English input while accepting self-rated English-to-Chinese", () => {
    expect(isCorrectEnglishAnswer("  NEW   YORK ", "new york")).toBe(true);
    expect(isCorrectEnglishAnswer("new-york", "new york")).toBe(false);
    let state = session(1);
    const current = getCurrentReviewQuestion(state)!;
    const result = submitReviewAnswer(state, {
      questionId: current.id,
      ...(current.direction === "zh-to-en" ? { answer: current.word.toUpperCase().replace(" ", "   ") } : { remembered: true }),
      now,
    });
    expect(result.answer.correct).toBe(true);
    expect(result.complete).toBe(true);
  });

  it("keeps a fixed question snapshot through resume and supports explicit abandonment", () => {
    const state = session(2);
    const recovered = resumeReviewSession(JSON.parse(JSON.stringify(state)))!;
    expect(getCurrentReviewQuestion(recovered)).toEqual(getCurrentReviewQuestion(state));
    const abandoned = abandonReviewSession(recovered, now + 1);
    expect(getCurrentReviewQuestion(abandoned)).toBeUndefined();
    expect(abandoned.answers).toEqual([]);
    expect(resumeReviewSession({ schemaVersion: 2 })).toBeUndefined();
  });

  it("places a wrong primary retry after five other primary questions, then retains its first-answer result", () => {
    let state = session(7);
    const first = getCurrentReviewQuestion(state)!;
    let result = submitReviewAnswer(state, { questionId: first.id, ...answerFor(first, false), now });
    state = result.session;
    for (let index = 0; index < 5; index += 1) {
      const current = getCurrentReviewQuestion(state)!;
      expect(current.attempt).toBe("primary");
      result = submitReviewAnswer(state, { questionId: current.id, ...answerFor(current, true), now: now + index + 1 });
      state = result.session;
    }
    const retry = getCurrentReviewQuestion(state)!;
    expect(retry).toMatchObject({ attempt: "retry", direction: first.direction, wordKey: first.wordKey });
    state = submitReviewAnswer(state, { questionId: retry.id, ...answerFor(retry, true), now: now + 10 }).session;
    const stats = getReviewCompletionStats(state);
    expect(stats.primary).toMatchObject({ total: 6, correct: 5, wrong: 1 });
    expect(stats.retry).toMatchObject({ total: 1, correct: 1, wrong: 0 });
  });

  it("puts a retry at the end when there are fewer than five other primary questions and reports completion stats", () => {
    let state = session(2);
    const first = getCurrentReviewQuestion(state)!;
    state = submitReviewAnswer(state, { questionId: first.id, ...answerFor(first, false), now }).session;
    const second = getCurrentReviewQuestion(state)!;
    state = submitReviewAnswer(state, { questionId: second.id, ...answerFor(second, true), now }).session;
    const retry = getCurrentReviewQuestion(state)!;
    expect(retry.attempt).toBe("retry");
    state = submitReviewAnswer(state, { questionId: retry.id, ...answerFor(retry, true), now }).session;
    const stats = getReviewCompletionStats(state);
    expect(stats.primary.accuracy).toBe(0.5);
    expect(stats.retry.accuracy).toBe(1);
    expect(stats.remainingDueOldCount).toBe(0);
    expect(stats.nextDueAt).toBe(now + 1);
  });

  it("continues with the current due set after submitted cards become future-due", () => {
    const oldWords = Array.from({ length: 12 }, (_, index) => ({ word: `old${index}`, meaning: "旧" }));
    const queue = buildReviewQueue({
      mode: "spaced", activeLibraryId: "today", now,
      libraries: [
        { id: "today", date: "2026-08-06", words: [{ word: "today", meaning: "今天" }] },
        { id: "old", date: "2026-07-01", words: oldWords },
      ],
    });
    const state = createReviewSession({ queue, activeLibraryId: "today", id: "first", now });
    const futureSchedules = Object.fromEntries(queue.cards.filter((card) => card.origin === "old").map((card) => [card.cardId, {
      cardId: card.cardId, wordKey: card.wordKey, stage: 1 as const, dueAt: now + 24 * 60 * 60 * 1000,
      nextDirection: card.direction, revisionClock: { wallTime: now, logical: 0, deviceId: "a" }, revisionEventId: card.cardId, stats: {},
    }]));
    const next = buildReviewQueue({
      mode: "spaced", activeLibraryId: "today", now, reviewCards: futureSchedules,
      ...getNextOldBatchOptions(state),
      libraries: [
        { id: "today", date: "2026-08-06", words: [{ word: "today", meaning: "今天" }] },
        { id: "old", date: "2026-07-01", words: oldWords },
      ],
    });
    expect(getNextOldBatchOptions(state)).toEqual({ includeCurrent: false });
    expect(next.cards.map((card) => card.wordKey)).toEqual(queue.remainingDueOldCards.map((card) => card.wordKey));
    expect(next.cards.every((card) => card.origin === "old")).toBe(true);
  });
});
