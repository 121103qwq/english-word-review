import { decodeGzipJson } from "./codec";
import type {
  DictionaryCheckResult,
  DictionaryEntry,
  DictionaryRoot,
  EncodedDictionarySource,
  RootLexiconEntry,
} from "./types";

export function normalizeDictionaryWord(value: string): string {
  return value.trim().replaceAll("’", "'").toLocaleLowerCase("en-US");
}

export function levenshteinDistance(left: string, right: string, maximum = Number.POSITIVE_INFINITY): number {
  if (Math.abs(left.length - right.length) > maximum) return maximum + 1;
  let previous = Array.from({ length: right.length + 1 }, (_, index) => index);
  for (let leftIndex = 1; leftIndex <= left.length; leftIndex += 1) {
    const current = [leftIndex];
    let rowMinimum = current[0];
    for (let rightIndex = 1; rightIndex <= right.length; rightIndex += 1) {
      const cost = left[leftIndex - 1] === right[rightIndex - 1] ? 0 : 1;
      current[rightIndex] = Math.min(
        current[rightIndex - 1] + 1,
        previous[rightIndex] + 1,
        previous[rightIndex - 1] + cost,
      );
      rowMinimum = Math.min(rowMinimum, current[rightIndex]);
    }
    if (rowMinimum > maximum) return maximum + 1;
    previous = current;
  }
  return previous[right.length];
}

function chunkKey(value: string): string {
  return `${value[0] ?? "_"}${value[1] ?? "_"}`;
}

function deleteVariants(word: string): Set<string> {
  const result = new Set([word]);
  for (let index = 0; index < word.length; index += 1) {
    result.add(word.slice(0, index) + word.slice(index + 1));
  }
  return result;
}

export class OfflineDictionary {
  readonly dictionaryVersion: string;
  readonly entryCount: number;
  private readonly entryCache = new Map<string, Promise<DictionaryEntry[]>>();
  private readonly deleteCache = new Map<string, Promise<Record<string, string[]>>>();
  private rootsPromise?: Promise<RootLexiconEntry[]>;

  constructor(private readonly source: EncodedDictionarySource) {
    this.dictionaryVersion = source.dictionaryVersion;
    this.entryCount = source.entryCount;
  }

  private loadEntries(key: string): Promise<DictionaryEntry[]> {
    const encoded = this.source.entryChunks[key];
    if (!encoded) return Promise.resolve([]);
    let cached = this.entryCache.get(key);
    if (!cached) {
      cached = decodeGzipJson<DictionaryEntry[]>(encoded);
      this.entryCache.set(key, cached);
    }
    return cached;
  }

  private loadDeletes(key: string): Promise<Record<string, string[]>> {
    const encoded = this.source.deleteChunks[key];
    if (!encoded) return Promise.resolve({});
    let cached = this.deleteCache.get(key);
    if (!cached) {
      cached = decodeGzipJson<Record<string, string[]>>(encoded);
      this.deleteCache.set(key, cached);
    }
    return cached;
  }

  private loadRoots(): Promise<RootLexiconEntry[]> {
    this.rootsPromise ??= decodeGzipJson<RootLexiconEntry[]>(this.source.roots);
    return this.rootsPromise;
  }

  async lookup(input: string): Promise<DictionaryEntry | null> {
    const word = normalizeDictionaryWord(input);
    if (!word) return null;
    const entries = await this.loadEntries(chunkKey(word));
    let low = 0;
    let high = entries.length - 1;
    while (low <= high) {
      const middle = (low + high) >>> 1;
      const comparison = entries[middle].word.localeCompare(word, "en");
      if (comparison === 0) return entries[middle];
      if (comparison < 0) low = middle + 1;
      else high = middle - 1;
    }
    return null;
  }

  async suggest(input: string, limit = 5): Promise<DictionaryEntry[]> {
    const word = normalizeDictionaryWord(input);
    if (!word || limit <= 0) return [];
    // Exact matching must happen first: valid words never enter the error/suggestion branch.
    if (await this.lookup(word)) return [];

    const variants = deleteVariants(word);
    const indexes = await Promise.all([...new Set([...variants].map(chunkKey))].map((key) => this.loadDeletes(key)));
    const candidateWords = new Set<string>();
    for (const index of indexes) {
      for (const variant of variants) {
        for (const candidate of index[variant] ?? []) candidateWords.add(candidate);
      }
    }
    const candidates = (
      await Promise.all([...candidateWords].map((candidate) => this.lookup(candidate)))
    ).filter((entry): entry is DictionaryEntry => entry !== null && levenshteinDistance(word, entry.word, 1) === 1);
    candidates.sort((left, right) => left.frequencyRank - right.frequencyRank || left.word.localeCompare(right.word, "en"));
    return candidates.slice(0, Math.min(5, limit));
  }

  async check(input: string): Promise<DictionaryCheckResult> {
    const normalized = normalizeDictionaryWord(input);
    const entry = await this.lookup(normalized);
    return {
      input,
      normalized,
      entry,
      suggestions: entry ? [] : await this.suggest(normalized),
    };
  }

  async rootsFor(entry: DictionaryEntry): Promise<DictionaryRoot[]> {
    const reliable = entry.roots.filter((root) => root.meaningZh.trim());
    if (reliable.length) return reliable;

    // ECDICT records irregular forms with `0:<lemma>` in `exchange`.  They do
    // not necessarily have an ENGRa decomposition and cannot be discovered by
    // the spelling-based fallback below (for example, built -> build).
    const lemma = entry.forms["0"]?.trim().toLocaleLowerCase("en-US");
    if (lemma && lemma !== entry.word && /^[a-z]+$/u.test(lemma)) {
      const lemmaEntry = await this.lookup(lemma);
      if (lemmaEntry) {
        const meaningZh = lemmaEntry.translation
          .split("\n")
          .find((line) => /[\u3400-\u9fff]/u.test(line))
          ?.replace(/^[a-z]+\.\s*/iu, "")
          .trim() ?? "";
        if (meaningZh) {
          return [{
            form: lemma,
            meaningZh,
            meaningEn: "",
            kind: "lemma",
            inferred: true,
            source: "inferred",
          }];
        }
      }
    }

    const roots = await this.loadRoots();
    const letters = entry.word.replace(/[^a-z]/gu, "");
    const matches: Array<RootLexiconEntry & { start: number; end: number }> = [];
    for (const root of roots) {
      if (root.form.length < 3 || !root.meaningZh.trim()) continue;
      let start = letters.indexOf(root.form);
      while (start >= 0) {
        const allowed = root.position === "any" || (root.position === "prefix" && start === 0) ||
          (root.position === "suffix" && start + root.form.length === letters.length);
        if (allowed) matches.push({ ...root, start, end: start + root.form.length });
        start = letters.indexOf(root.form, start + 1);
      }
    }
    matches.sort((left, right) => right.form.length - left.form.length || left.start - right.start);
    const covered = new Set<number>();
    const selected: typeof matches = [];
    for (const match of matches) {
      const positions = Array.from({ length: match.end - match.start }, (_, index) => match.start + index);
      if (positions.some((position) => covered.has(position))) continue;
      selected.push(match);
      positions.forEach((position) => covered.add(position));
    }
    if (!selected.some((root) => root.form.length >= 3) || covered.size / Math.max(1, letters.length) < 0.6) return [];
    return selected
      .sort((left, right) => left.start - right.start)
      .map(({ start: _start, end: _end, position: _position, ...root }) => ({
        ...root,
        inferred: true,
        source: "inferred" as const,
      }));
  }

  clearCache(): void {
    this.entryCache.clear();
    this.deleteCache.clear();
    this.rootsPromise = undefined;
  }
}
