import { MIMO_TTS_MODEL, MimoApiKeyResolver, MimoTtsClient, type MimoPrivateKeySource } from "../audio/mimo-tts";
import {
  FeedbackSoundService,
  createCustomFeedbackSound,
  type FeedbackSoundAsset,
  type FeedbackSoundMimeType,
} from "../audio/feedback-sounds";
import { createTtsCacheKey, IndexedDbTtsCacheBackend, TtsCache } from "../audio/tts-cache";
import type { LegacyRuntimeApi } from "../core/types";
import {
  deleteSecret,
  getRuntimePlatform,
  isNativeRuntime,
  loadSecret,
  saveSecret,
  saveTextFile,
  speakEnglish,
} from "../platform/runtime";
import {
  createDefaultShortcutMap,
  formatShortcutBinding,
  isShortcutInputTarget,
  matchesShortcut,
  setShortcutBinding,
  shortcutFromKeyboardEvent,
  SHORTCUT_ACTION_CONTEXT,
  SHORTCUT_ACTION_LABELS,
  SHORTCUT_ACTIONS,
} from "./shortcuts";
import {
  deserializePlatformSettingsSnapshot,
  inspectPlatformSettingsFile,
  serializePlatformSettingsSnapshot,
  updateLocalDeviceSettings,
  updateSyncedPlatformSettings,
} from "./model";
import {
  createSettingsAsset,
  IndexedDbSettingsAssetBackend,
  SettingsAssetRepository,
  type StoredSettingsAsset,
} from "./assets";
import { PlatformSettingsStorage } from "./storage";
import {
  listSettingsChoices,
  readSettingsChoice,
  uploadSettingsToMirrors,
  type SettingsFileChoice,
  type SettingsGitHubTransport,
  type SettingsRemoteDocument,
  type SettingsTransport,
} from "./transports";
import type {
  LocalPlatformSettingsStateV1,
  PlatformKind,
  PlatformSettingsSnapshotV1,
  SettingsAssetReference,
  ShortcutAction,
  ShortcutContext,
  ShortcutMap,
} from "./types";

const HTML_MIMO_KEY = "english-word-review:mimo-api-key";

const UI_TO_ACTION = {
  next: "next-word",
  speak: "speak-word",
  undo: "undo-answer",
  reveal: "reveal-answer",
  uncertain: "uncertain",
  know: "answer-known",
  dontKnow: "answer-unknown",
  option1: "choice-1",
  option2: "choice-2",
  option3: "choice-3",
  option4: "choice-4",
  intensiveKnow: "intensive-correct",
  intensiveDontKnow: "intensive-wrong",
  intensiveConfirm: "intensive-submit",
  rootOption1: "root-choice-1",
  rootOption2: "root-choice-2",
  rootOption3: "root-choice-3",
  rootOption4: "root-choice-4",
  rootConfirm: "root-submit",
  reviewSubmit: "review-submit",
  reviewKnow: "review-known",
  reviewDontKnow: "review-unknown",
  modeForward: "mode-forward",
  modeReverse: "mode-reverse",
  modeRare: "mode-rare",
  modeIntensive: "mode-intensive",
  modeRoot: "mode-root",
  modeReview: "mode-review",
  toggleWrongOnly: "toggle-wrong-only",
} as const satisfies Record<string, ShortcutAction>;

const ACTION_TO_LEGACY: Readonly<Partial<Record<ShortcutAction, string>>> = Object.freeze({
  "speak-word": "speak",
  "reveal-answer": "reveal",
  uncertain: "uncertain",
  "answer-known": "know",
  "answer-unknown": "dontKnow",
  "choice-1": "option1",
  "choice-2": "option2",
  "choice-3": "option3",
  "choice-4": "option4",
  "intensive-correct": "intensiveKnow",
  "intensive-wrong": "intensiveDontKnow",
  "intensive-submit": "intensiveConfirm",
  "root-choice-1": "rootOption1",
  "root-choice-2": "rootOption2",
  "root-choice-3": "rootOption3",
  "root-choice-4": "rootOption4",
  "root-submit": "rootConfirm",
  "review-submit": "reviewSubmit",
  "review-known": "reviewKnow",
  "review-unknown": "reviewDontKnow",
  "mode-forward": "modeForward",
  "mode-reverse": "modeReverse",
  "mode-rare": "modeRare",
  "mode-intensive": "modeIntensive",
  "mode-root": "modeRoot",
  "mode-review": "modeReview",
  "toggle-wrong-only": "toggleWrongOnly",
});

const PLATFORM_LABELS: Readonly<Record<PlatformKind, string>> = Object.freeze({
  windows: "Windows",
  android: "Android",
  html: "HTML / 浏览器",
});

const SHORTCUT_ACTION_SET = new Set<string>(SHORTCUT_ACTIONS);

function shortcutActionFromUi(value: string | undefined): ShortcutAction | undefined {
  if (!value) return undefined;
  if (SHORTCUT_ACTION_SET.has(value)) return value as ShortcutAction;
  return UI_TO_ACTION[value as keyof typeof UI_TO_ACTION];
}

type StatusKind = "" | "good" | "bad";

export interface PlatformSettingsControllerOptions {
  deviceId: string;
  legacyRuntime: LegacyRuntimeApi;
  getSettingsTransports: (strict?: boolean) => SettingsTransport[];
  playPrimaryForWord: (word: string) => Promise<boolean>;
  reportStatus?: (message: string, kind?: StatusKind) => void;
}

function clone<T>(value: T): T {
  return structuredClone(value);
}

function byId<T extends HTMLElement>(id: string): T {
  const element = document.getElementById(id);
  if (!element) throw new Error(`缺少设置界面元素：${id}`);
  return element as T;
}

function input(id: string): HTMLInputElement {
  return byId<HTMLInputElement>(id);
}

function select(id: string): HTMLSelectElement {
  return byId<HTMLSelectElement>(id);
}

function mimeFromFile(file: File): FeedbackSoundMimeType {
  if (file.type === "audio/wav" || /\.wav$/i.test(file.name)) return "audio/wav";
  if (file.type === "audio/ogg" || /\.ogg$/i.test(file.name)) return "audio/ogg";
  if (file.type === "audio/mpeg" || /\.mp3$/i.test(file.name)) return "audio/mpeg";
  throw new Error("只支持 MP3、WAV 或 OGG 音效");
}

function formatBytes(value: number): string {
  if (value >= 1024 ** 3) return `${(value / 1024 ** 3).toFixed(2)} GiB`;
  if (value >= 1024 ** 2) return `${(value / 1024 ** 2).toFixed(1)} MiB`;
  return `${Math.ceil(value / 1024)} KiB`;
}

function playAudio(bytes: Uint8Array, mimeType: string, volume = 1): Promise<void> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(new Blob([bytes as BlobPart], { type: mimeType }));
    const audio = new Audio(url);
    const cleanup = () => URL.revokeObjectURL(url);
    audio.volume = Math.max(0, Math.min(1, volume));
    audio.onended = () => { cleanup(); resolve(); };
    audio.onerror = () => { cleanup(); reject(new Error("音频播放失败")); };
    void audio.play().catch((error) => { cleanup(); reject(error); });
  });
}

function currentShortcutContext(mode: ReturnType<LegacyRuntimeApi["getModeState"]>["mode"]): ShortcutContext {
  if (mode === "reverse" || mode === "rare") return "choice";
  if (mode === "intensive") return "intensive";
  if (mode === "root") return "root";
  if (mode === "review") return "review";
  return "forward";
}

function contextIsActive(action: ShortcutAction, context: ShortcutContext): boolean {
  const actionContext = SHORTCUT_ACTION_CONTEXT[action];
  if (context === "review") return actionContext === "mode" || actionContext === "review" || action === "speak-word";
  if (action === "undo-answer" && context === "root") return false;
  return actionContext === "global" || actionContext === "mode" || actionContext === context;
}

export function legacyActionForShortcut(action: ShortcutAction, context: ShortcutContext): string | undefined {
  if (action === "next-word") {
    if (context === "intensive") return "intensiveSkip";
    if (context === "root") return "rootSkip";
    return "next";
  }
  if (action === "undo-answer") {
    if (context === "root" || context === "review") return undefined;
    return context === "intensive" ? "undoIntensive" : "undoNormal";
  }
  return ACTION_TO_LEGACY[action];
}

function referencedAssets(snapshot: Pick<PlatformSettingsSnapshotV1, "settings">): SettingsAssetReference[] {
  return [snapshot.settings.feedback.customCorrectSound, snapshot.settings.feedback.customWrongSound]
    .filter((value): value is SettingsAssetReference => Boolean(value));
}

export class PlatformSettingsController {
  readonly platform = getRuntimePlatform();
  readonly storage: PlatformSettingsStorage;

  private state: LocalPlatformSettingsStateV1;
  private draft: LocalPlatformSettingsStateV1;
  private dirty = false;
  private activeShortcutContext: ShortcutContext = "global";
  private currentQuestionWord = "";
  private currentQuestionSafeToSpeak = false;
  private readonly assets = new SettingsAssetRepository(new IndexedDbSettingsAssetBackend());
  private readonly cache: TtsCache;
  private readonly feedback = new FeedbackSoundService();
  private readonly mimo: MimoTtsClient;

  constructor(private readonly options: PlatformSettingsControllerOptions) {
    this.storage = new PlatformSettingsStorage(this.platform, options.deviceId);
    this.state = this.storage.loadOrCreate();
    this.draft = clone(this.state);
    this.cache = new TtsCache(new IndexedDbTtsCacheBackend(), {
      maxBytes: this.state.deviceLocal.ttsCacheLimitBytes,
    });
    const localKey = {
      load: async () => {
        if (!isNativeRuntime()) return localStorage.getItem(HTML_MIMO_KEY) ?? "";
        return loadSecret("mimo-api-key");
      },
      save: async (value: string) => {
        if (!isNativeRuntime()) {
          localStorage.setItem(HTML_MIMO_KEY, value);
          return;
        }
        await saveSecret("mimo-api-key", value);
      },
    };
    const remoteKey: MimoPrivateKeySource = {
      readMimoApiKey: async () => {
        const github = this.options.getSettingsTransports(false)
          .find((transport): transport is SettingsGitHubTransport =>
            typeof (transport as Partial<SettingsGitHubTransport>).readMimoApiKey === "function");
        return github?.readMimoApiKey() ?? null;
      },
    };
    this.mimo = new MimoTtsClient(new MimoApiKeyResolver(localKey, remoteKey));
  }

  async initialize(): Promise<void> {
    this.bindUi();
    this.populateSystemVoices();
    this.renderForm();
    await this.applyState();
    void this.refreshCacheUsage().catch(() => {
      this.setMimoStatus("语音缓存暂不可用，不影响系统朗读", "bad");
    });
    this.options.legacyRuntime.subscribeQuestion((question) => this.onQuestion(question.word, question.safeToSpeak));
    this.options.legacyRuntime.subscribeAnswerFeedback((correct) => this.onAnswerFeedback(correct));
    window.__englishReviewShortcutHandler = (event) => this.handleShortcut(event);
    void this.pruneUnusedSettingsAssets().catch(() => undefined);
  }

  async speakWord(word?: string, speechState: LocalPlatformSettingsStateV1 = this.state): Promise<void> {
    if (word === undefined && !this.currentQuestionSafeToSpeak) {
      this.options.reportStatus?.("当前题型会因提前朗读泄露答案，请先完成作答。", "bad");
      return;
    }
    const normalized = (word ?? (this.currentQuestionWord || this.options.legacyRuntime.getCurrentWord())).trim();
    if (!normalized) return;
    if (word === undefined) this.currentQuestionWord = normalized;
    if (await this.options.playPrimaryForWord(normalized)) return;
    const settings = speechState.settings;
    if (settings.speechProvider !== "mimo") {
      await speakEnglish(normalized, {
        rate: settings.speechRate,
        voice: speechState.deviceLocal.systemVoiceId,
      });
      return;
    }

    let cacheKey: string | undefined;
    let cacheUnavailable = false;
    try {
      cacheKey = await createTtsCacheKey({
        text: normalized,
        voice: speechState.deviceLocal.mimoVoice,
        rate: settings.speechRate,
        model: MIMO_TTS_MODEL,
      });
      const cached = await this.cache.get(cacheKey);
      if (cached) {
        await playAudio(cached.bytes, cached.mimeType);
        return;
      }
    } catch {
      cacheUnavailable = true;
    }
    const result = await this.mimo.synthesize(normalized, {
      voice: speechState.deviceLocal.mimoVoice,
      rate: settings.speechRate,
    });
    if (result.status === "ok") {
      if (cacheKey) {
        try {
          await this.cache.put(cacheKey, result.bytes, result.mimeType);
          await this.refreshCacheUsage();
        } catch {
          cacheUnavailable = true;
        }
      }
      await playAudio(result.bytes, result.mimeType);
      this.setMimoStatus(cacheUnavailable ? "MiMo 已就绪；本次缓存不可用" : "MiMo 已就绪", cacheUnavailable ? "" : "good");
      return;
    }
    const reason = result.reason === "missing-key" ? "未取得 MiMo 密钥" : "MiMo 暂不可用";
    this.setMimoStatus(`${reason}，本次已回退系统朗读`, "bad");
    await speakEnglish(normalized, {
      rate: settings.speechRate,
      voice: speechState.deviceLocal.systemVoiceId,
    });
  }

  onQuestion(word: string, safeToSpeak: boolean): void {
    this.currentQuestionWord = word.trim();
    this.currentQuestionSafeToSpeak = safeToSpeak;
    if (this.state.settings.autoSpeak && safeToSpeak && this.currentQuestionWord) {
      void this.speakWord(this.currentQuestionWord).catch((error) => this.report(error, "bad"));
    }
  }

  onAnswerFeedback(correct: boolean): void {
    const settings = this.state.settings.feedback;
    if (settings.soundEnabled) {
      void this.feedback.play(correct ? "correct" : "wrong").catch(() => undefined);
    }
    if (this.platform === "android" && settings.vibrationEnabled && navigator.vibrate) {
      navigator.vibrate(correct ? 35 : [45, 35, 45]);
    }
  }

  private report(error: unknown, kind: StatusKind = ""): void {
    const message = error instanceof Error ? error.message : String(error);
    const settingsStatus = document.getElementById("settingsGeneralStatus");
    if (settingsStatus) {
      settingsStatus.textContent = message;
      settingsStatus.dataset.kind = kind;
      settingsStatus.classList.toggle("good", kind === "good");
      settingsStatus.classList.toggle("bad", kind === "bad");
    }
    this.options.reportStatus?.(message, kind);
  }

  private setTextStatus(id: string, message: string, kind: StatusKind = ""): void {
    const element = byId<HTMLElement>(id);
    element.textContent = message;
    element.dataset.kind = kind;
    element.classList.toggle("good", kind === "good");
    element.classList.toggle("bad", kind === "bad");
  }

  private setMimoStatus(message: string, kind: StatusKind = ""): void {
    this.setTextStatus("settingsMimoKeyStatus", message, kind);
  }

  private bindUi(): void {
    window.addEventListener("english-review:settings-open", () => {
      this.draft = clone(this.state);
      this.dirty = false;
      this.renderForm();
    });
    window.addEventListener("english-review:settings-action", (rawEvent) => {
      const event = rawEvent as CustomEvent<{ action?: string }>;
      const action = event.detail?.action;
      if (action === "cancel") {
        if (this.dirty && !confirm("设置尚未保存，确定放弃本次修改吗？")) {
          event.preventDefault();
          return;
        }
        this.draft = clone(this.state);
        this.dirty = false;
        void this.pruneUnusedSettingsAssets().catch(() => undefined);
        return;
      }
      if (action === "apply" || action === "save") {
        try {
          this.persistForm();
        } catch (error) {
          event.preventDefault();
          this.report(error, "bad");
        }
      }
    });

    byId("settingsDialog").addEventListener("input", (event) => {
      if ((event.target as Element | null)?.closest("[data-setting-key]")) this.dirty = true;
    });
    byId("settingsDialog").addEventListener("change", (event) => {
      if ((event.target as Element | null)?.closest("[data-setting-key]")) this.dirty = true;
    });

    document.querySelectorAll<HTMLElement>("#shortcutBindingList [data-shortcut-action]").forEach((row) => {
      const action = shortcutActionFromUi(row.dataset.shortcutAction);
      if (!action) return;
      row.querySelectorAll<HTMLButtonElement>("[data-shortcut-slot]").forEach((button) => {
        button.addEventListener("click", () => {
          const slot = button.dataset.shortcutSlot === "secondary" ? 1 : 0;
          this.activateShortcutContext(SHORTCUT_ACTION_CONTEXT[action]);
          this.captureShortcut(button, action, slot);
        });
      });
    });
    document.querySelectorAll<HTMLElement>("#shortcutBindingList .shortcut-group[data-shortcut-context]").forEach((group) => {
      const rawContext = group.dataset.shortcutContext;
      const context = rawContext === "common" ? "global" : rawContext as ShortcutContext | undefined;
      if (!context) return;
      group.addEventListener("click", () => this.activateShortcutContext(context));
      group.addEventListener("focusin", () => this.activateShortcutContext(context));
    });
    document.querySelectorAll<HTMLButtonElement>("#shortcutBindingList [data-shortcut-reset]").forEach((button) => {
      button.addEventListener("click", () => {
        const action = shortcutActionFromUi(button.dataset.shortcutReset);
        if (!action) return;
        this.draft.settings.shortcutBindings[action] = clone(createDefaultShortcutMap()[action]);
        this.dirty = true;
        this.renderShortcuts();
      });
    });
    byId<HTMLButtonElement>("settingsResetShortcutGroupBtn").onclick = () => {
      const defaults = createDefaultShortcutMap();
      for (const action of SHORTCUT_ACTIONS) {
        if (SHORTCUT_ACTION_CONTEXT[action] === this.activeShortcutContext) {
          this.draft.settings.shortcutBindings[action] = clone(defaults[action]);
        }
      }
      this.dirty = true;
      this.renderShortcuts();
    };
    byId<HTMLButtonElement>("settingsResetAllShortcutsBtn").onclick = () => {
      this.draft.settings.shortcutBindings = createDefaultShortcutMap();
      this.dirty = true;
      this.renderShortcuts();
    };

    byId<HTMLInputElement>("settingsCorrectSoundFile").onchange = (event) => {
      void this.selectCustomSound(event.currentTarget as HTMLInputElement, "correct").catch((error) => this.report(error, "bad"));
    };
    byId<HTMLInputElement>("settingsWrongSoundFile").onchange = (event) => {
      void this.selectCustomSound(event.currentTarget as HTMLInputElement, "wrong").catch((error) => this.report(error, "bad"));
    };
    byId<HTMLButtonElement>("settingsPreviewCorrectSoundBtn").onclick = () => void this.previewFeedback("correct");
    byId<HTMLButtonElement>("settingsPreviewWrongSoundBtn").onclick = () => void this.previewFeedback("wrong");
    byId<HTMLButtonElement>("settingsResetCorrectSoundBtn").onclick = () => {
      delete this.draft.settings.feedback.customCorrectSound;
      this.dirty = true;
      void this.pruneUnusedSettingsAssets().catch(() => undefined);
    };
    byId<HTMLButtonElement>("settingsResetWrongSoundBtn").onclick = () => {
      delete this.draft.settings.feedback.customWrongSound;
      this.dirty = true;
      void this.pruneUnusedSettingsAssets().catch(() => undefined);
    };
    byId<HTMLButtonElement>("settingsSpeechPreviewBtn").onclick = () => {
      this.readFormIntoDraft();
      const previewState = clone(this.draft);
      void this.speakWord("example", previewState).catch((error) => this.report(error, "bad"));
    };
    byId<HTMLButtonElement>("settingsMimoFetchKeyBtn").onclick = () => void this.fetchMimoKey();
    byId<HTMLButtonElement>("settingsMimoClearKeyBtn").onclick = () => void this.clearMimoKey();
    byId<HTMLButtonElement>("settingsClearMimoCacheBtn").onclick = () => {
      void this.cache.clear().then(() => this.refreshCacheUsage()).catch((error) => this.report(error, "bad"));
    };
    byId<HTMLButtonElement>("settingsRefreshCloudFilesBtn").onclick = () => void this.refreshCloudFiles();
    byId<HTMLButtonElement>("settingsUploadCloudFileBtn").onclick = () => void this.uploadCloudFile();
    byId<HTMLButtonElement>("settingsExportFileBtn").onclick = () => void this.exportSettingsFile();
    byId<HTMLButtonElement>("settingsImportFileBtn").onclick = () => input("settingsImportFileInput").click();
    input("settingsImportFileInput").onchange = (event) => void this.importSettingsFile(event.currentTarget as HTMLInputElement);
    this.activateShortcutContext("global");
  }

  private activateShortcutContext(context: ShortcutContext): void {
    this.activeShortcutContext = context;
    const dataContext = context === "global" ? "common" : context;
    document.querySelectorAll<HTMLElement>("#shortcutBindingList .shortcut-group[data-shortcut-context]").forEach((group) => {
      const active = group.dataset.shortcutContext === dataContext;
      group.classList.toggle("active", active);
      group.setAttribute("aria-current", active ? "true" : "false");
    });
    const labels: Record<ShortcutContext, string> = {
      global: "通用",
      forward: "看英文说中文",
      choice: "选择题",
      intensive: "错词强化",
      root: "词根背诵",
      review: "检查背诵",
      mode: "题型切换",
    };
    byId<HTMLButtonElement>("settingsResetShortcutGroupBtn").textContent = `恢复当前分组（${labels[context]}）`;
  }

  private renderForm(): void {
    const synced = this.draft.settings;
    const local = this.draft.deviceLocal;
    select("settingsTheme").value = synced.theme;
    input("settingsUiScale").value = String(Math.round(synced.uiScale * 100));
    input("settingsWordScale").value = String(Math.round(synced.wordScale * 100));
    input("settingsReducedMotion").checked = synced.reducedMotion;
    input("settingsFocusMode").checked = synced.focusMode;
    input("settingsConfirmReset").checked = synced.confirmRestart;
    input("settingsAutoAdvanceDelay").value = String(synced.autoAdvanceDelayMs);
    input("settingsAutoSpeak").checked = synced.autoSpeak;
    select("settingsSpeechProvider").value = synced.speechProvider;
    select("settingsSystemVoice").value = local.systemVoiceId ?? "";
    select("settingsMimoVoice").value = local.mimoVoice;
    input("settingsSpeechRate").value = String(synced.speechRate);
    select("settingsMimoCacheLimit").value = String(local.ttsCacheLimitBytes);
    select("settingsSoundTheme").value = synced.feedback.soundEnabled ? synced.feedback.theme : "silent";
    input("settingsSoundEnabled").checked = synced.feedback.soundEnabled;
    input("settingsSoundVolume").value = String(Math.round(synced.feedback.volume * 100));
    input("settingsVibration").checked = synced.feedback.vibrationEnabled;
    input("settingsVibration").disabled = this.platform !== "android";
    input("settingsShortcutsEnabled").checked = synced.shortcutsEnabled;
    input("settingsPushLargeAssets").checked = synced.feedback.pushLargeAttachments;
    input("settingsPlatform").value = PLATFORM_LABELS[this.platform];
    input("settingsDeviceCode").value = local.identity.deviceCode;
    input("settingsDeviceName").value = local.identity.deviceName;
    input("settingsLocation").value = local.identity.location;
    byId<HTMLOutputElement>("settingsAutoAdvanceDelayOutput").textContent = `${(synced.autoAdvanceDelayMs / 1000).toFixed(2)} 秒`;
    byId<HTMLOutputElement>("settingsUiScaleOutput").textContent = `${Math.round(synced.uiScale * 100)}%`;
    byId<HTMLOutputElement>("settingsWordScaleOutput").textContent = `${Math.round(synced.wordScale * 100)}%`;
    byId<HTMLOutputElement>("settingsSpeechRateOutput").textContent = `${synced.speechRate.toFixed(2)}×`;
    byId<HTMLOutputElement>("settingsSoundVolumeOutput").textContent = `${Math.round(synced.feedback.volume * 100)}%`;
    this.renderShortcuts();
  }

  private readFormIntoDraft(): void {
    const synced = this.draft.settings;
    synced.theme = select("settingsTheme").value as typeof synced.theme;
    synced.uiScale = Number(input("settingsUiScale").value) / 100;
    synced.wordScale = Number(input("settingsWordScale").value) / 100;
    synced.reducedMotion = input("settingsReducedMotion").checked;
    synced.focusMode = input("settingsFocusMode").checked;
    synced.confirmRestart = input("settingsConfirmReset").checked;
    synced.autoAdvanceDelayMs = Number(input("settingsAutoAdvanceDelay").value);
    synced.autoSpeak = input("settingsAutoSpeak").checked;
    synced.speechProvider = select("settingsSpeechProvider").value as typeof synced.speechProvider;
    synced.speechRate = Number(input("settingsSpeechRate").value);
    synced.feedback.theme = select("settingsSoundTheme").value as typeof synced.feedback.theme;
    synced.feedback.soundEnabled = input("settingsSoundEnabled").checked && synced.feedback.theme !== "silent";
    synced.feedback.volume = Number(input("settingsSoundVolume").value) / 100;
    synced.feedback.vibrationEnabled = this.platform === "android" && input("settingsVibration").checked;
    synced.feedback.pushLargeAttachments = input("settingsPushLargeAssets").checked;
    synced.shortcutsEnabled = input("settingsShortcutsEnabled").checked;
    this.draft.deviceLocal.systemVoiceId = select("settingsSystemVoice").value || undefined;
    this.draft.deviceLocal.mimoVoice = select("settingsMimoVoice").value as typeof this.draft.deviceLocal.mimoVoice;
    this.draft.deviceLocal.ttsCacheLimitBytes = Number(select("settingsMimoCacheLimit").value);
    this.draft.deviceLocal.identity.deviceName = input("settingsDeviceName").value.trim();
    this.draft.deviceLocal.identity.location = input("settingsLocation").value.trim();
  }

  private persistForm(): void {
    this.readFormIntoDraft();
    let next = updateSyncedPlatformSettings(this.state, (settings) => Object.assign(settings, clone(this.draft.settings)));
    next = updateLocalDeviceSettings(next, (local) => Object.assign(local, clone(this.draft.deviceLocal)));
    this.storage.save(next);
    this.state = next;
    this.draft = clone(next);
    this.dirty = false;
    void this.pruneUnusedSettingsAssets().catch(() => undefined);
    void this.applyState().catch((error) => this.report(error, "bad"));
    this.setTextStatus("settingsGeneralStatus", "已保存，仅影响当前平台的个性化设置。", "good");
    this.options.reportStatus?.("个性化设置已保存；未写入词库或学习进度。", "good");
  }

  private async applyState(): Promise<void> {
    const settings = this.state.settings;
    const resolvedTheme = settings.theme === "system"
      ? (globalThis.matchMedia?.("(prefers-color-scheme: light)").matches ? "light" : "dark")
      : settings.theme;
    document.documentElement.dataset.theme = resolvedTheme;
    document.documentElement.style.setProperty("--ui-scale", String(settings.uiScale));
    document.documentElement.style.setProperty("--word-scale", String(settings.wordScale));
    // The 8.2 UI intentionally does not expose or activate reduced-motion.
    document.body.classList.remove("reduced-motion");
    document.body.classList.toggle("focus-mode", settings.focusMode);
    this.options.legacyRuntime.setAutoAdvanceDelay(settings.autoAdvanceDelayMs);
    this.options.legacyRuntime.setConfirmRestart(settings.confirmRestart);
    this.options.legacyRuntime.updateShortcutLabels(this.shortcutLabels(settings.shortcutBindings));
    void this.cache.setMaxBytes(this.state.deviceLocal.ttsCacheLimitBytes).catch(() => {
      this.setMimoStatus("语音缓存暂不可用，不影响系统朗读", "bad");
    });
    void this.configureFeedback().catch(() => {
      this.setTextStatus("settingsFeedbackStatus", "音效附件暂不可用，答题流程不受影响", "bad");
    });
  }

  private shortcutLabels(map: ShortcutMap): Record<string, string> {
    return Object.fromEntries(Object.entries(UI_TO_ACTION).map(([uiAction, action]) => {
      const values = map[action].filter((value): value is NonNullable<typeof value> => Boolean(value));
      return [uiAction, values.length ? values.map(formatShortcutBinding).join(" / ") : ""];
    }));
  }

  private renderShortcuts(): void {
    document.querySelectorAll<HTMLElement>("#shortcutBindingList [data-shortcut-action]").forEach((row) => {
      const action = shortcutActionFromUi(row.dataset.shortcutAction);
      if (!action) return;
      row.querySelectorAll<HTMLButtonElement>("[data-shortcut-slot]").forEach((button) => {
        const slot = button.dataset.shortcutSlot === "secondary" ? 1 : 0;
        const value = this.draft.settings.shortcutBindings[action][slot];
        button.textContent = value ? formatShortcutBinding(value) : "未绑定";
        button.dataset.empty = String(!value);
      });
    });
  }

  private captureShortcut(button: HTMLButtonElement, action: ShortcutAction, slot: 0 | 1): void {
    const previous = button.textContent;
    button.textContent = "请按键…";
    this.setTextStatus("settingsShortcutStatus", "按下新按键；Delete 清除，Escape 取消。", "");
    const capture = (event: KeyboardEvent) => {
      event.preventDefault();
      event.stopImmediatePropagation();
      window.removeEventListener("keydown", capture, true);
      if (event.code === "Escape") {
        button.textContent = previous;
        this.setTextStatus("settingsShortcutStatus", "已取消录入。", "");
        return;
      }
      if (event.code === "Delete" || event.code === "Backspace") {
        const cleared = setShortcutBinding(this.draft.settings.shortcutBindings, action, slot, null);
        if (cleared.status === "applied") this.draft.settings.shortcutBindings = cleared.shortcuts;
        this.dirty = true;
        this.renderShortcuts();
        this.setTextStatus("settingsShortcutStatus", "按键已清除；应用后生效。", "good");
        return;
      }
      const candidate = shortcutFromKeyboardEvent(event);
      if (!candidate.valid || !candidate.binding) {
        button.textContent = previous;
        this.setTextStatus("settingsShortcutStatus", candidate.reason ?? "该按键不能绑定", "bad");
        return;
      }
      let result = setShortcutBinding(this.draft.settings.shortcutBindings, action, slot, candidate.binding);
      if (result.status === "conflict") {
        const conflict = SHORTCUT_ACTION_LABELS[result.conflict.action];
        if (!confirm(`该按键已用于“${conflict}”。是否交换两个按键？`)) {
          button.textContent = previous;
          this.setTextStatus("settingsShortcutStatus", "冲突未处理，原按键保持不变。", "bad");
          return;
        }
        result = setShortcutBinding(this.draft.settings.shortcutBindings, action, slot, candidate.binding, "swap");
      }
      if (result.status !== "applied") {
        button.textContent = previous;
        this.setTextStatus("settingsShortcutStatus", result.status === "rejected" ? result.reason : "按键冲突", "bad");
        return;
      }
      this.draft.settings.shortcutBindings = result.shortcuts;
      this.dirty = true;
      this.renderShortcuts();
      this.setTextStatus("settingsShortcutStatus", result.swapped ? "已交换冲突按键；应用后生效。" : "新按键已录入；应用后生效。", "good");
    };
    window.addEventListener("keydown", capture, true);
  }

  private handleShortcut(event: KeyboardEvent): boolean {
    if (!this.state.settings.shortcutsEnabled || event.isComposing || event.repeat || isShortcutInputTarget(event.target)) return false;
    const context = currentShortcutContext(this.options.legacyRuntime.getModeState().mode);
    for (const action of SHORTCUT_ACTIONS) {
      if (!contextIsActive(action, context)) continue;
      if (!this.state.settings.shortcutBindings[action].some((binding) => binding && matchesShortcut(event, binding))) continue;
      if (action === "speak-word") {
        void this.speakWord().catch((error) => this.report(error, "bad"));
        return true;
      }
      const legacyAction = legacyActionForShortcut(action, context);
      return legacyAction ? this.options.legacyRuntime.performAction(legacyAction) : false;
    }
    return false;
  }

  private populateSystemVoices(): void {
    if (!("speechSynthesis" in window)) return;
    const populate = () => {
      const field = select("settingsSystemVoice");
      const selected = this.draft.deviceLocal.systemVoiceId ?? "";
      field.replaceChildren(new Option("系统默认语音", ""));
      speechSynthesis.getVoices()
        .filter((voice) => /^en(?:-|_)/i.test(voice.lang))
        .sort((left, right) => left.name.localeCompare(right.name))
        .forEach((voice) => field.add(new Option(`${voice.name} (${voice.lang})`, voice.name)));
      field.value = selected;
    };
    populate();
    speechSynthesis.addEventListener?.("voiceschanged", populate);
  }

  private async selectCustomSound(field: HTMLInputElement, result: "correct" | "wrong"): Promise<void> {
    const file = field.files?.[0];
    field.value = "";
    if (!file) return;
    const bytes = new Uint8Array(await file.arrayBuffer());
    const mimeType = mimeFromFile(file);
    const decoded = await createCustomFeedbackSound(bytes, file.name, mimeType);
    const asset = await createSettingsAsset(decoded.bytes, decoded.fileName, decoded.mimeType);
    await this.assets.save(asset);
    if (result === "correct") this.draft.settings.feedback.customCorrectSound = asset.reference;
    else this.draft.settings.feedback.customWrongSound = asset.reference;
    this.draft.settings.feedback.theme = "custom";
    this.draft.settings.feedback.soundEnabled = true;
    select("settingsSoundTheme").value = "custom";
    input("settingsSoundEnabled").checked = true;
    this.dirty = true;
    await this.pruneUnusedSettingsAssets();
    this.options.reportStatus?.(`${result === "correct" ? "答对" : "答错"}音效已保存在独立本机附件库；应用后启用。`, "good");
  }

  private referencedSettingsAssetHashes(): string[] {
    return [
      ...referencedAssets(this.state),
      ...referencedAssets(this.draft),
    ].map((reference) => reference.sha256);
  }

  private async pruneUnusedSettingsAssets(): Promise<void> {
    await this.assets.prune(this.referencedSettingsAssetHashes());
  }

  private async feedbackAsset(reference?: SettingsAssetReference): Promise<FeedbackSoundAsset | undefined> {
    if (!reference) return undefined;
    const asset = await this.assets.get(reference.sha256);
    return asset && { ...asset.reference, bytes: asset.bytes };
  }

  private async configureFeedback(state = this.state): Promise<void> {
    const settings = state.settings.feedback;
    this.feedback.configure({
      theme: settings.soundEnabled ? settings.theme : "silent",
      volume: settings.volume,
      custom: {
        correct: await this.feedbackAsset(settings.customCorrectSound),
        wrong: await this.feedbackAsset(settings.customWrongSound),
      },
    });
  }

  private async previewFeedback(result: "correct" | "wrong"): Promise<void> {
    this.readFormIntoDraft();
    await this.configureFeedback(this.draft);
    await this.feedback.play(result);
    await this.configureFeedback(this.state);
  }

  private async fetchMimoKey(): Promise<void> {
    try {
      const github = this.options.getSettingsTransports(true)
        .find((transport): transport is SettingsGitHubTransport =>
          typeof (transport as Partial<SettingsGitHubTransport>).readMimoApiKey === "function");
      if (!github) throw new Error("需要启用并完整配置 GitHub 私密数据仓库");
      const key = await github.readMimoApiKey();
      if (!key) throw new Error("私密仓库中未找到 MiMo 密钥文件");
      if (isNativeRuntime()) await saveSecret("mimo-api-key", key);
      else localStorage.setItem(HTML_MIMO_KEY, key);
      this.setMimoStatus("密钥已保存到本机", "good");
    } catch (error) {
      this.setMimoStatus(error instanceof Error ? error.message : String(error), "bad");
    }
  }

  private async clearMimoKey(): Promise<void> {
    try {
      if (isNativeRuntime()) {
        await deleteSecret("mimo-api-key");
      } else {
        localStorage.removeItem(HTML_MIMO_KEY);
      }
      this.setMimoStatus("本机 MiMo 密钥已删除", "good");
    } catch (error) {
      this.setMimoStatus(error instanceof Error ? error.message : String(error), "bad");
    }
  }

  private async refreshCacheUsage(): Promise<void> {
    const stats = await this.cache.stats();
    this.setTextStatus("settingsMimoCacheUsage", `当前缓存：${formatBytes(stats.totalBytes)} / ${formatBytes(stats.maxBytes)}`, "");
  }

  private async assetsForSnapshot(snapshot: PlatformSettingsSnapshotV1): Promise<StoredSettingsAsset[]> {
    const values = await Promise.all(referencedAssets(snapshot).map((reference) => this.assets.get(reference.sha256)));
    return values.filter((value): value is StoredSettingsAsset => Boolean(value));
  }

  private async refreshCloudFiles(): Promise<void> {
    const host = byId<HTMLElement>("settingsCloudFileList");
    const status = "settingsCloudStatus";
    try {
      this.readFormIntoDraft();
      const transports = this.options.getSettingsTransports(true);
      if (!transports.length) throw new Error("请先在数据同步中配置至少一个可用镜像");
      this.setTextStatus(status, "正在读取云端设置文件……", "");
      const result = await listSettingsChoices(transports, this.platform, this.draft.deviceLocal.identity.location);
      host.replaceChildren();
      if (!result.choices.length) {
        const empty = document.createElement("div");
        empty.className = "settings-file-empty";
        empty.textContent = "没有找到当前平台的设置文件。";
        host.append(empty);
      } else {
        result.choices.forEach((choice) => host.append(this.renderCloudChoice(choice)));
      }
      this.setTextStatus(status, result.errors.length
        ? `已列出 ${result.choices.length} 份；${result.errors.length} 个镜像不可用`
        : `已列出 ${result.choices.length} 份设置文件`, result.errors.length ? "bad" : "good");
    } catch (error) {
      this.setTextStatus(status, error instanceof Error ? error.message : String(error), "bad");
    }
  }

  private renderCloudChoice(choice: SettingsFileChoice): HTMLElement {
    const row = document.createElement("div");
    row.className = "settings-file-item";
    const text = document.createElement("span");
    text.textContent = `${choice.location} · ${choice.deviceName} · ${new Date(choice.modifiedAt).toLocaleString()} · ${choice.sources.map((source) => source.transport.label).join(" / ")}`;
    const apply = document.createElement("button");
    apply.type = "button";
    apply.textContent = "预览并应用";
    apply.onclick = () => void this.applyCloudChoice(choice);
    row.append(text, apply);
    return row;
  }

  private async applyCloudChoice(choice: SettingsFileChoice): Promise<void> {
    const status = "settingsCloudStatus";
    try {
      this.setTextStatus(status, "正在下载并校验设置文件……", "");
      const document = await readSettingsChoice(choice);
      if (!document.snapshot) {
        await saveTextFile(`只读-${choice.fileName}`, document.rawText, "application/json");
        this.setTextStatus(status, `该文件格式 v${String(document.schemaVersion ?? "未知")} 当前只能下载备份，未应用。`, "bad");
        return;
      }
      if (!confirm(this.snapshotPreview(document.snapshot))) return;
      await this.downloadSnapshotAssets(document.snapshot, choice);
      this.applySnapshot(document.snapshot);
      this.setTextStatus(status, "同平台设置已应用；本机设备身份、具体系统语音、MiMo 密钥和缓存未被覆盖。", "good");
    } catch (error) {
      this.setTextStatus(status, error instanceof Error ? error.message : String(error), "bad");
    }
  }

  private async downloadSnapshotAssets(snapshot: PlatformSettingsSnapshotV1, choice: SettingsFileChoice): Promise<void> {
    for (const reference of referencedAssets(snapshot)) {
      if (await this.assets.get(reference.sha256)) continue;
      for (const source of choice.sources) {
        try {
          const remote = await source.transport.readAsset(reference);
          if (!remote.data) continue;
          await this.assets.save({ reference, bytes: remote.data });
          break;
        } catch {
          // Try the next mirror. Missing optional audio never blocks the settings file itself.
        }
      }
    }
  }

  private async uploadCloudFile(): Promise<void> {
    const status = "settingsCloudStatus";
    try {
      this.persistForm();
      const transports = this.options.getSettingsTransports(true);
      if (!transports.length) throw new Error("请先在数据同步中配置至少一个可用镜像");
      const snapshot = this.storage.createSnapshot();
      this.setTextStatus(status, "正在上传到全部启用镜像……", "");
      const result = await uploadSettingsToMirrors(
        snapshot,
        transports,
        await this.assetsForSnapshot(snapshot),
        snapshot.settings.feedback.pushLargeAttachments,
      );
      const ok = result.statuses.filter((item) => item.status === "success").length;
      const failed = result.statuses.length - ok;
      this.setTextStatus(status, `${result.fileName}：${ok} 个镜像成功${failed ? `，${failed} 个失败` : ""}${result.requiresLargeAttachmentOptIn ? "；大附件未自动推送" : ""}`, failed ? "bad" : "good");
    } catch (error) {
      this.setTextStatus(status, error instanceof Error ? error.message : String(error), "bad");
    }
  }

  private async exportSettingsFile(): Promise<void> {
    const status = "settingsFileStatus";
    try {
      this.persistForm();
      const snapshot = this.storage.createSnapshot();
      const filename = `${snapshot.sourceDevice.deviceCode}-${snapshot.platform}-settings.json`;
      const result = await saveTextFile(filename, serializePlatformSettingsSnapshot(snapshot), "application/json");
      this.setTextStatus(status, result.saved ? "设置文件已导出。" : "已取消导出。", result.saved ? "good" : "");
    } catch (error) {
      this.setTextStatus(status, error instanceof Error ? error.message : String(error), "bad");
    }
  }

  private async importSettingsFile(field: HTMLInputElement): Promise<void> {
    const file = field.files?.[0];
    field.value = "";
    if (!file) return;
    const status = "settingsFileStatus";
    try {
      const text = await file.text();
      const inspection = inspectPlatformSettingsFile(text);
      if (!inspection.supported) {
        await saveTextFile(`只读-${file.name}`, text, "application/json");
        this.setTextStatus(status, `${inspection.error ?? "未知设置格式"}；原文件已按只读备份处理。`, "bad");
        return;
      }
      const snapshot = deserializePlatformSettingsSnapshot(text);
      if (!confirm(this.snapshotPreview(snapshot))) return;
      this.applySnapshot(snapshot);
      this.setTextStatus(status, "设置文件已应用；本机专属字段保持不变。", "good");
    } catch (error) {
      this.setTextStatus(status, error instanceof Error ? error.message : String(error), "bad");
    }
  }

  private snapshotPreview(snapshot: PlatformSettingsSnapshotV1): string {
    return [
      "确认应用以下设置文件？",
      `平台：${PLATFORM_LABELS[snapshot.platform]}`,
      `来源：${snapshot.sourceDevice.deviceName} / ${snapshot.sourceDevice.location}`,
      `修改时间：${new Date(snapshot.modifiedAt).toLocaleString()}`,
      "本机设备名、地点、系统语音、MiMo 密钥和缓存不会被覆盖。",
    ].join("\n");
  }

  private applySnapshot(snapshot: PlatformSettingsSnapshotV1): void {
    this.state = this.storage.applySnapshot(snapshot);
    this.draft = clone(this.state);
    this.dirty = false;
    this.renderForm();
    void this.pruneUnusedSettingsAssets().catch(() => undefined);
    void this.applyState().catch((error) => this.report(error, "bad"));
  }
}

export async function initPlatformSettingsController(
  options: PlatformSettingsControllerOptions,
): Promise<PlatformSettingsController> {
  const controller = new PlatformSettingsController(options);
  await controller.initialize();
  return controller;
}
