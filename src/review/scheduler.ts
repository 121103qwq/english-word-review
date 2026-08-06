import type { HybridClock } from "../core/types";
import {
  emptyReviewDeviceStats,
  normalizeEnglish,
  reviewCardId,
  type ReviewCardState,
  type ReviewDeviceStats,
  type ReviewDirection,
  type ReviewStage,
} from "./types";

export const REVIEW_INTERVALS_MS = Object.freeze([
  1, 3, 7, 14, 30, 60,
].map((days) => days * 24 * 60 * 60 * 1000)) as readonly number[];

export function nextReviewDirection(direction: ReviewDirection): ReviewDirection {
  return direction === "en-to-zh" ? "zh-to-en" : "en-to-zh";
}

export function reviewDue(state: ReviewCardState | undefined, now: number): boolean {
  return Boolean(state && state.dueAt <= now);
}

export function reviewIntervalForStage(stage: ReviewStage): number {
  return REVIEW_INTERVALS_MS[stage];
}

function boundedStage(value: number): ReviewStage {
  return Math.max(0, Math.min(5, value)) as ReviewStage;
}

/**
 * Apply a first answer to the fixed, explainable v1 schedule. Retry answers
 * intentionally never call this function.
 */
export function advanceReviewCard(args: {
  previous?: ReviewCardState;
  word: string;
  direction: ReviewDirection;
  correct: boolean;
  at: number;
  clock: HybridClock;
  eventId: string;
  stats?: Record<string, ReviewDeviceStats>;
}): ReviewCardState {
  const wordKey = normalizeEnglish(args.word);
  const previous = args.previous;
  if (!previous) {
    return {
      cardId: reviewCardId(wordKey),
      wordKey,
      stage: 0,
      dueAt: args.at + reviewIntervalForStage(0),
      nextDirection: args.correct ? nextReviewDirection(args.direction) : args.direction,
      lastReviewedAt: args.at,
      revisionClock: args.clock,
      revisionEventId: args.eventId,
      stats: structuredClone(args.stats ?? {}),
    };
  }

  if (!args.correct) {
    return {
      ...structuredClone(previous),
      stage: 0,
      dueAt: args.at + reviewIntervalForStage(0),
      // An error deliberately keeps the direction, even if it was early.
      nextDirection: args.direction,
      lastReviewedAt: args.at,
      revisionClock: args.clock,
      revisionEventId: args.eventId,
      stats: structuredClone(args.stats ?? previous.stats),
    };
  }

  if (!reviewDue(previous, args.at)) {
    return {
      ...structuredClone(previous),
      nextDirection: nextReviewDirection(args.direction),
      lastReviewedAt: args.at,
      revisionClock: args.clock,
      revisionEventId: args.eventId,
      stats: structuredClone(args.stats ?? previous.stats),
    };
  }

  const stage = boundedStage(previous.stage + 1);
  return {
    ...structuredClone(previous),
    stage,
    dueAt: args.at + reviewIntervalForStage(stage),
    nextDirection: nextReviewDirection(args.direction),
    lastReviewedAt: args.at,
    revisionClock: args.clock,
    revisionEventId: args.eventId,
    stats: structuredClone(args.stats ?? previous.stats),
  };
}

export function incrementReviewStats(
  previous: ReviewDeviceStats | undefined,
  attempt: "primary" | "retry",
  correct: boolean,
): ReviewDeviceStats {
  const result = { ...(previous ?? emptyReviewDeviceStats()) };
  const key = `${attempt}${correct ? "Right" : "Wrong"}` as keyof ReviewDeviceStats;
  result[key] += 1;
  return result;
}

/** A historical word gets a deterministic first due time before it has a card. */
export function initialHistoricalDueAt(libraryDate: string): number {
  const parsed = Date.parse(`${libraryDate.slice(0, 10)}T00:00:00.000Z`);
  return (Number.isFinite(parsed) ? parsed : Date.now()) + reviewIntervalForStage(0);
}
