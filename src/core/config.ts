export const APP_VERSION = "8.4.0";
export const SCHEMA_VERSION = 4 as const;
export const ALGORITHM_VERSION = "weighted-random-v1" as const;
export const CONTENT_VERSION = "2026-08-06.2";
export const MIN_READER_VERSION = "8.0.0";

export const ALGORITHM_CONFIG = Object.freeze({
  recentLimit: 6,
  learnedRightThreshold: 2,
  clearAccuracyThreshold: 0.9,
  wrongBonusCap: 30,
  rightReliefFactor: 3,
  reverseWrongIncrement: 16,
  reverseWeightCap: 80,
  reverseRightMultiplier: 0.12,
  rareMeaningMaxWeight: 5,
});

export const REQUIRED_FEATURES = [
  "event-log-v1",
  "dual-mirror-v1",
  "algorithm-lock-v1",
  "spaced-review-v1",
] as const;

export const V4_STORAGE_KEY = "english-word-review-v4";
export const DEVICE_ID_KEY = "english-word-review-device-v1";
export const SYNC_CONFIG_KEY = "english-word-review-sync-config-v1";
export const MIGRATION_BACKUP_KEY = "english-word-review-pre-v4-backup";
