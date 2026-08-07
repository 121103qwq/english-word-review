import type { LegacyBundle } from "../core/types";

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

export function comparableLegacyBundle(bundle: LegacyBundle): string {
  const normalized = structuredClone(bundle);
  normalized.rootStudyStore.items.sort((left, right) => left.id.localeCompare(right.id));
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
