import type { HybridClock, LegacyBundle } from "../core/types";
import type {
  AudioAssetMeta,
  ContentSnapshotV1,
  CustomLibrary,
  CustomLibraryWord,
  ResolvedWord,
  RootComponent,
  StoredAudioAsset,
  WordOverride,
} from "./types";

export const CONTENT_APP_VERSION = "8.2.3" as const;
export const DICTIONARY_VERSION = "ecdict-bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b+engra-798d54beb0deae476b856719cb8d5ad33d0baab2";
export const MAX_MP3_BYTES = 20 * 1024 * 1024;

export interface ModelOptions {
  now?: Date;
  uuid?: () => string;
}

const fallbackUuid = (): string =>
  globalThis.crypto?.randomUUID?.() ?? `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;

const cleanWord = (word: string): string => word.trim().toLocaleLowerCase("en-US");

export function localIsoDate(date = new Date()): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

export function compareHybridClock(a: HybridClock, b: HybridClock): number {
  return a.wallTime - b.wallTime || a.logical - b.logical || a.deviceId.localeCompare(b.deviceId);
}

export function compareContentRevision(a: Pick<ContentSnapshotV1, "revision" | "revisionId">, b: Pick<ContentSnapshotV1, "revision" | "revisionId">): number {
  return compareHybridClock(a.revision, b.revision) || a.revisionId.localeCompare(b.revisionId);
}

export function nextHybridClock(previous: HybridClock, deviceId: string, now = Date.now()): HybridClock {
  return now > previous.wallTime
    ? { wallTime: now, logical: 0, deviceId }
    : { wallTime: previous.wallTime, logical: previous.logical + 1, deviceId };
}

export function createEmptyContentSnapshot(deviceId: string, options: ModelOptions = {}): ContentSnapshotV1 {
  const now = options.now ?? new Date();
  return {
    schemaVersion: 1,
    appVersion: CONTENT_APP_VERSION,
    dictionaryVersion: DICTIONARY_VERSION,
    revision: { wallTime: now.getTime(), logical: 0, deviceId },
    revisionId: (options.uuid ?? fallbackUuid)(),
    modifiedAt: now.toISOString(),
    activeLibraryId: null,
    libraries: [],
    globalOverrides: {},
    assets: {},
  };
}

export function parseWordInput(input: string): string[] {
  const seen = new Set<string>();
  const result: string[] = [];
  for (const raw of input.split(/[,，.。\r\n]+/u)) {
    const word = cleanWord(raw);
    if (word && !seen.has(word)) {
      seen.add(word);
      result.push(word);
    }
  }
  return result;
}

export function createLibrary(
  input: { date?: string; name?: string; words?: CustomLibraryWord[] },
  options: ModelOptions = {},
): CustomLibrary {
  const now = options.now ?? new Date();
  const date = input.date?.trim() || localIsoDate(now);
  const parsedDate = /^\d{4}-\d{2}-\d{2}$/.test(date) ? new Date(`${date}T00:00:00`) : undefined;
  if (!parsedDate || Number.isNaN(parsedDate.getTime()) || localIsoDate(parsedDate) !== date) {
    throw new Error("词库日期必须是有效的 YYYY-MM-DD 日期");
  }
  return {
    id: (options.uuid ?? fallbackUuid)(),
    date,
    ...(input.name?.trim() ? { name: input.name.trim() } : {}),
    words: structuredClone(input.words ?? []),
    createdAt: now.toISOString(),
    modifiedAt: now.toISOString(),
  };
}

function automaticLegacyRootForms(bundle: LegacyBundle): Map<string, Set<string>> {
  const result = new Map<string, Set<string>>();
  for (const library of [bundle.store.current, ...bundle.store.archives]) {
    for (const word of library.words) {
      const forms = result.get(cleanWord(word.en)) ?? new Set<string>();
      const roots = Array.isArray(word.roots) ? word.roots as Array<Record<string, unknown>> : [];
      for (const root of roots) {
        if (root.source !== "engra" && root.source !== "inferred") continue;
        const form = String(root.root ?? root.form ?? "").trim().toLocaleLowerCase("en-US");
        if (form) forms.add(form);
      }
      if (forms.size) result.set(cleanWord(word.en), forms);
    }
  }
  return result;
}

function legacyRoots(bundle: LegacyBundle): Map<string, RootComponent[]> {
  const result = new Map<string, RootComponent[]>();
  const automaticForms = automaticLegacyRootForms(bundle);
  for (const item of bundle.rootStudyStore.items) {
    const source = (item as unknown as Record<string, unknown>).source;
    if (source === "engra" || source === "inferred") continue;
    for (const rawWord of item.words) {
      const word = cleanWord(rawWord);
      if (automaticForms.get(word)?.has(item.root.trim().toLocaleLowerCase("en-US"))) continue;
      const roots = result.get(word) ?? [];
      roots.push({ root: item.root, meaning: item.meaning, source: "legacy" });
      result.set(word, roots);
    }
  }
  return result;
}

export function migrateLegacyBundle(bundle: LegacyBundle, deviceId: string, options: ModelOptions = {}): ContentSnapshotV1 {
  const snapshot = createEmptyContentSnapshot(deviceId, options);
  const rootMap = legacyRoots(bundle);
  const migrateLibrary = (library: LegacyBundle["store"]["current"]): CustomLibrary => {
    const now = options.now ?? new Date();
    return {
      id: library.id || (options.uuid ?? fallbackUuid)(),
      date: library.date || localIsoDate(now),
      words: library.words.map((legacy): CustomLibraryWord => {
        const word = cleanWord(legacy.en);
        const roots = rootMap.get(word);
        return {
          word,
          source: "user",
          legacyOverride: {
            ...(legacy.zh ? { meaning: legacy.zh } : {}),
            ...(roots?.length ? { roots: structuredClone(roots) } : {}),
          },
          legacyProgress: structuredClone(legacy),
        };
      }),
      createdAt: now.toISOString(),
      modifiedAt: now.toISOString(),
    };
  };
  snapshot.libraries = [migrateLibrary(bundle.store.current), ...bundle.store.archives.map(migrateLibrary)];
  snapshot.activeLibraryId = snapshot.libraries[0]?.id ?? null;
  return snapshot;
}

const overrideFields: Array<keyof WordOverride> = ["meaning", "roots", "pronunciation", "audioAssetIds", "primaryAudioAssetId"];

function copyDefined(target: WordOverride, source?: WordOverride): void {
  if (!source) return;
  for (const field of overrideFields) {
    const value = source[field];
    if (value !== undefined) (target as Record<string, unknown>)[field] = structuredClone(value);
  }
}

function isAutomaticRoot(root: RootComponent): boolean {
  return root.source === "engra" || root.source === "inferred";
}

function mergeLegacyRoots(
  legacyRoots: RootComponent[] | undefined,
  dictionaryRoots: RootComponent[] | undefined,
): RootComponent[] | undefined {
  const dictionary = dictionaryRoots?.map((root) => structuredClone(root)) ?? [];
  const dictionaryForms = new Set(dictionary.map((root) => root.root.trim().toLocaleLowerCase("en-US")));
  const legacy = (legacyRoots ?? [])
    .filter((root) => !isAutomaticRoot(root))
    .filter((root) => !dictionaryForms.has(root.root.trim().toLocaleLowerCase("en-US")))
    .map((root) => structuredClone(root));
  const roots = [...dictionary, ...legacy];
  return roots.length ? roots : undefined;
}

/** Resolves each field independently: local > global > legacy > dictionary. */
export function resolveWord(
  entry: CustomLibraryWord,
  globalOverride?: WordOverride,
  dictionaryValue?: WordOverride,
): ResolvedWord {
  const value: WordOverride = {};
  copyDefined(value, dictionaryValue);
  const legacy = structuredClone(entry.legacyOverride ?? {});
  delete legacy.roots;
  copyDefined(value, legacy);
  const legacyRoots = entry.legacyOverride?.roots;
  const dictionaryRoots = dictionaryValue?.roots;
  const mergedRoots = mergeLegacyRoots(legacyRoots, dictionaryRoots);
  if (mergedRoots) value.roots = mergedRoots;
  copyDefined(value, globalOverride);
  copyDefined(value, entry.override);
  return { word: entry.word, source: entry.source, ...value };
}

export function applyWordOverride(
  snapshot: ContentSnapshotV1,
  word: string,
  patch: WordOverride,
  scope: { type: "library"; libraryId: string } | { type: "global" },
): ContentSnapshotV1 {
  const next = structuredClone(snapshot);
  const normalized = cleanWord(word);
  if (scope.type === "library") {
    const library = next.libraries.find((item) => item.id === scope.libraryId);
    const entry = library?.words.find((item) => cleanWord(item.word) === normalized);
    if (!library || !entry) throw new Error("词库或单词不存在");
    entry.override = { ...(entry.override ?? {}), ...structuredClone(patch) };
    return next;
  }

  next.globalOverrides[normalized] = {
    ...(next.globalOverrides[normalized] ?? {}),
    ...structuredClone(patch),
  };
  const changedFields = overrideFields.filter((field) => patch[field] !== undefined);
  for (const library of next.libraries) {
    for (const entry of library.words) {
      if (cleanWord(entry.word) !== normalized || !entry.override) continue;
      for (const field of changedFields) delete entry.override[field];
      if (Object.keys(entry.override).length === 0) delete entry.override;
    }
  }
  return next;
}

function isMp3(bytes: Uint8Array): boolean {
  if (bytes.length < 3) return false;
  if (bytes[0] === 0x49 && bytes[1] === 0x44 && bytes[2] === 0x33) return true; // ID3
  for (let index = 0; index < Math.min(bytes.length - 1, 4096); index += 1) {
    if (bytes[index] === 0xff && (bytes[index + 1] & 0xe0) === 0xe0) return true;
  }
  return false;
}

async function sha256(bytes: Uint8Array): Promise<string> {
  const buffer = await globalThis.crypto.subtle.digest("SHA-256", bytes as BufferSource);
  return [...new Uint8Array(buffer)].map((part) => part.toString(16).padStart(2, "0")).join("");
}

export async function createMp3Asset(
  input: Blob | ArrayBuffer | Uint8Array,
  fileName: string,
  options: { now?: Date; durationMs?: number } = {},
): Promise<StoredAudioAsset> {
  if (input instanceof Blob && input.size > MAX_MP3_BYTES) throw new Error("MP3 文件不能超过 20 MiB");
  const bytes = input instanceof Blob
    ? new Uint8Array(await input.arrayBuffer())
    : input instanceof Uint8Array
      ? new Uint8Array(input)
      : new Uint8Array(input);
  if (bytes.byteLength > MAX_MP3_BYTES) throw new Error("MP3 文件不能超过 20 MiB");
  if (!isMp3(bytes)) throw new Error("文件不是有效的 MP3 音频");
  const hash = await sha256(bytes);
  const now = options.now ?? new Date();
  const meta: AudioAssetMeta = {
    id: hash,
    sha256: hash,
    mimeType: "audio/mpeg",
    byteLength: bytes.byteLength,
    fileName: fileName.trim() || `${hash}.mp3`,
    createdAt: now.toISOString(),
    ...(options.durationMs === undefined ? {} : { durationMs: options.durationMs }),
  };
  return { meta, bytes };
}
