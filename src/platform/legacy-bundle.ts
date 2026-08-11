import { mastery } from "../core/algorithm";
import type { LegacyBundle, LegacyWord, RootStudyItem } from "../core/types";

interface ReconcileMarkerStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

export const STARTUP_RECONCILE_MARKER_KEY = "english-review:startup-reconcile-v1";
export type LegacyProjectionDecision = "matched" | "apply" | "continue";

function canonicalize(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonicalize);
  if (value === null || typeof value !== "object") return value;
  const record = value as Record<string, unknown>;
  return Object.fromEntries(
    Object.keys(record)
      .filter((key) => record[key] !== undefined)
      .sort()
      .map((key) => [key, canonicalize(record[key])]),
  );
}

const WORD_COUNT_FIELDS = [
  "right",
  "wrong",
  "reverseRight",
  "reverseWrong",
  "rareRight",
  "rareWrong",
  "spellRight",
  "spellWrong",
  "meaningRight",
  "meaningWrong",
] as const;

const ROOT_STUDY_COUNT_FIELDS = [
  "choiceRight",
  "choiceWrong",
  "writeRight",
  "writeWrong",
] as const;

function normalizeCount(value: unknown): unknown {
  if (value === undefined) return 0;
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric : value;
}

function countForMastery(value: unknown): number {
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric : 0;
}

function canonicalKey(value: unknown): string {
  return JSON.stringify(canonicalize(value));
}

function sortCanonical<T>(values: T[]): T[] {
  return values.sort((left, right) => canonicalKey(left).localeCompare(canonicalKey(right)));
}

function normalizeWord(value: LegacyWord): LegacyWord {
  const word = structuredClone(value);
  const record = word as Record<string, unknown>;
  for (const field of WORD_COUNT_FIELDS) record[field] = normalizeCount(record[field]);
  word.mastery = mastery(countForMastery(word.right), countForMastery(word.wrong));
  word.reverseMastery = mastery(
    countForMastery(word.reverseRight),
    countForMastery(word.reverseWrong),
  );
  word.rareMastery = mastery(
    countForMastery(word.rareRight),
    countForMastery(word.rareWrong),
  );
  if (Array.isArray(record.roots)) record.roots = sortCanonical(record.roots.map(canonicalize));
  return word;
}

function normalizeLibrary<T extends { words: LegacyWord[] }>(library: T): T {
  const normalized = structuredClone(library);
  normalized.words = sortCanonical(normalized.words.map(normalizeWord));
  return normalized;
}

function normalizeRootStudyItem(value: RootStudyItem): RootStudyItem {
  const item = structuredClone(value);
  const record = item as unknown as Record<string, unknown>;
  for (const field of ROOT_STUDY_COUNT_FIELDS) record[field] = normalizeCount(record[field]);
  item.words = Array.isArray(item.words)
    ? [...new Set(item.words.map((word) => String(word)))].sort((left, right) => left.localeCompare(right, "en"))
    : [];
  return item;
}

export function comparableLegacyBundle(bundle: LegacyBundle): string {
  const normalized = structuredClone(bundle);
  normalized.store.current = normalizeLibrary(normalized.store.current);
  normalized.store.archives = sortCanonical(normalized.store.archives.map(normalizeLibrary));
  normalized.intensiveStore.words = sortCanonical(normalized.intensiveStore.words.map(normalizeWord));
  normalized.rootStudyStore.items = sortCanonical(
    normalized.rootStudyStore.items.map(normalizeRootStudyItem),
  );
  return JSON.stringify(canonicalize(normalized));
}

function fingerprint(value: string): string {
  let hash = 2166136261;
  for (let index = 0; index < value.length; index += 1) {
    hash ^= value.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  return `${value.length.toString(36)}-${(hash >>> 0).toString(16).padStart(8, "0")}`;
}

export function decideLegacyProjection(
  projected: LegacyBundle,
  live: LegacyBundle,
  storage: ReconcileMarkerStorage,
): LegacyProjectionDecision {
  const projectedComparable = comparableLegacyBundle(projected);
  if (projectedComparable === comparableLegacyBundle(live)) return "matched";

  const marker = fingerprint(projectedComparable);
  try {
    if (storage.getItem(STARTUP_RECONCILE_MARKER_KEY) === marker) return "continue";
    storage.setItem(STARTUP_RECONCILE_MARKER_KEY, marker);
    return "apply";
  } catch {
    // A failed marker write must never trap the app in a reload loop.
    return "continue";
  }
}

export function reconcileLegacyProjection(
  projected: LegacyBundle,
  live: LegacyBundle,
  storage: ReconcileMarkerStorage,
  apply: (bundle: LegacyBundle) => void,
): LegacyProjectionDecision {
  const decision = decideLegacyProjection(projected, live, storage);
  if (decision === "apply") apply(projected);
  return decision;
}
