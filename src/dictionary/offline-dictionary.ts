import { decodeGzipJson } from "./codec";
import rootGlossZh from "./root-gloss-zh.json";
import type {
  DictionaryCheckResult,
  DictionaryEntry,
  DictionaryRoot,
  EncodedDictionarySource,
  RootLexiconEntry,
} from "./types";

const rootGlosses: Record<string, string> = rootGlossZh;

interface ProductiveAffix {
  form: string;
  meaningZh: string;
}

interface ProductiveDerivation {
  prefix?: ProductiveAffix;
  suffix?: ProductiveAffix;
  base: DictionaryEntry;
  baseMeaningZh: string;
  affixLength: number;
  exactStem: boolean;
}

const PRODUCTIVE_PREFIXES: readonly ProductiveAffix[] = [
  { form: "under", meaningZh: "在下；不足" },
  { form: "super", meaningZh: "在上；超过" },
  { form: "inter", meaningZh: "在……之间；相互" },
  { form: "micro", meaningZh: "微小" },
  { form: "multi", meaningZh: "多；多个" },
  { form: "post", meaningZh: "在后；之后" },
  { form: "over", meaningZh: "在上；过度" },
  { form: "anti", meaningZh: "反对；抵抗" },
  { form: "auto", meaningZh: "自己；自动" },
  { form: "semi", meaningZh: "半；部分" },
  { form: "non", meaningZh: "非；不" },
  { form: "mis", meaningZh: "错误；不当" },
  { form: "pre", meaningZh: "预先；在前" },
  { form: "sub", meaningZh: "在下；次级" },
  { form: "dis", meaningZh: "不；相反；分开" },
  { form: "un", meaningZh: "不；相反" },
  { form: "re", meaningZh: "再；重新" },
  { form: "co", meaningZh: "共同；一起" },
];

const PRODUCTIVE_SUFFIXES: readonly ProductiveAffix[] = [
  { form: "ation", meaningZh: "行为；过程；结果" },
  { form: "ical", meaningZh: "与……有关的；具有……特征的" },
  { form: "less", meaningZh: "没有……的" },
  { form: "ness", meaningZh: "性质；状态" },
  { form: "ment", meaningZh: "行为；过程；结果" },
  { form: "able", meaningZh: "能够……的；可……的" },
  { form: "ible", meaningZh: "能够……的；可……的" },
  { form: "ship", meaningZh: "身份；关系；状态" },
  { form: "hood", meaningZh: "时期；身份；状态" },
  { form: "ward", meaningZh: "朝向……" },
  { form: "tion", meaningZh: "行为；过程；结果" },
  { form: "sion", meaningZh: "行为；过程；结果" },
  { form: "ful", meaningZh: "充满……的；具有……的" },
  { form: "ous", meaningZh: "具有……性质的" },
  { form: "ive", meaningZh: "具有……性质的" },
  { form: "ity", meaningZh: "性质；状态" },
  { form: "ize", meaningZh: "使成为；使……化" },
  { form: "ise", meaningZh: "使成为；使……化" },
  { form: "ify", meaningZh: "使成为" },
  { form: "ism", meaningZh: "主义；体系；现象" },
  { form: "ist", meaningZh: "从事……的人；……者" },
  { form: "ial", meaningZh: "与……有关的" },
  { form: "al", meaningZh: "与……有关的；行为或结果" },
  { form: "ic", meaningZh: "与……有关的；具有……特征的" },
  { form: "ly", meaningZh: "以……方式；具有……性质" },
  { form: "er", meaningZh: "做……的人或事物" },
  { form: "or", meaningZh: "做……的人或事物" },
  { form: "ed", meaningZh: "处于……状态的；已经……的" },
  { form: "ing", meaningZh: "正在……的；与……有关的" },
  { form: "en", meaningZh: "使成为；变得" },
  { form: "y", meaningZh: "具有……特征的" },
];

function preferredBaseParts(suffix?: string): readonly string[] {
  if (["ness", "ly", "ity"].includes(suffix ?? "")) return ["a", "adj"];
  if (["er", "or", "ment", "tion", "sion", "ation", "able", "ible", "ed", "ing"].includes(suffix ?? "")) {
    return ["v", "vi", "vt"];
  }
  if (["ful", "less", "ship", "hood"].includes(suffix ?? "")) return ["n"];
  return [];
}

function briefChineseMeaning(translation: string, preferredParts: readonly string[] = []): string {
  const lines = translation.split("\n").filter((item) => /[\u3400-\u9fff]/u.test(item));
  const preferred = lines.find((line) => preferredParts.some((part) =>
    new RegExp(`^\\s*${part}\\.`, "iu").test(line)));
  const line = preferred ?? lines[0] ?? "";
  const cleaned = line.replace(/^[a-z]+\.\s*/iu, "").replace(/\[[^\]]+\]/gu, "").trim();
  return cleaned.split(/[,，;；]/u).map((item) => item.trim())
    .filter((item) => /[\u3400-\u9fff]/u.test(item)).slice(0, 2).join("；");
}

function restoredBaseForms(stem: string, suffix?: string): string[] {
  const forms = new Set([stem]);
  if (stem.endsWith("i")) forms.add(`${stem.slice(0, -1)}y`);
  if (/([^aeiou])\1$/u.test(stem)) forms.add(stem.slice(0, -1));
  if (!stem.endsWith("e")) forms.add(`${stem}e`);
  if (suffix === "ation") forms.add(`${stem}ate`);
  if (stem.endsWith("abil")) forms.add(`${stem.slice(0, -4)}able`);
  if (stem.endsWith("ibil")) forms.add(`${stem.slice(0, -4)}ible`);
  return [...forms].filter((form) => form.length >= 3 && /^[a-z]+$/u.test(form));
}

function applyRootGloss<T extends { meaningEn: string; meaningZh: string }>(root: T): T {
  const meaningZh = rootGlosses[root.meaningEn.trim()]?.trim() ?? "";
  // Generated dictionaries before the gloss mapping used an ECDICT definition
  // of the root's spelling.  An unmapped automatic root must stay hidden,
  // rather than falling back to that unrelated word definition or English.
  return { ...root, meaningZh };
}

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
      cached = decodeGzipJson<DictionaryEntry[]>(encoded).then((entries) => entries.map((entry) => ({
        ...entry,
        roots: entry.roots.map(applyRootGloss),
      })));
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
    this.rootsPromise ??= decodeGzipJson<RootLexiconEntry[]>(this.source.roots).then((roots) => roots.map(applyRootGloss));
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

  private async inferProductiveDerivation(entry: DictionaryEntry): Promise<DictionaryRoot[]> {
    const word = entry.word.replace(/[^a-z]/gu, "");
    if (word.length < 5) return [];

    const prefixes: Array<ProductiveAffix | undefined> = [
      undefined,
      ...PRODUCTIVE_PREFIXES.filter((prefix) => word.startsWith(prefix.form)),
    ];
    const suffixes: Array<ProductiveAffix | undefined> = [
      undefined,
      ...PRODUCTIVE_SUFFIXES.filter((suffix) => word.endsWith(suffix.form)),
    ];
    const candidates: ProductiveDerivation[] = [];

    for (const prefix of prefixes) {
      for (const suffix of suffixes) {
        if (!prefix && !suffix) continue;
        const prefixLength = prefix?.form.length ?? 0;
        const suffixLength = suffix?.form.length ?? 0;
        const stem = word.slice(prefixLength, word.length - suffixLength || undefined);
        if (stem.length < 3 || (prefixLength + stem.length + suffixLength) / word.length < 0.6) continue;

        for (const baseForm of restoredBaseForms(stem, suffix?.form)) {
          if (baseForm === word) continue;
          const base = await this.lookup(baseForm);
          // ECDICT uses the maximum rank for obscure names, abbreviations and
          // cross-reference-only spellings. They are unsafe decomposition
          // bases (for example happi, kinde and stope).
          if (!base || base.frequencyRank >= 9_999_999 || /^\s*(?:abbr\.|\[=)/iu.test(base.translation)) continue;
          const baseMeaningZh = briefChineseMeaning(base.translation, preferredBaseParts(suffix?.form));
          if (!baseMeaningZh) continue;
          candidates.push({
            prefix,
            suffix,
            base,
            baseMeaningZh,
            affixLength: prefixLength + suffixLength,
            exactStem: baseForm === stem,
          });
        }
      }
    }

    candidates.sort((left, right) =>
      right.base.word.length - left.base.word.length ||
      right.affixLength - left.affixLength ||
      Number(right.exactStem) - Number(left.exactStem) ||
      left.base.frequencyRank - right.base.frequencyRank ||
      left.base.word.localeCompare(right.base.word, "en"));
    const best = candidates[0];
    if (!best) return [];

    const result: DictionaryRoot[] = [];
    if (best.prefix) {
      result.push({
        form: `${best.prefix.form}-`,
        meaningZh: best.prefix.meaningZh,
        meaningEn: "",
        kind: "prefix",
        inferred: true,
        source: "inferred",
      });
    }
    result.push({
      form: best.base.word,
      meaningZh: best.baseMeaningZh,
      meaningEn: "",
      kind: "lemma",
      inferred: true,
      source: "inferred",
    });
    if (best.suffix) {
      result.push({
        form: `-${best.suffix.form}`,
        meaningZh: best.suffix.meaningZh,
        meaningEn: "",
        kind: "suffix",
        inferred: true,
        source: "inferred",
      });
    }
    return result;
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
    if (selected.some((root) => root.form.length >= 3) && covered.size / Math.max(1, letters.length) >= 0.6) {
      return selected
        .sort((left, right) => left.start - right.start)
        .map(({ start: _start, end: _end, position: _position, ...root }) => ({
          ...root,
          inferred: true,
          source: "inferred" as const,
        }));
    }
    return this.inferProductiveDerivation(entry);
  }

  clearCache(): void {
    this.entryCache.clear();
    this.deleteCache.clear();
    this.rootsPromise = undefined;
  }
}
