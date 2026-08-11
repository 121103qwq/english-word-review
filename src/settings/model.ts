import { APP_VERSION } from "../core/config";
import {
  createDefaultShortcutMap,
  SHORTCUT_ACTIONS,
  validateShortcutMap,
  normalizeShortcutBinding,
} from "./shortcuts";
import {
  PLATFORM_SETTINGS_SCHEMA_VERSION,
  type DeviceIdentity,
  type FeedbackSettings,
  type LocalDeviceSettings,
  type LocalPlatformSettingsStateV1,
  type MimoVoice,
  type PlatformKind,
  type PlatformSettingsSnapshotV1,
  type SettingsAssetReference,
  type ShortcutBinding,
  type ShortcutMap,
  type SyncedPlatformSettings,
} from "./types";

export const TTS_CACHE_LIMIT_OPTIONS = [
  200 * 1024 * 1024,
  512 * 1024 * 1024,
  1024 * 1024 * 1024,
  3 * 1024 * 1024 * 1024,
] as const;
export const DEFAULT_TTS_CACHE_LIMIT_BYTES = TTS_CACHE_LIMIT_OPTIONS[1];
export const MAX_CUSTOM_FEEDBACK_SOUND_BYTES = 2 * 1024 * 1024;
export const LARGE_SETTINGS_ASSET_THRESHOLD_BYTES = 10 * 1024 * 1024;

const PLATFORM_KINDS = ["windows", "android", "html"] as const;
const THEMES = ["system", "light", "dark"] as const;
const SPEECH_PROVIDERS = ["system", "mimo"] as const;
const MIMO_VOICES = ["Mia", "Chloe", "Milo", "Dean"] as const;
const FEEDBACK_THEMES = ["silent", "crisp", "soft", "minimal", "custom"] as const;

export interface SettingsModelOptions {
  now?: Date;
  uuid?: () => string;
  appVersion?: string;
}

const fallbackUuid = (): string =>
  globalThis.crypto?.randomUUID?.() ?? `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;

const clone = <T>(value: T): T => structuredClone(value);

export class PlatformSettingsValidationError extends Error {
  constructor(
    message: string,
    readonly code: "invalid" | "unsupported-schema" | "platform-mismatch" = "invalid",
  ) {
    super(message);
    this.name = "PlatformSettingsValidationError";
  }
}

export function isPlatformKind(value: unknown): value is PlatformKind {
  return typeof value === "string" && (PLATFORM_KINDS as readonly string[]).includes(value);
}

/** Stable, non-secret installation label derived from the existing device UUID. */
export function deriveDeviceCode(deviceId: string): string {
  let hash = 0x811c9dc5;
  for (let index = 0; index < deviceId.length; index += 1) {
    hash ^= deviceId.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193);
  }
  return (hash >>> 0).toString(16).padStart(8, "0").toUpperCase();
}

export function createDefaultSyncedSettings(): SyncedPlatformSettings {
  return {
    theme: "system",
    uiScale: 1,
    wordScale: 1,
    reducedMotion: false,
    focusMode: false,
    confirmRestart: true,
    autoSpeak: false,
    speechProvider: "system",
    speechRate: 1,
    feedback: {
      soundEnabled: false,
      volume: 0.6,
      vibrationEnabled: false,
      theme: "silent",
      pushLargeAttachments: false,
    },
    shortcutsEnabled: true,
    shortcutBindings: createDefaultShortcutMap(),
    // Preserve the legacy forward-mode delay as the initial value.
    autoAdvanceDelayMs: 650,
  };
}

export function createDefaultDeviceSettings(deviceId: string): LocalDeviceSettings {
  return {
    identity: { deviceCode: deriveDeviceCode(deviceId), deviceName: "", location: "" },
    mimoVoice: "Mia",
    ttsCacheLimitBytes: DEFAULT_TTS_CACHE_LIMIT_BYTES,
  };
}

export function createDefaultPlatformSettingsState(
  platform: PlatformKind,
  deviceId: string,
  options: SettingsModelOptions = {},
): LocalPlatformSettingsStateV1 {
  const now = options.now ?? new Date();
  return {
    schemaVersion: PLATFORM_SETTINGS_SCHEMA_VERSION,
    appVersion: options.appVersion ?? APP_VERSION,
    platform,
    revisionId: (options.uuid ?? fallbackUuid)(),
    modifiedAt: now.toISOString(),
    settings: createDefaultSyncedSettings(),
    deviceLocal: createDefaultDeviceSettings(deviceId),
  };
}

export function updateSyncedPlatformSettings(
  state: LocalPlatformSettingsStateV1,
  update: (draft: SyncedPlatformSettings) => void,
  options: SettingsModelOptions = {},
): LocalPlatformSettingsStateV1 {
  const next = clone(state);
  update(next.settings);
  const validated = validateSyncedSettings(next.settings);
  const now = options.now ?? new Date();
  next.settings = validated;
  next.appVersion = options.appVersion ?? APP_VERSION;
  next.revisionId = (options.uuid ?? fallbackUuid)();
  next.modifiedAt = now.toISOString();
  return next;
}

export function updateLocalDeviceSettings(
  state: LocalPlatformSettingsStateV1,
  update: (draft: LocalDeviceSettings) => void,
  options: Omit<SettingsModelOptions, "uuid"> = {},
): LocalPlatformSettingsStateV1 {
  const next = clone(state);
  update(next.deviceLocal);
  next.deviceLocal = validateLocalDeviceSettings(next.deviceLocal, true);
  next.appVersion = options.appVersion ?? APP_VERSION;
  next.modifiedAt = (options.now ?? new Date()).toISOString();
  // A local-only edit does not create a new sync revision.
  return next;
}

export function createPlatformSettingsSnapshot(
  state: LocalPlatformSettingsStateV1,
): PlatformSettingsSnapshotV1 {
  const checked = validateLocalPlatformSettingsState(state);
  const identity = checked.deviceLocal.identity;
  if (!identity.deviceName || !identity.location) {
    throw new PlatformSettingsValidationError("上传设置前必须填写设备名称和当前地点");
  }
  return {
    schemaVersion: PLATFORM_SETTINGS_SCHEMA_VERSION,
    appVersion: checked.appVersion,
    platform: checked.platform,
    revisionId: checked.revisionId,
    modifiedAt: checked.modifiedAt,
    sourceDevice: clone(identity),
    settings: clone(checked.settings),
  };
}

/** Applies only synchronized fields and preserves this installation's identity, voice and cache limit. */
export function applyPlatformSettingsSnapshot(
  state: LocalPlatformSettingsStateV1,
  snapshot: PlatformSettingsSnapshotV1,
): LocalPlatformSettingsStateV1 {
  const local = validateLocalPlatformSettingsState(state);
  const remote = validatePlatformSettingsSnapshot(snapshot);
  if (local.platform !== remote.platform) {
    throw new PlatformSettingsValidationError(
      `不能将 ${remote.platform} 设置应用到 ${local.platform}`,
      "platform-mismatch",
    );
  }
  return {
    ...local,
    appVersion: APP_VERSION,
    revisionId: remote.revisionId,
    modifiedAt: remote.modifiedAt,
    settings: clone(remote.settings),
    deviceLocal: clone(local.deviceLocal),
  };
}

function record(value: unknown, label: string): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new PlatformSettingsValidationError(`${label} 格式无效`);
  }
  return value as Record<string, unknown>;
}

function textValue(value: unknown, label: string, options: { allowEmpty?: boolean; maxLength?: number } = {}): string {
  if (typeof value !== "string") throw new PlatformSettingsValidationError(`${label} 必须是文本`);
  const normalized = value.trim();
  if (!options.allowEmpty && !normalized) throw new PlatformSettingsValidationError(`${label}不能为空`);
  if (normalized.length > (options.maxLength ?? 500)) throw new PlatformSettingsValidationError(`${label}过长`);
  return normalized;
}

function booleanValue(value: unknown, label: string): boolean {
  if (typeof value !== "boolean") throw new PlatformSettingsValidationError(`${label} 必须是布尔值`);
  return value;
}

function numberValue(value: unknown, label: string, min: number, max: number): number {
  if (typeof value !== "number" || !Number.isFinite(value) || value < min || value > max) {
    throw new PlatformSettingsValidationError(`${label} 必须在 ${min} 到 ${max} 之间`);
  }
  return value;
}

function enumValue<T extends string>(value: unknown, values: readonly T[], label: string): T {
  if (typeof value !== "string" || !values.includes(value as T)) {
    throw new PlatformSettingsValidationError(`${label} 不受支持`);
  }
  return value as T;
}

function isoDateValue(value: unknown, label: string): string {
  const text = textValue(value, label);
  if (Number.isNaN(Date.parse(text))) throw new PlatformSettingsValidationError(`${label} 不是有效时间`);
  return text;
}

function validateIdentity(value: unknown, allowEmpty: boolean): DeviceIdentity {
  const source = record(value, "设备信息");
  const deviceCode = textValue(source.deviceCode, "设备编码").toUpperCase();
  if (!/^[0-9A-F]{8}$/.test(deviceCode)) throw new PlatformSettingsValidationError("设备编码必须是 8 位十六进制字符");
  return {
    deviceCode,
    deviceName: textValue(source.deviceName, "设备名称", { allowEmpty, maxLength: 80 }),
    location: textValue(source.location, "当前地点", { allowEmpty, maxLength: 80 }),
  };
}

function validateAssetReference(value: unknown, label: string): SettingsAssetReference {
  const source = record(value, label);
  const sha256 = textValue(source.sha256, `${label} SHA-256`).toLowerCase();
  if (!/^[0-9a-f]{64}$/.test(sha256)) throw new PlatformSettingsValidationError(`${label} SHA-256 无效`);
  const mimeType = enumValue(source.mimeType, ["audio/mpeg", "audio/wav", "audio/ogg"] as const, `${label}格式`);
  const byteLength = numberValue(source.byteLength, `${label}大小`, 1, MAX_CUSTOM_FEEDBACK_SOUND_BYTES);
  return { sha256, mimeType, byteLength, fileName: textValue(source.fileName, `${label}文件名`, { maxLength: 180 }) };
}

function validateFeedbackSettings(value: unknown): FeedbackSettings {
  const source = record(value, "答题反馈设置");
  return {
    soundEnabled: booleanValue(source.soundEnabled, "答题音效开关"),
    volume: numberValue(source.volume, "音效音量", 0, 1),
    vibrationEnabled: booleanValue(source.vibrationEnabled, "振动开关"),
    theme: enumValue(source.theme, FEEDBACK_THEMES, "音效主题"),
    ...(source.customCorrectSound === undefined ? {} : { customCorrectSound: validateAssetReference(source.customCorrectSound, "答对音效") }),
    ...(source.customWrongSound === undefined ? {} : { customWrongSound: validateAssetReference(source.customWrongSound, "答错音效") }),
    pushLargeAttachments: booleanValue(source.pushLargeAttachments, "大附件推送开关"),
  };
}

function validateBinding(value: unknown, label: string): ShortcutBinding | null {
  if (value === null) return null;
  const source = record(value, label);
  if (typeof source.code !== "string") throw new PlatformSettingsValidationError(`${label} 缺少物理按键代码`);
  if (source.ctrl !== undefined && typeof source.ctrl !== "boolean") throw new PlatformSettingsValidationError(`${label} ctrl 无效`);
  if (source.shift !== undefined && typeof source.shift !== "boolean") throw new PlatformSettingsValidationError(`${label} shift 无效`);
  return normalizeShortcutBinding({
    code: source.code,
    ...(source.ctrl ? { ctrl: true } : {}),
    ...(source.shift ? { shift: true } : {}),
  });
}

function validateShortcutBindings(value: unknown): ShortcutMap {
  const source = record(value, "快捷键设置");
  const defaults = createDefaultShortcutMap();
  const result = {} as ShortcutMap;
  for (const action of SHORTCUT_ACTIONS) {
    const raw = source[action];
    if (raw === undefined) {
      result[action] = clone(defaults[action]);
      continue;
    }
    if (!Array.isArray(raw) || raw.length !== 2) {
      throw new PlatformSettingsValidationError(`${action} 必须有两个快捷键槽位`);
    }
    result[action] = [validateBinding(raw[0], `${action} 主按键`), validateBinding(raw[1], `${action} 备用按键`)];
  }
  const validation = validateShortcutMap(result);
  if (!validation.valid) throw new PlatformSettingsValidationError(validation.reason ?? "快捷键设置无效");
  return result;
}

export function validateSyncedSettings(value: unknown): SyncedPlatformSettings {
  const source = record(value, "同步设置");
  return {
    theme: enumValue(source.theme, THEMES, "主题"),
    uiScale: numberValue(source.uiScale, "界面缩放", 0.8, 1.4),
    wordScale: numberValue(source.wordScale, "题目字号", 0.8, 1.6),
    reducedMotion: booleanValue(source.reducedMotion, "减少动画"),
    focusMode: booleanValue(source.focusMode, "专注模式"),
    confirmRestart: booleanValue(source.confirmRestart, "重新开始确认"),
    autoSpeak: booleanValue(source.autoSpeak, "自动朗读"),
    speechProvider: enumValue(source.speechProvider, SPEECH_PROVIDERS, "朗读提供方"),
    speechRate: numberValue(source.speechRate, "朗读语速", 0.5, 2),
    feedback: validateFeedbackSettings(source.feedback),
    shortcutsEnabled: booleanValue(source.shortcutsEnabled, "快捷键总开关"),
    shortcutBindings: validateShortcutBindings(source.shortcutBindings),
    autoAdvanceDelayMs: numberValue(source.autoAdvanceDelayMs, "自动进入下一题延迟", 500, 5_000),
  };
}

function validateLocalDeviceSettings(value: unknown, allowEmptyIdentity: boolean): LocalDeviceSettings {
  const source = record(value, "本机设置");
  const cacheLimit = numberValue(source.ttsCacheLimitBytes, "MiMo 缓存上限", TTS_CACHE_LIMIT_OPTIONS[0], TTS_CACHE_LIMIT_OPTIONS.at(-1)!);
  if (!(TTS_CACHE_LIMIT_OPTIONS as readonly number[]).includes(cacheLimit)) {
    throw new PlatformSettingsValidationError("MiMo 缓存上限必须使用预设档位");
  }
  return {
    identity: validateIdentity(source.identity, allowEmptyIdentity),
    ...(source.systemVoiceId === undefined
      ? {}
      : { systemVoiceId: textValue(source.systemVoiceId, "系统语音", { allowEmpty: true, maxLength: 300 }) }),
    mimoVoice: enumValue<MimoVoice>(source.mimoVoice, MIMO_VOICES, "MiMo 音色"),
    ttsCacheLimitBytes: cacheLimit,
  };
}

function validateBase(value: unknown): {
  source: Record<string, unknown>;
  platform: PlatformKind;
  appVersion: string;
  revisionId: string;
  modifiedAt: string;
} {
  const source = record(value, "设置文件");
  if (source.schemaVersion !== PLATFORM_SETTINGS_SCHEMA_VERSION) {
    throw new PlatformSettingsValidationError(
      `不支持设置格式 v${String(source.schemaVersion ?? "未知")}`,
      "unsupported-schema",
    );
  }
  if (!isPlatformKind(source.platform)) throw new PlatformSettingsValidationError("设置平台无效");
  return {
    source,
    platform: source.platform,
    appVersion: textValue(source.appVersion, "应用版本", { maxLength: 40 }),
    revisionId: textValue(source.revisionId, "设置版本 ID", { maxLength: 200 }),
    modifiedAt: isoDateValue(source.modifiedAt, "修改时间"),
  };
}

export function validatePlatformSettingsSnapshot(value: unknown): PlatformSettingsSnapshotV1 {
  const base = validateBase(value);
  return {
    schemaVersion: PLATFORM_SETTINGS_SCHEMA_VERSION,
    appVersion: base.appVersion,
    platform: base.platform,
    revisionId: base.revisionId,
    modifiedAt: base.modifiedAt,
    sourceDevice: validateIdentity(base.source.sourceDevice, false),
    settings: validateSyncedSettings(base.source.settings),
  };
}

export function validateLocalPlatformSettingsState(value: unknown): LocalPlatformSettingsStateV1 {
  const base = validateBase(value);
  return {
    schemaVersion: PLATFORM_SETTINGS_SCHEMA_VERSION,
    appVersion: base.appVersion,
    platform: base.platform,
    revisionId: base.revisionId,
    modifiedAt: base.modifiedAt,
    settings: validateSyncedSettings(base.source.settings),
    deviceLocal: validateLocalDeviceSettings(base.source.deviceLocal, true),
  };
}

export function serializePlatformSettingsSnapshot(snapshot: PlatformSettingsSnapshotV1): string {
  return JSON.stringify(validatePlatformSettingsSnapshot(snapshot), null, 2);
}

export function deserializePlatformSettingsSnapshot(text: string): PlatformSettingsSnapshotV1 {
  try {
    return validatePlatformSettingsSnapshot(JSON.parse(text));
  } catch (error) {
    if (error instanceof PlatformSettingsValidationError) throw error;
    throw new PlatformSettingsValidationError("设置文件不是有效的 JSON");
  }
}

export interface SettingsFileInspection {
  supported: boolean;
  schemaVersion?: number;
  appVersion?: string;
  platform?: PlatformKind;
  revisionId?: string;
  modifiedAt?: string;
  sourceDevice?: Partial<DeviceIdentity>;
  error?: string;
}

/** Reads safe metadata even when a future schema cannot be applied. */
export function inspectPlatformSettingsFile(text: string): SettingsFileInspection {
  try {
    const value = JSON.parse(text) as unknown;
    const source = record(value, "设置文件");
    const inspection: SettingsFileInspection = {
      supported: source.schemaVersion === PLATFORM_SETTINGS_SCHEMA_VERSION,
      ...(typeof source.schemaVersion === "number" ? { schemaVersion: source.schemaVersion } : {}),
      ...(typeof source.appVersion === "string" ? { appVersion: source.appVersion } : {}),
      ...(isPlatformKind(source.platform) ? { platform: source.platform } : {}),
      ...(typeof source.revisionId === "string" ? { revisionId: source.revisionId } : {}),
      ...(typeof source.modifiedAt === "string" ? { modifiedAt: source.modifiedAt } : {}),
    };
    if (source.sourceDevice && typeof source.sourceDevice === "object" && !Array.isArray(source.sourceDevice)) {
      const device = source.sourceDevice as Record<string, unknown>;
      inspection.sourceDevice = {
        ...(typeof device.deviceCode === "string" ? { deviceCode: device.deviceCode } : {}),
        ...(typeof device.deviceName === "string" ? { deviceName: device.deviceName } : {}),
        ...(typeof device.location === "string" ? { location: device.location } : {}),
      };
    }
    if (inspection.supported) validatePlatformSettingsSnapshot(value);
    else inspection.error = `当前版本不能应用设置格式 v${String(source.schemaVersion ?? "未知")}`;
    return inspection;
  } catch (error) {
    return { supported: false, error: error instanceof Error ? error.message : "设置文件无效" };
  }
}

function safeFileSegment(value: string, fallback: string): string {
  const result = value.trim().replace(/[<>:"/\\|?*\u0000-\u001f]+/g, "-").replace(/\s+/g, "-").replace(/[. -]+$/g, "");
  return (result || fallback).slice(0, 80);
}

function localMinute(date: Date): string {
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}-${pad(date.getHours())}${pad(date.getMinutes())}`;
}

function shortRevisionCode(revisionId: string): string {
  return deriveDeviceCode(revisionId).slice(-4);
}

export function buildPlatformSettingsFileName(snapshot: PlatformSettingsSnapshotV1, createdAt = new Date()): string {
  const checked = validatePlatformSettingsSnapshot(snapshot);
  const device = checked.sourceDevice;
  return [
    device.deviceCode,
    safeFileSegment(device.deviceName, "device"),
    safeFileSegment(device.location, "location"),
    localMinute(createdAt),
    shortRevisionCode(checked.revisionId),
  ].join("_") + ".json";
}

export function totalCustomFeedbackAssetBytes(settings: SyncedPlatformSettings): number {
  return (settings.feedback.customCorrectSound?.byteLength ?? 0) + (settings.feedback.customWrongSound?.byteLength ?? 0);
}
