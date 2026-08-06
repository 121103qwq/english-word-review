import type { LegacyBundle } from "../core/types";

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

export function comparableLegacyBundle(bundle: LegacyBundle): string {
  const normalized = structuredClone(bundle);
  normalized.rootStudyStore.items.sort((left, right) => left.id.localeCompare(right.id));
  return JSON.stringify(canonicalize(normalized));
}
