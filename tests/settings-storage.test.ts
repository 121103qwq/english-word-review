import { describe, expect, it } from "vitest";
import { PlatformSettingsStorage, platformSettingsStorageKey, type SettingsStorageLike } from "../src/settings/storage";

class MemoryStorage implements SettingsStorageLike {
  readonly values = new Map<string, string>();
  getItem(key: string): string | null { return this.values.get(key) ?? null; }
  setItem(key: string, value: string): void { this.values.set(key, value); }
  removeItem(key: string): void { this.values.delete(key); }
}

describe("platform settings local storage", () => {
  it("uses separate Windows, Android and HTML keys", () => {
    expect(platformSettingsStorageKey("windows")).not.toBe(platformSettingsStorageKey("android"));
    expect(platformSettingsStorageKey("android")).not.toBe(platformSettingsStorageKey("html"));
  });

  it("persists synchronized and local-only edits without cloud access", () => {
    const storage = new MemoryStorage();
    const repository = new PlatformSettingsStorage("windows", "device-a", storage, {
      now: () => new Date("2026-08-08T02:00:00.000Z"),
      uuid: () => "revision-a",
      appVersion: "8.2.0",
    });
    repository.updateDeviceLocal((local) => {
      local.identity.deviceName = "主电脑";
      local.identity.location = "家里";
      local.systemVoiceId = "voice-a";
    });
    repository.updateSynced((settings) => {
      settings.theme = "dark";
      settings.uiScale = 1.15;
    });

    const loaded = repository.load();
    expect(loaded?.settings.theme).toBe("dark");
    expect(loaded?.settings.uiScale).toBe(1.15);
    expect(loaded?.deviceLocal.systemVoiceId).toBe("voice-a");
    expect(storage.values.size).toBe(1);
  });

  it("applies same-platform files while preserving local identity and reset never touches it", () => {
    const storage = new MemoryStorage();
    const first = new PlatformSettingsStorage("windows", "first", storage, { uuid: () => "r1" });
    first.updateDeviceLocal((local) => {
      local.identity.deviceName = "电脑 A";
      local.identity.location = "家里";
    });
    first.updateSynced((settings) => { settings.theme = "light"; });
    const snapshot = first.createSnapshot();

    const otherStorage = new MemoryStorage();
    const second = new PlatformSettingsStorage("windows", "second", otherStorage, { uuid: () => "r2" });
    second.updateDeviceLocal((local) => {
      local.identity.deviceName = "电脑 B";
      local.identity.location = "学校";
      local.systemVoiceId = "voice-b";
    });
    const applied = second.applySnapshot(snapshot);
    expect(applied.settings.theme).toBe("light");
    expect(applied.deviceLocal.identity.deviceName).toBe("电脑 B");
    expect(applied.deviceLocal.systemVoiceId).toBe("voice-b");

    const reset = second.reset();
    expect(reset.settings.theme).toBe("system");
    expect(reset.deviceLocal.identity.deviceName).toBe("电脑 B");
    expect(reset.deviceLocal.systemVoiceId).toBe("voice-b");
  });

  it("does not overwrite malformed local data", () => {
    const storage = new MemoryStorage();
    const key = platformSettingsStorageKey("html");
    storage.setItem(key, "{broken");
    const repository = new PlatformSettingsStorage("html", "browser", storage);

    expect(() => repository.loadOrCreate()).toThrow("原数据未被覆盖");
    expect(storage.getItem(key)).toBe("{broken");
  });
});
