import { describe, expect, it } from "vitest";
import { offlineDictionary } from "../src/dictionary";
import { decodeGzipJson } from "../src/dictionary/codec";
import { GENERATED_DICTIONARY } from "../src/dictionary/generated/data";
import type { DictionaryEntry } from "../src/dictionary/types";

describe("offline dictionary", () => {
  it("declares the exact number of entries stored in chunks", async () => {
    const chunks = await Promise.all(
      Object.values(GENERATED_DICTIONARY.entryChunks).map((chunk) => decodeGzipJson<DictionaryEntry[]>(chunk)),
    );
    expect(chunks.reduce((total, entries) => total + entries.length, 0)).toBe(GENERATED_DICTIONARY.entryCount);
  });

  it.each(["apple", "built", "kick", "counter"])("contains %s with Chinese meaning", async (word) => {
    const entry = await offlineDictionary.lookup(word);
    expect(entry?.word).toBe(word);
    expect(entry?.translation).toMatch(/[\u3400-\u9fff]/u);
  });

  it("matches case-insensitively before suggesting", async () => {
    const result = await offlineDictionary.check("  ApPlE  ");
    expect(result.entry?.word).toBe("apple");
    expect(result.suggestions).toEqual([]);
  });

  it("suggests only verified distance-one words", async () => {
    const result = await offlineDictionary.check("applf");
    expect(result.entry).toBeNull();
    expect(result.suggestions.map((entry) => entry.word)).toContain("apple");
    expect(result.suggestions).toHaveLength(Math.min(5, result.suggestions.length));
  });

  it("does not suggest distance-two words", async () => {
    const result = await offlineDictionary.check("applzz");
    expect(result.entry).toBeNull();
    expect(result.suggestions.map((entry) => entry.word)).not.toContain("apple");
  });

  it("returns reliable roots or explicitly marked inferred roots", async () => {
    const built = await offlineDictionary.lookup("built");
    expect(built).not.toBeNull();
    const roots = await offlineDictionary.rootsFor(built!);
    expect(roots.length).toBeGreaterThan(0);
    expect(roots.every((root) => root.source === "engra" || root.inferred)).toBe(true);
  });
});
