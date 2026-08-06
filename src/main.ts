import {
  ALGORITHM_VERSION,
  APP_VERSION,
  REQUIRED_FEATURES,
  SYNC_CONFIG_KEY,
} from "./core/config";
import { EventStore } from "./core/events";
import { mergeSnapshots } from "./core/merge";
import type { LegacyBundle, V4Snapshot } from "./core/types";
import { initContentManagerUi, type ContentManagerUi } from "./content/ui";
import type { ContentSnapshotV1 } from "./content/types";
import { initReviewUi } from "./review/ui";
import { BrowserCredentialVault } from "./security/browser-vault";
import { deleteSecret, isNativeRuntime, loadSecret, saveSecret, speakEnglish } from "./platform/runtime";
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

const browserVault = new BrowserCredentialVault<SyncCredentials>(localStorage);
let contentManager: ContentManagerUi | undefined;
let unlockedBrowserCredentials: SyncCredentials | undefined;

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

function downloadJson(value: unknown, filename: string): void {
  const url = URL.createObjectURL(
    new Blob([JSON.stringify(value, null, 2)], { type: "application/json;charset=utf-8" }),
  );
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
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
      delete unlockedBrowserCredentials?.webdavPasswords[config.id];
      renderWebDavForms(current.length ? current : structuredClone(DEFAULT_SYNC_METADATA.webdavs));
    };
    head.append(enabledLabel, remove);
    const fields = document.createElement("div");
    fields.className = "sync-fields";
    fields.append(
      createField("名称", "name", config.name),
      createField("HTTPS 根地址", "rootUrl", config.rootUrl, "url"),
      createField("用户名", "username", config.username),
      createField("密码", "password", unlockedBrowserCredentials?.webdavPasswords[config.id] ?? "", "password"),
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

function applyCredentials(credentials: SyncCredentials): void {
  unlockedBrowserCredentials = credentials;
  byId<HTMLInputElement>("githubToken").value = credentials.githubToken;
  for (const card of document.querySelectorAll<HTMLElement>(".webdav-config")) {
    const password = card.querySelector<HTMLInputElement>('[data-field="password"]');
    if (password) password.value = credentials.webdavPasswords[card.dataset.webdavId || ""] ?? "";
  }
}

async function persistSecret(key: string, value: string): Promise<void> {
  if (value) await saveSecret(key, value);
  else await deleteSecret(key);
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
  } else if (credentials.githubToken || Object.values(credentials.webdavPasswords).some(Boolean)) {
    const masterPassword = byId<HTMLInputElement>("syncMasterPassword").value;
    if (!masterPassword) throw new Error("请先填写主密码再保存 HTML 凭据");
    await browserVault.save(credentials, masterPassword);
    unlockedBrowserCredentials = credentials;
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
    byId<HTMLElement>("browserVaultGroup").hidden = true;
    const token = await loadSecret("github-token");
    const passwords = Object.fromEntries(await Promise.all(metadata.webdavs.map(async (config, index) => [
      config.id,
      (await loadSecret(`webdav-password:${config.id}`)) || (index === 0 ? await loadSecret("webdav-password") : ""),
    ])));
    applyCredentials({ githubToken: token, webdavPasswords: passwords });
  } else if (browserVault.hasStoredCredentials()) {
    setSyncStatus("同步凭据已加密保存；请输入主密码解锁。", "");
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

function contentStatus(result: ContentSyncResult): string {
  if (!result.statuses.length) return "词库已保存本地。";
  const detail = result.statuses.map((status) =>
    `${status.label}: ${status.write === "ok" ? "已更新" : status.queued ? "已排队" : "未写入"}${status.message ? `（${status.message}）` : ""}`);
  return `${result.complete ? "内容镜像同步完成" : "内容已保存；部分镜像等待重试"}\n${detail.join("\n")}`;
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
    legacyRuntime.applyBundle(store.project());
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

byId<HTMLButtonElement>("exportV4Btn").onclick = () => {
  if (!contentManager) return;
  const content = contentManager.getSnapshot();
  downloadJson({
    schemaVersion: 5,
    appVersion: APP_VERSION,
    exportedAt: new Date().toISOString(),
    learning: store.getSnapshot(),
    content,
    audioManifest: Object.values(content.assets),
  }, `英语单词背诵-完整-8.1-${new Date().toISOString().slice(0, 10)}.json`);
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
      downloadJson(snapshot, `只读备份-${file.name}`);
      try {
        localStorage.setItem(`english-word-review-readonly-backup-${Date.now()}`, text);
      } catch {
        // The downloaded original remains the authoritative backup if storage is full.
      }
      setSyncStatus(`未覆盖当前进度：${issue}。原文件已下载为只读备份。`, "bad");
      byId<HTMLElement>("syncPanel").hidden = false;
      return;
    }
    const merged = mergeSnapshots(store.getSnapshot(), snapshot);
    store.replaceSnapshot(merged);
    if (parsed.schemaVersion === 5) {
      if (!contentManager) throw new Error("内容存储尚未初始化");
      if (parsed.content?.schemaVersion !== 1) throw new Error("内容快照格式无效");
      await contentManager.replaceFromRemote(parsed.content);
    } else {
      legacyRuntime.applyBundle(store.project());
    }
  } catch (error) {
    setSyncStatus(`完整快照导入失败：${error instanceof Error ? error.message : String(error)}`, "bad");
    byId<HTMLElement>("syncPanel").hidden = false;
  }
};

byId<HTMLButtonElement>("syncManageBtn").onclick = () => {
  const panel = byId<HTMLElement>("syncPanel");
  panel.hidden = !panel.hidden;
  if (!panel.hidden) byId<HTMLInputElement>("githubOwner").focus();
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
  void synchronize().catch((error) => setSyncStatus(error instanceof Error ? error.message : String(error), "bad"));
};
byId<HTMLButtonElement>("unlockCredentialsBtn").onclick = () => {
  void (async () => {
    const masterPassword = byId<HTMLInputElement>("syncMasterPassword").value;
    const credentials = await browserVault.unlock(masterPassword);
    applyCredentials(credentials);
    setSyncStatus("凭据已解锁，正在自动同步……");
    await synchronize(false);
  })().catch((error) => setSyncStatus(error instanceof Error ? error.message : String(error), "bad"));
};
byId<HTMLButtonElement>("clearCredentialsBtn").onclick = () => {
  if (!confirm("只清除加密的云端凭据，不会删除本地词库。确定继续吗？")) return;
  browserVault.clear();
  applyCredentials({ githubToken: "", webdavPasswords: {} });
  byId<HTMLInputElement>("syncMasterPassword").value = "";
  setSyncStatus("凭据密文已清除；本地词库未受影响。", "good");
};

byId<HTMLButtonElement>("speakBtn").onclick = () => {
  const word = legacyRuntime.getCurrentWord();
  if (!word) return;
  void (async () => {
    if (contentManager && await contentManager.playPrimaryForWord(word)) return;
    await speakEnglish(word);
  })().catch((error) => alert(error instanceof Error ? error.message : String(error)));
};

byId<HTMLElement>("versionChip").textContent = `${APP_VERSION} · ${ALGORITHM_VERSION}`;

const lastSync = sessionStorage.getItem("english-review:last-sync");
if (lastSync) {
  sessionStorage.removeItem("english-review:last-sync");
  const parsed = JSON.parse(lastSync) as { summary: string; complete: boolean };
  byId<HTMLElement>("syncPanel").hidden = false;
  setSyncStatus(parsed.summary, parsed.complete ? "good" : "bad");
}

let reloadRequested = false;
function comparableBundle(bundle: LegacyBundle): string {
  const normalized = structuredClone(bundle);
  normalized.rootStudyStore.items.sort((left, right) => left.id.localeCompare(right.id));
  return JSON.stringify(normalized);
}

if (store.readOnly) {
  const snapshot = store.getSnapshot();
  const projected = comparableBundle(store.project());
  const live = comparableBundle(legacyRuntime.getBundle());
  const marker = `english-review:readonly-view:${snapshot.updatedAt}`;
  if (projected !== live && sessionStorage.getItem(marker) !== "applied") {
    sessionStorage.setItem(marker, "applied");
    reloadRequested = true;
    legacyRuntime.applyBundle(store.project());
  } else {
    markReadOnly(incompatibility(snapshot) ?? "快照要求更高版本");
  }
} else {
  const projected = comparableBundle(store.project());
  const live = comparableBundle(legacyRuntime.getBundle());
  if (projected !== live) {
    reloadRequested = true;
    legacyRuntime.applyBundle(store.project());
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
  });
  initReviewUi({ store, legacyRuntime });
  byId<HTMLButtonElement>("exportV4Btn").disabled = false;
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
  void initializeApplication().catch((error) => setSyncStatus(`初始化失败：${error instanceof Error ? error.message : String(error)}`, "bad"));
}
