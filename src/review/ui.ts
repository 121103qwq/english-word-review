import type { EventStore } from "../core/events";
import type { LegacyRuntimeApi } from "../core/types";
import {
  abandonReviewSession,
  buildReviewQueue,
  createReviewSession,
  getCurrentReviewQuestion,
  getReviewCompletionStats,
  getNextOldBatchOptions,
  resumeReviewSession,
  submitReviewAnswer,
  type ReviewLibraryInput,
  type ReviewQueue,
  type ReviewSessionMode,
  type ReviewSessionState,
} from "./session";

const SESSION_KEY = "english-word-review:spaced-review-session-v1";

interface ReviewUiOptions {
  store: EventStore;
  legacyRuntime: LegacyRuntimeApi;
}

const byId = <T extends HTMLElement>(id: string): T => {
  const element = document.getElementById(id);
  if (!element) throw new Error(`缺少检查背诵界面元素：${id}`);
  return element as T;
};

function formatPercent(value: number): string {
  return `${Math.round(value * 100)}%`;
}

function formatDue(value: number | undefined): string {
  if (value === undefined) return "暂无后续到期项";
  return new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}

function librariesFromRuntime(runtime: LegacyRuntimeApi): { libraries: ReviewLibraryInput[]; activeLibraryId: string } {
  const bundle = runtime.getBundle();
  const active = bundle.store.current;
  return {
    activeLibraryId: active.id,
    libraries: [active, ...bundle.store.archives].map((library) => ({
      id: library.id,
      date: library.date,
      words: library.words.map((word) => ({ word: word.en, meaning: word.zh })),
    })),
  };
}

function sessionFromStorage(): ReviewSessionState | undefined {
  try { return resumeReviewSession(JSON.parse(localStorage.getItem(SESSION_KEY) || "null")); }
  catch { return undefined; }
}

export function initReviewUi(options: ReviewUiOptions): void {
  const panel = byId<HTMLElement>("reviewPanel");
  const choose = byId<HTMLElement>("reviewChooseState");
  const questionState = byId<HTMLElement>("reviewQuestionState");
  const resultState = byId<HTMLElement>("reviewResultState");
  const dueNote = byId<HTMLElement>("reviewDueNote");
  const spacedButton = byId<HTMLButtonElement>("reviewSpacedBtn");
  const input = byId<HTMLInputElement>("reviewInput");
  const submit = byId<HTMLButtonElement>("reviewSubmitBtn");
  const meanings = byId<HTMLElement>("reviewMeanings");
  const feedback = byId<HTMLElement>("reviewFeedback");
  const judge = byId<HTMLElement>("reviewJudge");
  const prompt = byId<HTMLElement>("reviewPrompt");
  const promptLabel = byId<HTMLElement>("reviewPromptLabel");
  const progress = byId<HTMLElement>("reviewProgress");
  const retryHint = byId<HTMLElement>("reviewRetryHint");
  const nextBatch = byId<HTMLButtonElement>("reviewNextBatchBtn");
  let session: ReviewSessionState | undefined;
  let currentQueue: ReviewQueue | undefined;
  let showingMeaning = false;
  let locked = false;
  let awaitingAdvance = false;

  if (options.store.readOnly) {
    const open = byId<HTMLButtonElement>("reviewOpenBtn");
    open.disabled = true;
    open.title = "当前学习快照处于只读保护，不能记录检查结果";
  }

  const showState = (state: "choose" | "question" | "result") => {
    choose.hidden = state !== "choose";
    questionState.hidden = state !== "question";
    resultState.hidden = state !== "result";
  };
  const persist = () => {
    if (session) localStorage.setItem(SESSION_KEY, JSON.stringify(session));
    else localStorage.removeItem(SESSION_KEY);
  };
  const clearFeedback = () => {
    feedback.textContent = "";
    feedback.className = "review-feedback";
  };
  const nextDue = (): number | undefined => Object.values(options.store.getReviewCards())
    .map((state) => state.dueAt).filter((dueAt) => Number.isFinite(dueAt)).sort((left, right) => left - right)[0];

  const prepareQueue = (mode: ReviewSessionMode, oldOnly = false): ReviewQueue => {
    const runtime = librariesFromRuntime(options.legacyRuntime);
    return buildReviewQueue({
      libraries: runtime.libraries,
      activeLibraryId: runtime.activeLibraryId,
      reviewCards: options.store.getReviewCards(),
      mode,
      ...(oldOnly ? { includeCurrent: false } : {}),
    });
  };

  const refreshChoice = () => {
    const runtime = librariesFromRuntime(options.legacyRuntime);
    const spaced = prepareQueue("spaced");
    byId<HTMLElement>("reviewCurrentDescription").textContent = `当前活动词库的全部 ${runtime.libraries.find((item) => item.id === runtime.activeLibraryId)?.words.length ?? 0} 个词`;
    if (spaced.selectedOldCount) {
      dueNote.textContent = `有 ${spaced.dueOldCount} 个到期旧词；本轮会加入最早到期的 ${spaced.selectedOldCount} 个。`;
      byId<HTMLElement>("reviewSpacedDescription").textContent = `本期全部 + ${spaced.selectedOldCount} 个到期旧词${spaced.remainingDueOldCards.length ? `（另有 ${spaced.remainingDueOldCards.length} 个待处理）` : ""}`;
      spacedButton.disabled = false;
    } else {
      dueNote.textContent = "暂无到期旧词；“间隔复习”将退化为仅本期。";
      byId<HTMLElement>("reviewSpacedDescription").textContent = "暂无到期旧词，开始后仅检查本期词库";
      spacedButton.disabled = false;
    }
  };

  const renderQuestion = () => {
    if (!session) return;
    const question = getCurrentReviewQuestion(session);
    if (!question) { renderResult(); return; }
    showState("question");
    locked = false;
    awaitingAdvance = false;
    showingMeaning = false;
    input.value = "";
    input.hidden = false;
    input.placeholder = question.direction === "zh-to-en"
      ? "输入完整英文"
      : "可写下或口述后查看释义（不自动判分）";
    submit.hidden = false;
    submit.textContent = question.direction === "zh-to-en" ? "提交（Enter）" : "显示完整释义";
    meanings.hidden = true;
    judge.hidden = true;
    clearFeedback();
    promptLabel.textContent = question.direction === "en-to-zh" ? "英 → 中：先独立回忆，再查看完整释义" : "中 → 英：输入完整英文";
    prompt.textContent = question.direction === "en-to-zh" ? question.word : (question.meanings.join("；") || "（暂无中文释义）");
    const primaryTotal = session.questions.filter((item) => item.attempt === "primary").length;
    const primaryDone = session.answers.filter((item) => item.attempt === "primary").length;
    progress.textContent = `${question.attempt === "retry" ? "补测" : "主队列"} · ${primaryDone + (question.attempt === "primary" ? 1 : 0)}/${primaryTotal}`;
    retryHint.textContent = question.attempt === "retry"
      ? "这是首答错误后的同向补测；补测不会改变首次结果。"
      : "";
    window.setTimeout(() => input.focus(), 0);
  };

  const renderResult = () => {
    if (!session) return;
    showState("result");
    const stats = getReviewCompletionStats(session);
    const due = nextDue();
    byId<HTMLElement>("reviewResultSummary").innerHTML = [
      `<p>首答正确率：<b>${formatPercent(stats.primary.accuracy)}</b>（${stats.primary.correct}/${stats.primary.total}）</p>`,
      `<p>英→中：${formatPercent(stats.directions["en-to-zh"].accuracy)}；中→英：${formatPercent(stats.directions["zh-to-en"].accuracy)}</p>`,
      `<p>本期：${formatPercent(stats.origins.current.accuracy)}；旧词：${stats.origins.old.total ? formatPercent(stats.origins.old.accuracy) : "本轮无旧词"}</p>`,
      `<p>补测：${stats.retry.total ? `${stats.retry.correct}/${stats.retry.total}` : "无"}；下次到期：${formatDue(due)}</p>`,
      `<p>剩余到期旧词：${stats.remainingDueOldCount} 个</p>`,
    ].join("");
    nextBatch.hidden = session.mode !== "spaced" || stats.remainingDueOldCount === 0;
    localStorage.removeItem(SESSION_KEY);
  };

  const commitAnswer = (remembered?: boolean) => {
    if (!session || locked) return;
    const question = getCurrentReviewQuestion(session);
    if (!question) return;
    locked = true;
    const result = submitReviewAnswer(session, {
      questionId: question.id,
      ...(question.direction === "zh-to-en" ? { answer: input.value } : { remembered }),
    });
    session = result.session;
    persist();
    options.store.recordReviewAnswer({
      sessionId: session.id,
      word: result.question.word,
      cardId: result.answer.cardId,
      direction: result.answer.direction,
      attempt: result.answer.attempt,
      correct: result.answer.correct,
      sourceLibraryIds: result.answer.sourceLibraryIds,
      answeredAt: result.answer.answeredAt,
    });
    meanings.textContent = `${result.question.word}：${result.question.meanings.join("；") || "暂无中文释义"}`;
    meanings.hidden = false;
    feedback.textContent = result.answer.correct ? "正确。" : "这题先记为没记得，正确答案已显示。";
    feedback.className = `review-feedback ${result.answer.correct ? "good" : "bad"}`;
    input.hidden = true;
    judge.hidden = true;
    awaitingAdvance = true;
    submit.hidden = false;
    submit.textContent = result.complete ? "查看结果（Enter）" : "下一题（Enter）";
    window.setTimeout(() => submit.focus(), 0);
  };

  const advanceAfterFeedback = () => {
    if (!session || !awaitingAdvance) return;
    awaitingAdvance = false;
    if (getCurrentReviewQuestion(session)) renderQuestion();
    else renderResult();
  };

  const revealMeanings = () => {
    if (!session || locked || showingMeaning) return;
    const question = getCurrentReviewQuestion(session);
    if (!question || question.direction !== "en-to-zh") return;
    showingMeaning = true;
    meanings.textContent = question.meanings.join("；") || "暂无中文释义";
    meanings.hidden = false;
    input.hidden = true;
    judge.hidden = false;
    submit.hidden = true;
    feedback.textContent = "能独立回忆任一核心常用义，即可选“记得”。";
  };

  const start = (mode: ReviewSessionMode, oldOnly = false) => {
    currentQueue = prepareQueue(mode, oldOnly);
    const runtime = librariesFromRuntime(options.legacyRuntime);
    session = createReviewSession({
      queue: currentQueue,
      activeLibraryId: runtime.activeLibraryId,
      nextDueAt: nextDue(),
    });
    persist();
    if (!currentQueue.cards.length) {
      dueNote.textContent = oldOnly ? "没有更多到期旧词。" : "当前活动词库为空，暂时无法开始检查。";
      session = undefined;
      persist();
      showState("choose");
      return;
    }
    renderQuestion();
  };

  byId<HTMLButtonElement>("reviewOpenBtn").onclick = () => {
    panel.hidden = false;
    session = sessionFromStorage();
    if (session && !session.abandonedAt) {
      if (getCurrentReviewQuestion(session)) renderQuestion();
      else renderResult();
    } else {
      session = undefined;
      persist();
      refreshChoice();
      showState("choose");
    }
    panel.scrollIntoView({ behavior: "smooth", block: "start" });
  };
  byId<HTMLButtonElement>("reviewCloseBtn").onclick = () => { panel.hidden = true; };
  byId<HTMLButtonElement>("reviewBackBtn").onclick = () => { panel.hidden = true; };
  byId<HTMLButtonElement>("reviewCurrentBtn").onclick = () => start("current");
  spacedButton.onclick = () => start("spaced");
  byId<HTMLButtonElement>("reviewNextBatchBtn").onclick = () => {
    if (!session) return;
    const options = getNextOldBatchOptions(session);
    start("spaced", options.includeCurrent === false);
  };
  submit.onclick = () => {
    if (awaitingAdvance) { advanceAfterFeedback(); return; }
    const question = session && getCurrentReviewQuestion(session);
    if (question?.direction === "en-to-zh") revealMeanings();
    else commitAnswer();
  };
  byId<HTMLButtonElement>("reviewKnowBtn").onclick = () => commitAnswer(true);
  byId<HTMLButtonElement>("reviewDontBtn").onclick = () => commitAnswer(false);
  input.addEventListener("keydown", (event) => {
    if (event.isComposing || event.keyCode === 229 || event.key !== "Enter") return;
    event.preventDefault();
    const question = session && getCurrentReviewQuestion(session);
    if (question?.direction === "en-to-zh") revealMeanings();
    else commitAnswer();
  });
  byId<HTMLButtonElement>("reviewAbandonBtn").onclick = () => {
    if (!session || !confirm("放弃本次检查？已提交的答案会保留，未答题不会改变状态。")) return;
    if (!confirm("再次确认：确定放弃未完成的检查吗？")) return;
    session = abandonReviewSession(session);
    session = undefined;
    persist();
    panel.hidden = true;
  };

  // The legacy learner registers its document shortcut handler earlier. Capture
  // here so review typing and shortcuts never leak through to the page beneath.
  document.addEventListener("keydown", (event) => {
    if (panel.hidden) return;
    if (event.key === "Enter" && !event.isComposing && awaitingAdvance) {
      event.preventDefault();
      event.stopImmediatePropagation();
      advanceAfterFeedback();
      return;
    }
    if (event.key === "Enter" && !event.isComposing && document.activeElement === input) {
      event.preventDefault();
      const question = session && getCurrentReviewQuestion(session);
      if (question?.direction === "en-to-zh") revealMeanings();
      else commitAnswer();
    }
    event.stopImmediatePropagation();
    if (event.key === "Escape" && !questionState.hidden) panel.hidden = true;
    if (event.key === "Enter" && !event.isComposing && document.activeElement !== input) {
      const question = session && getCurrentReviewQuestion(session);
      if (question?.direction === "en-to-zh" && !showingMeaning) {
        event.preventDefault();
        revealMeanings();
      }
    }
  }, true);
}
