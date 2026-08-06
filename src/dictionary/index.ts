import { GENERATED_DICTIONARY } from "./generated/data";
import { OfflineDictionary } from "./offline-dictionary";

export * from "./offline-dictionary";
export * from "./types";

export const offlineDictionary = new OfflineDictionary(GENERATED_DICTIONARY);

export async function lookupWord(word: string) {
  const entry = await offlineDictionary.lookup(word);
  if (!entry) return undefined;
  const roots = await offlineDictionary.rootsFor(entry);
  return roots === entry.roots ? entry : { ...entry, roots };
}

export async function checkWord(word: string) {
  const result = await offlineDictionary.check(word);
  return {
    normalized: result.normalized,
    entry: result.entry ?? undefined,
    suggestions: result.suggestions.map((entry) => entry.word),
  };
}

export async function inferRoots(word: string) {
  const entry = await offlineDictionary.lookup(word);
  return entry ? offlineDictionary.rootsFor(entry) : [];
}
