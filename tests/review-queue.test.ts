import { describe, expect, it } from "vitest";
import { buildReviewQueue } from "../src/review/session";
import { reviewCardId, type ReviewCardState } from "../src/review/types";

const NOW = Date.parse("2026-08-06T12:00:00.000Z");
const revision = { wallTime: NOW, logical: 0, deviceId: "a" };
const state = (word: string, dueAt: number, stage = 0): ReviewCardState => ({
  cardId: reviewCardId(word), wordKey: word, dueAt, stage: stage as 0 | 1 | 2 | 3 | 4 | 5,
  nextDirection: "en-to-zh", revisionClock: revision, revisionEventId: word, stats: {},
});

describe("review queue", () => {
  it("uses every current-library word once, merging duplicate meanings", () => {
    const queue = buildReviewQueue({
      mode: "current", activeLibraryId: "today", now: NOW,
      libraries: [
        { id: "today", date: "2026-08-06", words: [
          { word: "Apple", meaning: "苹果" }, { word: "apple", meaning: "苹果；公司" }, { word: "dog", meaning: "狗" },
        ] },
        { id: "old", date: "2026-08-01", words: [{ word: "old", meaning: "旧" }, { word: "apple", meaning: "苹果；水果\n公司" }] },
      ],
    });
    expect(queue.cards.map((card) => card.wordKey)).toEqual(["apple", "dog"]);
    expect(queue.cards[0].meanings).toEqual(["苹果", "公司", "水果"]);
    expect(queue.cards[0].sourceLibraryIds).toEqual(["old", "today"]);
    expect(queue.dueOldCount).toBe(0);
  });

  it("merges archived duplicates globally, excludes current duplicates, and uses at most ten due cards", () => {
    const libraries = [
      { id: "today", date: "2026-08-06", words: [{ word: "Apple", meaning: "今天的苹果" }] },
      { id: "a", date: "2026-07-01", words: [{ word: "apple", meaning: "苹果" }, { word: "b", meaning: "乙" }] },
      { id: "b", date: "2026-07-02", words: [{ word: "B", meaning: "字母B" }, { word: "c", meaning: "丙" }] },
      ...Array.from({ length: 10 }, (_, index) => ({ id: `x${index}`, date: "2026-07-01", words: [{ word: `word${index}`, meaning: String(index) }] })),
    ];
    const queue = buildReviewQueue({ mode: "spaced", activeLibraryId: "today", libraries, now: NOW });
    expect(queue.cards.filter((card) => card.origin === "old")).toHaveLength(10);
    expect(queue.cards.some((card) => card.origin === "old" && card.wordKey === "apple")).toBe(false);
    const b = [...queue.cards, ...queue.remainingDueOldCards].find((card) => card.wordKey === "b");
    expect(b?.meanings).toEqual(["乙", "字母B"]);
    expect(queue.remainingDueOldCards).toHaveLength(2);
  });

  it("derives unscheduled historical due time from its latest source date and orders due old cards deterministically", () => {
    const queue = buildReviewQueue({
      mode: "spaced", activeLibraryId: "today", now: NOW,
      reviewCards: {
        [reviewCardId("a")]: state("a", NOW - 2000, 3),
        [reviewCardId("b")]: state("b", NOW - 2000, 1),
      },
      libraries: [
        { id: "today", date: "2026-08-06", words: [] },
        { id: "old-1", date: "2026-08-01", words: [{ word: "c", meaning: "C" }, { word: "a", meaning: "A" }] },
        { id: "old-2", date: "2026-08-04", words: [{ word: "c", meaning: "另一个C" }, { word: "b", meaning: "B" }] },
      ],
    });
    expect(queue.cards.map((card) => card.wordKey)).toEqual(["c", "b", "a"]);
    expect(queue.cards[0].dueAt).toBe(Date.parse("2026-08-05T00:00:00.000Z"));
    expect(queue.cards[0].meanings).toEqual(["C", "另一个C"]);
  });

  it("keeps stable roughly balanced initial directions and supports an old-only next batch", () => {
    const libraries = [
      { id: "today", date: "2026-08-06", words: Array.from({ length: 30 }, (_, index) => ({ word: `today${index}`, meaning: "x" })) },
      { id: "old", date: "2026-07-01", words: Array.from({ length: 12 }, (_, index) => ({ word: `old${index}`, meaning: "x" })) },
    ];
    const first = buildReviewQueue({ mode: "spaced", activeLibraryId: "today", libraries, now: NOW });
    const second = buildReviewQueue({ mode: "spaced", activeLibraryId: "today", libraries: [{ ...libraries[0], words: [] }, libraries[1]], now: NOW, oldLimit: 10 });
    const directions = first.cards.filter((card) => card.origin === "current").map((card) => card.direction);
    expect(Math.abs(directions.filter((direction) => direction === "en-to-zh").length - directions.filter((direction) => direction === "zh-to-en").length)).toBeLessThanOrEqual(6);
    expect(first.remainingDueOldCards).toHaveLength(2);
    expect(second.cards).toHaveLength(10);
  });
});
