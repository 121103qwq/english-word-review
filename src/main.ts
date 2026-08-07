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
  createNativePasswordVerifier,
  NATIVE_CREDENTIAL_PASSWORD_KEY,
  parseNativePasswordVerifier,
  verifyNativePassword,
} from "./security/native-password";
import {
  deleteSecret,
  isNativeRuntime,
  loadSecret,
  saveSecret,
  saveTextFile,
  speakEnglish,
} from "./platform/runtime";
import {
  reconcileLegacyProjection,
  type LegacyProjectionDecision,
} from "./platform/legacy-bundle";
import { retryContentOutbox, syncContentStartup, type ContentSyncResult } from "./sync/content-sync";
import {
  ContentGitHubTransport,
  ContentWebDavTransport,
  type ContentTransport,
} from "./sync/content-transports";
import {
  GitHubTransport,
  WebDavTransport,
  syncMirrors,
  type GitHubConfig,
  type SyncTransport,
  type WebDavConfig,
} from "./sync/transports";

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
let contentManager: ContentManagerUi | undefined;
let settingsController: PlatformSettingsController | undefined;
let latestReviewSpeechCandidate: { word: string; safeToSpeak: boolean } | undefined;
let currentCredentials: SyncCredentials | undefined;
let nativeCredentialVerifier: string | undefined;
let nativeCredentialVerifierInvalid = false;
let nativeCredentialsUnlocked = false;

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
      createField("密码", "password", currentCredentials?.webdavPasswords[config.id] ?? "", "password"),
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

function requireNativeCredentialsUnlocked(): void {
  if (isNativeRuntime() && !nativeCredentialsUnlocked) {
    throw new Error(nativeCredentialVerifier ? "请先输入凭据密码解锁" : "请先设置凭据密码并解锁");
  }
}

function renderNativeCredentialGate(): void {
  const hasPassword = Boolean(nativeCredentialVerifier);
  const passwordLabel = byId<HTMLElement>("nativeCredentialPasswordLabel");
  const passwordInput = byId<HTMLInputElement>("nativeCredentialPassword");
  const confirmLabel = byId<HTMLElement>("nativeCredentialConfirmLabel");
  const action = byId<HTMLButtonElement>("nativeCredentialActionBtn");

  if (nativeCredentialVerifierInvalid) {
    byId<HTMLElement>("nativeCredentialTitle").textContent = "原生凭据密码记录损坏";
    byId<HTMLElement>("nativeCredentialNote").textContent = "请清除原生同步凭据后重新设置密码。";
    passwordLabel.hidden = true;
    confirmLabel.hidden = true;
    action.hidden = true;
    return;
  }
  if (nativeCredentialsUnlocked) {
    byId<HTMLElement>("nativeCredentialTitle").textContent = "原生凭据已解锁";
    byId<HTMLElement>("nativeCredentialNote").textContent = "本次运行已可读取 Windows 凭据管理器或 Android Keystore。";
    passwordLabel.hidden = true;
    confirmLabel.hidden = true;
    action.hidden = true;
    return;
  }

  byId<HTMLElement>("nativeCredentialTitle").textContent = hasPassword ? "输入凭据密码" : "首次设置凭据密码";
  byId<HTMLElement>("nativeCredentialNote").textContent = hasPassword
    ? "输入已设置的密码后，才能读取原生安全存储中的同步凭据。"
    : "首次使用 Windows 凭据管理器或 Android Keystore 前必须设置并确认密码。";
  byId<HTMLElement>("nativeCredentialPasswordLabelText").textContent = hasPassword ? "凭据密码" : "设置密码";
  passwordInput.autocomplete = hasPassword ? "current-password" : "new-password";
  passwordLabel.hidden = false;
  confirmLabel.hidden = hasPassword;
  action.hidden = false;
  action.textContent = hasPassword ? "解锁并自动同步" : "设置密码并解锁";
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
  requireNativeCredentialsUnlocked();
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
    nativeCredentialVerifier = (await loadSecret(NATIVE_CREDENTIAL_PASSWORD_KEY)) || undefined;
    nativeCredentialVerifierInvalid = false;
    if (nativeCredentialVerifier) {
      try {
        parseNativePasswordVerifier(nativeCredentialVerifier);
      } catch {
        nativeCredentialVerifierInvalid = true;
      }
    }
    renderNativeCredentialGate();
  } else {
    byId<HTMLElement>("nativeCredentialGroup").hidden = true;
    try {
      const storedCredentials = browserCredentialStore.load();
      if (storedCredentials) {
        applyCredentials(storedCredentials);
        setSyncStatus("HTML 凭据已从本地直接加载。", "good");
      } else if (browserCredentialStore.hasLegacyEncryptedCredentials()) {
        setSyncStatus("检测到旧版加密凭据；请重新填写令牌和密码并保存。旧密文会在保存或清除时删除。", "bad");
      }
    } catch (error) {
      setSyncStatus(error instanceof Error ? error.message : String(error), "bad");
    }
  }
}

function joinUrl(rootUrl: string, path: string): string {
  return `${rootUrl.replace(/\/+$/, "")}/${path.replace(/^\/+/, "")}`;
}

function buildTransports(strict = false): { progress: SyncTransport[]; content: ContentTransport[] } {
  const metadata = metadataFromForm();
  const credentials = credentialsFromForm();
  const progress: SyncTransport[] = [];
  const content: ContentTransport[] = [];
  if (metadata.github.enabled) {
    const token = credentials.githubToken;
    if (!metadata.github.owner || !metadata.github.repo || !token) {
      if (strict) throw new Error("GitHub 设置不完整");
    } else {
      progress.push(new GitHubTransport({ ...metadata.github, token }));
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
    if (!config.rootUrl.startsWith("https://") || !config.username || !password) {
      if (strict) throw new Error(`${config.name} 设置不完整，且根地址必须使用 HTTPS`);
      continue;
    }
    progress.push(new WebDavTransport({
      url: joinUrl(config.rootUrl, "progress/snapshot-v4.json"),
      username: config.username,
      password,
    }));
    content.push(new ContentWebDavTransport({
      id: config.id,
      name: config.name,
      rootUrl: config.rootUrl,
      username: config.username,
      password,
      enabled: true,
    }));
  }
  return { progress, content };
}

/** Manual-only settings-file transports; never used by startup content sync or its outbox. */
function buildSettingsTransports(strict = false): SettingsTransport[] {
  const metadata = metadataFromForm();
  const credentials = credentialsFromForm();
  const transports: SettingsTransport[] = [];
  if (metadata.github.enabled) {
    if (!metadata.github.owner || !metadata.github.repo || !credentials.githubToken) {
      if (strict) throw new Error("GitHub 设置不完整");
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
    if (!config.rootUrl.startsWith("https://") || !config.username || !password) {
      if (strict) throw new Error(`${config.name} 设置不完整，且根地址必须使用 HTTPS`);
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
  return transports;
}

function contentStatus(result: ContentSyncResult): string {
  if (!result.statuses.length) return "词库已保存本地。";
  const detail = result.statuses.map((status) =>
    `${status.label}: ${status.write === "ok" ? "已更新" : status.queued ? "已排队" : "未写入"}${status.message ? `（${status.message}）` : ""}`);
  return `${result.complete ? "内容镜像同步完成" : "内容已保存；部分镜像等待重试"}\n${detail.join("\n")}`;
}

function openDataSyncSettings(focusField = true): void {
  const settingsUi = window.__englishReviewSettingsUi;
  if (!settingsUi) return;
  settingsUi.activateSection("data-sync");
  settingsUi.open();
  if (!focusField) return;
  window.setTimeout(() => {
    if (isNativeRuntime() && !nativeCredentialsUnlocked && !nativeCredentialVerifierInvalid) {
      byId<HTMLInputElement>("nativeCredentialPassword").focus();
    } else {
      byId<HTMLInputElement>("githubOwner").focus();
    }
  }, 0);
}

async function synchronize(saveSettings = true): Promise<void> {
  if (store.readOnly) throw new Error("当前快照只读，不能同步回写");
  if (saveSettings) await saveSyncSettings();
  const transports = buildTransports(true);
  if (!transports.progress.length || !transports.content.length) throw new Error("请至少配置一个可用同步镜像");

  setSyncStatus("正在同步学习事件与词库内容……");
  const learningResult = await syncMirrors(store.getSnapshot(), transports.progress);
  const issue = incompatibility(learningResult.snapshot);
  if (issue) throw new Error(`远端快照只能只读查看：${issue}`);
  store.replaceSnapshot(learningResult.snapshot);
  const contentResult = await syncContentStartup({
    persistence: contentManager!.backend,
    transports: transports.content,
  });
  await contentManager!.repository.pruneUnreferencedAssets();
  const lines = learningResult.statuses.map((status) => {
    const read = status.read === "ok" ? "读取成功" : status.read === "missing" ? "新建" : "读取失败";
    const write = status.write === "ok" ? "写入成功" : status.write === "failed" ? "写入失败" : "未写入";
    return `${status.name}: ${read}，${write}${status.message ? `（${status.message}）` : ""}`;
  });
  const complete = learningResult.complete && contentResult.complete;
  const summary = `${complete ? "同步完成" : "部分成功；失败镜像将在下次补写"}\n学习进度：${lines.join("\n")}\n${contentStatus(contentResult)}`;
  sessionStorage.setItem("english-review:last-sync", JSON.stringify({ summary, complete }));
  if (JSON.stringify(contentManager!.getSnapshot()) !== JSON.stringify(contentResult.snapshot)) {
    await contentManager!.acceptSynchronizedSnapshot(contentResult.snapshot);
  } else {
    reconcileProjectedLegacy(true);
  }
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
  void (async () => {
    requireNativeCredentialsUnlocked();
    await synchronize();
  })().catch((error) => setSyncStatus(error instanceof Error ? error.message : String(error), "bad"));
};
byId<HTMLButtonElement>("clearCredentialsBtn").onclick = () => {
  if (!confirm("只清除 HTML 保存的云端凭据，不会删除本地词库。确定继续吗？")) return;
  browserCredentialStore.clear();
  applyCredentials({ githubToken: "", webdavPasswords: {} });
  setSyncStatus("HTML 本地凭据已清除；本地词库未受影响。", "good");
};
byId<HTMLButtonElement>("nativeCredentialActionBtn").onclick = () => {
  void (async () => {
    if (!isNativeRuntime() || nativeCredentialVerifierInvalid) return;
    const passwordInput = byId<HTMLInputElement>("nativeCredentialPassword");
    const password = passwordInput.value;
    if (!password) throw new Error("凭据密码不能为空");
    const settingPassword = !nativeCredentialVerifier;

    if (settingPassword) {
      const confirmation = byId<HTMLInputElement>("nativeCredentialPasswordConfirm").value;
      if (password !== confirmation) throw new Error("两次输入的密码不一致");
      const verifier = await createNativePasswordVerifier(password);
      nativeCredentialVerifier = JSON.stringify(verifier);
      await saveSecret(NATIVE_CREDENTIAL_PASSWORD_KEY, nativeCredentialVerifier);
    } else if (!await verifyNativePassword(nativeCredentialVerifier!, password)) {
      throw new Error("凭据密码错误");
    }

    nativeCredentialsUnlocked = true;
    passwordInput.value = "";
    byId<HTMLInputElement>("nativeCredentialPasswordConfirm").value = "";
    renderNativeCredentialGate();
    const enteredCredentials = credentialsFromForm();
    const hasEnteredCredentials = enteredCredentials.githubToken || Object.values(enteredCredentials.webdavPasswords).some(Boolean);
    const credentials = settingPassword && hasEnteredCredentials
      ? (await saveSyncSettings(), enteredCredentials)
      : await loadNativeStoredCredentials();
    if (credentials.githubToken || Object.values(credentials.webdavPasswords).some(Boolean)) {
      setSyncStatus("原生凭据已解锁，正在自动同步……", "good");
      await synchronize(false);
    } else {
      setSyncStatus("凭据密码已设置并解锁；请填写同步凭据后保存。", "good");
    }
  })().catch((error) => setSyncStatus(error instanceof Error ? error.message : String(error), "bad"));
};
byId<HTMLButtonElement>("clearNativeCredentialsBtn").onclick = () => {
  void (async () => {
    if (!isNativeRuntime()) return;
    if (!confirm("这会清除凭据密码、GitHub Token 和所有 WebDAV 密码，但不会删除本地词库。确定继续吗？")) return;
    const ids = new Set([
      ...readMetadata().webdavs.map((config) => config.id),
      ...metadataFromForm().webdavs.map((config) => config.id),
    ]);
    await Promise.all([
      deleteSecret(NATIVE_CREDENTIAL_PASSWORD_KEY),
      deleteSecret("github-token"),
      deleteSecret("webdav-password"),
      ...[...ids].map((id) => deleteSecret(`webdav-password:${id}`)),
    ]);
    nativeCredentialVerifier = undefined;
    nativeCredentialVerifierInvalid = false;
    nativeCredentialsUnlocked = false;
    applyCredentials({ githubToken: "", webdavPasswords: {} });
    renderNativeCredentialGate();
    setSyncStatus("原生同步凭据已清除；请重新设置凭据密码。", "good");
  })().catch((error) => setSyncStatus(error instanceof Error ? error.message : String(error), "bad"));
};

byId<HTMLButtonElement>("speakBtn").onclick = () => {
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
};

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

async function refreshContentFromStorage(): Promise<void> {
  if (!contentManager) return;
  const stored = await contentManager.backend.getCurrent();
  if (stored && JSON.stringify(stored) !== JSON.stringify(contentManager.getSnapshot())) {
    await contentManager.acceptSynchronizedSnapshot(stored);
  }
}

function scheduleOutboxRetries(): void {
  for (const delay of [15_000, 60_000, 300_000]) {
    window.setTimeout(() => {
      if (!contentManager) return;
      const transports = buildTransports(false).content;
      if (!transports.length) return;
      void retryContentOutbox(contentManager.backend, transports)
        .then(async () => {
          await contentManager!.repository.pruneUnreferencedAssets();
          await refreshContentFromStorage();
        })
        .catch(() => undefined);
    }, delay);
  }
}

async function initializeApplication(): Promise<void> {
  await loadSyncForm();
  contentManager = await initContentManagerUi({
    deviceId: store.deviceId,
    legacyRuntime,
    getTransports: () => buildTransports(false).content,
    onCommitted: async (_snapshot, result) => {
      if (!result) return;
      const summary = contentStatus(result);
      setSyncStatus(summary, result.complete ? "good" : "bad");
      sessionStorage.setItem("english-review:content-status", summary);
      const transports = buildTransports(false).content;
      if (transports.length) await retryContentOutbox(contentManager!.backend, transports);
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

  const settingsButton = byId<HTMLButtonElement>("settingsOpenBtn");
  void initPlatformSettingsController({
      deviceId: store.deviceId,
      legacyRuntime,
      getSettingsTransports: buildSettingsTransports,
      playPrimaryForWord: (word) => contentManager!.playPrimaryForWord(word),
      canUseNativeSecrets: () => !isNativeRuntime() || nativeCredentialsUnlocked,
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
  const transports = buildTransports(false).content;
  if (transports.length && !store.readOnly) {
    setSyncStatus("启动检查：正在比较本地与所有内容镜像……");
    const result = await syncContentStartup({ persistence: contentManager.backend, transports });
    setSyncStatus(contentStatus(result), result.complete ? "good" : "bad");
    if (JSON.stringify(result.snapshot) !== JSON.stringify(contentManager.getSnapshot())) {
      await contentManager.acceptSynchronizedSnapshot(result.snapshot);
      return;
    }
    await retryContentOutbox(contentManager.backend, transports);
    await contentManager.repository.pruneUnreferencedAssets();
  }
  scheduleOutboxRetries();
}

byId<HTMLButtonElement>("exportV4Btn").disabled = true;
if (!reloadRequested) {
  if (startupMismatchSkipped) {
    reportRepeatedLegacyRefresh();
  }
  void initializeApplication().catch((error) => setSyncStatus(`初始化失败：${error instanceof Error ? error.message : String(error)}`, "bad"));
}
