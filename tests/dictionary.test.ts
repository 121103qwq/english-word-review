import { describe, expect, it } from "vitest";
import { checkWord, lookupWord, offlineDictionary } from "../src/dictionary";
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
  }, 15_000);

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

  it("uses the productive-affix fallback for unusual instead of the bad upstream us root", async () => {
    const unusual = await offlineDictionary.lookup("unusual");
    expect(unusual).not.toBeNull();
    const roots = await offlineDictionary.rootsFor(unusual!);
    expect(roots.filter((root) => !root.alternative).map(({ form, meaningZh }) => ({ form, meaningZh }))).toEqual([
      { form: "un-", meaningZh: "不；相反" },
      { form: "usual", meaningZh: "平常的；通常的" },
    ]);
    expect(roots.every((root) => root.inferred && root.source === "inferred")).toBe(true);
    expect(roots.some((root) => root.form === "us" || root.meaningZh === "我们")).toBe(false);
  });

  it.each([
    "unusual",
    "unhappy",
    "happiness",
    "kindness",
    "careless",
    "helpful",
    "preview",
    "rewrite",
    "misprint",
    "nonstop",
    "teacher",
    "quickly",
  ])("provides a Chinese construction hint for derived word %s", async (word) => {
    const entry = await offlineDictionary.lookup(word);
    expect(entry).not.toBeNull();
    const roots = await offlineDictionary.rootsFor(entry!);
    expect(roots.length).toBeGreaterThan(0);
    expect(roots.every((root) => /[\u3400-\u9fff]/u.test(root.meaningZh))).toBe(true);
    expect(roots.every((root) => root.source === "engra" || root.inferred)).toBe(true);
  });

  it("passes inferred construction hints through lookup and manual-library checking", async () => {
    const lookup = await lookupWord("unusual");
    const checked = await checkWord("unusual");
    expect(lookup?.roots.filter((root) => !root.alternative).map((root) => root.form)).toEqual(["un-", "usual"]);
    expect(checked.entry?.roots.filter((root) => !root.alternative).map((root) => root.form)).toEqual(["un-", "usual"]);
  });

  it("selects the base-word part of speech required by a productive suffix", async () => {
    const entry = await offlineDictionary.lookup("kindness");
    const roots = await offlineDictionary.rootsFor(entry!);
    expect(roots[0]).toMatchObject({ form: "kind", meaningZh: "亲切的；仁慈的" });
  });

  it("rejects person names as derivation bases and restores doubled consonants", async () => {
    const entry = await offlineDictionary.lookup("wedding");
    const roots = await offlineDictionary.rootsFor(entry!);
    expect(roots.map((root) => root.form)).toEqual(["wed", "-ing"]);
    expect(roots[0]?.meaningZh).toMatch(/结婚/u);
    expect(roots.some((root) => root.form === "wedd" || /人名/u.test(root.meaningZh))).toBe(false);
  });

  it.each([
    ["nuclear", "nucleus", "-ar"],
    ["cellular", "cell", "-ular"],
    ["muscular", "muscle", "-ular"],
    ["familiar", "family", "-ar"],
  ])("restores the dictionary base across a Latin adjective boundary for %s", async (word, base, suffix) => {
    const entry = await offlineDictionary.lookup(word);
    expect(entry).not.toBeNull();
    const roots = await offlineDictionary.rootsFor(entry!);
    expect(roots.map((root) => root.form)).toEqual([base, suffix]);
    expect(roots.every((root) => /[\u3400-\u9fff]/u.test(root.meaningZh))).toBe(true);
  });

  it.each([
    ["running", "run", "-ing"],
    ["planned", "plan", "-ed"],
    ["swimming", "swim", "-ing"],
  ])("retains the productive suffix for inflected form %s", async (word, lemma, suffix) => {
    const entry = await offlineDictionary.lookup(word);
    const roots = await offlineDictionary.rootsFor(entry!);
    expect(roots.map((root) => root.form)).toEqual([lemma, suffix]);
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

  it.each([
    ["unhappy", ["un-", "happy"]],
    ["rewrite", ["re-", "write"]],
    ["careless", ["care", "-less"]],
    ["overcook", ["over-", "cook"]],
  ])("keeps useful boundary affixes when parsing %s", async (word, expectedForms) => {
    const entry = await offlineDictionary.lookup(word);
    expect(entry).not.toBeNull();
    const roots = await offlineDictionary.rootsFor(entry!);
    expect(roots.map((root) => root.form)).toEqual(expect.arrayContaining(expectedForms));
    expect(roots.every((root) => root.meaningZh.trim())).toBe(true);
  });

  it("separates a lower-confidence overlapping parse from the primary guess", async () => {
    const entry = await offlineDictionary.lookup("rewrite");
    const roots = await offlineDictionary.rootsFor(entry!);
    expect(roots.filter((root) => !root.alternative).map((root) => root.form)).toEqual(["re-", "write"]);
    expect(roots.some((root) => root.alternative)).toBe(true);
  });

  it("does not promote a coincidental short boundary match into the primary construction", async () => {
    const entry = await offlineDictionary.lookup("relationship");
    const roots = await offlineDictionary.rootsFor(entry!);

    expect(roots.filter((root) => !root.alternative).map((root) => root.form)).not.toContain("re");
  });
});
