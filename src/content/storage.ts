import { CONTENT_APP_VERSION, createEmptyContentSnapshot, nextHybridClock } from "./model";
import type { ContentBackup, ContentOutboxItem, ContentSnapshotV1, StoredAudioAsset } from "./types";

export const CONTENT_BACKUP_LIMIT = 30;
export const OUTBOX_RETRY_DELAYS_MS = [15_000, 60_000, 300_000] as const;

/** Minimal persistence contract used by content mirror synchronization. */
export interface ContentPersistence {
  getCurrent(): Promise<ContentSnapshotV1 | undefined>;
  setCurrent(snapshot: ContentSnapshotV1): Promise<void>;
  saveBackup(backup: ContentBackup): Promise<void>;
  listBackups(): Promise<ContentBackup[]>;
  deleteBackup(id: string): Promise<void>;
  enqueueOutbox(item: ContentOutboxItem): Promise<void>;
}

export interface ContentStorageBackend {
  getCurrent(): Promise<ContentSnapshotV1 | undefined>;
  putCurrent(snapshot: ContentSnapshotV1): Promise<void>;
  listBackups(): Promise<ContentBackup[]>;
  putBackup(backup: ContentBackup): Promise<void>;
  deleteBackup(id: string): Promise<void>;
  getAsset(id: string): Promise<StoredAudioAsset | undefined>;
  listAssets(): Promise<StoredAudioAsset[]>;
  putAsset(asset: StoredAudioAsset): Promise<void>;
  deleteAsset(id: string): Promise<void>;
  listOutbox(): Promise<ContentOutboxItem[]>;
  putOutbox(item: ContentOutboxItem): Promise<void>;
  deleteOutbox(id: string): Promise<void>;
}

const clone = <T>(value: T): T => structuredClone(value);

export class MemoryContentBackend implements ContentStorageBackend, ContentPersistence {
  private current?: ContentSnapshotV1;
  private backups = new Map<string, ContentBackup>();
  private assets = new Map<string, StoredAudioAsset>();
  private outbox = new Map<string, ContentOutboxItem>();

  async getCurrent(): Promise<ContentSnapshotV1 | undefined> { return this.current && clone(this.current); }
  async putCurrent(snapshot: ContentSnapshotV1): Promise<void> { this.current = clone(snapshot); }
  async setCurrent(snapshot: ContentSnapshotV1): Promise<void> { return this.putCurrent(snapshot); }
  async listBackups(): Promise<ContentBackup[]> { return [...this.backups.values()].map(clone); }
  async putBackup(backup: ContentBackup): Promise<void> { this.backups.set(backup.id, clone(backup)); }
  async saveBackup(backup: ContentBackup): Promise<void> { return this.putBackup(backup); }
  async deleteBackup(id: string): Promise<void> { this.backups.delete(id); }
  async getAsset(id: string): Promise<StoredAudioAsset | undefined> { const value = this.assets.get(id); return value && clone(value); }
  async listAssets(): Promise<StoredAudioAsset[]> { return [...this.assets.values()].map(clone); }
  async putAsset(asset: StoredAudioAsset): Promise<void> { this.assets.set(asset.meta.id, clone(asset)); }
  async deleteAsset(id: string): Promise<void> { this.assets.delete(id); }
  async listOutbox(): Promise<ContentOutboxItem[]> { return [...this.outbox.values()].map(clone); }
  async putOutbox(item: ContentOutboxItem): Promise<void> { this.outbox.set(item.id, clone(item)); }
  async enqueueOutbox(item: ContentOutboxItem): Promise<void> { return this.putOutbox(item); }
  async deleteOutbox(id: string): Promise<void> { this.outbox.delete(id); }
}

type StoreName = "state" | "backups" | "assets" | "outbox";

function request<T>(value: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    value.onsuccess = () => resolve(value.result);
    value.onerror = () => reject(value.error);
  });
}

export class IndexedDbContentBackend implements ContentStorageBackend, ContentPersistence {
  private readonly dbPromise: Promise<IDBDatabase>;

  constructor(name = "english-word-review-content-v1") {
    if (!globalThis.indexedDB) throw new Error("当前环境不支持 IndexedDB");
    this.dbPromise = new Promise((resolve, reject) => {
      const opening = indexedDB.open(name, 1);
      opening.onupgradeneeded = () => {
        for (const store of ["state", "backups", "assets", "outbox"] as StoreName[]) {
          if (!opening.result.objectStoreNames.contains(store)) opening.result.createObjectStore(store);
        }
      };
      opening.onsuccess = () => resolve(opening.result);
      opening.onerror = () => reject(opening.error);
    });
  }

  private async get<T>(store: StoreName, key: string): Promise<T | undefined> {
    const db = await this.dbPromise;
    return request(db.transaction(store).objectStore(store).get(key)) as Promise<T | undefined>;
  }
  private async put<T>(store: StoreName, key: string, value: T): Promise<void> {
    const db = await this.dbPromise;
    await request(db.transaction(store, "readwrite").objectStore(store).put(value, key));
  }
  private async delete(store: StoreName, key: string): Promise<void> {
    const db = await this.dbPromise;
    await request(db.transaction(store, "readwrite").objectStore(store).delete(key));
  }
  private async values<T>(store: StoreName): Promise<T[]> {
    const db = await this.dbPromise;
    return request(db.transaction(store).objectStore(store).getAll()) as Promise<T[]>;
  }

  getCurrent(): Promise<ContentSnapshotV1 | undefined> { return this.get("state", "current"); }
  putCurrent(snapshot: ContentSnapshotV1): Promise<void> { return this.put("state", "current", snapshot); }
  setCurrent(snapshot: ContentSnapshotV1): Promise<void> { return this.putCurrent(snapshot); }
  listBackups(): Promise<ContentBackup[]> { return this.values("backups"); }
  putBackup(backup: ContentBackup): Promise<void> { return this.put("backups", backup.id, backup); }
  saveBackup(backup: ContentBackup): Promise<void> { return this.putBackup(backup); }
  deleteBackup(id: string): Promise<void> { return this.delete("backups", id); }
  getAsset(id: string): Promise<StoredAudioAsset | undefined> { return this.get("assets", id); }
  listAssets(): Promise<StoredAudioAsset[]> { return this.values("assets"); }
  putAsset(asset: StoredAudioAsset): Promise<void> { return this.put("assets", asset.meta.id, asset); }
  deleteAsset(id: string): Promise<void> { return this.delete("assets", id); }
  listOutbox(): Promise<ContentOutboxItem[]> { return this.values("outbox"); }
  putOutbox(item: ContentOutboxItem): Promise<void> { return this.put("outbox", item.id, item); }
  enqueueOutbox(item: ContentOutboxItem): Promise<void> { return this.putOutbox(item); }
  deleteOutbox(id: string): Promise<void> { return this.delete("outbox", id); }
}

export interface ContentRepositoryOptions {
  now?: () => Date;
  uuid?: () => string;
}

export class ContentRepository {
  private readonly now: () => Date;
  private readonly uuid: () => string;

  constructor(
    private readonly backend: ContentStorageBackend,
    private readonly deviceId: string,
    options: ContentRepositoryOptions = {},
  ) {
    this.now = options.now ?? (() => new Date());
    this.uuid = options.uuid ?? (() => globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`);
  }

  async open(): Promise<ContentSnapshotV1> {
    const stored = await this.backend.getCurrent();
    if (stored) return stored;
    const initial = createEmptyContentSnapshot(this.deviceId, { now: this.now(), uuid: this.uuid });
    await this.backend.putCurrent(initial);
    return initial;
  }

  async replaceFromRemote(snapshot: ContentSnapshotV1): Promise<void> {
    const current = await this.backend.getCurrent();
    if (current) await this.backup(current);
    await this.backend.putCurrent(snapshot);
  }

  async commit(
    mutate: (current: ContentSnapshotV1) => ContentSnapshotV1 | void,
    pendingMirrorIds: string[] = [],
  ): Promise<ContentSnapshotV1> {
    const current = await this.open();
    await this.backup(current);
    const draft = clone(current);
    const changed = mutate(draft) ?? draft;
    const now = this.now();
    changed.appVersion = CONTENT_APP_VERSION;
    changed.revision = nextHybridClock(current.revision, this.deviceId, now.getTime());
    changed.revisionId = this.uuid();
    changed.modifiedAt = now.toISOString();
    await this.backend.putCurrent(changed);
    if (pendingMirrorIds.length) {
      await this.backend.putOutbox({
        id: changed.revisionId,
        createdAt: changed.modifiedAt,
        snapshot: clone(changed),
        pendingMirrorIds: [...new Set(pendingMirrorIds)],
        attempt: 0,
        nextAttemptAt: new Date(now.getTime() + OUTBOX_RETRY_DELAYS_MS[0]).toISOString(),
      });
    }
    return clone(changed);
  }

  async saveAsset(asset: StoredAudioAsset): Promise<void> {
    if (!(await this.backend.getAsset(asset.meta.id))) await this.backend.putAsset(asset);
  }

  /** Deletes only binary objects no longer referenced by current, backups, or pending uploads. */
  async pruneUnreferencedAssets(): Promise<string[]> {
    const snapshots = [
      await this.backend.getCurrent(),
      ...(await this.backend.listBackups()).map((backup) => backup.snapshot),
      ...(await this.backend.listOutbox()).map((item) => item.snapshot),
    ].filter((snapshot): snapshot is ContentSnapshotV1 => Boolean(snapshot));
    const referenced = new Set<string>();
    const collect = (override: { audioAssetIds?: string[]; primaryAudioAssetId?: string | null } | undefined) => {
      override?.audioAssetIds?.forEach((id) => referenced.add(id));
      if (override?.primaryAudioAssetId) referenced.add(override.primaryAudioAssetId);
    };
    for (const snapshot of snapshots) {
      Object.values(snapshot.globalOverrides).forEach(collect);
      for (const library of snapshot.libraries) {
        for (const word of library.words) {
          collect(word.legacyOverride);
          collect(word.override);
        }
      }
    }
    const deleted: string[] = [];
    for (const asset of await this.backend.listAssets()) {
      if (referenced.has(asset.meta.id)) continue;
      await this.backend.deleteAsset(asset.meta.id);
      deleted.push(asset.meta.id);
    }
    return deleted;
  }

  async markMirrorComplete(outboxId: string, mirrorId: string): Promise<void> {
    const item = (await this.backend.listOutbox()).find((candidate) => candidate.id === outboxId);
    if (!item) return;
    item.pendingMirrorIds = item.pendingMirrorIds.filter((id) => id !== mirrorId);
    if (!item.pendingMirrorIds.length) await this.backend.deleteOutbox(item.id);
    else await this.backend.putOutbox(item);
  }

  async markOutboxAttempt(outboxId: string): Promise<void> {
    const item = (await this.backend.listOutbox()).find((candidate) => candidate.id === outboxId);
    if (!item) return;
    item.attempt += 1;
    const delay = OUTBOX_RETRY_DELAYS_MS[Math.min(item.attempt, OUTBOX_RETRY_DELAYS_MS.length - 1)];
    item.nextAttemptAt = new Date(this.now().getTime() + delay).toISOString();
    await this.backend.putOutbox(item);
  }

  private async backup(snapshot: ContentSnapshotV1): Promise<void> {
    const createdAt = this.now().toISOString();
    await this.backend.putBackup({ id: `${createdAt}:${this.uuid()}`, createdAt, snapshot: clone(snapshot) });
    const backups = (await this.backend.listBackups()).sort((a, b) =>
      a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id));
    for (const expired of backups.slice(0, Math.max(0, backups.length - CONTENT_BACKUP_LIMIT))) {
      await this.backend.deleteBackup(expired.id);
    }
  }
}
