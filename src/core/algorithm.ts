import { ALGORITHM_CONFIG } from "./config";

export interface ScoreLike {
  right: number;
  wrong: number;
  reverseReviewWeight?: number;
}

export function mastery(right: number, wrong: number): number {
  const total = right + wrong;
  return total ? Math.round((right / total) * 100) : 0;
}

export function recencySize(length: number): number {
  return Math.max(0, Math.min(ALGORITHM_CONFIG.recentLimit, length - 1));
}

export function weightedRandomWeight(score: ScoreLike): number {
  const wrongBonus = Math.min(
    ALGORITHM_CONFIG.wrongBonusCap,
    score.wrong * (score.wrong + 4),
  );
  const rightRelief = Math.min(
    wrongBonus,
    score.right * ALGORITHM_CONFIG.rightReliefFactor,
  );
  return 1 + wrongBonus - rightRelief + Math.max(0, 2 - score.right);
}

export function reverseWeight(score: ScoreLike): number {
  const base = 1 + Math.max(0, 2 - score.right);
  const reviewBonus = Math.round(Math.max(0, score.reverseReviewWeight ?? 0));
  return Math.max(1, base + reviewBonus);
}

export function updateReverseReviewWeight(current: number, correct: boolean): number {
  return correct
    ? Math.max(0, current * ALGORITHM_CONFIG.reverseRightMultiplier)
    : Math.min(
        ALGORITHM_CONFIG.reverseWeightCap,
        current + ALGORITHM_CONFIG.reverseWrongIncrement,
      );
}

export function rareMeaningWeight(index: number, length: number): number {
  if (length <= 1) return 1;
  const position = index / (length - 1);
  return 1 + Math.round(position * (ALGORITHM_CONFIG.rareMeaningMaxWeight - 1));
}

export function isLearned(right: number): boolean {
  return right >= ALGORITHM_CONFIG.learnedRightThreshold;
}

export function isCleared(items: ScoreLike[]): boolean {
  if (!items.length || !items.every((item) => isLearned(item.right))) return false;
  const totals = items.reduce(
    (acc, item) => ({ right: acc.right + item.right, total: acc.total + item.right + item.wrong }),
    { right: 0, total: 0 },
  );
  return totals.total > 0 && totals.right / totals.total >= ALGORITHM_CONFIG.clearAccuracyThreshold;
}
