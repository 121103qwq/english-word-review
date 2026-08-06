import { describe, expect, it } from "vitest";
import {
  isCleared,
  rareMeaningWeight,
  recencySize,
  reverseWeight,
  updateReverseReviewWeight,
  weightedRandomWeight,
} from "../src/core/algorithm";
import { editDistance, makeMeaningChoices, makeWordChoices, weightedPick } from "../src/core/selection";
import { legacyBundle } from "./fixtures";

function legacyWeight(right: number, wrong: number): number {
  const wrongBonus = Math.min(30, wrong * (wrong + 4));
  const rightRelief = Math.min(wrongBonus, right * 3);
  return 1 + wrongBonus - rightRelief + Math.max(0, 2 - right);
}

function fixedRandom(values: number[]): () => number {
  let index = 0;
  return () => values[index++ % values.length];
}

describe("weighted-random-v1 golden behavior", () => {
  it("matches every legacy wrong/right weight in the supported range", () => {
    for (let right = 0; right <= 12; right += 1) {
      for (let wrong = 0; wrong <= 12; wrong += 1) {
        expect(weightedRandomWeight({ right, wrong })).toBe(legacyWeight(right, wrong));
      }
    }
  });

  it("locks reverse +16/cap80 and correct ×0.12 order", () => {
    let weight = 0;
    for (let index = 0; index < 7; index += 1) weight = updateReverseReviewWeight(weight, false);
    expect(weight).toBe(80);
    weight = updateReverseReviewWeight(weight, true);
    expect(weight).toBeCloseTo(9.6);
    expect(reverseWeight({ right: 1, wrong: 7, reverseReviewWeight: weight })).toBe(12);
  });

  it("locks recency, rare meaning, mastery and clear thresholds", () => {
    expect([0, 1, 2, 7, 20].map(recencySize)).toEqual([0, 0, 1, 6, 6]);
    expect(Array.from({ length: 5 }, (_, index) => rareMeaningWeight(index, 5))).toEqual([1, 2, 3, 4, 5]);
    expect(isCleared([{ right: 2, wrong: 0 }, { right: 2, wrong: 0 }])).toBe(true);
    expect(isCleared([{ right: 2, wrong: 1 }, { right: 2, wrong: 0 }])).toBe(false);
  });

  it("selects the same candidate with a fixed random source and applies recent/avoid masks", () => {
    const list = [
      { id: "a", right: 0, wrong: 0 },
      { id: "b", right: 0, wrong: 2 },
      { id: "c", right: 2, wrong: 0 },
    ];
    const picked = weightedPick(
      list,
      (item) => legacyWeight(item.right, item.wrong),
      fixedRandom([0.4]),
      new Set([list[1]]),
      list[0],
    );
    expect(picked?.id).toBe("b");
  });

  it("keeps one correct answer, three unique distractors and the spelling-diversity constraint", () => {
    const words = legacyBundle().store.current.words;
    const random = fixedRandom([0.12, 0.7, 0.33, 0.91, 0.2, 0.61, 0.47, 0.8, 0.15, 0.52]);
    const target = words[0];
    const choices = makeWordChoices(words, target, random);
    expect(choices).toHaveLength(4);
    expect(new Set(choices.map((word) => word.en)).size).toBe(4);
    expect(choices.filter((word) => word === target)).toHaveLength(1);
    const distractors = choices.filter((word) => word !== target);
    const averageSpellingDistance = distractors.reduce((sum, distractor) => {
      const maxLength = Math.max(target.en.length, distractor.en.length);
      return sum + editDistance(target.en, distractor.en) / maxLength;
    }, 0) / distractors.length;
    expect(averageSpellingDistance).toBeLessThanOrEqual(0.5);
    const meanings = makeMeaningChoices(words, target, "承认", fixedRandom([0.2, 0.6, 0.8, 0.1, 0.7]));
    expect(meanings).toHaveLength(4);
    expect(new Set(meanings).size).toBe(4);
    expect(meanings).toContain("承认");
  });
});
