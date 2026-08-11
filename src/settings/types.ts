/**
 * Settings are deliberately independent from word-library content and learning
 * progress. This module must stay free of imports from content/events/review.
 */
export const PLATFORM_SETTINGS_SCHEMA_VERSION = 1 as const;

export type PlatformKind = "windows" | "android" | "html";
export type ThemePreference = "system" | "light" | "dark";
export type SpeechProvider = "system" | "mimo";
export type MimoVoice = "Mia" | "Chloe" | "Milo" | "Dean";
export type FeedbackSoundTheme = "silent" | "crisp" | "soft" | "minimal" | "custom";

export interface DeviceIdentity {
  /** Stable eight-character code derived from this installation's device UUID. */
  deviceCode: string;
  /** User-facing name, for example "卧室电脑". */
  deviceName: string;
  /** User-entered location label, for example "家里". Never inferred from Wi-Fi/GPS. */
  location: string;
}

export interface SettingsAssetReference {
  sha256: string;
  mimeType: "audio/mpeg" | "audio/wav" | "audio/ogg";
  byteLength: number;
  fileName: string;
}

export type ShortcutContext = "global" | "forward" | "choice" | "intensive" | "root" | "review" | "mode";

export type ShortcutAction =
  | "next-word"
  | "speak-word"
  | "undo-answer"
  | "reveal-answer"
  | "uncertain"
  | "answer-known"
  | "answer-unknown"
  | "choice-1"
  | "choice-2"
  | "choice-3"
  | "choice-4"
  | "intensive-correct"
  | "intensive-wrong"
  | "intensive-submit"
  | "root-choice-1"
  | "root-choice-2"
  | "root-choice-3"
  | "root-choice-4"
  | "root-submit"
  | "review-submit"
  | "review-known"
  | "review-unknown"
  | "mode-forward"
  | "mode-reverse"
  | "mode-rare"
  | "mode-intensive"
  | "mode-root"
  | "mode-review"
  | "toggle-wrong-only";

/** A binding follows the physical key position (`KeyboardEvent.code`). */
export interface ShortcutBinding {
  code: string;
  ctrl?: boolean;
  shift?: boolean;
}

export type ShortcutSlots = [ShortcutBinding | null, ShortcutBinding | null];
export type ShortcutMap = Record<ShortcutAction, ShortcutSlots>;

export interface FeedbackSettings {
  soundEnabled: boolean;
  volume: number;
  vibrationEnabled: boolean;
  theme: FeedbackSoundTheme;
  customCorrectSound?: SettingsAssetReference;
  customWrongSound?: SettingsAssetReference;
  /** Assets above 10 MiB are only uploaded after an explicit opt-in. */
  pushLargeAttachments: boolean;
}

/**
 * Fields that may be copied between devices of the same platform.
 * Secrets, cached audio, concrete system voices and device identity never live here.
 */
export interface SyncedPlatformSettings {
  theme: ThemePreference;
  uiScale: number;
  wordScale: number;
  reducedMotion: boolean;
  focusMode: boolean;
  confirmRestart: boolean;
  autoSpeak: boolean;
  speechProvider: SpeechProvider;
  speechRate: number;
  feedback: FeedbackSettings;
  shortcutsEnabled: boolean;
  shortcutBindings: ShortcutMap;
  autoAdvanceDelayMs: number;
}

/** Values which must remain on one physical installation. */
export interface LocalDeviceSettings {
  identity: DeviceIdentity;
  systemVoiceId?: string;
  mimoVoice: MimoVoice;
  /** User-selectable 200 MiB, 512 MiB, 1 GiB or 3 GiB limit. */
  ttsCacheLimitBytes: number;
}

/**
 * Immutable file exchanged manually through GitHub/WebDAV/local import-export.
 * `sourceDevice` is display metadata only and is never applied as local identity.
 */
export interface PlatformSettingsSnapshotV1 {
  schemaVersion: typeof PLATFORM_SETTINGS_SCHEMA_VERSION;
  appVersion: string;
  platform: PlatformKind;
  revisionId: string;
  modifiedAt: string;
  sourceDevice: DeviceIdentity;
  settings: SyncedPlatformSettings;
}

/** Complete state persisted only on the current installation. */
export interface LocalPlatformSettingsStateV1 {
  schemaVersion: typeof PLATFORM_SETTINGS_SCHEMA_VERSION;
  appVersion: string;
  platform: PlatformKind;
  revisionId: string;
  modifiedAt: string;
  settings: SyncedPlatformSettings;
  deviceLocal: LocalDeviceSettings;
}

export type LibraryDraftInputMode = "paste" | "table";

export interface LibraryDraftRow {
  word: string;
  meaning: string;
  rootText: string;
}

/** Local-only recoverable draft. It is intentionally absent from settings snapshots. */
export interface LibraryCreationDraftV1 {
  schemaVersion: 1;
  platform: PlatformKind;
  date: string;
  name: string;
  targetLibraryId: string;
  inputMode: LibraryDraftInputMode;
  pasteInput: string;
  tableWords: string[];
  checkedRows: LibraryDraftRow[];
  modifiedAt: string;
}
