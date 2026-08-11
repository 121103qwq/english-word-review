import { stableHash, projectReviewCards, projectSnapshot } from "../core/events";
import type { LegacyLibrary, LegacyWord, V4Snapshot } from "../core/types";
import type { AudioAssetMeta, ContentSnapshotV1, CustomLibrary, WordOverride } from "../content/types";

export const LIBRARY_SYNC_SCHEMA_VERSION = 2 as const;
const LEGACY_LIBRARY_SYNC_SCHEMA_VERSION = 1 as const;
export const LIBRARY_SYNC_ROOT_PATH = "library-sync";
export const LIBRARY_SYNC_BATCHES_PER_LOCATION = 30;

export interface LibrarySyncDevice {
  deviceCode: string;
  deviceName?: string;
  location: string;
}

interface LibrarySyncFileBase {
  schemaVersion: typeof LEGACY_LIBRARY_SYNC_SCHEMA_VERSION | typeof LIBRARY_SYNC_SCHEMA_VERSION;
  format: "english-word-review-library-sync";
  appVersion: string;
  batchId: string;
  createdAt: string;
  sourceDevice: LibrarySyncDevice;
}

export interface LibraryProgressSummaryV1 {
  libraryId: string;
  words: Array<Record<string, unknown> & { word: string }>;
  reviewCards: Record<string, unknown>;
  fingerprint: string;
}

export interface LibraryContentSyncFileV1 extends LibrarySyncFileBase {
  kind: "library";
  library: CustomLibrary;
  /** Global values used by this library, preserved independently of other libraries. */
  globalOverrides: Record<string, WordOverride>;
  /** Metadata only; MP3 bytes are stored once under library-sync/assets/<sha>.mp3. */
  assets: Record<string, AudioAssetMeta>;
  wordFingerprint: string;
  contentFingerprint: string;
  progress: LibraryProgressSummaryV1;
}

export interface LearningProgressSyncFileV1 extends LibrarySyncFileBase {
  kind: "learning-progress";
  progress: LearningProgressSnapshotV1;
}

export interface LearningProgressSnapshotV1 {
  schemaVersion: V4Snapshot["schemaVersion"];
  appVersion: string;
  contentVersion: string;
  algorithmVersion: V4Snapshot["algorithmVersion"];
  minReaderVersion: string;
  requiredFeatures: string[];
  checkpoint: Omit<V4Snapshot["checkpoint"], "data"> & {
    progress: LearningCheckpointProgressV1;
  };
  events: V4Snapshot["events"];
  generations: Record<string, string>;
  updatedAt: string;
  fingerprint: string;
}

interface LearningCheckpointProgressV1 {
  libraries: Array<{ libraryId: string; words: Array<Record<string, unknown> & { word: string }> }>;
  intensiveWords: Array<Record<string, unknown> & { word: string }>;
  rootItems: Array<{
    id: string;
    choiceRight: number;
    choiceWrong: number;
    writeRight: number;
    writeWrong: number;
  }>;
}

export type LibrarySyncFileV1 = LibraryContentSyncFileV1 | LearningProgressSyncFileV1;

export interface LibrarySyncBatchV1 {
  batchId: string;
  createdAt: string;
  sourceDevice: LibrarySyncDevice;
  files: LibrarySyncFileV1[];
}

export type LibraryCopyComparison =
  | "identical"
  | "same-words-progress-different"
  | "same-words-content-different"
  | "different-words";

const PROGRESS_FIELDS = [
  "right", "wrong", "mastery",
  "reverseRight", "reverseWrong", "reverseMastery", "reverseReviewWeight",
  "rareRight", "rareWrong", "rareMastery",
  "spellRight", "spellWrong", "meaningRight", "meaningWrong",
] as const;

function clone<T>(value: T): T {
  return structuredClone(value);
}

function normalizedDevice(source: LibrarySyncDevice): LibrarySyncDevice {
  const deviceCode = source.deviceCode.trim().toUpperCase();
  const location = source.location.trim();
  if (!/^[0-9A-F]{8}$/.test(deviceCode)) throw new Error("设备编码必须是 8 位十六进制字符");
  if (!location) throw new Error("主动同步前必须填写地点");
  return {
    deviceCode,
    ...(source.deviceName?.trim() ? { deviceName: source.deviceName.trim() } : {}),
    location,
  };
}

function wordKeys(library: CustomLibrary): string[] {
  return [...new Set(library.words.map((item) => item.word.trim().toLocaleLowerCase()).filter(Boolean))].sort();
}

function contentForFingerprint(
  library: CustomLibrary,
  globalOverrides: Record<string, WordOverride>,
  assets: Record<string, AudioAssetMeta>,
): unknown {
  return {
    id: library.id,
    date: library.date,
    name: library.name ?? "",
    words: [...library.words]
      .map((item) => clone(item))
      .sort((left, right) => left.word.toLocaleLowerCase().localeCompare(right.word.toLocaleLowerCase())),
    globalOverrides,
    assets,
  };
}

function overridesForLibrary(snapshot: ContentSnapshotV1, library: CustomLibrary): Record<string, WordOverride> {
  const words = new Set(wordKeys(library));
  return Object.fromEntries(Object.entries(snapshot.globalOverrides)
    .filter(([word]) => words.has(word.trim().toLocaleLowerCase()))
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([word, override]) => [word, clone(override)]));
}

function assetsForLibrary(
  snapshot: ContentSnapshotV1,
  library: CustomLibrary,
  globalOverrides: Record<string, WordOverride>,
): Record<string, AudioAssetMeta> {
  const ids = new Set<string>();
  const collect = (override: WordOverride | undefined) => {
    override?.audioAssetIds?.forEach((id) => ids.add(id));
    if (override?.primaryAudioAssetId) ids.add(override.primaryAudioAssetId);
  };
  Object.values(globalOverrides).forEach(collect);
  for (const word of library.words) {
    collect(word.legacyOverride);
    collect(word.override);
  }
  return Object.fromEntries([...ids].sort().flatMap((id) => {
    const meta = snapshot.assets[id];
    return meta ? [[id, clone(meta)] as const] : [];
  }));
}

function legacyLibrary(snapshot: V4Snapshot, libraryId: string): LegacyLibrary | undefined {
  const projected = projectSnapshot(snapshot);
  return [projected.store.current, ...projected.store.archives].find((library) => library.id === libraryId);
}

function progressWord(word: LegacyWord): Record<string, unknown> & { word: string } {
  const result: Record<string, unknown> & { word: string } = { word: word.en.trim().toLocaleLowerCase() };
  for (const field of PROGRESS_FIELDS) result[field] = Number(word[field] ?? 0);
  return result;
}

function checkpointProgress(snapshot: V4Snapshot): LearningCheckpointProgressV1 {
  const data = snapshot.checkpoint.data;
  return {
    libraries: [data.store.current, ...data.store.archives].map((library) => ({
      libraryId: library.id,
      words: library.words.map(progressWord).sort((left, right) => left.word.localeCompare(right.word)),
    })).sort((left, right) => left.libraryId.localeCompare(right.libraryId)),
    intensiveWords: data.intensiveStore.words
      .map(progressWord)
      .sort((left, right) => left.word.localeCompare(right.word)),
    rootItems: data.rootStudyStore.items.map((item) => ({
      id: item.id,
      choiceRight: Number(item.choiceRight) || 0,
      choiceWrong: Number(item.choiceWrong) || 0,
      writeRight: Number(item.writeRight) || 0,
      writeWrong: Number(item.writeWrong) || 0,
    })).sort((left, right) => left.id.localeCompare(right.id)),
  };
}

function progressWithoutFingerprint(snapshot: V4Snapshot): Omit<LearningProgressSnapshotV1, "fingerprint"> {
  const { data: _data, ...checkpoint } = snapshot.checkpoint;
  const learningEvents = snapshot.events.filter((event) =>
    event.type !== "setting" && event.type !== "intensive-selection");
  return {
    schemaVersion: snapshot.schemaVersion,
    appVersion: snapshot.appVersion,
    contentVersion: snapshot.contentVersion,
    algorithmVersion: snapshot.algorithmVersion,
    minReaderVersion: snapshot.minReaderVersion,
    requiredFeatures: clone(snapshot.requiredFeatures),
    checkpoint: { ...clone(checkpoint), progress: checkpointProgress(snapshot) },
    events: clone(learningEvents),
    generations: clone(snapshot.generations),
    updatedAt: snapshot.updatedAt,
  };
}

export function createLearningProgressSnapshot(snapshot: V4Snapshot): LearningProgressSnapshotV1 {
  const progress = progressWithoutFingerprint(snapshot);
  return { ...progress, fingerprint: stableHash(progress) };
}

function applyWordProgress(target: LegacyWord[], incoming: Array<Record<string, unknown> & { word: string }>): void {
  const values = new Map(incoming.map((word) => [word.word.trim().toLocaleLowerCase(), word]));
  for (const word of target) {
    const progress = values.get(word.en.trim().toLocaleLowerCase());
    if (!progress) continue;
    for (const field of PROGRESS_FIELDS) word[field] = Number(progress[field]) || 0;
  }
}

/** Rebuilds a valid v4 snapshot using local definitions/settings and remote learning state only. */
export function inflateLearningProgressSnapshot(
  progress: LearningProgressSnapshotV1,
  local: V4Snapshot,
): V4Snapshot {
  const result = clone(local);
  result.appVersion = progress.appVersion;
  result.contentVersion = progress.contentVersion;
  result.algorithmVersion = progress.algorithmVersion;
  result.minReaderVersion = progress.minReaderVersion;
  result.requiredFeatures = clone(progress.requiredFeatures);
  result.events = clone(progress.events);
  result.generations = clone(progress.generations);
  result.updatedAt = progress.updatedAt;
  result.checkpoint = {
    id: progress.checkpoint.id,
    createdAt: progress.checkpoint.createdAt,
    vector: clone(progress.checkpoint.vector),
    ...(progress.checkpoint.lineage ? { lineage: clone(progress.checkpoint.lineage) } : {}),
    ...(progress.checkpoint.lastReviewedAt ? { lastReviewedAt: clone(progress.checkpoint.lastReviewedAt) } : {}),
    ...(progress.checkpoint.reviewCards ? { reviewCards: clone(progress.checkpoint.reviewCards) } : {}),
    data: clone(local.checkpoint.data),
  };

  const libraries = new Map(progress.checkpoint.progress.libraries.map((library) => [library.libraryId, library.words]));
  for (const library of [result.checkpoint.data.store.current, ...result.checkpoint.data.store.archives]) {
    applyWordProgress(library.words, libraries.get(library.id) ?? []);
  }
  applyWordProgress(result.checkpoint.data.intensiveStore.words, progress.checkpoint.progress.intensiveWords);
  const roots = new Map(progress.checkpoint.progress.rootItems.map((item) => [item.id, item]));
  for (const item of result.checkpoint.data.rootStudyStore.items) {
    const incoming = roots.get(item.id);
    if (!incoming) continue;
    item.choiceRight = incoming.choiceRight;
    item.choiceWrong = incoming.choiceWrong;
    item.writeRight = incoming.writeRight;
    item.writeWrong = incoming.writeWrong;
  }
  return result;
}

/**
 * Produces a semantic progress summary. Event IDs and device ordering are not
 * compared, so two devices that replay to the same visible progress match.
 */
export function summarizeLibraryProgress(snapshot: V4Snapshot, libraryId: string): LibraryProgressSummaryV1 {
  const library = legacyLibrary(snapshot, libraryId);
  const words = (library?.words ?? [])
    .map(progressWord)
    .sort((left, right) => left.word.localeCompare(right.word));
  const libraryWords = new Set(words.map((word) => word.word));
  const reviewCards = Object.fromEntries(Object.entries(projectReviewCards(snapshot))
    .filter(([, card]) => libraryWords.has(card.wordKey))
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([id, card]) => [id, {
      wordKey: card.wordKey,
      stage: card.stage,
      dueAt: card.dueAt,
      nextDirection: card.nextDirection,
      lastReviewedAt: card.lastReviewedAt,
      stats: card.stats,
    }]));
  const semantic = { libraryId, words, reviewCards };
  return { ...semantic, fingerprint: stableHash(semantic) };
}

export function createLibrarySyncBatch(
  content: ContentSnapshotV1,
  learning: V4Snapshot,
  source: LibrarySyncDevice,
  options: { now?: Date; batchId?: string } = {},
): LibrarySyncBatchV1 {
  const createdAt = (options.now ?? new Date()).toISOString();
  const batchId = options.batchId ?? globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`;
  const sourceDevice = normalizedDevice(source);
  const base = {
    schemaVersion: LIBRARY_SYNC_SCHEMA_VERSION,
    format: "english-word-review-library-sync" as const,
    appVersion: content.appVersion,
    batchId,
    createdAt,
    sourceDevice,
  };
  const files: LibrarySyncFileV1[] = content.libraries.map((library) => {
    const globalOverrides = overridesForLibrary(content, library);
    const assets = assetsForLibrary(content, library, globalOverrides);
    return {
      ...base,
      kind: "library" as const,
      library: clone(library),
      globalOverrides,
      assets,
      wordFingerprint: stableHash(wordKeys(library)),
      contentFingerprint: stableHash(contentForFingerprint(library, globalOverrides, assets)),
      progress: summarizeLibraryProgress(learning, library.id),
    };
  });
  files.push({ ...base, kind: "learning-progress", progress: createLearningProgressSnapshot(learning) });
  return { batchId, createdAt, sourceDevice, files };
}

export function compareLibraryCopies(
  left: LibraryContentSyncFileV1,
  right: LibraryContentSyncFileV1,
): LibraryCopyComparison {
  if (left.wordFingerprint !== right.wordFingerprint) return "different-words";
  if (left.contentFingerprint !== right.contentFingerprint) return "same-words-content-different";
  if (left.progress.fingerprint !== right.progress.fingerprint) return "same-words-progress-different";
  return "identical";
}

/** Apply explicitly selected library files without replacing unrelated local libraries. */
export function mergeLibrarySyncFilesIntoContent(
  target: ContentSnapshotV1,
  files: LibraryContentSyncFileV1[],
): void {
  for (const file of files) {
    const incoming = clone(file.library);
    for (const word of incoming.words) {
      const global = file.globalOverrides[word.word.trim().toLocaleLowerCase()];
      if (global) word.override = { ...clone(global), ...(word.override ? clone(word.override) : {}) };
      // A downloaded library is a self-contained copy. It must not inherit a
      // same-spelling global value that belongs to another local library.
      word.ignoreGlobalOverride = true;
    }
    const index = target.libraries.findIndex((library) => library.id === incoming.id);
    if (index >= 0) target.libraries[index] = incoming;
    else target.libraries.unshift(incoming);
    // A selected file must not change another local library containing the
    // same word. Source-global values therefore become local values here.
    Object.assign(target.assets, clone(file.assets));
    if (!target.activeLibraryId) target.activeLibraryId = file.library.id;
  }
}

export function parseLibrarySyncFile(text: string): LibrarySyncFileV1 {
  const value = JSON.parse(text) as Partial<LibrarySyncFileV1>;
  if (
    (value.schemaVersion !== LEGACY_LIBRARY_SYNC_SCHEMA_VERSION && value.schemaVersion !== LIBRARY_SYNC_SCHEMA_VERSION) ||
    value.format !== "english-word-review-library-sync" ||
    (value.kind !== "library" && value.kind !== "learning-progress") ||
    typeof value.batchId !== "string" || !value.batchId ||
    typeof value.createdAt !== "string" || !Number.isFinite(Date.parse(value.createdAt)) ||
    !value.sourceDevice
  ) throw new Error("文件不是有效的词库多文件同步格式 v1");
  normalizedDevice(value.sourceDevice);
  if (value.kind === "library") {
    if (!value.library || !Array.isArray(value.library.words) || typeof value.library.id !== "string") {
      throw new Error("词库同步文件缺少有效词库");
    }
    if (!value.progress || value.progress.libraryId !== value.library.id) {
      throw new Error("词库同步文件的进度与词库不匹配");
    }
    const expectedWords = stableHash(wordKeys(value.library));
    if (!value.globalOverrides || !value.assets) throw new Error("词库同步文件缺少全局覆盖或音频清单");
    const expectedContent = stableHash(contentForFingerprint(value.library, value.globalOverrides, value.assets));
    if (value.wordFingerprint !== expectedWords || value.contentFingerprint !== expectedContent) {
      throw new Error("词库同步文件校验失败");
    }
    const progress = value.progress;
    const expectedProgress = stableHash({
      libraryId: progress.libraryId,
      words: progress.words,
      reviewCards: progress.reviewCards,
    });
    if (progress.fingerprint !== expectedProgress) throw new Error("词库同步文件的学习进度校验失败");
  } else {
    const raw = value as Partial<LearningProgressSyncFileV1> & { snapshot?: V4Snapshot };
    // Read already-uploaded v1 files once and immediately canonicalize them to
    // the progress-only representation. New files never contain legacy content.
    const progress = raw.progress ?? (raw.snapshot?.schemaVersion === 4
      ? createLearningProgressSnapshot(raw.snapshot)
      : undefined);
    if (
      !progress || progress.schemaVersion !== 4 || !Array.isArray(progress.events) ||
      !progress.checkpoint?.progress ||
      !Array.isArray(progress.checkpoint.progress.libraries) ||
      !Array.isArray(progress.checkpoint.progress.intensiveWords) ||
      !Array.isArray(progress.checkpoint.progress.rootItems)
    ) {
      throw new Error("学习进度同步文件缺少有效 v4 学习载荷");
    }
    if (progress.events.some((event) => event.type === "setting" || event.type === "intensive-selection")) {
      throw new Error("Learning progress files must not contain content or setting events");
    }
    const { fingerprint, ...semantic } = progress;
    if (fingerprint !== stableHash(semantic)) throw new Error("学习进度同步文件校验失败");
    const canonical = { ...raw, progress } as Record<string, unknown>;
    delete canonical.snapshot;
    return canonical as unknown as LibrarySyncFileV1;
  }
  return value as LibrarySyncFileV1;
}

export function syncFileSlug(value: string, fallback: string): string {
  const result = value.normalize("NFKC").trim()
    .replace(/[<>:"/\\|?*\u0000-\u001f_]+/g, "-")
    .replace(/\s+/g, "-")
    .replace(/-+/g, "-")
    .replace(/^-|-$/g, "")
    .slice(0, 48);
  return result || fallback;
}

export function librarySyncMinute(value: string): string {
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) throw new Error("同步时间无效");
  const part = (number: number) => String(number).padStart(2, "0");
  return `${date.getFullYear()}${part(date.getMonth() + 1)}${part(date.getDate())}-${part(date.getHours())}${part(date.getMinutes())}`;
}

export function librarySyncFileName(file: LibrarySyncFileV1): string {
  const code = syncFileSlug(file.sourceDevice.deviceCode, "DEVICE").toUpperCase();
  const location = syncFileSlug(file.sourceDevice.location, "未设置地点");
  const time = librarySyncMinute(file.createdAt);
  const batch = syncFileSlug(file.batchId, "batch").slice(0, 12);
  const item = file.kind === "learning-progress"
    ? "progress"
    : `library-${syncFileSlug(file.library.id, "library")}-${stableHash(file.library.id).slice(0, 8)}`;
  return `${code}_${location}_${time}_${batch}_${item}.json`;
}

export function sameSyncMinute(left: LibrarySyncFileV1, right: LibrarySyncFileV1): boolean {
  return left.sourceDevice.deviceCode === right.sourceDevice.deviceCode &&
    left.sourceDevice.location === right.sourceDevice.location &&
    librarySyncMinute(left.createdAt) === librarySyncMinute(right.createdAt);
}
