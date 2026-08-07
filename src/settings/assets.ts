import type { SettingsAssetReference } from "./types";

export const SETTINGS_ATTACHMENT_AUTO_UPLOAD_LIMIT = 10 * 1024 * 1024;
export const MAX_SETTINGS_AUDIO_ASSET_BYTES = 2 * 1024 * 1024;

export interface StoredSettingsAsset {
  reference: SettingsAssetReference;
  bytes: Uint8Array;
}

export interface SettingsAssetBackend {
  get(sha256: string): Promise<StoredSettingsAsset | undefined>;
  put(asset: StoredSettingsAsset): Promise<void>;
  delete(sha256: string): Promise<void>;
  list(): Promise<StoredSettingsAsset[]>;
}

export interface SettingsAttachmentSelection {
  selected: StoredSettingsAsset[];
  omitted: StoredSettingsAsset[];
  totalBytes: number;
  requiresLargeAttachmentOptIn: boolean;
}

const clone = <T>(value: T): T => structuredClone(value);

function toHex(bytes: Uint8Array): string {
  return [...bytes].map((part) => part.toString(16).padStart(2, "0")).join("");
}

function matchesAudioSignature(bytes: Uint8Array, mimeType: SettingsAssetReference["mimeType"]): boolean {
  if (mimeType === "audio/wav") {
    return bytes.length >= 12 &&
      String.fromCharCode(...bytes.subarray(0, 4)) === "RIFF" &&
      String.fromCharCode(...bytes.subarray(8, 12)) === "WAVE";
  }
  if (mimeType === "audio/ogg") return bytes.length >= 4 && String.fromCharCode(...bytes.subarray(0, 4)) === "OggS";
  return bytes.length >= 3 && (
    String.fromCharCode(...bytes.subarray(0, 3)) === "ID3" ||
    (bytes[0] === 0xff && (bytes[1] & 0xe0) === 0xe0)
  );
}

export async function createSettingsAsset(
  bytes: Uint8Array,
  fileName: string,
  mimeType: SettingsAssetReference["mimeType"],
): Promise<StoredSettingsAsset> {
  if (!bytes.byteLength) throw new Error("设置附件不能为空");
  if (bytes.byteLength > MAX_SETTINGS_AUDIO_ASSET_BYTES) throw new Error("单个设置音频附件不能超过 2 MiB");
  if (!matchesAudioSignature(bytes, mimeType)) throw new Error("设置附件格式与内容不匹配");
  const digest = await globalThis.crypto.subtle.digest("SHA-256", bytes as BufferSource);
  const sha256 = toHex(new Uint8Array(digest));
  return {
    reference: { sha256, mimeType, byteLength: bytes.byteLength, fileName },
    bytes: new Uint8Array(bytes),
  };
}

/**
 * More than 10 MiB is never uploaded implicitly. The caller must show the
 * opt-in control and call again with includeLargeAttachments=true.
 */
export function selectSettingsAttachments(
  assets: StoredSettingsAsset[],
  includeLargeAttachments = false,
): SettingsAttachmentSelection {
  const unique = [...new Map(assets.map((asset) => [asset.reference.sha256, asset])).values()];
  const totalBytes = unique.reduce((sum, asset) => sum + asset.reference.byteLength, 0);
  const requiresLargeAttachmentOptIn = totalBytes > SETTINGS_ATTACHMENT_AUTO_UPLOAD_LIMIT;
  return {
    selected: requiresLargeAttachmentOptIn && !includeLargeAttachments ? [] : unique.map(clone),
    omitted: requiresLargeAttachmentOptIn && !includeLargeAttachments ? unique.map(clone) : [],
    totalBytes,
    requiresLargeAttachmentOptIn,
  };
}

export class MemorySettingsAssetBackend implements SettingsAssetBackend {
  private readonly assets = new Map<string, StoredSettingsAsset>();

  async get(sha256: string): Promise<StoredSettingsAsset | undefined> {
    const value = this.assets.get(sha256);
    return value && clone(value);
  }

  async put(asset: StoredSettingsAsset): Promise<void> {
    if (!this.assets.has(asset.reference.sha256)) this.assets.set(asset.reference.sha256, clone(asset));
  }

  async delete(sha256: string): Promise<void> {
    this.assets.delete(sha256);
  }

  async list(): Promise<StoredSettingsAsset[]> {
    return [...this.assets.values()].map(clone);
  }
}

function idbRequest<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

/** Dedicated settings attachment database; never shares content stores/outbox. */
export class IndexedDbSettingsAssetBackend implements SettingsAssetBackend {
  private readonly dbPromise: Promise<IDBDatabase>;

  constructor(name = "english-word-review-settings-assets-v1") {
    if (!globalThis.indexedDB) throw new Error("当前环境不支持 IndexedDB");
    this.dbPromise = new Promise((resolve, reject) => {
      const opening = indexedDB.open(name, 1);
      opening.onupgradeneeded = () => {
        if (!opening.result.objectStoreNames.contains("assets")) opening.result.createObjectStore("assets");
      };
      opening.onsuccess = () => resolve(opening.result);
      opening.onerror = () => reject(opening.error);
    });
  }

  private async store(mode: IDBTransactionMode = "readonly"): Promise<IDBObjectStore> {
    const db = await this.dbPromise;
    return db.transaction("assets", mode).objectStore("assets");
  }

  async get(sha256: string): Promise<StoredSettingsAsset | undefined> {
    return idbRequest((await this.store()).get(sha256)) as Promise<StoredSettingsAsset | undefined>;
  }

  async put(asset: StoredSettingsAsset): Promise<void> {
    if (await this.get(asset.reference.sha256)) return;
    await idbRequest((await this.store("readwrite")).put(asset, asset.reference.sha256));
  }

  async delete(sha256: string): Promise<void> {
    await idbRequest((await this.store("readwrite")).delete(sha256));
  }

  async list(): Promise<StoredSettingsAsset[]> {
    return idbRequest((await this.store()).getAll()) as Promise<StoredSettingsAsset[]>;
  }
}

export class SettingsAssetRepository {
  constructor(private readonly backend: SettingsAssetBackend) {}

  async save(asset: StoredSettingsAsset): Promise<void> {
    if (asset.reference.byteLength !== asset.bytes.byteLength) throw new Error("设置附件长度校验失败");
    const verified = await createSettingsAsset(asset.bytes, asset.reference.fileName, asset.reference.mimeType);
    if (verified.reference.sha256 !== asset.reference.sha256) throw new Error("设置附件哈希校验失败");
    await this.backend.put(verified);
  }

  get(sha256: string): Promise<StoredSettingsAsset | undefined> {
    return this.backend.get(sha256);
  }

  list(): Promise<StoredSettingsAsset[]> {
    return this.backend.list();
  }

  async prune(referencedHashes: Iterable<string>): Promise<string[]> {
    const referenced = new Set(referencedHashes);
    const removed: string[] = [];
    for (const asset of await this.backend.list()) {
      if (referenced.has(asset.reference.sha256)) continue;
      await this.backend.delete(asset.reference.sha256);
      removed.push(asset.reference.sha256);
    }
    return removed;
  }
}
