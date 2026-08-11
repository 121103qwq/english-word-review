import { describe, expect, it } from "vitest";
import {
  applyPlatformSettingsSnapshot,
  buildPlatformSettingsFileName,
  createDefaultPlatformSettingsState,
  createPlatformSettingsSnapshot,
  deriveDeviceCode,
  inspectPlatformSettingsFile,
  PlatformSettingsValidationError,
  updateLocalDeviceSettings,
  updateSyncedPlatformSettings,
  validatePlatformSettingsSnapshot,
} from "../src/settings/model";

const NOW = new Date("2026-08-08T01:35:00.000Z");

function readyWindowsState() {
  let state = createDefaultPlatformSettingsState("windows", "device-uuid", {
    now: NOW,
    uuid: () => "revision-one",
    appVersion: "8.2.0",
  });
  state = updateLocalDeviceSettings(state, (local) => {
    local.identity.deviceName = "卧室电脑";
    local.identity.location = "家里";
    local.systemVoiceId = "Microsoft Jenny";
    local.mimoVoice = "Dean";
    local.ttsCacheLimitBytes = 3 * 1024 * 1024 * 1024;
  }, { now: NOW, appVersion: "8.2.0" });
  return state;
}

describe("platform settings model", () => {
  it("creates platform-scoped defaults and a stable eight-character device code", () => {
    const first = createDefaultPlatformSettingsState("windows", "device-uuid", { now: NOW, uuid: () => "r1" });
    const second = createDefaultPlatformSettingsState("windows", "device-uuid", { now: NOW, uuid: () => "r2" });

    expect(first.platform).toBe("windows");
    expect(first.settings.theme).toBe("system");
    expect(first.settings.shortcutsEnabled).toBe(true);
    expect(first.settings.autoAdvanceDelayMs).toBe(650);
    expect(first.settings.feedback.soundEnabled).toBe(false);
    expect(first.deviceLocal.ttsCacheLimitBytes).toBe(512 * 1024 * 1024);
    expect(first.deviceLocal.identity.deviceCode).toMatch(/^[0-9A-F]{8}$/);
    expect(first.deviceLocal.identity.deviceCode).toBe(second.deviceLocal.identity.deviceCode);
    expect(deriveDeviceCode("device-uuid")).not.toBe(deriveDeviceCode("another-device"));
  });

  it("exports only synchronized fields and preserves all local-only values when applying", () => {
    const source = readyWindowsState();
    const snapshot = createPlatformSettingsSnapshot(source);
    const serialized = JSON.stringify(snapshot);

    expect(snapshot.sourceDevice.deviceName).toBe("卧室电脑");
    expect(serialized).not.toContain("Microsoft Jenny");
    expect(serialized).not.toContain("ttsCacheLimitBytes");
    expect(serialized).not.toContain("mimoVoice");
    expect(serialized).not.toContain("apiKey");

    let target = createDefaultPlatformSettingsState("windows", "other-device", { now: NOW, uuid: () => "target" });
    target = updateLocalDeviceSettings(target, (local) => {
      local.identity.deviceName = "办公室电脑";
      local.identity.location = "公司";
      local.systemVoiceId = "Local Voice";
    });
    snapshot.settings.theme = "dark";
    const applied = applyPlatformSettingsSnapshot(target, snapshot);

    expect(applied.settings.theme).toBe("dark");
    expect(applied.deviceLocal.identity.deviceName).toBe("办公室电脑");
    expect(applied.deviceLocal.identity.location).toBe("公司");
    expect(applied.deviceLocal.systemVoiceId).toBe("Local Voice");
  });

  it("rejects cross-platform application and invalid ranges", () => {
    const snapshot = createPlatformSettingsSnapshot(readyWindowsState());
    const android = createDefaultPlatformSettingsState("android", "phone", { now: NOW, uuid: () => "android" });

    expect(() => applyPlatformSettingsSnapshot(android, snapshot)).toThrowError(PlatformSettingsValidationError);
    const invalid = structuredClone(snapshot);
    invalid.settings.uiScale = 1.5;
    expect(() => validatePlatformSettingsSnapshot(invalid)).toThrow("界面缩放");
  });

  it("requires device name and location only when creating a transferable file", () => {
    const local = createDefaultPlatformSettingsState("html", "browser", { now: NOW, uuid: () => "local" });
    expect(local.deviceLocal.identity.deviceName).toBe("");
    expect(() => createPlatformSettingsSnapshot(local)).toThrow("设备名称和当前地点");
  });

  it("updates sync revision only for synchronized settings", () => {
    const original = readyWindowsState();
    const localEdit = updateLocalDeviceSettings(original, (local) => { local.systemVoiceId = "Another voice"; }, {
      now: new Date(NOW.getTime() + 1_000),
    });
    const syncEdit = updateSyncedPlatformSettings(localEdit, (settings) => { settings.wordScale = 1.25; }, {
      now: new Date(NOW.getTime() + 2_000),
      uuid: () => "sync-revision",
    });

    expect(localEdit.revisionId).toBe(original.revisionId);
    expect(syncEdit.revisionId).toBe("sync-revision");
    expect(syncEdit.settings.wordScale).toBe(1.25);
  });

  it("inspects future snapshots without applying them and builds safe filenames", () => {
    const snapshot = createPlatformSettingsSnapshot(readyWindowsState());
    const future = JSON.stringify({ ...snapshot, schemaVersion: 2 });
    const inspection = inspectPlatformSettingsFile(future);

    expect(inspection.supported).toBe(false);
    expect(inspection.schemaVersion).toBe(2);
    expect(inspection.sourceDevice?.deviceName).toBe("卧室电脑");
    expect(buildPlatformSettingsFileName(snapshot, new Date(2026, 7, 8, 1, 35))).toMatch(
      /^[0-9A-F]{8}_卧室电脑_家里_20260808-0135_[0-9A-F]{4}\.json$/,
    );
  });
});
