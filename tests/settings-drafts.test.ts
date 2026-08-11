import { describe, expect, it } from "vitest";
import {
  createEmptyLibraryDraft,
  isLibraryDraftEmpty,
  LibraryDraftStorage,
  libraryDraftStorageKey,
} from "../src/settings/drafts";
import type { SettingsStorageLike } from "../src/settings/storage";

class MemoryStorage implements SettingsStorageLike {
  readonly values = new Map<string, string>();
  getItem(key: string): string | null { return this.values.get(key) ?? null; }
  setItem(key: string, value: string): void { this.values.set(key, value); }
  removeItem(key: string): void { this.values.delete(key); }
}

describe("local library creation draft", () => {
  it("persists paste/table/check state locally until explicitly cleared", () => {
    const storage = new MemoryStorage();
    const drafts = new LibraryDraftStorage("windows", storage, () => new Date("2026-08-08T03:00:00.000Z"));
    const saved = drafts.update((draft) => {
      draft.name = "阅读生词";
      draft.inputMode = "table";
      draft.tableWords = ["apple", "built", ""];
      draft.checkedRows = [{ word: "apple", meaning: "苹果", rootText: "" }];
    });

    expect(saved.modifiedAt).toBe("2026-08-08T03:00:00.000Z");
    expect(new LibraryDraftStorage("windows", storage).load()?.tableWords).toEqual(["apple", "built", ""]);
    expect(storage.values.has(libraryDraftStorageKey("windows"))).toBe(true);

    drafts.clear();
    expect(drafts.load()).toBeUndefined();
  });

  it("keeps drafts platform-local and detects an untouched form", () => {
    const now = new Date(2026, 7, 8);
    const draft = createEmptyLibraryDraft("android", now);
    expect(isLibraryDraftEmpty(draft)).toBe(true);
    draft.pasteInput = "apple";
    expect(isLibraryDraftEmpty(draft)).toBe(false);

    const storage = new MemoryStorage();
    new LibraryDraftStorage("android", storage).save(draft);
    expect(new LibraryDraftStorage("windows", storage).load()).toBeUndefined();
  });
});
