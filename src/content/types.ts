import type { HybridClock, LegacyWord } from "../core/types";

export type RootSource = "manual" | "legacy" | "engra" | "inferred";

export interface RootComponent {
  root: string;
  meaning: string;
  note?: string;
  source: RootSource;
}

export interface WordOverride {
  meaning?: string;
  roots?: RootComponent[];
  pronunciation?: string;
  audioAssetIds?: string[];
  primaryAudioAssetId?: string | null;
}

export interface CustomLibraryWord {
  word: string;
  source: "dictionary" | "user";
  /** Values migrated from v4/v3 remain below manual local/global overrides. */
  legacyOverride?: WordOverride;
  override?: WordOverride;
  legacyProgress?: LegacyWord;
}

export interface CustomLibrary {
  id: string;
  date: string;
  name?: string;
  words: CustomLibraryWord[];
  createdAt: string;
  modifiedAt: string;
}

export interface AudioAssetMeta {
  /** Content address; identical MP3 bytes share one asset. */
  id: string;
  sha256: string;
  mimeType: "audio/mpeg";
  byteLength: number;
  fileName: string;
  createdAt: string;
  durationMs?: number;
}

export interface ContentSnapshotV1 {
  schemaVersion: 1;
  appVersion: string;
  dictionaryVersion: string;
  revision: HybridClock;
  revisionId: string;
  modifiedAt: string;
  activeLibraryId: string | null;
  libraries: CustomLibrary[];
  globalOverrides: Record<string, WordOverride>;
  assets: Record<string, AudioAssetMeta>;
}

export interface ContentBackup {
  id: string;
  createdAt: string;
  snapshot: ContentSnapshotV1;
}

export interface ContentOutboxItem {
  id: string;
  createdAt: string;
  snapshot: ContentSnapshotV1;
  pendingMirrorIds: string[];
  attempt: number;
  nextAttemptAt: string;
}

export interface StoredAudioAsset {
  meta: AudioAssetMeta;
  bytes: Uint8Array;
}

export interface ResolvedWord {
  word: string;
  meaning?: string;
  roots?: RootComponent[];
  pronunciation?: string;
  audioAssetIds?: string[];
  primaryAudioAssetId?: string | null;
  source: CustomLibraryWord["source"];
}
