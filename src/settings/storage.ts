import {
  applyPlatformSettingsSnapshot,
  createDefaultPlatformSettingsState,
  createPlatformSettingsSnapshot,
  updateLocalDeviceSettings,
  updateSyncedPlatformSettings,
  validateLocalPlatformSettingsState,
} from "./model";
import type {
  LocalDeviceSettings,
  LocalPlatformSettingsStateV1,
  PlatformKind,
  PlatformSettingsSnapshotV1,
  SyncedPlatformSettings,
} from "./types";

export const PLATFORM_SETTINGS_STORAGE_KEY_PREFIX = "english-word-review-platform-settings-v1";

export interface SettingsStorageLike {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export interface PlatformSettingsStorageOptions {
  now?: () => Date;
  uuid?: () => string;
  appVersion?: string;
}

export function platformSettingsStorageKey(platform: PlatformKind): string {
  return `${PLATFORM_SETTINGS_STORAGE_KEY_PREFIX}:${platform}`;
}

/**
 * Local persistence only. This repository never accesses GitHub/WebDAV and is
 * intentionally separate from content persistence and learning-event storage.
 */
export class PlatformSettingsStorage {
  private readonly key: string;

  constructor(
    readonly platform: PlatformKind,
    private readonly deviceId: string,
    private readonly storage: SettingsStorageLike = globalThis.localStorage,
    private readonly options: PlatformSettingsStorageOptions = {},
  ) {
    if (!storage) throw new Error("当前环境不支持本地设置存储");
    this.key = platformSettingsStorageKey(platform);
  }

  load(): LocalPlatformSettingsStateV1 | undefined {
    const raw = this.storage.getItem(this.key);
    if (!raw) return undefined;
    let parsed: unknown;
    try {
      parsed = JSON.parse(raw);
    } catch {
      throw new Error("本机个性化设置已损坏，原数据未被覆盖");
    }
    const state = validateLocalPlatformSettingsState(parsed);
    if (state.platform !== this.platform) throw new Error(`本机设置平台不匹配：${state.platform}`);
    return state;
  }

  loadOrCreate(): LocalPlatformSettingsStateV1 {
    const existing = this.load();
    if (existing) return existing;
    const created = createDefaultPlatformSettingsState(this.platform, this.deviceId, {
      now: this.options.now?.(),
      uuid: this.options.uuid,
      appVersion: this.options.appVersion,
    });
    this.save(created);
    return created;
  }

  save(state: LocalPlatformSettingsStateV1): void {
    const checked = validateLocalPlatformSettingsState(state);
    if (checked.platform !== this.platform) throw new Error(`不能把 ${checked.platform} 设置保存到 ${this.platform} 档案`);
    this.storage.setItem(this.key, JSON.stringify(checked));
  }

  updateSynced(update: (draft: SyncedPlatformSettings) => void): LocalPlatformSettingsStateV1 {
    const next = updateSyncedPlatformSettings(this.loadOrCreate(), update, {
      now: this.options.now?.(),
      uuid: this.options.uuid,
      appVersion: this.options.appVersion,
    });
    this.save(next);
    return next;
  }

  updateDeviceLocal(update: (draft: LocalDeviceSettings) => void): LocalPlatformSettingsStateV1 {
    const next = updateLocalDeviceSettings(this.loadOrCreate(), update, {
      now: this.options.now?.(),
      appVersion: this.options.appVersion,
    });
    this.save(next);
    return next;
  }

  createSnapshot(): PlatformSettingsSnapshotV1 {
    return createPlatformSettingsSnapshot(this.loadOrCreate());
  }

  applySnapshot(snapshot: PlatformSettingsSnapshotV1): LocalPlatformSettingsStateV1 {
    const next = applyPlatformSettingsSnapshot(this.loadOrCreate(), snapshot);
    this.save(next);
    return next;
  }

  reset(): LocalPlatformSettingsStateV1 {
    const previous = this.loadOrCreate();
    const reset = createDefaultPlatformSettingsState(this.platform, this.deviceId, {
      now: this.options.now?.(),
      uuid: this.options.uuid,
      appVersion: this.options.appVersion,
    });
    // Reset personalization without changing device identity or concrete voice.
    reset.deviceLocal = structuredClone(previous.deviceLocal);
    this.save(reset);
    return reset;
  }

  clear(): void {
    this.storage.removeItem(this.key);
  }
}
