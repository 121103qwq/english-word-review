export type RootSource = "engra" | "inferred";

export interface DictionaryRoot {
  form: string;
  meaningZh: string;
  meaningEn: string;
  kind: string;
  inferred: boolean;
  source: RootSource;
}

export interface DictionaryEntry {
  word: string;
  translation: string;
  phonetic: string;
  forms: Record<string, string>;
  frequencyRank: number;
  roots: DictionaryRoot[];
}

export interface RootLexiconEntry {
  form: string;
  meaningZh: string;
  meaningEn: string;
  kind: string;
  position: "prefix" | "suffix" | "any";
}

export interface EncodedDictionarySource {
  dictionaryVersion: string;
  ecdictCommit: string;
  engraCommit: string;
  sourceHashes: Record<string, string>;
  entryCount: number;
  entryChunks: Record<string, string>;
  deleteChunks: Record<string, string>;
  roots: string;
}

export interface DictionaryCheckResult {
  input: string;
  normalized: string;
  entry: DictionaryEntry | null;
  suggestions: DictionaryEntry[];
}

