import type { HybridClock } from "../core/types";

export const REVIEW_ALGORITHM_VERSION = "spaced-review-v1" as const;

export type ReviewDirection = "en-to-zh" | "zh-to-en";
export type ReviewAnswerKind = "primary" | "retry";
export type ReviewStage = 0 | 1 | 2 | 3 | 4 | 5;

export interface ReviewDeviceStats {
  primaryRight: number;
  primaryWrong: number;
  retryRight: number;
  retryWrong: number;
}

/**
 * A globally merged schedule. `wordKey` is deliberately independent of a
 * library id: the ordinary learning scores remain library-scoped, while a
 * review card represents one spelling across every library.
 */
export interface ReviewCardState {
  cardId: string;
  wordKey: string;
  stage: ReviewStage;
  dueAt: number;
  nextDirection: ReviewDirection;
  lastReviewedAt?: number;
  revisionClock: HybridClock;
  revisionEventId: string;
  stats: Record<string, ReviewDeviceStats>;
}

export interface ReviewAnswerInput {
  sessionId: string;
  word: string;
  cardId?: string;
  direction: ReviewDirection;
  attempt: ReviewAnswerKind;
  correct: boolean;
  sourceLibraryIds: string[];
  /** Primarily for deterministic tests; production callers can omit it. */
  answeredAt?: number;
}

export function normalizeEnglish(word: string): string {
  return word.trim().replace(/\s+/g, " ").toLocaleLowerCase("en-US");
}

export function reviewCardId(word: string): string {
  return `review:${normalizeEnglish(word)}`;
}

export function emptyReviewDeviceStats(): ReviewDeviceStats {
  return { primaryRight: 0, primaryWrong: 0, retryRight: 0, retryWrong: 0 };
}
