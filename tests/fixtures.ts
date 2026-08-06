import type { LegacyBundle } from "../src/core/types";

export function legacyBundle(): LegacyBundle {
  return {
    store: {
      current: {
        id: "daily-a",
        date: "2026-08-06",
        words: [
          { en: "accept", zh: "接受；承认", right: 0, wrong: 0, mastery: 0, reverseRight: 0, reverseWrong: 0, reverseMastery: 0, reverseReviewWeight: 0, rareRight: 0, rareWrong: 0, rareMastery: 0 },
          { en: "except", zh: "除……之外；不包括", right: 0, wrong: 0, mastery: 0, reverseRight: 0, reverseWrong: 0, reverseMastery: 0, reverseReviewWeight: 0, rareRight: 0, rareWrong: 0, rareMastery: 0 },
          { en: "expect", zh: "预期；期待", right: 0, wrong: 0, mastery: 0, reverseRight: 0, reverseWrong: 0, reverseMastery: 0, reverseReviewWeight: 0, rareRight: 0, rareWrong: 0, rareMastery: 0 },
          { en: "aspect", zh: "方面；外观", right: 0, wrong: 0, mastery: 0, reverseRight: 0, reverseWrong: 0, reverseMastery: 0, reverseReviewWeight: 0, rareRight: 0, rareWrong: 0, rareMastery: 0 },
        ],
      },
      archives: [],
    },
    intensiveStore: {
      reviewedLibraryId: "daily-a",
      words: [{ en: "accept", zh: "接受；承认", spellRight: 0, spellWrong: 0, meaningRight: 0, meaningWrong: 0 }],
    },
    rootStudyStore: {
      items: [{ id: "cap\0抓", root: "cap", meaning: "抓", words: ["capture"], choiceRight: 0, choiceWrong: 0, writeRight: 0, writeWrong: 0 }],
    },
    settings: { meaningMatchMode: "contains", rootVisible: true },
  };
}

export class MemoryStorage implements Storage {
  private values = new Map<string, string>();

  get length(): number { return this.values.size; }
  clear(): void { this.values.clear(); }
  getItem(key: string): string | null { return this.values.get(key) ?? null; }
  key(index: number): string | null { return [...this.values.keys()][index] ?? null; }
  removeItem(key: string): void { this.values.delete(key); }
  setItem(key: string, value: string): void { this.values.set(key, value); }
}
