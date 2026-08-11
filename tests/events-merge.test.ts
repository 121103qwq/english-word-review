import { describe, expect, it } from "vitest";
import { DEVICE_ID_KEY, MIGRATION_BACKUP_KEY } from "../src/core/config";
import { compactSnapshot, EventStore, projectSnapshot, rebuildRootStudyStore } from "../src/core/events";
import { mergeSnapshots } from "../src/core/merge";
import { legacyBundle, MemoryStorage } from "./fixtures";

describe("v4 migration and learning event replay", () => {
  it("backs up old keys and preserves legacy checkpoint values", () => {
    const storage = new MemoryStorage();
    storage.setItem("english-word-review-v3", JSON.stringify({ sentinel: true }));
    const data = legacyBundle();
    data.store.current.words[0].right = 7;
    data.store.current.words[0].reverseReviewWeight = 3.84;
    const store = EventStore.open(data, storage);
    expect(storage.getItem(MIGRATION_BACKUP_KEY)).toContain("sentinel");
    expect(store.project().store.current.words[0].right).toBe(7);
    expect(store.project().store.current.words[0].reverseReviewWeight).toBe(3.84);
  });

  it("replays answer order exactly, then undoes by target event id", () => {
    const storage = new MemoryStorage();
    storage.setItem(DEVICE_ID_KEY, "device-a");
    const store = EventStore.open(legacyBundle(), storage);
    const wrong = store.recordAnswer({ area: "library", libraryId: "daily-a", itemId: "accept", mode: "reverse", correct: false });
    store.recordAnswer({ area: "library", libraryId: "daily-a", itemId: "accept", mode: "reverse", correct: true });
    let word = store.project().store.current.words[0];
    expect(word.reverseWrong).toBe(1);
    expect(word.reverseRight).toBe(1);
    expect(word.reverseReviewWeight).toBeCloseTo(1.92);
    store.recordUndo(wrong);
    word = store.project().store.current.words[0];
    expect(word.reverseWrong).toBe(0);
    expect(word.reverseRight).toBe(1);
    expect(word.reverseReviewWeight).toBe(0);
  });

  it("uses reset generations so old events do not revive", () => {
    const storage = new MemoryStorage();
    const store = EventStore.open(legacyBundle(), storage);
    store.recordAnswer({ area: "library", libraryId: "daily-a", itemId: "accept", mode: "forward", correct: false });
    store.recordReset("library:daily-a:forward");
    store.recordAnswer({ area: "library", libraryId: "daily-a", itemId: "accept", mode: "forward", correct: true });
    const word = store.project().store.current.words[0];
    expect({ right: word.right, wrong: word.wrong }).toEqual({ right: 1, wrong: 0 });
  });

  it("unions duplicate device logs idempotently and compacts without changing projection", () => {
    const leftStorage = new MemoryStorage();
    const rightStorage = new MemoryStorage();
    leftStorage.setItem(DEVICE_ID_KEY, "a");
    rightStorage.setItem(DEVICE_ID_KEY, "b");
    const left = EventStore.open(legacyBundle(), leftStorage);
    const right = EventStore.open(legacyBundle(), rightStorage);
    left.recordAnswer({ area: "library", libraryId: "daily-a", itemId: "accept", mode: "reverse", correct: false });
    right.recordAnswer({ area: "library", libraryId: "daily-a", itemId: "except", mode: "forward", correct: true });
    const merged = mergeSnapshots(left.getSnapshot(), right.getSnapshot(), left.getSnapshot());
    expect(merged.events).toHaveLength(2);
    const before = projectSnapshot(merged);
    const compacted = compactSnapshot(merged);
    expect(compacted.events).toHaveLength(0);
    expect(projectSnapshot(compacted)).toEqual(before);
    expect(compacted.checkpoint.vector).toEqual({ a: 1, b: 1 });
  });

  it("rebuilds root cards from current definitions, retaining stats only for identical root and meaning", () => {
    const legacy = legacyBundle();
    legacy.rootStudyStore.items[0] = {
      ...legacy.rootStudyStore.items[0],
      id: "cap\u0000old",
      root: "cap",
      meaning: "old",
      choiceRight: 5,
      writeWrong: 3,
      words: ["stale"],
    };
    const libraries = [{
      id: "daily-a",
      date: "2026-08-06",
      words: [{ en: "accept", zh: "accept", roots: [{ root: "cap", meaning: "new" }] }],
    }];
    const corrected = rebuildRootStudyStore(legacy.rootStudyStore, libraries);
    expect(corrected.items).toEqual([expect.objectContaining({
      id: "cap\u0000new", words: ["accept"], choiceRight: 0, writeWrong: 0,
    })]);

    const unchanged = rebuildRootStudyStore(legacy.rootStudyStore, [{
      ...libraries[0],
      words: [{ en: "accept", zh: "accept", roots: [{ root: "cap", meaning: "old" }] }],
    }]);
    expect(unchanged.items).toEqual([expect.objectContaining({
      id: "cap\u0000old", words: ["accept"], choiceRight: 5, writeWrong: 3,
    })]);
  });

  it("excludes lower-confidence alternative guesses from independent root study", () => {
    const rebuilt = rebuildRootStudyStore({ items: [] }, [{
      id: "daily-a",
      date: "2026-08-06",
      words: [{
        en: "rewrite",
        zh: "重写",
        roots: [
          { root: "re", meaning: "再；重新" },
          { root: "rite", meaning: "仪式", alternative: true },
        ],
      }],
    }]);

    expect(rebuilt.items.map((item) => item.root)).toEqual(["re"]);
  });
});
