import { describe, expect, it } from "vitest";
import { rootsToText } from "../src/content/ui";

describe("content root editing", () => {
  it("does not promote lower-confidence alternatives into editable manual roots", () => {
    expect(rootsToText([
      { root: "re", meaning: "再；重新", source: "inferred" },
      { root: "rite", meaning: "仪式", source: "inferred", alternative: true },
    ])).toBe("re = 再；重新");
  });
});
