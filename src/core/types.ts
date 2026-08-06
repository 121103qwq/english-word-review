import type { ALGORITHM_VERSION } from "./config";
import type {
  ReviewAnswerKind,
  ReviewCardState,
  ReviewDeviceStats,
  ReviewDirection,
} from "../review/types";

export interface LegacyWord {
  en: string;
  zh: string;
  right?: number;
  wrong?: number;
  mastery?: number;
  reverseRight?: number;
  reverseWrong?: number;
  reverseMastery?: number;
  reverseReviewWeight?: number;
  rareRight?: number;
  rareWrong?: number;
  rareMastery?: number;
  spellRight?: number;
  spellWrong?: number;
  meaningRight?: number;
  meaningWrong?: number;
  [key: string]: unknown;
}

export interface LegacyLibrary {
  id: string;
  date: string;
  words: LegacyWord[];
}

export interface LegacyStore {
  current: LegacyLibrary;
  archives: LegacyLibrary[];
}

export interface IntensiveStore {
  reviewedLibraryId: string;
  words: LegacyWord[];
}

export interface RootStudyItem {
  id: string;
  root: string;
  meaning: string;
  words: string[];
  choiceRight: number;
  choiceWrong: number;
  writeRight: number;
  writeWrong: number;
}

export interface RootStudyStore {
  items: RootStudyItem[];
}

export interface LegacySettings {
  meaningMatchMode: "exact" | "contains";
  rootVisible: boolean;
}

export interface LegacyBundle {
  store: LegacyStore;
  intensiveStore: IntensiveStore;
  rootStudyStore: RootStudyStore;
  settings: LegacySettings;
}

export type LearningArea = "library" | "intensive" | "root";
export type LearningMode =
  | "forward"
  | "reverse"
  | "rare"
  | "spell"
  | "meaning"
  | "root-choice"
  | "root-write";

export interface HybridClock {
  wallTime: number;
  logical: number;
  deviceId: string;
}

interface BaseEvent {
  id: string;
  deviceId: string;
  seq: number;
  clock: HybridClock;
  algorithmVersion: typeof ALGORITHM_VERSION | "spaced-review-v1";
  scope: string;
  generationId: string;
}

export interface AnswerEvent extends BaseEvent {
  type: "answer";
  payload: {
    area: LearningArea;
    libraryId?: string;
    itemId: string;
    mode: LearningMode;
    correct: boolean;
    answeredAt?: string;
    reviewIntervalMs?: number;
  };
}

export interface UndoEvent extends BaseEvent {
  type: "undo";
  targetEventId: string;
}

export interface ResetEvent extends BaseEvent {
  type: "reset";
  newGenerationId: string;
}

export interface SettingEvent extends BaseEvent {
  type: "setting";
  key: keyof LegacySettings;
  value: LegacySettings[keyof LegacySettings];
}

export interface IntensiveSelectionEvent extends BaseEvent {
  type: "intensive-selection";
  words: Array<Pick<LegacyWord, "en" | "zh">>;
  reviewedLibraryId: string;
}

export interface ReviewAnswerEvent extends BaseEvent {
  type: "review-answer";
  algorithmVersion: "spaced-review-v1";
  payload: {
    sessionId: string;
    cardId: string;
    wordKey: string;
    direction: ReviewDirection;
    attempt: ReviewAnswerKind;
    correct: boolean;
    sourceLibraryIds: string[];
    answeredAt: string;
    /** The originating device's monotonically increasing counters. */
    deviceStats: ReviewDeviceStats;
    /** Present only for a first answer; retries must never schedule. */
    scheduleAfter?: ReviewCardState;
  };
}

export type LearningEvent =
  | AnswerEvent
  | UndoEvent
  | ResetEvent
  | SettingEvent
  | IntensiveSelectionEvent
  | ReviewAnswerEvent;

export interface Checkpoint {
  id: string;
  createdAt: string;
  vector: Record<string, number>;
  data: LegacyBundle;
  lineage?: string[];
  lastReviewedAt?: Record<string, number>;
  /** Optional so existing v4 snapshots migrate without a schema bump. */
  reviewCards?: Record<string, ReviewCardState>;
}

export interface V4Snapshot {
  schemaVersion: 4;
  appVersion: string;
  contentVersion: string;
  algorithmVersion: typeof ALGORITHM_VERSION;
  minReaderVersion: string;
  requiredFeatures: string[];
  checkpoint: Checkpoint;
  events: LearningEvent[];
  generations: Record<string, string>;
  updatedAt: string;
}

export interface LegacyRuntimeApi {
  getBundle(): LegacyBundle;
  applyBundle(bundle: LegacyBundle): void;
  getCurrentWord(): string;
}

export interface V8Bridge {
  answer(payload: AnswerEvent["payload"]): string;
  undo(targetEventId?: string): void;
  reset(scope: string): void;
  setting<K extends keyof LegacySettings>(key: K, value: LegacySettings[K]): void;
  intensiveSelection(words: Array<Pick<LegacyWord, "en" | "zh">>, reviewedLibraryId: string): void;
  replaceLibraries(libraries: LegacyLibrary[], activeLibraryId: string): void;
}

declare global {
  interface Window {
    __englishReviewLegacy?: LegacyRuntimeApi;
    __v8Bridge?: V8Bridge;
  }
}
