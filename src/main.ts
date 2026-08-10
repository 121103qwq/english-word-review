import {
  ALGORITHM_VERSION,
  APP_VERSION,
  REQUIRED_FEATURES,
  SYNC_CONFIG_KEY,
} from "./core/config";
import { EventStore } from "./core/events";
import { mergeSnapshots } from "./core/merge";
import type { V4Snapshot } from "./core/types";
import { initContentManagerUi, type ContentManagerUi } from "./content/ui";
import type { ContentSnapshotV1 } from "./content/types";
import { initReviewUi } from "./review/ui";
import {
  initPlatformSettingsController,
  type PlatformSettingsController,
} from "./settings/controller";
import {
  SettingsGitHubTransport,
  SettingsWebDavTransport,
  type SettingsTransport,
} from "./settings/transports";
import { BrowserCredentialStore } from "./security/browser-vault";
import {
  deleteSecret,
  getRuntimePlatform,
  isNativeRuntime,
  loadSecret,
  openExternalUrl,
  saveSecret,
  saveTextFile,
  speakEnglish,
} from "./platform/runtime";
import {
  GitHubReleaseUpdateChecker,
  type AvailableUpdate,
} from "./platform/update-check";
import {
  reconcileLegacyProjection,
  type LegacyProjectionDecision,
} from "./platform/legacy-bundle";
import type { ContentSyncResult } from "./sync/content-sync";
import {
  ContentGitHubTransport,
  ContentWebDavTransport,
  type ContentTransport,
} from "./sync/content-transports";
import {
  type GitHubConfig,
} from "./sync/transports";
import { IndexedDbLibrarySyncArchive } from "./sync/library-archive";
import {
  LibraryFileGitHubTransport,
  LibraryFileWebDavTransport,
  downloadLibrarySyncSelection,
  librarySyncAssetSelection,
  listLibrarySyncChoices,
  uploadLibrarySyncSelection,
  type CloudLibraryFileChoice,
  type LibraryFileTransport,
  type LibrarySyncChoiceModel,
} from "./sync/library-file-transports";
import {
  createLibrarySyncBatch,
  mergeLibrarySyncFilesIntoContent,
  type LibraryContentSyncFileV1,
  type LibrarySyncBatchV1,
  type LibrarySyncFileV1,
} from "./sync/library-files";

interface GitHubMetadata extends Omit<GitHubConfig, "token"> {
  enabled: boolean;
}

interface WebDavMetadata {
  id: string;
  name: string;
  rootUrl: string;
  username: string;
  enabled: boolean;
}

interface SyncMetadata {
  github: GitHubMetadata;
  webdavs: WebDavMetadata[];
}

interface SyncCredentials {
  githubToken: string;
  webdavPasswords: Record<string, string>;
}

const DEFAULT_SYNC_METADATA: SyncMetadata = {
  github: {
    enabled: true,
    owner: "121103qwq",
    repo: "english-word-review-data",
    branch: "main",
    path: "progress/snapshot-v4.json",
  },
  webdavs: [{ id: "webdav-primary", name: "WebDAV 1", rootUrl: "", username: "", enabled: false }],
};

const browserCredentialStore = new BrowserCredentialStore<SyncCredentials>(localStorage);
const librarySyncArchive = new IndexedDbLibrarySyncArchive();
let contentManager: ContentManagerUi | undefined;
let settingsController: PlatformSettingsController | undefined;
let latestReviewSpeechCandidate: { word: string; safeToSpeak: boolean } | undefined;
let currentCredentials: SyncCredentials | undefined;
let currentLibrarySyncBatch: LibrarySyncBatchV1 | undefined;
let currentLibrarySyncChoices: LibrarySyncChoiceModel | undefined;
let currentLibrarySyncTransports: LibraryFileTransport[] = [];
const updateChecker = new GitHubReleaseUpdateChecker(APP_VERSION, getRuntimePlatform());
let availableUpdate: AvailableUpdate | undefined;

const byId = <T extends HTMLElement>(id: string): T => {
  const element = document.getElementById(id);
  if (!element) throw new Error(`缺少界面元素：${id}`);
  return element as T;
};

const legacy = window.__englishReviewLegacy;
if (!legacy) throw new Error("旧版学习界面尚未初始化");
const legacyRuntime = legacy;

const store = EventStore.open(legacyRuntime.getBundle());
window.__v8Bridge = {
  answer: (payload) => store.recordAnswer(payload),
  undo: (targetEventId) => store.recordUndo(targetEventId),
  reset: (scope) => store.recordReset(scope),
  setting: (key, value) => store.recordSetting(key, value),
  intensiveSelection: (words, reviewedLibraryId) => store.recordIntensiveSelection(words, reviewedLibraryId),
  replaceLibraries: (libraries, activeLibraryId) => store.replaceLibraries(libraries, activeLibraryId),
};
window.__englishReviewSaveTextFile = saveTextFile;

async function downloadJson(value: unknown, filename: string): Promise<boolean> {
  const result = await saveTextFile(filename, JSON.stringify(value, null, 2), "application/json");
  return result.saved;
}

function versionCompare(left: string, right: string): number {
  const a = left.split(".").map(Number);
  const b = right.split(".").map(Number);
  for (let index = 0; index < Math.max(a.length, b.length); index += 1) {
    const difference = (a[index] ?? 0) - (b[index] ?? 0);
    if (difference) return difference;
  }
  return 0;
}

function incompatibility(snapshot: V4Snapshot): string | null {
  if (snapshot.schemaVersion !== 4) return `不支持 schemaVersion ${String(snapshot.schemaVersion)}`;
  if (snapshot.algorithmVersion !== ALGORITHM_VERSION) {
    return `不支持算法 ${String(snapshot.algorithmVersion)}`;
  }
  if (versionCompare(snapshot.minReaderVersion, APP_VERSION) > 0) {
    return `至少需要阅读器 ${snapshot.minReaderVersion}`;
  }
  const unknown = snapshot.requiredFeatures.filter(
    (feature) => !REQUIRED_FEATURES.includes(feature as typeof REQUIRED_FEATURES[number]),
  );
  return unknown.length ? `未知必需功能：${unknown.join("、")}` : null;
}

function setSyncStatus(message: string, kind: "" | "good" | "bad" = ""): void {
  const status = byId<HTMLSpanElement>("syncStatus");
  status.textContent = message;
  status.className = `sync-status ${kind}`.trim();
}

function readMetadata(): SyncMetadata {
  try {
    const saved = JSON.parse(localStorage.getItem(SYNC_CONFIG_KEY) || "null") as
      (Partial<SyncMetadata> & { webdav?: { url?: string; username?: string } }) | null;
    const webdavs = Array.isArray(saved?.webdavs)
      ? saved.webdavs
      : saved?.webdav
        ? [{
            id: "webdav-primary",
            name: "WebDAV 1",
            rootUrl: saved.webdav.url ?? "",
            username: saved.webdav.username ?? "",
            enabled: Boolean(saved.webdav.url),
          }]
        : DEFAULT_SYNC_METADATA.webdavs;
    return {
      github: { ...DEFAULT_SYNC_METADATA.github, ...saved?.github },
      webdavs: webdavs.map((config, index) => ({
        id: config.id || `webdav-${index + 1}`,
        name: config.name || `WebDAV ${index + 1}`,
        rootUrl: config.rootUrl || "",
        username: config.username || "",
        enabled: Boolean(config.enabled),
      })),
    };
  } catch {
    return structuredClone(DEFAULT_SYNC_METADATA);
  }
}

function createField(labelText: string, field: string, value: string, type = "text"): HTMLLabelElement {
  const label = document.createElement("label");
  label.textContent = labelText;
  const input = document.createElement("input");
  input.dataset.field = field;
  input.type = type;
  input.value = value;
  input.autocomplete = "off";
  label.append(input);
  return label;
}

function renderWebDavForms(configs: WebDavMetadata[]): void {
  const host = byId("webdavConfigs");
  host.replaceChildren();
  configs.forEach((config, index) => {
    const card = document.createElement("div");
    card.className = "webdav-config";
    card.dataset.webdavId = config.id;
    const head = document.createElement("div");
    head.className = "webdav-config-head";
    const enabledLabel = document.createElement("label");
    const enabled = document.createElement("input");
    enabled.type = "checkbox";
    enabled.dataset.field = "enabled";
    enabled.checked = config.enabled;
    enabledLabel.append(enabled, document.createTextNode("启用"));
    const remove = document.createElement("button");
    remove.type = "button";
    remove.textContent = "移除";
    remove.disabled = configs.length === 1;
    remove.onclick = () => {
      const current = metadataFromForm().webdavs.filter((item) => item.id !== config.id);
      delete currentCredentials?.webdavPasswords[config.id];
      renderWebDavForms(current.length ? current : structuredClone(DEFAULT_SYNC_METADATA.webdavs));
    };
    head.append(enabledLabel, remove);
    const fields = document.createElement("div");
    fields.className = "sync-fields";
    fields.append(
      createField("名称", "name", config.name),
      createField("HTTPS 根地址", "rootUrl", config.rootUrl, "url"),
      createField("用户名", "username", config.username),
      createField("WebDAV 服务密码（匿名服务可留空）", "password", currentCredentials?.webdavPasswords[config.id] ?? "", "password"),
    );
    if (index === 0) fields.querySelector<HTMLInputElement>('[data-field="rootUrl"]')!.id = "webdavUrl";
    card.append(head, fields);
    host.append(card);
  });
}

function metadataFromForm(): SyncMetadata {
  return {
    github: {
      enabled: byId<HTMLInputElement>("githubEnabled").checked,
      owner: byId<HTMLInputElement>("githubOwner").value.trim(),
      repo: byId<HTMLInputElement>("githubRepo").value.trim(),
      branch: byId<HTMLInputElement>("githubBranch").value.trim() || "main",
      path: byId<HTMLInputElement>("githubPath").value.trim() || "progress/snapshot-v4.json",
    },
    webdavs: [...document.querySelectorAll<HTMLElement>(".webdav-config")].map((card, index) => ({
      id: card.dataset.webdavId || `webdav-${index + 1}`,
      name: card.querySelector<HTMLInputElement>('[data-field="name"]')?.value.trim() || `WebDAV ${index + 1}`,
      rootUrl: card.querySelector<HTMLInputElement>('[data-field="rootUrl"]')?.value.trim().replace(/\/+$/, "") || "",
      username: card.querySelector<HTMLInputElement>('[data-field="username"]')?.value.trim() || "",
      enabled: Boolean(card.querySelector<HTMLInputElement>('[data-field="enabled"]')?.checked),
    })),
  };
}

function credentialsFromForm(): SyncCredentials {
  return {
    githubToken: byId<HTMLInputElement>("githubToken").value,
    webdavPasswords: Object.fromEntries(
      [...document.querySelectorAll<HTMLElement>(".webdav-config")].map((card) => [
        card.dataset.webdavId || "",
        card.querySelector<HTMLInputElement>('[data-field="password"]')?.value ?? "",
      ]),
    ),
  };
}

function applyCredentials(credentials: unknown): void {
  const candidate = credentials as Partial<SyncCredentials> | null;
  const passwords = candidate?.webdavPasswords && typeof candidate.webdavPasswords === "object"
    ? Object.fromEntries(Object.entries(candidate.webdavPasswords).filter((entry): entry is [string, string] => typeof entry[1] === "string"))
    : {};
  const normalized: SyncCredentials = {
    githubToken: typeof candidate?.githubToken === "string" ? candidate.githubToken : "",
    webdavPasswords: passwords,
  };
  currentCredentials = normalized;
  byId<HTMLInputElement>("githubToken").value = normalized.githubToken;
  for (const card of document.querySelectorAll<HTMLElement>(".webdav-config")) {
    const password = card.querySelector<HTMLInputElement>('[data-field="password"]');
    if (password) password.value = normalized.webdavPasswords[card.dataset.webdavId || ""] ?? "";
  }
}

async function persistSecret(key: string, value: string): Promise<void> {
  if (value) await saveSecret(key, value);
  else await deleteSecret(key);
}

async function loadNativeStoredCredentials(metadata = readMetadata()): Promise<SyncCredentials> {
  const token = await loadSecret("github-token");
  const passwords = Object.fromEntries(await Promise.all(metadata.webdavs.map(async (config, index) => [
    config.id,
    (await loadSecret(`webdav-password:${config.id}`)) || (index === 0 ? await loadSecret("webdav-password") : ""),
  ])));
  const credentials = { githubToken: token, webdavPasswords: passwords };
  applyCredentials(credentials);
  return credentials;
}

async function saveSyncSettings(): Promise<void> {
  const metadata = metadataFromForm();
  localStorage.setItem(SYNC_CONFIG_KEY, JSON.stringify(metadata));
  const credentials = credentialsFromForm();
  if (isNativeRuntime()) {
    await Promise.all([
      persistSecret("github-token", credentials.githubToken),
      ...metadata.webdavs.map((config) => persistSecret(`webdav-password:${config.id}`, credentials.webdavPasswords[config.id] ?? "")),
    ]);
  } else {
    if (credentials.githubToken || Object.values(credentials.webdavPasswords).some(Boolean)) {
      browserCredentialStore.save(credentials);
    } else {
      browserCredentialStore.clear();
    }
    currentCredentials = credentials;
  }
  setSyncStatus("同步设置已保存。", "good");
}

async function loadSyncForm(): Promise<void> {
  const metadata = readMetadata();
  byId<HTMLInputElement>("githubEnabled").checked = metadata.github.enabled;
  byId<HTMLInputElement>("githubOwner").value = metadata.github.owner;
  byId<HTMLInputElement>("githubRepo").value = metadata.github.repo;
  byId<HTMLInputElement>("githubBranch").value = metadata.github.branch;
  byId<HTMLInputElement>("githubPath").value = metadata.github.path;
  renderWebDavForms(metadata.webdavs.length ? metadata.webdavs : structuredClone(DEFAULT_SYNC_METADATA.webdavs));
  if (isNativeRuntime()) {
    byId<HTMLElement>("browserCredentialGroup").hidden = true;
    byId<HTMLElement>("nativeCredentialGroup").hidden = false;
    await loadNativeStoredCredentials(metadata);
    setSyncStatus("原生同步凭据已从系统安全存储直接加载。", "good");
  } else {
    byId<HTMLElement>("nativeCredentialGroup").hidden = true;
    try {
      const storedCredentials = browserCredentialStore.load();
      if (storedCredentials) {
        applyCredentials(storedCredentials);
        setSyncStatus("HTML 凭据已从本地直接加载。", "good");
      } else if (browserCredentialStore.hasLegacyEncryptedCredentials()) {
        setSyncStatus("检测到旧版加密凭据；请重新填写令牌和服务密码并保存。旧密文会作为兼容备份保留，只有点击清除凭据才会删除。", "bad");
      }
    } catch (error) {
      setSyncStatus(error instanceof Error ? error.message : String(error), "bad");
    }
  }
}

function buildLegacyContentTransports(strict = false): ContentTransport[] {
  const metadata = metadataFromForm();
  const credentials = credentialsFromForm();
  const content: ContentTransport[] = [];
  if (metadata.github.enabled) {
    const token = credentials.githubToken;
    if (!metadata.github.owner || !metadata.github.repo || !token) {
      if (strict) throw new Error("GitHub 设置不完整");
    } else {
      content.push(new ContentGitHubTransport({
        id: "github",
        owner: metadata.github.owner,
        repo: metadata.github.repo,
        branch: metadata.github.branch,
        token,
      }));
    }
  }
  for (const config of metadata.webdavs.filter((item) => item.enabled)) {
    const password = credentials.webdavPasswords[config.id] ?? "";
    if (!config.rootUrl.startsWith("https://")) {
      continue;
    }
    content.push(new ContentWebDavTransport({
      id: config.id,
      name: config.name,
      rootUrl: config.rootUrl,
      username: config.username,
      password,
      enabled: true,
    }));
  }
  if (strict && !content.length) throw new Error("请至少完整配置一个 GitHub 或 HTTPS WebDAV 镜像");
  return content;
}

function buildLibraryFileTransports(strict = false): LibraryFileTransport[] {
  const metadata = metadataFromForm();
  const credentials = credentialsFromForm();
  const transports: LibraryFileTransport[] = [];
  if (metadata.github.enabled) {
    const token = credentials.githubToken;
    if (!metadata.github.owner || !metadata.github.repo || !token) {
      // An incomplete enabled mirror must not block another valid mirror.
    } else {
      transports.push(new LibraryFileGitHubTransport({
        id: "github-library-files",
        owner: metadata.github.owner,
        repo: metadata.github.repo,
        branch: metadata.github.branch,
        token,
      }));
    }
  }
  for (const config of metadata.webdavs.filter((item) => item.enabled)) {
    if (!config.rootUrl.startsWith("https://")) continue;
    transports.push(new LibraryFileWebDavTransport({
      id: `library-${config.id}`,
      name: config.name,
      rootUrl: config.rootUrl,
      username: config.username,
      password: credentials.webdavPasswords[config.id] ?? "",
    }));
  }
  if (strict && !transports.length) throw new Error("请至少完整配置一个 GitHub 或 HTTPS WebDAV 镜像");
  return transports;
}

/** Manual-only settings-file transports; never used by startup content sync or its outbox. */
function buildSettingsTransports(strict = false): SettingsTransport[] {
  const metadata = metadataFromForm();
  const credentials = credentialsFromForm();
  const transports: SettingsTransport[] = [];
  if (metadata.github.enabled) {
    if (!metadata.github.owner || !metadata.github.repo || !credentials.githubToken) {
      // An incomplete enabled mirror must not block another valid mirror.
    } else {
      transports.push(new SettingsGitHubTransport({
        id: "github-settings",
        owner: metadata.github.owner,
        repo: metadata.github.repo,
        branch: metadata.github.branch,
        token: credentials.githubToken,
      }));
    }
  }
  for (const config of metadata.webdavs.filter((item) => item.enabled)) {
    const password = credentials.webdavPasswords[config.id] ?? "";
    if (!config.rootUrl.startsWith("https://")) {
      continue;
    }
    transports.push(new SettingsWebDavTransport({
      id: `settings-${config.id}`,
      name: config.name,
      rootUrl: config.rootUrl,
      username: config.username,
      password,
    }));
  }
  if (strict && !transports.length) throw new Error("请至少完整配置一个 GitHub 或 HTTPS WebDAV 镜像");
  return transports;
}

function contentStatus(result: ContentSyncResult): string {
  if (!result.statuses.length) return "词库已保存本地。";
  const detail = result.statuses.map((status) =>
    `${status.label}: ${status.write === "ok" ? "已更新" : status.queued ? "已排队" : "未写入"}${status.message ? `（${status.message}）` : ""}`);
  return `${result.complete ? "内容镜像同步完成" : "内容已保存；部分镜像等待重试"}\n${detail.join("\n")}`;
}

function closeUpdatePrompt(): void {
  byId<HTMLElement>("updateModal").hidden = true;
  availableUpdate = undefined;
}

function showUpdatePrompt(update: AvailableUpdate): void {
  const promptKey = `english-review:update-prompted:${update.version}`;
  if (sessionStorage.getItem(promptKey)) return;
  sessionStorage.setItem(promptKey, "1");
  availableUpdate = update;
  byId("updateVersionText").textContent = `当前版本 ${APP_VERSION}，最新正式版 ${update.version}。`;
  byId("updateAssetText").textContent = update.assetName
    ? `将打开适合当前平台的文件：${update.assetName}`
    : "未找到当前平台的专用文件，将打开 GitHub Release 页面。";
  byId<HTMLElement>("updateModal").hidden = false;
  byId<HTMLElement>("updateDialog").focus();
}

async function checkForApplicationUpdate(): Promise<void> {
  try {
    const update = await updateChecker.check();
    if (update) showUpdatePrompt(update);
  } catch {
    // Update checks must never block offline startup or manual synchronization.
  }
}

function openDataSyncSettings(focusField = true): void {
  const settingsUi = window.__englishReviewSettingsUi;
  if (!settingsUi) return;
  settingsUi.activateSection("data-sync");
  settingsUi.open();
  void checkForApplicationUpdate();
  if (!focusField) return;
  window.setTimeout(() => {
    byId<HTMLInputElement>("githubOwner").focus();
  }, 0);
}

function syncIdentity(): { deviceCode: string; deviceName?: string; location: string } {
  const stored = settingsController?.storage.loadOrCreate().deviceLocal.identity;
  const deviceCode = byId<HTMLInputElement>("settingsDeviceCode").value.trim() || stored?.deviceCode || "";
  const deviceName = byId<HTMLInputElement>("settingsDeviceName").value.trim() || stored?.deviceName || "";
  const location = byId<HTMLInputElement>("settingsLocation").value.trim() || stored?.location || "";
  if (!deviceCode) throw new Error("设备编码尚未初始化，请稍后再试");
  if (!location) throw new Error("主动同步前，请先在“设置文件同步”中填写当前地点");
  return { deviceCode, ...(deviceName ? { deviceName } : {}), location };
}

function formatBytes(value: number): string {
  if (value >= 1024 * 1024 * 1024) return `${(value / (1024 * 1024 * 1024)).toFixed(2)} GiB`;
  if (value >= 1024 * 1024) return `${(value / (1024 * 1024)).toFixed(1)} MiB`;
  if (value >= 1024) return `${(value / 1024).toFixed(1)} KiB`;
  return `${value} B`;
}

function syncFileTitle(file: LibrarySyncFileV1): string {
  if (file.kind === "learning-progress") return "学习进度（事件合并）";
  return `${file.library.date} · ${file.library.name || `${file.library.words.length} 词`}`;
}

function syncFileMeta(file: LibrarySyncFileV1): string {
  const source = file.sourceDevice;
  const device = source.deviceName ? `${source.deviceCode} / ${source.deviceName}` : source.deviceCode;
  const detail = file.kind === "learning-progress"
    ? `${file.snapshot.events.length} 个未压缩学习事件`
    : `${file.library.words.length} 个单词`;
  return `${device} · ${source.location} · ${new Date(file.createdAt).toLocaleString()} · ${detail}`;
}

function comparisonText(choice: CloudLibraryFileChoice): { text: string; kind: "" | "good" | "warn" } {
  if (choice.comparison === "identical") return { text: "本机同时间副本：内容与进度一致", kind: "good" };
  if (choice.comparison === "same-words-progress-different" || choice.comparison === "same-minute-progress-different") {
    return { text: "本机同时间副本：单词相同，但学习进度不同", kind: "warn" };
  }
  if (choice.comparison === "same-words-content-different") {
    return { text: "本机同时间副本：单词相同，但释义、词根或音频不同", kind: "warn" };
  }
  if (choice.comparison === "different-words") return { text: "本机同时间副本：单词列表不同", kind: "warn" };
  return { text: "本机没有同时间副本", kind: "" };
}

function syncChoiceRow(
  side: "local" | "cloud",
  fileName: string,
  file: LibrarySyncFileV1,
  state: { text: string; kind: "" | "good" | "warn" },
): HTMLElement {
  const label = document.createElement("label");
  label.className = "data-sync-file-item";
  const checkbox = document.createElement("input");
  checkbox.type = "checkbox";
  checkbox.value = fileName;
  checkbox.dataset.syncSide = side;
  const body = document.createElement("span");
  const title = document.createElement("span");
  title.className = "data-sync-file-title";
  title.textContent = syncFileTitle(file);
  const meta = document.createElement("span");
  meta.className = "data-sync-file-meta";
  meta.textContent = `${syncFileMeta(file)}\n${fileName}`;
  const badge = document.createElement("span");
  badge.className = `data-sync-file-state ${state.kind}`.trim();
  badge.textContent = state.text;
  body.append(title, meta, badge);
  label.append(checkbox, body);
  return label;
}

function renderLibrarySyncChoices(model: LibrarySyncChoiceModel): void {
  const localHost = byId("dataSyncLocalList");
  const cloudHost = byId("dataSyncCloudList");
  localHost.replaceChildren();
  cloudHost.replaceChildren();
  const local = [...model.local].sort((left, right) =>
    right.file.createdAt.localeCompare(left.file.createdAt) || left.fileName.localeCompare(right.fileName));
  if (!local.length) {
    const empty = document.createElement("div");
    empty.className = "data-sync-file-empty";
    empty.textContent = "本机没有可上传的词库或进度文件。";
    localHost.append(empty);
  } else {
    for (const choice of local) {
      const current = choice.file.batchId === currentLibrarySyncBatch?.batchId;
      const state = choice.existingCloudCopies
        ? { text: `云端已有 ${choice.existingCloudCopies} 份相同文件`, kind: "good" as const }
        : { text: current ? "本机当前批次，尚未上传" : "本机历史副本，可重新上传", kind: "" as const };
      localHost.append(syncChoiceRow("local", choice.fileName, choice.file, state));
    }
  }
  if (!model.cloud.length && !model.legacy.length) {
    const empty = document.createElement("div");
    empty.className = "data-sync-file-empty";
    empty.textContent = "云端没有找到多文件同步记录。";
    cloudHost.append(empty);
  } else {
    for (const choice of model.cloud) {
      cloudHost.append(syncChoiceRow("cloud", choice.fileName, choice.file, comparisonText(choice)));
    }
    for (const legacy of model.legacy) {
      const item = document.createElement("div");
      item.className = "data-sync-file-item";
      const spacer = document.createElement("span");
      const body = document.createElement("span");
      const title = document.createElement("span");
      title.className = "data-sync-file-title";
      title.textContent = `${legacy.label} · 旧版整份内容快照`;
      const meta = document.createElement("span");
      meta.className = "data-sync-file-meta";
      meta.textContent = "仅用于兼容检查，不会自动覆盖或混入当前词库。";
      const backup = document.createElement("button");
      backup.type = "button";
      backup.textContent = "另存只读备份";
      backup.onclick = () => void downloadJson(legacy.snapshot, `旧版内容只读备份-${legacy.transportId}.json`);
      body.append(title, meta, backup);
      item.append(spacer, body);
      cloudHost.append(item);
    }
  }
  const message = model.errors.length
    ? `已列出本机 ${model.local.length} 项、云端 ${model.cloud.length} 项；${model.errors.length} 个镜像读取失败。`
    : `已列出本机 ${model.local.length} 项、云端 ${model.cloud.length} 项。`;
  const status = byId<HTMLElement>("dataSyncSelectionStatus");
  status.textContent = message;
  status.className = `sync-status ${model.errors.length ? "bad" : "good"}`;
}

async function refreshLibrarySyncChoices(): Promise<void> {
  if (!contentManager) throw new Error("词库尚未初始化");
  currentLibrarySyncTransports = buildLibraryFileTransports(true);
  const identity = syncIdentity();
  currentLibrarySyncBatch = createLibrarySyncBatch(contentManager.getSnapshot(), store.getSnapshot(), identity);
  byId("dataSyncLocalList").innerHTML = '<div class="data-sync-file-empty">正在整理本机词库……</div>';
  byId("dataSyncCloudList").innerHTML = '<div class="data-sync-file-empty">正在读取云端文件……</div>';
  byId<HTMLElement>("dataSyncSelectionIdentity").textContent =
    `${identity.deviceCode}${identity.deviceName ? ` / ${identity.deviceName}` : ""} · ${identity.location}`;
  currentLibrarySyncChoices = await listLibrarySyncChoices(
    currentLibrarySyncBatch,
    currentLibrarySyncTransports,
    buildLegacyContentTransports(false),
    { archive: librarySyncArchive },
  );
  renderLibrarySyncChoices(currentLibrarySyncChoices);
}

async function openLibrarySyncPicker(): Promise<void> {
  if (store.readOnly) throw new Error("当前快照只读，只能导出备份，不能应用同步文件");
  await saveSyncSettings();
  const modal = byId<HTMLElement>("dataSyncSelectionModal");
  modal.hidden = false;
  byId<HTMLInputElement>("dataSyncIncludeAssets").checked = false;
  byId<HTMLElement>("dataSyncSelectionStatus").textContent = "正在准备手动同步列表……";
  byId<HTMLElement>("dataSyncSelectionDialog").focus();
  await refreshLibrarySyncChoices();
}

function closeLibrarySyncPicker(): void {
  byId<HTMLElement>("dataSyncSelectionModal").hidden = true;
  currentLibrarySyncBatch = undefined;
  currentLibrarySyncChoices = undefined;
  currentLibrarySyncTransports = [];
}

function reportLibrarySyncPickerError(error: unknown): void {
  const message = error instanceof Error ? error.message : String(error);
  setSyncStatus(message, "bad");
  const status = byId<HTMLElement>("dataSyncSelectionStatus");
  status.textContent = message;
  status.className = "sync-status bad";
  if (!byId<HTMLElement>("dataSyncSelectionModal").hidden) {
    byId<HTMLElement>("dataSyncSelectionIdentity").textContent = "同步列表尚未读取";
    byId("dataSyncLocalList").innerHTML = '<div class="data-sync-file-empty">配置完整镜像后可列出本机文件。</div>';
    byId("dataSyncCloudList").innerHTML = '<div class="data-sync-file-empty">尚未读取云端文件。</div>';
  }
}

async function applyDownloadedSyncFiles(files: LibrarySyncFileV1[]): Promise<void> {
  if (!contentManager || !files.length) return;
  const progress = files.filter((file) => file.kind === "learning-progress");
  if (progress.length) {
    let merged = store.getSnapshot();
    for (const file of progress) {
      const issue = incompatibility(file.snapshot);
      if (issue) throw new Error(`学习进度文件只能只读查看：${issue}`);
      merged = mergeSnapshots(merged, file.snapshot);
    }
    store.replaceSnapshot(merged);
  }
  const libraries = files.filter((file): file is LibraryContentSyncFileV1 => file.kind === "library");
  if (libraries.length) {
    const next = await contentManager.repository.commit((draft) => {
      mergeLibrarySyncFilesIntoContent(draft, libraries);
    });
    await contentManager.acceptSynchronizedSnapshot(next);
    await contentManager.repository.pruneUnreferencedAssets();
  } else if (progress.length) {
    reconcileProjectedLegacy(true);
  }
}

async function executeLibrarySyncSelection(): Promise<void> {
  if (!currentLibrarySyncBatch || !currentLibrarySyncChoices || !contentManager) {
    throw new Error("请先刷新同步列表");
  }
  const localNames = [...document.querySelectorAll<HTMLInputElement>('[data-sync-side="local"]:checked')]
    .map((input) => input.value);
  const cloudNames = [...document.querySelectorAll<HTMLInputElement>('[data-sync-side="cloud"]:checked')]
    .map((input) => input.value);
  if (!localNames.length && !cloudNames.length) throw new Error("请至少勾选一个要上传或下载的文件");
  const includeAssets = byId<HTMLInputElement>("dataSyncIncludeAssets").checked;
  const status = byId<HTMLElement>("dataSyncSelectionStatus");
  const selectedLocalFiles = currentLibrarySyncChoices.local
    .filter((choice) => localNames.includes(choice.fileName)).map((choice) => choice.file);
  const selectedCloudFiles = currentLibrarySyncChoices.cloud
    .filter((choice) => cloudNames.includes(choice.fileName)).map((choice) => choice.file);
  const assets = librarySyncAssetSelection([...selectedLocalFiles, ...selectedCloudFiles]);
  status.textContent = `正在执行：上传 ${localNames.length} 项，下载 ${cloudNames.length} 项${includeAssets ? `，同时传输 ${assets.assets.size} 个 MP3（${formatBytes(assets.totalBytes)}）` : ""}……`;
  status.className = "sync-status";
  const uploads = localNames.length
    ? await uploadLibrarySyncSelection(currentLibrarySyncBatch, localNames, currentLibrarySyncTransports, {
        archive: librarySyncArchive,
        assetBackend: contentManager.backend,
        includeAssets,
      })
    : [];
  const downloaded = cloudNames.length
    ? await downloadLibrarySyncSelection(currentLibrarySyncChoices.cloud, cloudNames, {
        archive: librarySyncArchive,
        assetBackend: contentManager.backend,
        includeAssets,
      })
    : [];
  const failed = uploads.filter((result) => result.status === "failed");
  const summary = `${failed.length ? "同步部分成功" : "同步完成"}：上传 ${localNames.length} 个文件，下载 ${downloaded.length} 个文件${includeAssets ? `，处理 MP3 ${assets.assets.size} 个` : "；MP3 未勾选传输"}${failed.length ? `。失败镜像：${failed.map((item) => `${item.label}（${item.message ?? "未知错误"}）`).join("、")}` : ""}`;
  sessionStorage.setItem("english-review:last-sync", JSON.stringify({ summary, complete: failed.length === 0 }));
  await applyDownloadedSyncFiles(downloaded);
  setSyncStatus(summary, failed.length ? "bad" : "good");
  status.textContent = summary;
  status.className = `sync-status ${failed.length ? "bad" : "good"}`;
  await refreshLibrarySyncChoices();
}

function markReadOnly(reason: string): void {
  document.body.classList.add("read-only");
  const banner = document.createElement("div");
  banner.className = "readonly-banner";
  banner.textContent = `只读保护：${reason}。可以查看和导出完整备份，但不会写回学习数据或远端镜像。`;
  document.querySelector("main")?.prepend(banner);
  byId<HTMLButtonElement>("syncNowBtn").disabled = true;
}

byId<HTMLButtonElement>("exportV4Btn").onclick = async (event) => {
  if (!contentManager) return;
  const button = event.currentTarget as HTMLButtonElement;
  button.blur();
  button.disabled = true;
  try {
    const content = contentManager.getSnapshot();
    await downloadJson({
      schemaVersion: 5,
      appVersion: APP_VERSION,
      exportedAt: new Date().toISOString(),
      learning: store.getSnapshot(),
      content,
      audioManifest: Object.values(content.assets),
    }, `英语单词背诵-完整-${APP_VERSION}-${new Date().toISOString().slice(0, 10)}.json`);
  } catch (error) {
    setSyncStatus(`完整快照导出失败：${error instanceof Error ? error.message : String(error)}`, "bad");
    openDataSyncSettings(false);
  } finally {
    button.disabled = false;
  }
};

byId<HTMLButtonElement>("importV4Btn").onclick = () => byId<HTMLInputElement>("importV4File").click();
byId<HTMLInputElement>("importV4File").onchange = async (event) => {
  const input = event.currentTarget as HTMLInputElement;
  const file = input.files?.[0];
  input.value = "";
  if (!file) return;
  try {
    const text = await file.text();
    const parsed = JSON.parse(text) as V4Snapshot | {
      schemaVersion: 5;
      learning: V4Snapshot;
      content: ContentSnapshotV1;
    };
    const snapshot = parsed.schemaVersion === 5 ? parsed.learning : parsed;
    const issue = incompatibility(snapshot);
    if (issue) {
      const saved = await downloadJson(snapshot, `只读备份-${file.name}`);
      try {
        localStorage.setItem(`english-word-review-readonly-backup-${Date.now()}`, text);
      } catch {
        // The downloaded original remains the authoritative backup if storage is full.
      }
      setSyncStatus(
        `未覆盖当前进度：${issue}。${saved ? "原文件已另存为只读备份。" : "已取消另存文件。"}`,
        "bad",
      );
      openDataSyncSettings(false);
      return;
    }
    const merged = mergeSnapshots(store.getSnapshot(), snapshot);
    store.replaceSnapshot(merged);
    if (parsed.schemaVersion === 5) {
      if (!contentManager) throw new Error("内容存储尚未初始化");
      if (parsed.content?.schemaVersion !== 1) throw new Error("内容快照格式无效");
      await contentManager.replaceFromRemote(parsed.content);
    } else {
      reconcileProjectedLegacy(true);
    }
  } catch (error) {
    setSyncStatus(`完整快照导入失败：${error instanceof Error ? error.message : String(error)}`, "bad");
    openDataSyncSettings(false);
  }
};

byId<HTMLButtonElement>("syncManageBtn").onclick = () => {
  openDataSyncSettings();
};
byId<HTMLButtonElement>("updateLaterBtn").onclick = closeUpdatePrompt;
byId<HTMLButtonElement>("updateNowBtn").onclick = (event) => {
  const update = availableUpdate;
  if (!update) return;
  const button = event.currentTarget as HTMLButtonElement;
  button.disabled = true;
  void openExternalUrl(update.downloadUrl)
    .then(closeUpdatePrompt)
    .catch((error) => {
      byId("updateAssetText").textContent = `无法打开更新地址：${error instanceof Error ? error.message : String(error)}`;
    })
    .finally(() => { button.disabled = false; });
};
byId<HTMLElement>("updateModal").onclick = (event) => {
  if (event.target === event.currentTarget) closeUpdatePrompt();
};
byId<HTMLButtonElement>("addWebdavBtn").onclick = () => {
  const current = metadataFromForm().webdavs;
  current.push({
    id: crypto.randomUUID(),
    name: `WebDAV ${current.length + 1}`,
    rootUrl: "",
    username: "",
    enabled: true,
  });
  renderWebDavForms(current);
  document.querySelector<HTMLElement>(".webdav-config:last-child input")?.focus();
};
byId<HTMLButtonElement>("saveSyncBtn").onclick = () => {
  void saveSyncSettings().catch((error) => setSyncStatus(String(error), "bad"));
};
byId<HTMLButtonElement>("syncNowBtn").onclick = () => {
  void openLibrarySyncPicker().catch(reportLibrarySyncPickerError);
};
byId<HTMLButtonElement>("dataSyncSelectionCloseBtn").onclick = closeLibrarySyncPicker;
byId<HTMLButtonElement>("dataSyncSelectionRefreshBtn").onclick = () => {
  void refreshLibrarySyncChoices().catch(reportLibrarySyncPickerError);
};
byId<HTMLButtonElement>("dataSyncSelectionApplyBtn").onclick = (event) => {
  const button = event.currentTarget as HTMLButtonElement;
  button.disabled = true;
  void executeLibrarySyncSelection()
    .catch((error) => {
      const status = byId<HTMLElement>("dataSyncSelectionStatus");
      status.textContent = error instanceof Error ? error.message : String(error);
      status.className = "sync-status bad";
    })
    .finally(() => { button.disabled = false; });
};
byId<HTMLElement>("dataSyncSelectionModal").onclick = (event) => {
  if (event.target === event.currentTarget) closeLibrarySyncPicker();
};
document.addEventListener("keydown", (event) => {
  if (event.key !== "Escape" || byId<HTMLElement>("dataSyncSelectionModal").hidden) return;
  event.preventDefault();
  event.stopImmediatePropagation();
  closeLibrarySyncPicker();
}, true);
document.addEventListener("keydown", (event) => {
  if (event.key !== "Escape" || byId<HTMLElement>("updateModal").hidden) return;
  event.preventDefault();
  event.stopImmediatePropagation();
  closeUpdatePrompt();
}, true);
byId<HTMLButtonElement>("clearCredentialsBtn").onclick = () => {
  if (!confirm("只清除 HTML 保存的云端凭据，不会删除本地词库。确定继续吗？")) return;
  browserCredentialStore.clear();
  applyCredentials({ githubToken: "", webdavPasswords: {} });
  setSyncStatus("HTML 本地凭据已清除；本地词库未受影响。", "good");
};
byId<HTMLButtonElement>("clearNativeCredentialsBtn").onclick = () => {
  void (async () => {
    if (!isNativeRuntime()) return;
    if (!confirm("这会清除 GitHub Token 和所有 WebDAV 服务凭据，但不会删除本地词库。确定继续吗？")) return;
    const ids = new Set([
      ...readMetadata().webdavs.map((config) => config.id),
      ...metadataFromForm().webdavs.map((config) => config.id),
    ]);
    await Promise.all([
      deleteSecret("github-token"),
      deleteSecret("webdav-password"),
      ...[...ids].map((id) => deleteSecret(`webdav-password:${id}`)),
    ]);
    applyCredentials({ githubToken: "", webdavPasswords: {} });
    setSyncStatus("原生同步凭据已清除；本地词库未受影响。", "good");
  })().catch((error) => setSyncStatus(error instanceof Error ? error.message : String(error), "bad"));
};

const runtimePlatform = getRuntimePlatform();
document.documentElement.dataset.runtimePlatform = runtimePlatform;
const androidSpeakButton = byId<HTMLButtonElement>("androidSpeakBtn");
androidSpeakButton.hidden = runtimePlatform !== "android";

function updateAndroidSpeakButton(safeToSpeak: boolean): void {
  if (runtimePlatform !== "android") return;
  androidSpeakButton.disabled = !safeToSpeak;
  androidSpeakButton.title = safeToSpeak ? "朗读当前单词" : "当前题型朗读会泄露答案";
}

function speakCurrentQuestion(): void {
  void (async () => {
    if (settingsController) {
      await settingsController.speakWord();
      return;
    }
    const word = legacyRuntime.getCurrentWord();
    if (!word) return;
    if (contentManager && await contentManager.playPrimaryForWord(word)) return;
    await speakEnglish(word);
  })().catch((error) => alert(error instanceof Error ? error.message : String(error)));
}

byId<HTMLButtonElement>("speakBtn").onclick = speakCurrentQuestion;
androidSpeakButton.onclick = (event) => {
  (event.currentTarget as HTMLButtonElement).blur();
  speakCurrentQuestion();
};
legacyRuntime.subscribeQuestion((question) => updateAndroidSpeakButton(question.safeToSpeak));

byId<HTMLElement>("versionChip").textContent = `${APP_VERSION} · ${ALGORITHM_VERSION}`;

const lastSync = sessionStorage.getItem("english-review:last-sync");
if (lastSync) {
  sessionStorage.removeItem("english-review:last-sync");
  const parsed = JSON.parse(lastSync) as { summary: string; complete: boolean };
  openDataSyncSettings(false);
  setSyncStatus(parsed.summary, parsed.complete ? "good" : "bad");
}

let reloadRequested = false;
let startupMismatchSkipped = false;

function reconcileProjectedLegacy(reportContinue = false): LegacyProjectionDecision {
  const decision = reconcileLegacyProjection(
    store.project(),
    legacyRuntime.getBundle(),
    sessionStorage,
    (bundle) => {
      reloadRequested = true;
      legacyRuntime.applyBundle(bundle);
    },
  );
  if (decision === "continue" && reportContinue) reportRepeatedLegacyRefresh();
  return decision;
}

function reportRepeatedLegacyRefresh(): void {
  openDataSyncSettings(false);
  setSyncStatus("已阻止内容投影重复刷新，应用将继续初始化；建议导出完整快照备份。", "bad");
}

if (store.readOnly) {
  const snapshot = store.getSnapshot();
  const decision = reconcileProjectedLegacy();
  if (decision !== "apply") {
    startupMismatchSkipped = decision === "continue";
    markReadOnly(incompatibility(snapshot) ?? "快照要求更高版本");
  }
} else {
  const decision = reconcileProjectedLegacy();
  if (decision !== "apply") {
    startupMismatchSkipped = decision === "continue";
  }
}

async function initializeApplication(): Promise<void> {
  await loadSyncForm();
  contentManager = await initContentManagerUi({
    deviceId: store.deviceId,
    legacyRuntime,
    // Content changes stay local until the user explicitly selects files to upload.
    getTransports: () => [],
    onCommitted: async (_snapshot, result) => {
      if (!result) return;
      const summary = contentStatus(result);
      setSyncStatus(summary, "good");
      sessionStorage.setItem("english-review:content-status", summary);
    },
    onLegacyProjectionReady: async () => {
      reconcileProjectedLegacy(true);
    },
  });
  const materializedDecision = reconcileProjectedLegacy();
  if (materializedDecision === "apply") {
    return;
  }
  if (materializedDecision === "continue") {
    reportRepeatedLegacyRefresh();
  }
  initReviewUi({
    store,
    legacyRuntime,
    onSpeakCandidate: (word, safeToSpeak) => {
      latestReviewSpeechCandidate = { word, safeToSpeak };
      updateAndroidSpeakButton(safeToSpeak);
      settingsController?.onQuestion(word, safeToSpeak);
    },
    onAnswerFeedback: (correct) => settingsController?.onAnswerFeedback(correct),
    onClose: () => {
      latestReviewSpeechCandidate = undefined;
      const state = legacyRuntime.getModeState();
      if (state.mode === "review") legacyRuntime.requestMode(state.studyMode);
    },
  });
  byId<HTMLButtonElement>("exportV4Btn").disabled = false;
  void checkForApplicationUpdate();

  const settingsButton = byId<HTMLButtonElement>("settingsOpenBtn");
  void initPlatformSettingsController({
      deviceId: store.deviceId,
      legacyRuntime,
      getSettingsTransports: buildSettingsTransports,
      playPrimaryForWord: (word) => contentManager!.playPrimaryForWord(word),
      reportStatus: setSyncStatus,
    })
    .then((controller) => {
      settingsController = controller;
      if (legacyRuntime.getModeState().mode === "review" && latestReviewSpeechCandidate) {
        controller.onQuestion(latestReviewSpeechCandidate.word, latestReviewSpeechCandidate.safeToSpeak);
      }
      settingsButton.disabled = false;
      settingsButton.title = "设置";
    })
    .catch((error) => {
      settingsButton.disabled = true;
      settingsButton.title = "个性化设置初始化失败";
      setSyncStatus(`个性化设置初始化失败，学习功能仍可使用：${error instanceof Error ? error.message : String(error)}`, "bad");
    });
}

byId<HTMLButtonElement>("exportV4Btn").disabled = true;
if (!reloadRequested) {
  if (startupMismatchSkipped) {
    reportRepeatedLegacyRefresh();
  }
  void initializeApplication().catch((error) => setSyncStatus(`初始化失败：${error instanceof Error ? error.message : String(error)}`, "bad"));
}
