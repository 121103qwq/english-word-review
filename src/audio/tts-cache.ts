export const MEBIBYTE = 1024 * 1024;
export const DEFAULT_TTS_CACHE_BYTES = 512 * MEBIBYTE;
export const MAX_TTS_CACHE_BYTES = 3 * 1024 * MEBIBYTE;
export const TTS_CACHE_SIZE_OPTIONS = [
  200 * MEBIBYTE,
  DEFAULT_TTS_CACHE_BYTES,
  1024 * MEBIBYTE,
  MAX_TTS_CACHE_BYTES,
] as const;

export interface TtsCacheKeyParts {
  text: string;
  voice: string;
  rate: number;
  model: string;
}

export interface TtsCacheEntry {
  key: string;
  bytes: Uint8Array;
  mimeType: "audio/wav" | "audio/mpeg";
  byteLength: number;
  createdAt: string;
  lastAccessedAt: string;
}

export type TtsCacheMetadata = Omit<TtsCacheEntry, "bytes">;

export interface TtsCacheStats {
  entries: number;
  totalBytes: number;
  maxBytes: number;
}

export interface TtsCacheBackend {
  get(key: string): Promise<TtsCacheEntry | undefined>;
  put(entry: TtsCacheEntry): Promise<void>;
  touch(key: string, lastAccessedAt: string): Promise<void>;
  delete(key: string): Promise<void>;
  /** Metadata-only listing: a 3 GiB cache must never be loaded into memory to calculate LRU. */
  list(): Promise<TtsCacheMetadata[]>;
}

const clone = <T>(value: T): T => structuredClone(value);

function toHex(bytes: Uint8Array): string {
  return [...bytes].map((part) => part.toString(16).padStart(2, "0")).join("");
}

export async function createTtsCacheKey(parts: TtsCacheKeyParts): Promise<string> {
  const normalized = JSON.stringify({
    text: parts.text.normalize("NFC"),
    voice: parts.voice,
    rate: Number(parts.rate.toFixed(2)),
    model: parts.model,
  });
  const digest = await globalThis.crypto.subtle.digest("SHA-256", new TextEncoder().encode(normalized));
  return `tts-v1-${toHex(new Uint8Array(digest))}`;
}

export class MemoryTtsCacheBackend implements TtsCacheBackend {
  private readonly entries = new Map<string, TtsCacheEntry>();

  async get(key: string): Promise<TtsCacheEntry | undefined> {
    const entry = this.entries.get(key);
    return entry && clone(entry);
  }

  async put(entry: TtsCacheEntry): Promise<void> {
    this.entries.set(entry.key, clone(entry));
  }

  async touch(key: string, lastAccessedAt: string): Promise<void> {
    const entry = this.entries.get(key);
    if (entry) entry.lastAccessedAt = lastAccessedAt;
  }

  async delete(key: string): Promise<void> {
    this.entries.delete(key);
  }

  async list(): Promise<TtsCacheMetadata[]> {
    return [...this.entries.values()].map(({ bytes: _bytes, ...metadata }) => clone(metadata));
  }
}

function idbRequest<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

function idbTransaction(transaction: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    transaction.oncomplete = () => resolve();
    transaction.onabort = () => reject(transaction.error);
    transaction.onerror = () => reject(transaction.error);
  });
}

const TTS_CACHE_DATABASE_OPEN_TIMEOUT_MS = 10_000;

function openTtsCacheDatabase(name: string): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const opening = globalThis.indexedDB.open(name, 2);
    let settled = false;
    let timeout: ReturnType<typeof setTimeout> | undefined;

    const rejectOpening = (error: unknown, fallbackMessage: string) => {
      if (settled) return;
      settled = true;
      if (timeout !== undefined) clearTimeout(timeout);
      try {
        opening.transaction?.abort();
      } catch {
        // The upgrade transaction may already have finished or be inactive.
      }
      reject(error ?? new Error(fallbackMessage));
    };

    opening.onupgradeneeded = () => {
      if (settled) {
        try {
          opening.transaction?.abort();
        } catch {
          // The request has already failed from the application's perspective.
        }
        return;
      }
      const database = opening.result;
      const metadataStore = database.objectStoreNames.contains("metadata")
        ? opening.transaction!.objectStore("metadata")
        : database.createObjectStore("metadata", { keyPath: "key" });
      const audioStore = database.objectStoreNames.contains("audio")
        ? opening.transaction!.objectStore("audio")
        : database.createObjectStore("audio");
      // Migrate the short-lived development schema without loading all audio
      // into application memory. This runs inside the IndexedDB upgrade.
      if (database.objectStoreNames.contains("entries")) {
        const cursorRequest = opening.transaction!.objectStore("entries").openCursor();
        cursorRequest.onsuccess = () => {
          const cursor = cursorRequest.result;
          if (!cursor) return;
          const entry = cursor.value as TtsCacheEntry;
          const { bytes, ...metadata } = entry;
          metadataStore.put(metadata);
          audioStore.put(bytes, entry.key);
          cursor.continue();
        };
      }
    };
    opening.onsuccess = () => {
      const database = opening.result;
      if (settled) {
        database.close();
        return;
      }
      settled = true;
      if (timeout !== undefined) clearTimeout(timeout);
      resolve(database);
    };
    opening.onerror = () => rejectOpening(opening.error, "TTS 缓存数据库打开失败");
    opening.onblocked = () => rejectOpening(
      new Error("TTS 缓存数据库被其他页面阻止，请关闭其他页面后重试"),
      "TTS 缓存数据库打开被阻止",
    );

    timeout = setTimeout(() => {
      rejectOpening(
        new Error("TTS 缓存数据库打开超时，请关闭其他页面后重试"),
        "TTS 缓存数据库打开超时",
      );
    }, TTS_CACHE_DATABASE_OPEN_TIMEOUT_MS);
  });
}

/** Dedicated cache database. It is intentionally separate from content/word-library storage. */
export class IndexedDbTtsCacheBackend implements TtsCacheBackend {
  private readonly dbPromise: Promise<IDBDatabase>;

  constructor(name = "english-word-review-tts-cache-v1") {
    if (!globalThis.indexedDB) throw new Error("当前环境不支持 IndexedDB");
    this.dbPromise = openTtsCacheDatabase(name);
  }

  async get(key: string): Promise<TtsCacheEntry | undefined> {
    const db = await this.dbPromise;
    const transaction = db.transaction(["metadata", "audio"]);
    const [metadata, bytes] = await Promise.all([
      idbRequest(transaction.objectStore("metadata").get(key)) as Promise<TtsCacheMetadata | undefined>,
      idbRequest(transaction.objectStore("audio").get(key)) as Promise<Uint8Array | undefined>,
    ]);
    return metadata && bytes ? { ...metadata, bytes } : undefined;
  }

  async put(entry: TtsCacheEntry): Promise<void> {
    const db = await this.dbPromise;
    const transaction = db.transaction(["metadata", "audio"], "readwrite");
    const { bytes, ...metadata } = entry;
    transaction.objectStore("metadata").put(metadata);
    transaction.objectStore("audio").put(bytes, entry.key);
    await idbTransaction(transaction);
  }

  async touch(key: string, lastAccessedAt: string): Promise<void> {
    const db = await this.dbPromise;
    const current = await idbRequest(db.transaction("metadata").objectStore("metadata").get(key)) as TtsCacheMetadata | undefined;
    if (!current) return;
    const transaction = db.transaction("metadata", "readwrite");
    transaction.objectStore("metadata").put({ ...current, lastAccessedAt });
    await idbTransaction(transaction);
  }

  async delete(key: string): Promise<void> {
    const db = await this.dbPromise;
    const transaction = db.transaction(["metadata", "audio"], "readwrite");
    transaction.objectStore("metadata").delete(key);
    transaction.objectStore("audio").delete(key);
    await idbTransaction(transaction);
  }

  async list(): Promise<TtsCacheMetadata[]> {
    const db = await this.dbPromise;
    return idbRequest(db.transaction("metadata").objectStore("metadata").getAll()) as Promise<TtsCacheMetadata[]>;
  }
}

function clampLimit(value: number): number {
  if (!Number.isFinite(value)) return DEFAULT_TTS_CACHE_BYTES;
  return Math.max(0, Math.min(MAX_TTS_CACHE_BYTES, Math.floor(value)));
}

export interface TtsCacheOptions {
  maxBytes?: number;
  now?: () => Date;
  availableStorageBytes?: () => Promise<number | undefined>;
}

export class TtsCache {
  private maxBytes: number;
  private readonly now: () => Date;
  private readonly availableStorageBytes: () => Promise<number | undefined>;

  constructor(private readonly backend: TtsCacheBackend, options: TtsCacheOptions = {}) {
    this.maxBytes = clampLimit(options.maxBytes ?? DEFAULT_TTS_CACHE_BYTES);
    this.now = options.now ?? (() => new Date());
    this.availableStorageBytes = options.availableStorageBytes ?? (async () => {
      const estimate = await globalThis.navigator?.storage?.estimate?.();
      return estimate?.quota === undefined || estimate.usage === undefined
        ? undefined
        : Math.max(0, estimate.quota - estimate.usage);
    });
  }

  getMaxBytes(): number {
    return this.maxBytes;
  }

  async setMaxBytes(value: number): Promise<TtsCacheStats> {
    this.maxBytes = clampLimit(value);
    await this.prune();
    return this.stats();
  }

  async get(key: string): Promise<TtsCacheEntry | undefined> {
    const entry = await this.backend.get(key);
    if (!entry) return undefined;
    entry.lastAccessedAt = this.now().toISOString();
    await this.backend.touch(key, entry.lastAccessedAt);
    return clone(entry);
  }

  /** Returns false when a single result is larger than the selected cache limit. */
  async put(
    key: string,
    bytes: Uint8Array,
    mimeType: TtsCacheEntry["mimeType"] = "audio/wav",
  ): Promise<boolean> {
    if (bytes.byteLength > this.maxBytes || this.maxBytes === 0) return false;
    if (!(await this.prepareSpace(key, bytes.byteLength))) return false;
    const timestamp = this.now().toISOString();
    const previous = await this.backend.get(key);
    await this.backend.put({
      key,
      bytes: new Uint8Array(bytes),
      mimeType,
      byteLength: bytes.byteLength,
      createdAt: previous?.createdAt ?? timestamp,
      lastAccessedAt: timestamp,
    });
    await this.prune();
    return Boolean(await this.backend.get(key));
  }

  private async prepareSpace(key: string, byteLength: number): Promise<boolean> {
    const entries = (await this.backend.list()).sort((left, right) =>
      left.lastAccessedAt.localeCompare(right.lastAccessedAt) ||
      left.createdAt.localeCompare(right.createdAt) ||
      left.key.localeCompare(right.key));
    const previousBytes = entries.find((entry) => entry.key === key)?.byteLength ?? 0;
    let cacheBytes = entries.reduce((sum, entry) => sum + entry.byteLength, 0) - previousBytes;
    let available = await this.availableStorageBytes();
    let additionalBytes = Math.max(0, byteLength - previousBytes);
    for (const entry of entries) {
      const exceedsCacheLimit = cacheBytes + byteLength > this.maxBytes;
      const exceedsDeviceSpace = available !== undefined && additionalBytes > available;
      if (!exceedsCacheLimit && !exceedsDeviceSpace) break;
      if (entry.key === key) continue;
      await this.backend.delete(entry.key);
      cacheBytes -= entry.byteLength;
      if (available !== undefined) available += entry.byteLength;
    }
    return cacheBytes + byteLength <= this.maxBytes && (available === undefined || additionalBytes <= available);
  }

  async delete(key: string): Promise<void> {
    await this.backend.delete(key);
  }

  async clear(): Promise<void> {
    await Promise.all((await this.backend.list()).map((entry) => this.backend.delete(entry.key)));
  }

  async stats(): Promise<TtsCacheStats> {
    const entries = await this.backend.list();
    return {
      entries: entries.length,
      totalBytes: entries.reduce((sum, entry) => sum + entry.byteLength, 0),
      maxBytes: this.maxBytes,
    };
  }

  async prune(): Promise<string[]> {
    const entries = (await this.backend.list()).sort((left, right) =>
      left.lastAccessedAt.localeCompare(right.lastAccessedAt) ||
      left.createdAt.localeCompare(right.createdAt) ||
      left.key.localeCompare(right.key));
    let total = entries.reduce((sum, entry) => sum + entry.byteLength, 0);
    const deleted: string[] = [];
    for (const entry of entries) {
      if (total <= this.maxBytes) break;
      await this.backend.delete(entry.key);
      total -= entry.byteLength;
      deleted.push(entry.key);
    }
    return deleted;
  }
}
