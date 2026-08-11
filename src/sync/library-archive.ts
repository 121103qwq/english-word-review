import {
  LIBRARY_SYNC_BATCHES_PER_LOCATION,
  librarySyncFileName,
  type LibrarySyncBatchV1,
  type LibrarySyncFileV1,
} from "./library-files";

export interface ArchivedLibrarySyncFile {
  fileName: string;
  rawText: string;
  file: LibrarySyncFileV1;
  savedAt: string;
}

export interface LibrarySyncArchiveBackend {
  list(): Promise<ArchivedLibrarySyncFile[]>;
  get(fileName: string): Promise<ArchivedLibrarySyncFile | undefined>;
  put(value: ArchivedLibrarySyncFile): Promise<void>;
  delete(fileName: string): Promise<void>;
}

const clone = <T>(value: T): T => structuredClone(value);

export class MemoryLibrarySyncArchive implements LibrarySyncArchiveBackend {
  private readonly files = new Map<string, ArchivedLibrarySyncFile>();
  async list(): Promise<ArchivedLibrarySyncFile[]> { return [...this.files.values()].map(clone); }
  async get(fileName: string): Promise<ArchivedLibrarySyncFile | undefined> {
    const value = this.files.get(fileName);
    return value && clone(value);
  }
  async put(value: ArchivedLibrarySyncFile): Promise<void> { this.files.set(value.fileName, clone(value)); }
  async delete(fileName: string): Promise<void> { this.files.delete(fileName); }
}

function request<T>(value: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    value.onsuccess = () => resolve(value.result);
    value.onerror = () => reject(value.error);
  });
}

export class IndexedDbLibrarySyncArchive implements LibrarySyncArchiveBackend {
  private readonly dbPromise: Promise<IDBDatabase>;

  constructor(name = "english-word-review-library-sync-v1") {
    if (!globalThis.indexedDB) throw new Error("当前环境不支持 IndexedDB");
    this.dbPromise = new Promise((resolve, reject) => {
      const opening = indexedDB.open(name, 1);
      opening.onupgradeneeded = () => {
        if (!opening.result.objectStoreNames.contains("files")) opening.result.createObjectStore("files");
      };
      opening.onsuccess = () => resolve(opening.result);
      opening.onerror = () => reject(opening.error);
    });
  }

  private async store(mode: IDBTransactionMode = "readonly"): Promise<IDBObjectStore> {
    const db = await this.dbPromise;
    return db.transaction("files", mode).objectStore("files");
  }

  async list(): Promise<ArchivedLibrarySyncFile[]> {
    return request((await this.store()).getAll()) as Promise<ArchivedLibrarySyncFile[]>;
  }

  async get(fileName: string): Promise<ArchivedLibrarySyncFile | undefined> {
    return request((await this.store()).get(fileName)) as Promise<ArchivedLibrarySyncFile | undefined>;
  }

  async put(value: ArchivedLibrarySyncFile): Promise<void> {
    await request((await this.store("readwrite")).put(clone(value), value.fileName));
  }

  async delete(fileName: string): Promise<void> {
    await request((await this.store("readwrite")).delete(fileName));
  }
}

function batchKey(file: LibrarySyncFileV1): string {
  return `${file.sourceDevice.deviceCode}|${file.sourceDevice.location}|${file.batchId}`;
}

export async function pruneLibrarySyncArchive(backend: LibrarySyncArchiveBackend): Promise<void> {
  const grouped = new Map<string, ArchivedLibrarySyncFile[]>();
  for (const item of await backend.list()) {
    const key = `${item.file.sourceDevice.deviceCode}|${item.file.sourceDevice.location}`;
    const list = grouped.get(key) ?? [];
    list.push(item);
    grouped.set(key, list);
  }
  for (const items of grouped.values()) {
    const batches = new Map<string, ArchivedLibrarySyncFile[]>();
    for (const item of items) {
      const key = batchKey(item.file);
      const batch = batches.get(key) ?? [];
      batch.push(item);
      batches.set(key, batch);
    }
    const sorted = [...batches.values()].sort((left, right) =>
      right[0].file.createdAt.localeCompare(left[0].file.createdAt) ||
      right[0].file.batchId.localeCompare(left[0].file.batchId));
    for (const expired of sorted.slice(LIBRARY_SYNC_BATCHES_PER_LOCATION)) {
      for (const item of expired) await backend.delete(item.fileName);
    }
  }
}

export async function archiveLibrarySyncFiles(
  backend: LibrarySyncArchiveBackend,
  files: LibrarySyncFileV1[],
  savedAt = new Date().toISOString(),
): Promise<void> {
  for (const file of files) {
    const fileName = librarySyncFileName(file);
    await backend.put({ fileName, rawText: JSON.stringify(file), file: clone(file), savedAt });
  }
  await pruneLibrarySyncArchive(backend);
}

export async function archiveLibrarySyncBatch(
  backend: LibrarySyncArchiveBackend,
  batch: LibrarySyncBatchV1,
  savedAt = new Date().toISOString(),
): Promise<void> {
  return archiveLibrarySyncFiles(backend, batch.files, savedAt);
}
