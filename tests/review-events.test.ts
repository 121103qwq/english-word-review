import { describe, expect, it } from "vitest";
import { DEVICE_ID_KEY } from "../src/core/config";
import { compactSnapshot, EventStore, projectReviewCards } from "../src/core/events";
import { mergeSnapshots } from "../src/core/merge";
import { reviewCardId } from "../src/review/types";
import { legacyBundle, MemoryStorage } from "./fixtures";

function answer(store: EventStore, correct: boolean, attempt: "primary" | "retry" = "primary"): string {
  return store.recordReviewAnswer({
    sessionId: "session-a",
    word: " Accept ",
    direction: "en-to-zh",
    attempt,
    correct,
    sourceLibraryIds: ["daily-a"],
    answeredAt: 1_000,
  });
}

describe("review event persistence", () => {
  it("migrates old v4 snapshots with no review checkpoint", () => {
    const store = EventStore.open(legacyBundle(), new MemoryStorage());
    expect(store.getSnapshot().checkpoint.reviewCards).toBeUndefined();
    expect(store.getReviewCards()).toEqual({});
  });

  it("persists first answers, leaves retry scheduling untouched, and preserves projection through compaction", () => {
    const storage = new MemoryStorage();
    storage.setItem(DEVICE_ID_KEY, "a");
    const store = EventStore.open(legacyBundle(), storage);
    answer(store, false);
    const first = store.getReviewCard("accept")!;
    answer(store, true, "retry");
    const afterRetry = store.getReviewCard("accept")!;
    expect(afterRetry.stage).toBe(first.stage);
    expect(afterRetry.dueAt).toBe(first.dueAt);
    expect(afterRetry.nextDirection).toBe(first.nextDirection);
    expect(afterRetry.stats.a).toMatchObject({ primaryWrong: 1, retryRight: 1 });
    const before = projectReviewCards(store.getSnapshot());
    store.compact();
    expect(store.getSnapshot().events).toHaveLength(0);
    expect(projectReviewCards(store.getSnapshot())).toEqual(before);
  });

  it("merges concurrent device answers without duplicate stage promotion and is idempotent", () => {
    const leftStorage = new MemoryStorage();
    const rightStorage = new MemoryStorage();
    leftStorage.setItem(DEVICE_ID_KEY, "left");
    rightStorage.setItem(DEVICE_ID_KEY, "right");
    const left = EventStore.open(legacyBundle(), leftStorage);
    const right = EventStore.open(legacyBundle(), rightStorage);
    answer(left, true);
    answer(right, true);
    const merged = mergeSnapshots(left.getSnapshot(), right.getSnapshot(), left.getSnapshot());
    const card = projectReviewCards(merged)[reviewCardId("accept")];
    expect(card.stage).toBe(0);
    expect(card.stats.left.primaryRight).toBe(1);
    expect(card.stats.right.primaryRight).toBe(1);
    const duplicate = mergeSnapshots(merged, merged);
    expect(projectReviewCards(duplicate)).toEqual(projectReviewCards(merged));
    expect(compactSnapshot(merged).checkpoint.reviewCards).toEqual(projectReviewCards(merged));
  });

  it("rejects unknown spaced-review event versions before syncing", () => {
    const store = EventStore.open(legacyBundle(), new MemoryStorage());
    answer(store, true);
    const snapshot = store.getSnapshot() as unknown as { events: Array<{ algorithmVersion: string }> };
    snapshot.events[0].algorithmVersion = "spaced-review-v2";
    expect(() => mergeSnapshots(snapshot as never)).toThrow(/Unknown spaced review algorithm/);
  });
});
