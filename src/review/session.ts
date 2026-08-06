import { normalizeEnglish, reviewCardId, type ReviewCardState, type ReviewDirection } from "./types";

/** A resolved word supplied by the content layer. Meanings are deliberately a list
 * so a session can keep its question text even if its source library changes. */
export interface ReviewLibraryWord {
  word: string;
  meaning?: string;
}

export interface ReviewLibraryInput {
  id: string;
  date: string;
  words: ReviewLibraryWord[];
}

export type ReviewSessionMode = "current" | "spaced";
export type ReviewQuestionAttempt = "primary" | "retry";
export type ReviewCardOrigin = "current" | "old";

export interface ReviewQueueCard {
  cardId: string;
  wordKey: string;
  word: string;
  meanings: string[];
  sourceLibraryIds: string[];
  origin: ReviewCardOrigin;
  direction: ReviewDirection;
  stage: number;
  dueAt: number;
  hasSchedule: boolean;
}

export interface ReviewQueue {
  mode: ReviewSessionMode;
  cards: ReviewQueueCard[];
  /** Due old cards not chosen for this batch, in deterministic queue order. */
  remainingDueOldCards: ReviewQueueCard[];
  dueOldCount: number;
  selectedOldCount: number;
  oldOffset: number;
}

export interface BuildReviewQueueInput {
  libraries: ReviewLibraryInput[];
  activeLibraryId: string;
  reviewCards?: Record<string, ReviewCardState>;
  mode: ReviewSessionMode;
  now?: number | Date;
  oldLimit?: number;
  /** Used after completion to continue the old-card backlog without repeating current cards. */
  includeCurrent?: boolean;
  /** Number of due old cards already processed by earlier batches. */
  oldOffset?: number;
}

export interface ReviewSessionQuestion {
  id: string;
  cardId: string;
  wordKey: string;
  word: string;
  meanings: string[];
  sourceLibraryIds: string[];
  origin: ReviewCardOrigin;
  direction: ReviewDirection;
  attempt: ReviewQuestionAttempt;
  /** Number of primary answers that must exist before this retry can appear. */
  eligibleAfterPrimaryAnswers?: number;
}

export interface SubmittedReviewAnswer {
  questionId: string;
  cardId: string;
  wordKey: string;
  direction: ReviewDirection;
  attempt: ReviewQuestionAttempt;
  origin: ReviewCardOrigin;
  sourceLibraryIds: string[];
  correct: boolean;
  answer?: string;
  answeredAt: number;
}

export interface ReviewSessionState {
  schemaVersion: 1;
  id: string;
  mode: ReviewSessionMode;
  activeLibraryId: string;
  createdAt: number;
  updatedAt: number;
  questions: ReviewSessionQuestion[];
  answers: SubmittedReviewAnswer[];
  remainingDueOldCount: number;
  /** A snapshot supplied by the caller for completion-page display. */
  nextDueAt?: number;
  abandonedAt?: number;
}

export interface CreateReviewSessionInput {
  queue: ReviewQueue;
  activeLibraryId: string;
  now?: number | Date;
  id?: string;
  nextDueAt?: number;
}

export interface SubmitReviewAnswerInput {
  questionId: string;
  /** Used for Chinese-to-English questions. */
  answer?: string;
  /** Used for English-to-Chinese self assessment after the full meaning is shown. */
  remembered?: boolean;
  now?: number | Date;
}

export interface ReviewCompletionStats {
  primary: { total: number; correct: number; wrong: number; accuracy: number };
  directions: Record<ReviewDirection, { total: number; correct: number; wrong: number; accuracy: number }>;
  origins: Record<ReviewCardOrigin, { total: number; correct: number; wrong: number; accuracy: number }>;
  retry: { total: number; correct: number; wrong: number; accuracy: number };
  nextDueAt?: number;
  remainingDueOldCount: number;
}

const DAY_MS = 24 * 60 * 60 * 1000;

function asMillis(value: number | Date | undefined): number {
  return value instanceof Date ? value.getTime() : value ?? Date.now();
}

function deterministicDirection(wordKey: string): ReviewDirection {
  let hash = 2166136261;
  for (let index = 0; index < wordKey.length; index += 1) {
    hash ^= wordKey.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  return (hash >>> 0) % 2 === 0 ? "en-to-zh" : "zh-to-en";
}

function initialDueAt(date: string): number {
  const parsed = Date.parse(`${date}T00:00:00.000Z`);
  return (Number.isFinite(parsed) ? parsed : 0) + DAY_MS;
}

function uniqueMeanings(values: Array<string | undefined>): string[] {
  const seen = new Set<string>();
  const meanings: string[] = [];
  for (const value of values) {
    // Semicolons and newlines separate senses in the imported word lists. Do
    // not split commas: they are often part of one Chinese gloss or example.
    for (const rawMeaning of value?.split(/[;；\r\n]+/u) ?? []) {
      const meaning = rawMeaning.trim();
      if (!meaning) continue;
      const key = meaning.toLocaleLowerCase("zh-Hans-CN");
      if (!seen.has(key)) {
        seen.add(key);
        meanings.push(meaning);
      }
    }
  }
  return meanings;
}

interface GroupedWord {
  wordKey: string;
  word: string;
  meanings: string[];
  sourceLibraryIds: string[];
  latestSourceDate: string;
}

function groupWords(libraries: ReviewLibraryInput[]): Map<string, GroupedWord> {
  const groups = new Map<string, GroupedWord>();
  for (const library of libraries) {
    for (const entry of library.words) {
      const wordKey = normalizeEnglish(entry.word);
      if (!wordKey) continue;
      const prior = groups.get(wordKey);
      if (prior) {
        prior.meanings = uniqueMeanings([...prior.meanings, entry.meaning]);
        if (!prior.sourceLibraryIds.includes(library.id)) prior.sourceLibraryIds.push(library.id);
        if (library.date > prior.latestSourceDate) prior.latestSourceDate = library.date;
      } else {
        groups.set(wordKey, {
          wordKey,
          word: entry.word.trim(),
          meanings: uniqueMeanings([entry.meaning]),
          sourceLibraryIds: [library.id],
          latestSourceDate: library.date,
        });
      }
    }
  }
  return groups;
}

function toCard(group: GroupedWord, origin: ReviewCardOrigin, schedule?: ReviewCardState): ReviewQueueCard {
  return {
    cardId: schedule?.cardId ?? reviewCardId(group.wordKey),
    wordKey: group.wordKey,
    word: group.word,
    meanings: [...group.meanings],
    sourceLibraryIds: [...group.sourceLibraryIds].sort(),
    origin,
    direction: schedule?.nextDirection ?? deterministicDirection(group.wordKey),
    stage: schedule?.stage ?? 0,
    dueAt: schedule?.dueAt ?? initialDueAt(group.latestSourceDate),
    hasSchedule: Boolean(schedule),
  };
}

/**
 * Forms an immutable queue snapshot. Current-library cards always win over old
 * cards with the same normalized English word, while old meanings are globally
 * merged across their source libraries.
 */
export function buildReviewQueue(input: BuildReviewQueueInput): ReviewQueue {
  const now = asMillis(input.now);
  const active = input.libraries.find((library) => library.id === input.activeLibraryId);
  if (!active) throw new Error("活动词库不存在");
  const schedules = input.reviewCards ?? {};
  const allGroups = groupWords(input.libraries);
  const currentKeys = new Set(groupWords([active]).keys());
  const current = [...currentKeys]
    .map((wordKey) => toCard(allGroups.get(wordKey)!, "current", schedules[reviewCardId(wordKey)]))
    .sort((left, right) => left.wordKey.localeCompare(right.wordKey));

  if (input.mode === "current") {
    return { mode: input.mode, cards: current, remainingDueOldCards: [], dueOldCount: 0, selectedOldCount: 0, oldOffset: 0 };
  }

  const archived = input.libraries.filter((library) => library.id !== active.id);
  const oldGroups = groupWords(archived);
  const dueOld = [...oldGroups.values()]
    .filter((group) => !currentKeys.has(group.wordKey))
    .map((group) => toCard(group, "old", schedules[reviewCardId(group.wordKey)]))
    .filter((card) => card.dueAt <= now)
    .sort((left, right) => left.dueAt - right.dueAt || left.stage - right.stage || left.wordKey.localeCompare(right.wordKey));
  const limit = Math.max(0, input.oldLimit ?? 10);
  const oldOffset = Math.max(0, input.oldOffset ?? 0);
  const selected = dueOld.slice(oldOffset, oldOffset + limit);
  return {
    mode: input.mode,
    cards: [...(input.includeCurrent === false ? [] : current), ...selected],
    remainingDueOldCards: dueOld.slice(oldOffset + limit),
    dueOldCount: dueOld.length,
    selectedOldCount: selected.length,
    oldOffset,
  };
}

function sessionId(): string {
  return globalThis.crypto?.randomUUID?.() ?? `review-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

export function createReviewSession(input: CreateReviewSessionInput): ReviewSessionState {
  const now = asMillis(input.now);
  const id = input.id ?? sessionId();
  // A deterministic ordering followed by alternating directions makes genuinely
  // new cards balanced within this session, while scheduled cards retain their
  // prescribed next direction.
  const directions = new Map<string, ReviewDirection>();
  const newCards = input.queue.cards.filter((card) => !card.hasSchedule)
    .sort((left, right) => stableDirectionHash(left.wordKey) - stableDirectionHash(right.wordKey) || left.wordKey.localeCompare(right.wordKey));
  newCards.forEach((card, index) => directions.set(card.cardId, index % 2 === 0 ? "en-to-zh" : "zh-to-en"));
  return {
    schemaVersion: 1,
    id,
    mode: input.queue.mode,
    activeLibraryId: input.activeLibraryId,
    createdAt: now,
    updatedAt: now,
    questions: input.queue.cards.map((card, index) => ({
      id: `${id}:${index}:${card.cardId}:primary`,
      cardId: card.cardId,
      wordKey: card.wordKey,
      word: card.word,
      meanings: [...card.meanings],
      sourceLibraryIds: [...card.sourceLibraryIds],
      origin: card.origin,
      direction: directions.get(card.cardId) ?? card.direction,
      attempt: "primary",
    })),
    answers: [],
    remainingDueOldCount: input.queue.remainingDueOldCards.length,
    ...(input.nextDueAt === undefined ? {} : { nextDueAt: input.nextDueAt }),
  };
}

function stableDirectionHash(wordKey: string): number {
  let hash = 2166136261;
  for (let index = 0; index < wordKey.length; index += 1) {
    hash ^= wordKey.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  return hash >>> 0;
}

/**
 * Arguments for the next old-only batch after a completed spaced session.
 * Do not carry an old-card offset across batches: submitted cards normally get
 * a future due time and disappear from the next due set, so an old offset would
 * incorrectly skip the cards that remain due.
 */
export function getNextOldBatchOptions(session: ReviewSessionState): Pick<BuildReviewQueueInput, "includeCurrent" | "oldOffset"> {
  void session;
  return { includeCurrent: false };
}

/** Returns a clone only when persisted data has the supported local-session schema. */
export function resumeReviewSession(value: unknown): ReviewSessionState | undefined {
  if (!value || typeof value !== "object") return undefined;
  const state = value as Partial<ReviewSessionState>;
  if (state.schemaVersion !== 1 || typeof state.id !== "string" || !Array.isArray(state.questions) || !Array.isArray(state.answers)) return undefined;
  return structuredClone(state) as ReviewSessionState;
}

function answeredQuestionIds(session: ReviewSessionState): Set<string> {
  return new Set(session.answers.map((answer) => answer.questionId));
}

function primaryAnsweredCount(session: ReviewSessionState): number {
  return session.answers.filter((answer) => answer.attempt === "primary").length;
}

function allPrimaryAnswered(session: ReviewSessionState): boolean {
  return session.questions.filter((question) => question.attempt === "primary")
    .every((question) => session.answers.some((answer) => answer.questionId === question.id));
}

export function getCurrentReviewQuestion(session: ReviewSessionState): ReviewSessionQuestion | undefined {
  if (session.abandonedAt) return undefined;
  const answered = answeredQuestionIds(session);
  const answeredPrimary = primaryAnsweredCount(session);
  const primaryComplete = allPrimaryAnswered(session);
  const retry = session.questions.find((question) => question.attempt === "retry" && !answered.has(question.id) &&
    (primaryComplete || answeredPrimary >= (question.eligibleAfterPrimaryAnswers ?? Number.POSITIVE_INFINITY)));
  if (retry) return structuredClone(retry);
  const primary = session.questions.find((question) => question.attempt === "primary" && !answered.has(question.id));
  return primary ? structuredClone(primary) : undefined;
}

export function normalizeEnglishAnswer(value: string): string {
  return value.trim().toLocaleLowerCase("en-US").replace(/\s+/gu, " ");
}

export function isCorrectEnglishAnswer(answer: string, expectedWord: string): boolean {
  return normalizeEnglishAnswer(answer) === normalizeEnglishAnswer(expectedWord);
}

function retryQuestion(question: ReviewSessionQuestion, primaryAnswersBefore: number): ReviewSessionQuestion {
  return {
    ...question,
    id: `${question.id}:retry`,
    attempt: "retry",
    // The current primary answer is not one of the five required other questions.
    eligibleAfterPrimaryAnswers: primaryAnswersBefore + 6,
  };
}

export interface SubmitReviewAnswerResult {
  session: ReviewSessionState;
  question: ReviewSessionQuestion;
  answer: SubmittedReviewAnswer;
  nextQuestion?: ReviewSessionQuestion;
  complete: boolean;
}

/** Applies exactly one submitted answer. The caller persists the returned state
 * and separately records its answer event with the EventStore. */
export function submitReviewAnswer(session: ReviewSessionState, input: SubmitReviewAnswerInput): SubmitReviewAnswerResult {
  const current = getCurrentReviewQuestion(session);
  if (!current || current.id !== input.questionId) throw new Error("当前题目已变化或不存在");
  const next = structuredClone(session);
  const correct = current.direction === "zh-to-en"
    ? isCorrectEnglishAnswer(input.answer ?? "", current.word)
    : input.remembered === true;
  const answer: SubmittedReviewAnswer = {
    questionId: current.id,
    cardId: current.cardId,
    wordKey: current.wordKey,
    direction: current.direction,
    attempt: current.attempt,
    origin: current.origin,
    sourceLibraryIds: [...current.sourceLibraryIds],
    correct,
    ...(input.answer === undefined ? {} : { answer: input.answer }),
    answeredAt: asMillis(input.now),
  };
  const primaryBefore = primaryAnsweredCount(next);
  next.answers.push(answer);
  if (current.attempt === "primary" && !correct) next.questions.push(retryQuestion(current, primaryBefore));
  next.updatedAt = answer.answeredAt;
  const nextQuestion = getCurrentReviewQuestion(next);
  return { session: next, question: current, answer, ...(nextQuestion ? { nextQuestion } : {}), complete: !nextQuestion };
}

/** Marks the local, unsynchronised session as intentionally abandoned. Submitted
 * answers remain available to the caller for event recording. */
export function abandonReviewSession(session: ReviewSessionState, now?: number | Date): ReviewSessionState {
  const next = structuredClone(session);
  next.abandonedAt = asMillis(now);
  next.updatedAt = next.abandonedAt;
  return next;
}

function bucket(): { total: number; correct: number; wrong: number; accuracy: number } {
  return { total: 0, correct: 0, wrong: 0, accuracy: 0 };
}

function finishBucket(value: { total: number; correct: number; wrong: number; accuracy: number }): void {
  value.accuracy = value.total ? value.correct / value.total : 0;
}

export function getReviewCompletionStats(session: ReviewSessionState): ReviewCompletionStats {
  const primary = bucket();
  const directions: ReviewCompletionStats["directions"] = { "en-to-zh": bucket(), "zh-to-en": bucket() };
  const origins: ReviewCompletionStats["origins"] = { current: bucket(), old: bucket() };
  const retry = bucket();
  for (const answer of session.answers) {
    const all = answer.attempt === "primary" ? primary : retry;
    all.total += 1;
    if (answer.attempt === "primary") {
      directions[answer.direction].total += 1;
      origins[answer.origin].total += 1;
    }
    if (answer.correct) {
      all.correct += 1;
      if (answer.attempt === "primary") {
        directions[answer.direction].correct += 1;
        origins[answer.origin].correct += 1;
      }
    } else {
      all.wrong += 1;
      if (answer.attempt === "primary") {
        directions[answer.direction].wrong += 1;
        origins[answer.origin].wrong += 1;
      }
    }
  }
  [primary, retry, directions["en-to-zh"], directions["zh-to-en"], origins.current, origins.old].forEach(finishBucket);
  return {
    primary,
    directions,
    origins,
    retry,
    ...(session.nextDueAt === undefined ? {} : { nextDueAt: session.nextDueAt }),
    remainingDueOldCount: session.remainingDueOldCount,
  };
}
