import { describe, expect, it } from "vitest";
import { offlineDictionary } from "../src/dictionary";
import { decodeGzipJson } from "../src/dictionary/codec";
import { GENERATED_DICTIONARY } from "../src/dictionary/generated/data";
import rootGlossZh from "../src/dictionary/root-gloss-zh.json";
import type { DictionaryEntry, RootLexiconEntry } from "../src/dictionary/types";

const rootGlosses: Record<string, string> = rootGlossZh;

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

  it("covers every generated root gloss with a fixed Chinese construction meaning", async () => {
    const roots = await decodeGzipJson<RootLexiconEntry[]>(GENERATED_DICTIONARY.roots);
    const glosses = new Set(roots.map((root) => root.meaningEn));
    expect(glosses.size).toBe(486);
    expect(Object.keys(rootGlossZh)).toHaveLength(486);
    expect([...glosses].every((gloss) => /[\u3400-\u9fff]/u.test(rootGlosses[gloss]))).toBe(true);
  });

  it.each([
    ["approval", "prov", "好；检验；证明"],
    ["graduate", "grad", "走；步；级"],
  ])("uses the fixed Chinese construction meaning for %s", async (word, form, meaningZh) => {
    const entry = await offlineDictionary.lookup(word);
    expect(entry).not.toBeNull();
    const root = (await offlineDictionary.rootsFor(entry!)).find((candidate) => candidate.form === form);
    expect(root?.meaningZh).toBe(meaningZh);
  });

  it("never exposes an automatic root without a Chinese construction meaning", async () => {
    for (const word of ["approval", "graduate"]) {
      const entry = await offlineDictionary.lookup(word);
      expect(entry).not.toBeNull();
      const roots = await offlineDictionary.rootsFor(entry!);
      expect(roots.length).toBeGreaterThan(0);
      expect(roots.every((root) => /[\u3400-\u9fff]/u.test(root.meaningZh))).toBe(true);
    }
  });
});
