import { ALGORITHM_VERSION, APP_VERSION, CONTENT_VERSION, REQUIRED_FEATURES } from "./config";
import { cloneBundle, compareClock, compareEvents, projectSnapshot, stableHash } from "./events";
import type { ReviewCardState } from "../review/types";
import type { Checkpoint, LegacyBundle, LegacyLibrary, LegacyWord, V4Snapshot } from "./types";

const SCORE_FIELDS = [
  "right",
  "wrong",
  "mastery",
  "reverseRight",
  "reverseWrong",
  "reverseMastery",
  "reverseReviewWeight",
  "rareRight",
  "rareWrong",
  "rareMastery",
  "spellRight",
  "spellWrong",
  "meaningRight",
  "meaningWrong",
] as const;

function maxWord(left: LegacyWord | undefined, right: LegacyWord): LegacyWord {
  if (!left) return structuredClone(right);
  const merged = { ...left, ...right };
  for (const field of SCORE_FIELDS) merged[field] = Math.max(Number(left[field] || 0), Number(right[field] || 0));
  return merged;
}

function mergeLibrary(left: LegacyLibrary | undefined, right: LegacyLibrary): LegacyLibrary {
  if (!left) return structuredClone(right);
  const words = new Map(left.words.map((word) => [word.en.toLowerCase(), structuredClone(word)]));
  for (const word of right.words) words.set(word.en.toLowerCase(), maxWord(words.get(word.en.toLowerCase()), word));
  return {
    id: right.id,
    date: left.date > right.date ? left.date : right.date,
    words: [...words.values()],
  };
}

export function mergeLegacyBundles(left: LegacyBundle, right: LegacyBundle): LegacyBundle {
  const libraries = new Map<string, LegacyLibrary>();
  for (const library of [left.store.current, ...left.store.archives]) libraries.set(library.id, structuredClone(library));
  for (const library of [right.store.current, ...right.store.archives]) libraries.set(library.id, mergeLibrary(libraries.get(library.id), library));
  const currentId = right.store.current.id || left.store.current.id;
  const current = libraries.get(currentId) ?? structuredClone(right.store.current);
  libraries.delete(current.id);

  const intensive = new Map(left.intensiveStore.words.map((word) => [word.en.toLowerCase(), structuredClone(word)]));
  for (const word of right.intensiveStore.words) intensive.set(word.en.toLowerCase(), maxWord(intensive.get(word.en.toLowerCase()), word));

  const roots = new Map(left.rootStudyStore.items.map((item) => [item.id, structuredClone(item)]));
  for (const item of right.rootStudyStore.items) {
    const old = roots.get(item.id);
    roots.set(item.id, old ? {
      ...old,
      ...item,
      choiceRight: Math.max(old.choiceRight, item.choiceRight),
      choiceWrong: Math.max(old.choiceWrong, item.choiceWrong),
      writeRight: Math.max(old.writeRight, item.writeRight),
      writeWrong: Math.max(old.writeWrong, item.writeWrong),
    } : structuredClone(item));
  }

  return {
    store: { current, archives: [...libraries.values()].sort((a, b) => b.date.localeCompare(a.date)) },
    intensiveStore: {
      reviewedLibraryId: right.intensiveStore.reviewedLibraryId || left.intensiveStore.reviewedLibraryId,
      words: [...intensive.values()],
    },
    rootStudyStore: { items: [...roots.values()] },
    settings: { ...left.settings, ...right.settings },
  };
}

function mergeCheckpoint(left: Checkpoint, right: Checkpoint): Checkpoint {
  if (left.id === right.id) {
    const vector = { ...left.vector };
    for (const [device, seq] of Object.entries(right.vector)) vector[device] = Math.max(vector[device] ?? 0, seq);
    const lineage = [...new Set([...(left.lineage ?? []), ...(right.lineage ?? [])])].sort();
    const lastReviewedAt = { ...(left.lastReviewedAt ?? {}) };
    for (const [key, value] of Object.entries(right.lastReviewedAt ?? {})) {
      lastReviewedAt[key] = Math.max(lastReviewedAt[key] ?? 0, value);
    }
    return {
      ...left,
      vector,
      lineage,
      lastReviewedAt,
      reviewCards: mergeReviewCards(left.reviewCards, right.reviewCards),
      createdAt: left.createdAt > right.createdAt ? left.createdAt : right.createdAt,
    };
  }
  if (checkpointDescendsFrom(left, right) && vectorDominates(left.vector, right.vector)) return structuredClone(left);
  if (checkpointDescendsFrom(right, left) && vectorDominates(right.vector, left.vector)) return structuredClone(right);

  // Two independently migrated legacy checkpoints have no replayable history.
  // Merge their monotonic counters deterministically; all later v4 work is event based.
  const [first, second] = [left, right].sort((a, b) => a.id.localeCompare(b.id));
  const data = mergeLegacyBundles(first.data, second.data);
  const vector = { ...left.vector };
  for (const [device, seq] of Object.entries(right.vector)) vector[device] = Math.max(vector[device] ?? 0, seq);
  return {
    id: `merged-${stableHash([left.id, right.id].sort())}-${stableHash(data)}`,
    createdAt: new Date().toISOString(),
    vector,
    data,
    lineage: [...new Set([first.id, second.id, ...(first.lineage ?? []), ...(second.lineage ?? [])])].sort(),
    lastReviewedAt: mergeReviewTimes(first.lastReviewedAt, second.lastReviewedAt),
    reviewCards: mergeReviewCards(first.reviewCards, second.reviewCards),
  };
}

function mergeReviewCards(
  left: Record<string, ReviewCardState> | undefined,
  right: Record<string, ReviewCardState> | undefined,
): Record<string, ReviewCardState> | undefined {
  if (!left && !right) return undefined;
  const merged: Record<string, ReviewCardState> = structuredClone(left ?? {});
  for (const [cardId, incoming] of Object.entries(right ?? {})) {
    const current = merged[cardId];
    const incomingWins = !current || compareClock(current.revisionClock, incoming.revisionClock) < 0 ||
      (current && compareClock(current.revisionClock, incoming.revisionClock) === 0 && current.revisionEventId < incoming.revisionEventId);
    const winner = structuredClone(incomingWins ? incoming : current!);
    const loser = incomingWins ? current : incoming;
    winner.stats = mergeReviewDeviceStats(winner.stats, loser?.stats);
    merged[cardId] = winner;
  }
  return merged;
}

function mergeReviewDeviceStats(
  left: ReviewCardState["stats"],
  right: ReviewCardState["stats"] | undefined,
): ReviewCardState["stats"] {
  const result = structuredClone(left);
  for (const [deviceId, stats] of Object.entries(right ?? {})) {
    const old = result[deviceId] ?? { primaryRight: 0, primaryWrong: 0, retryRight: 0, retryWrong: 0 };
    result[deviceId] = {
      primaryRight: Math.max(old.primaryRight, stats.primaryRight),
      primaryWrong: Math.max(old.primaryWrong, stats.primaryWrong),
      retryRight: Math.max(old.retryRight, stats.retryRight),
      retryWrong: Math.max(old.retryWrong, stats.retryWrong),
    };
  }
  return result;
}

function mergeReviewTimes(
  left: Record<string, number> | undefined,
  right: Record<string, number> | undefined,
): Record<string, number> {
  const merged = { ...(left ?? {}) };
  for (const [key, value] of Object.entries(right ?? {})) merged[key] = Math.max(merged[key] ?? 0, value);
  return merged;
}

function checkpointDescendsFrom(candidate: Checkpoint, ancestor: Checkpoint): boolean {
  return candidate.lineage?.includes(ancestor.id) ?? false;
}

function vectorDominates(left: Record<string, number>, right: Record<string, number>): boolean {
  return Object.entries(right).every(([device, seq]) => (left[device] ?? 0) >= seq);
}

function versionMax(left: string, right: string): string {
  const leftParts = left.split(".").map(Number);
  const rightParts = right.split(".").map(Number);
  for (let index = 0; index < Math.max(leftParts.length, rightParts.length); index += 1) {
    const difference = (leftParts[index] ?? 0) - (rightParts[index] ?? 0);
    if (difference) return difference > 0 ? left : right;
  }
  return left;
}

function versionCompare(left: string, right: string): number {
  const maximum = versionMax(left, right);
  if (left === right) return 0;
  return maximum === left ? 1 : -1;
}

export function mergeSnapshots(...inputs: Array<V4Snapshot | null | undefined>): V4Snapshot {
  const snapshots = inputs.filter((snapshot): snapshot is V4Snapshot => Boolean(snapshot));
  if (!snapshots.length) throw new Error("没有可合并的快照");
  const incompatible = snapshots.find((snapshot) => snapshot.algorithmVersion !== ALGORITHM_VERSION);
  if (incompatible) throw new Error(`不能合并算法版本 ${String(incompatible.algorithmVersion)}`);
  const future = snapshots.find((snapshot) => versionCompare(snapshot.minReaderVersion, APP_VERSION) > 0);
  if (future) throw new Error(`快照至少需要阅读器 ${future.minReaderVersion}`);
  const unknownFeature = snapshots
    .flatMap((snapshot) => snapshot.requiredFeatures)
    .find((feature) => !REQUIRED_FEATURES.includes(feature as typeof REQUIRED_FEATURES[number]));
  if (unknownFeature) throw new Error(`快照包含未知必需功能 ${unknownFeature}`);
  const unknownReviewAlgorithm = snapshots
    .flatMap((snapshot) => snapshot.events as Array<{ type: string; algorithmVersion?: string }>)
    .find((event) => event.type === "review-answer" && event.algorithmVersion !== "spaced-review-v1");
  if (unknownReviewAlgorithm) throw new Error(`Unknown spaced review algorithm: ${unknownReviewAlgorithm.algorithmVersion}`);
  let merged = structuredClone(snapshots[0]);
  for (const next of snapshots.slice(1)) {
    const checkpoint = mergeCheckpoint(merged.checkpoint, next.checkpoint);
    const vector = checkpoint.vector;
    const events = new Map([...merged.events, ...next.events].map((event) => [event.id, event]));
    const generations = { ...merged.generations, ...next.generations };
    const resetEvents = [...events.values()].filter((event) => event.type === "reset");
    for (const scope of new Set(resetEvents.map((event) => event.scope))) {
      const latest = resetEvents.filter((event) => event.scope === scope).sort(compareEvents).at(-1);
      if (latest?.type === "reset") generations[scope] = latest.newGenerationId;
    }
    merged = {
      ...merged,
      appVersion: APP_VERSION,
      contentVersion: [merged.contentVersion, next.contentVersion, CONTENT_VERSION].sort().at(-1)!,
      minReaderVersion: versionMax(merged.minReaderVersion, next.minReaderVersion),
      requiredFeatures: [...new Set([...REQUIRED_FEATURES, ...merged.requiredFeatures, ...next.requiredFeatures])],
      checkpoint,
      events: [...events.values()]
        .filter((event) => event.seq > (vector[event.deviceId] ?? 0))
        .sort(compareEvents),
      generations,
      updatedAt: new Date().toISOString(),
    };
  }
  return merged;
}

export function legacyProjection(snapshot: V4Snapshot): LegacyBundle {
  return cloneBundle(projectSnapshot(snapshot));
}
