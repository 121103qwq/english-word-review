import {
  ALGORITHM_VERSION,
  APP_VERSION,
  CONTENT_VERSION,
  DEVICE_ID_KEY,
  MIGRATION_BACKUP_KEY,
  MIN_READER_VERSION,
  REQUIRED_FEATURES,
  SCHEMA_VERSION,
  V4_STORAGE_KEY,
} from "./config";
import { mastery, updateReverseReviewWeight } from "./algorithm";
import {
  advanceReviewCard,
  incrementReviewStats,
} from "../review/scheduler";
import {
  REVIEW_ALGORITHM_VERSION,
  normalizeEnglish,
  reviewCardId,
  type ReviewCardState,
} from "../review/types";
import type {
  AnswerEvent,
  HybridClock,
  IntensiveSelectionEvent,
  LearningEvent,
  LearningMode,
  LegacyBundle,
  LegacyLibrary,
  LegacySettings,
  LegacyWord,
  ResetEvent,
  RootStudyStore,
  ReviewAnswerEvent,
  V4Snapshot,
} from "./types";

interface StorageLike {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

const LEGACY_KEYS = [
  "english-word-review-v2",
  "english-word-review-v3",
  "english-word-intensive-v1",
  "english-word-meaning-match-v1",
  "english-word-root-visible-v1",
  "english-word-root-study-v1",
];

export function cloneBundle(bundle: LegacyBundle): LegacyBundle {
  return structuredClone(bundle);
}

function canonicalJson(value: unknown): string {
  if (value === null || typeof value !== "object") return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  const record = value as Record<string, unknown>;
  return `{${Object.keys(record)
    .sort()
    .map((key) => `${JSON.stringify(key)}:${canonicalJson(record[key])}`)
    .join(",")}}`;
}

export function stableHash(value: unknown): string {
  const text = canonicalJson(value);
  let hash = 2166136261;
  for (let i = 0; i < text.length; i += 1) {
    hash ^= text.charCodeAt(i);
    hash = Math.imul(hash, 16777619);
  }
  return (hash >>> 0).toString(16).padStart(8, "0");
}

function uuid(): string {
  return globalThis.crypto?.randomUUID?.() ?? `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

export function compareClock(a: HybridClock, b: HybridClock): number {
  return a.wallTime - b.wallTime || a.logical - b.logical || a.deviceId.localeCompare(b.deviceId);
}

export function compareEvents(a: LearningEvent, b: LearningEvent): number {
  return compareClock(a.clock, b.clock) || a.id.localeCompare(b.id);
}

export function scopeForAnswer(payload: AnswerEvent["payload"]): string {
  if (payload.area === "library") return `library:${payload.libraryId}:${payload.mode}`;
  if (payload.area === "intensive") return `intensive:${payload.mode}`;
  return `root:${payload.mode}`;
}

function findLibrary(data: LegacyBundle, id?: string): LegacyLibrary | undefined {
  if (!id || data.store.current.id === id) return data.store.current;
  return data.store.archives.find((library) => library.id === id);
}

function findWord(library: LegacyLibrary | undefined, itemId: string): LegacyWord | undefined {
  return library?.words.find((word) => word.en.toLowerCase() === itemId.toLowerCase());
}

function applyLibraryAnswer(data: LegacyBundle, event: AnswerEvent): void {
  const word = findWord(findLibrary(data, event.payload.libraryId), event.payload.itemId);
  if (!word) return;
  const correct = event.payload.correct;
  if (event.payload.mode === "forward") {
    word[correct ? "right" : "wrong"] = Number(word[correct ? "right" : "wrong"] || 0) + 1;
    word.mastery = mastery(Number(word.right || 0), Number(word.wrong || 0));
  } else if (event.payload.mode === "reverse") {
    word[correct ? "reverseRight" : "reverseWrong"] = Number(word[correct ? "reverseRight" : "reverseWrong"] || 0) + 1;
    word.reverseReviewWeight = updateReverseReviewWeight(Number(word.reverseReviewWeight || 0), correct);
    word.reverseMastery = mastery(Number(word.reverseRight || 0), Number(word.reverseWrong || 0));
  } else if (event.payload.mode === "rare") {
    word[correct ? "rareRight" : "rareWrong"] = Number(word[correct ? "rareRight" : "rareWrong"] || 0) + 1;
    word.rareMastery = mastery(Number(word.rareRight || 0), Number(word.rareWrong || 0));
  }
}

function applyIntensiveAnswer(data: LegacyBundle, event: AnswerEvent): void {
  const word = data.intensiveStore.words.find((entry) => entry.en.toLowerCase() === event.payload.itemId.toLowerCase());
  if (!word) return;
  const prefix = event.payload.mode === "meaning" ? "meaning" : "spell";
  const field = `${prefix}${event.payload.correct ? "Right" : "Wrong"}` as
    | "meaningRight"
    | "meaningWrong"
    | "spellRight"
    | "spellWrong";
  word[field] = Number(word[field] || 0) + 1;
}

function applyRootAnswer(data: LegacyBundle, event: AnswerEvent): void {
  const item = data.rootStudyStore.items.find((entry) => entry.id === event.payload.itemId);
  if (!item) return;
  const prefix = event.payload.mode === "root-write" ? "write" : "choice";
  const field = `${prefix}${event.payload.correct ? "Right" : "Wrong"}` as
    | "choiceRight"
    | "choiceWrong"
    | "writeRight"
    | "writeWrong";
  item[field] = Number(item[field] || 0) + 1;
}

function applyAnswer(data: LegacyBundle, event: AnswerEvent): void {
  if (event.payload.area === "library") applyLibraryAnswer(data, event);
  else if (event.payload.area === "intensive") applyIntensiveAnswer(data, event);
  else applyRootAnswer(data, event);
}

function clearWordMode(word: LegacyWord, mode: LearningMode): void {
  if (mode === "forward") Object.assign(word, { right: 0, wrong: 0, mastery: 0 });
  else if (mode === "reverse") Object.assign(word, { reverseRight: 0, reverseWrong: 0, reverseMastery: 0, reverseReviewWeight: 0 });
  else if (mode === "rare") Object.assign(word, { rareRight: 0, rareWrong: 0, rareMastery: 0 });
  else if (mode === "spell") Object.assign(word, { spellRight: 0, spellWrong: 0 });
  else if (mode === "meaning") Object.assign(word, { meaningRight: 0, meaningWrong: 0 });
}

function applyReset(data: LegacyBundle, event: ResetEvent): void {
  const [area, idOrMode, maybeMode] = event.scope.split(":");
  if (area === "library") {
    const library = findLibrary(data, idOrMode);
    library?.words.forEach((word) => clearWordMode(word, maybeMode as LearningMode));
  } else if (area === "intensive") {
    data.intensiveStore.words.forEach((word) => clearWordMode(word, idOrMode as LearningMode));
  } else if (area === "root") {
    const write = idOrMode === "root-write";
    data.rootStudyStore.items.forEach((item) => {
      if (write) Object.assign(item, { writeRight: 0, writeWrong: 0 });
      else Object.assign(item, { choiceRight: 0, choiceWrong: 0 });
    });
  }
}

function applySelection(data: LegacyBundle, event: IntensiveSelectionEvent): void {
  const old = new Map(data.intensiveStore.words.map((word) => [word.en.toLowerCase(), word]));
  data.intensiveStore.words = event.words.map((selected) => ({
    ...(old.get(selected.en.toLowerCase()) ?? {
      spellRight: 0,
      spellWrong: 0,
      meaningRight: 0,
      meaningWrong: 0,
    }),
    en: selected.en,
    zh: selected.zh,
  }));
  data.intensiveStore.reviewedLibraryId = event.reviewedLibraryId;
}

/**
 * Rebuild root cards from the current word definitions.  A card's learning
 * history is meaningful only while both its root and its displayed meaning
 * still match, so corrected meanings deliberately start with fresh stats.
 */
export function rebuildRootStudyStore(base: RootStudyStore, libraries: LegacyLibrary[]): RootStudyStore {
  const previous = new Map(base.items.map((item) => [`${item.root}\u0000${item.meaning}`, item]));
  const items = new Map<string, RootStudyStore["items"][number]>();
  for (const library of libraries) {
    for (const word of library.words) {
      const roots = Array.isArray(word.roots) ? word.roots as Array<Record<string, unknown>> : [];
      for (const value of roots) {
        const root = String(value.root ?? value.form ?? "").trim();
        const meaning = String(value.meaning ?? value.meaningZh ?? value.zh ?? "").trim();
        if (!root || !meaning) continue;
        const id = `${root}\u0000${meaning}`;
        const old = previous.get(id);
        const item = items.get(id) ?? {
          id,
          root,
          meaning,
          words: [],
          choiceRight: old?.choiceRight ?? 0,
          choiceWrong: old?.choiceWrong ?? 0,
          writeRight: old?.writeRight ?? 0,
          writeWrong: old?.writeWrong ?? 0,
        };
        if (!item.words.includes(word.en)) item.words.push(word.en);
        items.set(id, item);
      }
    }
  }
  return { items: [...items.values()] };
}

export function projectSnapshot(snapshot: V4Snapshot): LegacyBundle {
  const data = cloneBundle(snapshot.checkpoint.data);
  const events = snapshot.events
    .filter((event) => event.seq > (snapshot.checkpoint.vector[event.deviceId] ?? 0))
    .sort(compareEvents);
  const latestReset = new Map<string, ResetEvent>();
  for (const event of events) {
    if (event.type !== "reset") continue;
    const previous = latestReset.get(event.scope);
    if (!previous || compareEvents(previous, event) < 0) latestReset.set(event.scope, event);
  }
  for (const reset of latestReset.values()) applyReset(data, reset);

  const undone = new Set(events.filter((event) => event.type === "undo").map((event) => event.targetEventId));
  for (const event of events) {
    if (event.type === "answer") {
      if (undone.has(event.id)) continue;
      const reset = latestReset.get(event.scope);
      if (reset && event.generationId !== reset.newGenerationId) continue;
      applyAnswer(data, event);
    } else if (event.type === "setting") {
      data.settings[event.key] = event.value as never;
    } else if (event.type === "intensive-selection") {
      applySelection(data, event);
    }
  }
  return data;
}

function mergeReviewStats(
  left: ReviewCardState["stats"],
  right: ReviewCardState["stats"],
): ReviewCardState["stats"] {
  const result = structuredClone(left);
  for (const [deviceId, stats] of Object.entries(right)) {
    const previous = result[deviceId] ?? { primaryRight: 0, primaryWrong: 0, retryRight: 0, retryWrong: 0 };
    result[deviceId] = {
      primaryRight: Math.max(previous.primaryRight, stats.primaryRight),
      primaryWrong: Math.max(previous.primaryWrong, stats.primaryWrong),
      retryRight: Math.max(previous.retryRight, stats.retryRight),
      retryWrong: Math.max(previous.retryWrong, stats.retryWrong),
    };
  }
  return result;
}

function applyReviewAnswer(cards: Record<string, ReviewCardState>, event: ReviewAnswerEvent): void {
  const { payload } = event;
  const prior = cards[payload.cardId];
  const accumulatedStats = mergeReviewStats(prior?.stats ?? {}, { [event.deviceId]: payload.deviceStats });
  let next = prior;
  if (payload.attempt === "primary" && payload.scheduleAfter) {
    const candidate = payload.scheduleAfter;
    if (!prior || compareClock(prior.revisionClock, candidate.revisionClock) < 0 ||
      (compareClock(prior.revisionClock, candidate.revisionClock) === 0 && prior.revisionEventId < candidate.revisionEventId)) {
      next = structuredClone(candidate);
    }
  }
  if (!next) return;
  next = {
    ...next,
    stats: mergeReviewStats(accumulatedStats, next.stats),
  };
  cards[payload.cardId] = next;
}

/** Materializes globally keyed review state without altering the legacy bundle projection. */
export function projectReviewCards(snapshot: V4Snapshot): Record<string, ReviewCardState> {
  const cards = structuredClone(snapshot.checkpoint.reviewCards ?? {});
  const events = snapshot.events
    .filter((event) => event.seq > (snapshot.checkpoint.vector[event.deviceId] ?? 0))
    .sort(compareEvents);
  for (const event of events) {
    if (event.type === "review-answer" && event.algorithmVersion === REVIEW_ALGORITHM_VERSION) {
      applyReviewAnswer(cards, event);
    }
  }
  return cards;
}

export function compactSnapshot(snapshot: V4Snapshot): V4Snapshot {
  const vector = { ...snapshot.checkpoint.vector };
  const lastReviewedAt = { ...(snapshot.checkpoint.lastReviewedAt ?? {}) };
  for (const event of snapshot.events) vector[event.deviceId] = Math.max(vector[event.deviceId] ?? 0, event.seq);
  for (const event of snapshot.events) {
    if (event.type !== "answer") continue;
    const key = `${event.scope}\u0000${event.payload.itemId.toLowerCase()}`;
    lastReviewedAt[key] = Math.max(lastReviewedAt[key] ?? 0, event.clock.wallTime);
  }
  const data = projectSnapshot(snapshot);
  const checkpoint = {
    id: `checkpoint-${stableHash(data)}-${stableHash(vector)}`,
    createdAt: new Date().toISOString(),
    vector,
    data,
    lineage: [...new Set([snapshot.checkpoint.id, ...(snapshot.checkpoint.lineage ?? [])])].sort(),
    lastReviewedAt,
    reviewCards: projectReviewCards(snapshot),
  };
  return { ...snapshot, checkpoint, events: [], updatedAt: new Date().toISOString() };
}

function compareVersion(a: string, b: string): number {
  const pa = a.split(".").map(Number);
  const pb = b.split(".").map(Number);
  for (let index = 0; index < Math.max(pa.length, pb.length); index += 1) {
    const difference = (pa[index] ?? 0) - (pb[index] ?? 0);
    if (difference) return difference;
  }
  return 0;
}

export class EventStore {
  readonly deviceId: string;
  private lastClock: HybridClock;
  private snapshot: V4Snapshot;
  private lastAnswerEventId?: string;

  private constructor(private readonly storage: StorageLike, snapshot: V4Snapshot, deviceId: string) {
    this.snapshot = snapshot;
    this.deviceId = deviceId;
    const latest = [
      ...snapshot.events.map((event) => event.clock),
      ...Object.values(snapshot.checkpoint.reviewCards ?? {}).map((card) => card.revisionClock),
    ].sort(compareClock).at(-1);
    this.lastClock = latest ?? { wallTime: Date.now(), logical: 0, deviceId };
  }

  get readOnly(): boolean {
    return compareVersion(this.snapshot.minReaderVersion, APP_VERSION) > 0 ||
      this.snapshot.requiredFeatures.some(
        (feature) => !REQUIRED_FEATURES.includes(feature as typeof REQUIRED_FEATURES[number]),
      ) || this.snapshot.events.some(
        (event) => event.type === "review-answer" && event.algorithmVersion !== REVIEW_ALGORITHM_VERSION,
      );
  }

  static open(live: LegacyBundle, storage: StorageLike = localStorage): EventStore {
    let deviceId = storage.getItem(DEVICE_ID_KEY);
    if (!deviceId) {
      deviceId = uuid();
      storage.setItem(DEVICE_ID_KEY, deviceId);
    }
    const existing = storage.getItem(V4_STORAGE_KEY);
    if (existing) {
      try {
        const parsed = JSON.parse(existing) as V4Snapshot;
        if (parsed.schemaVersion === SCHEMA_VERSION) return new EventStore(storage, parsed, deviceId);
      } catch {
        // Fall through to a safe migration snapshot.
      }
    }
    const backup = Object.fromEntries(LEGACY_KEYS.map((key) => [key, storage.getItem(key)]));
    if (!storage.getItem(MIGRATION_BACKUP_KEY)) storage.setItem(MIGRATION_BACKUP_KEY, JSON.stringify(backup));
    const checkpoint = {
      id: `legacy-${stableHash(live)}`,
      createdAt: new Date().toISOString(),
      vector: {},
      data: cloneBundle(live),
    };
    const snapshot: V4Snapshot = {
      schemaVersion: SCHEMA_VERSION,
      appVersion: APP_VERSION,
      contentVersion: CONTENT_VERSION,
      algorithmVersion: ALGORITHM_VERSION,
      minReaderVersion: MIN_READER_VERSION,
      requiredFeatures: [...REQUIRED_FEATURES],
      checkpoint,
      events: [],
      generations: {},
      updatedAt: new Date().toISOString(),
    };
    storage.setItem(V4_STORAGE_KEY, JSON.stringify(snapshot));
    return new EventStore(storage, snapshot, deviceId);
  }

  getSnapshot(): V4Snapshot {
    return structuredClone(this.snapshot);
  }

  replaceSnapshot(snapshot: V4Snapshot): void {
    if (this.readOnly) throw new Error("当前版本只能只读查看此快照");
    this.snapshot = structuredClone(snapshot);
    this.lastClock = [
      ...this.snapshot.events.map((event) => event.clock),
      ...Object.values(this.snapshot.checkpoint.reviewCards ?? {}).map((card) => card.revisionClock),
    ].sort(compareClock).at(-1) ?? { wallTime: Date.now(), logical: 0, deviceId: this.deviceId };
    this.save();
  }

  project(): LegacyBundle {
    return projectSnapshot(this.snapshot);
  }

  getReviewCards(): Record<string, ReviewCardState> {
    return projectReviewCards(this.snapshot);
  }

  getReviewCard(word: string): ReviewCardState | undefined {
    return this.getReviewCards()[reviewCardId(word)];
  }

  private nextClock(): HybridClock {
    const now = Date.now();
    const wallTime = Math.max(now, this.lastClock.wallTime);
    const logical = wallTime === this.lastClock.wallTime ? this.lastClock.logical + 1 : 0;
    return (this.lastClock = { wallTime, logical, deviceId: this.deviceId });
  }

  private append(event: LearningEvent): string {
    if (this.readOnly) return "";
    this.snapshot.events.push(event);
    this.snapshot.updatedAt = new Date().toISOString();
    this.snapshot.appVersion = APP_VERSION;
    this.snapshot.minReaderVersion = MIN_READER_VERSION;
    this.snapshot.requiredFeatures = [...new Set([...this.snapshot.requiredFeatures, ...REQUIRED_FEATURES])];
    this.save();
    return event.id;
  }

  private base(scope: string) {
    const seq = Math.max(
      this.snapshot.checkpoint.vector[this.deviceId] ?? 0,
      ...this.snapshot.events.filter((event) => event.deviceId === this.deviceId).map((event) => event.seq),
    ) + 1;
    return {
      id: `${this.deviceId}:${seq}`,
      deviceId: this.deviceId,
      seq,
      clock: this.nextClock(),
      algorithmVersion: ALGORITHM_VERSION,
      scope,
      generationId: this.snapshot.generations[scope] ?? "baseline",
    };
  }

  recordAnswer(payload: AnswerEvent["payload"]): string {
    const scope = scopeForAnswer(payload);
    const base = this.base(scope);
    const reviewKey = `${scope}\u0000${payload.itemId.toLowerCase()}`;
    const priorTimes = this.snapshot.events
      .filter((event): event is AnswerEvent =>
        event.type === "answer" &&
        event.scope === scope &&
        event.payload.itemId.toLowerCase() === payload.itemId.toLowerCase(),
      )
      .map((event) => event.clock.wallTime);
    const previous = Math.max(this.snapshot.checkpoint.lastReviewedAt?.[reviewKey] ?? 0, ...priorTimes);
    const event: AnswerEvent = {
      ...base,
      type: "answer",
      payload: {
        ...payload,
        answeredAt: new Date(base.clock.wallTime).toISOString(),
        reviewIntervalMs: previous ? Math.max(0, base.clock.wallTime - previous) : undefined,
      },
    };
    this.lastAnswerEventId = this.append(event);
    return this.lastAnswerEventId;
  }

  recordReviewAnswer(input: import("../review/types").ReviewAnswerInput): string {
    const wordKey = normalizeEnglish(input.word);
    if (!wordKey || !input.sessionId || !input.sourceLibraryIds.length) return "";
    const cardId = input.cardId ?? reviewCardId(wordKey);
    const base = this.base(`review:${wordKey}`);
    const at = input.answeredAt ?? base.clock.wallTime;
    const previous = this.getReviewCards()[cardId];
    const deviceStats = incrementReviewStats(previous?.stats[this.deviceId], input.attempt, input.correct);
    const stats = { ...(previous?.stats ?? {}), [this.deviceId]: deviceStats };
    const event: ReviewAnswerEvent = {
      ...base,
      type: "review-answer",
      algorithmVersion: REVIEW_ALGORITHM_VERSION,
      payload: {
        sessionId: input.sessionId,
        cardId,
        wordKey,
        direction: input.direction,
        attempt: input.attempt,
        correct: input.correct,
        sourceLibraryIds: [...new Set(input.sourceLibraryIds)].sort(),
        answeredAt: new Date(at).toISOString(),
        deviceStats,
        scheduleAfter: input.attempt === "primary" ? advanceReviewCard({
          previous,
          word: wordKey,
          direction: input.direction,
          correct: input.correct,
          at,
          clock: base.clock,
          eventId: base.id,
          stats,
        }) : undefined,
      },
    };
    return this.append(event);
  }

  recordUndo(targetEventId = this.lastAnswerEventId): void {
    if (!targetEventId) return;
    const target = this.snapshot.events.find((event) => event.id === targetEventId);
    if (!target) return;
    this.append({ ...this.base(target.scope), type: "undo", targetEventId });
  }

  recordReset(scope: string): void {
    const newGenerationId = uuid();
    const event: ResetEvent = { ...this.base(scope), type: "reset", newGenerationId };
    this.snapshot.generations[scope] = newGenerationId;
    this.append(event);
  }

  recordSetting<K extends keyof LegacySettings>(key: K, value: LegacySettings[K]): void {
    this.append({ ...this.base(`setting:${key}`), type: "setting", key, value });
  }

  recordIntensiveSelection(words: IntensiveSelectionEvent["words"], reviewedLibraryId: string): void {
    this.append({
      ...this.base("intensive-selection"),
      type: "intensive-selection",
      words,
      reviewedLibraryId,
    });
  }

  replaceLibraries(libraries: LegacyLibrary[], activeLibraryId: string): void {
    if (this.readOnly || !libraries.length) return;
    const existing = new Map(
      [this.snapshot.checkpoint.data.store.current, ...this.snapshot.checkpoint.data.store.archives]
        .map((library) => [library.id, library] as const),
    );
    const scoreFields = [
      "right", "wrong", "mastery", "reverseRight", "reverseWrong", "reverseMastery",
      "reverseReviewWeight", "rareRight", "rareWrong", "rareMastery", "spellRight",
      "spellWrong", "meaningRight", "meaningWrong",
    ] as const;
    const materialized = libraries.map((library) => {
      const oldWords = new Map((existing.get(library.id)?.words ?? []).map((word) => [word.en.toLowerCase(), word]));
      return {
        ...structuredClone(library),
        words: library.words.map((definition) => {
          const old = oldWords.get(definition.en.toLowerCase());
          const scores = Object.fromEntries(scoreFields.map((field) => [field, Number(old?.[field]) || 0]));
          return { ...structuredClone(definition), ...scores } as LegacyWord;
        }),
      };
    });
    const current = materialized.find((library) => library.id === activeLibraryId) ?? materialized[0];
    this.snapshot.checkpoint.data.store = {
      current,
      archives: materialized
        .filter((library) => library.id !== current.id)
        .sort((left, right) => right.date.localeCompare(left.date)),
    };
    this.snapshot.checkpoint.data.rootStudyStore = rebuildRootStudyStore(
      this.snapshot.checkpoint.data.rootStudyStore,
      materialized,
    );
    this.snapshot.updatedAt = new Date().toISOString();
    this.snapshot.appVersion = APP_VERSION;
    this.save();
  }

  compact(): void {
    if (this.readOnly) return;
    this.snapshot = compactSnapshot(this.snapshot);
    this.save();
  }

  private save(): void {
    this.storage.setItem(V4_STORAGE_KEY, JSON.stringify(this.snapshot));
  }
}
