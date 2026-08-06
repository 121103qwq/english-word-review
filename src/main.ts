import {
  ALGORITHM_VERSION,
  APP_VERSION,
  REQUIRED_FEATURES,
  SYNC_CONFIG_KEY,
} from "./core/config";
import { EventStore } from "./core/events";
import { mergeSnapshots } from "./core/merge";
import type { V4Snapshot } from "./core/types";
import { deleteSecret, loadSecret, saveSecret, speakEnglish } from "./platform/runtime";
import {
  GitHubTransport,
  WebDavTransport,
  syncMirrors,
  type GitHubConfig,
  type SyncTransport,
  type WebDavConfig,
} from "./sync/transports";

interface SyncMetadata {
  github: Omit<GitHubConfig, "token">;
  webdav: Omit<WebDavConfig, "password">;
}

const DEFAULT_SYNC_METADATA: SyncMetadata = {
  github: { owner: "", repo: "", branch: "main", path: "english-review/snapshot-v4.json" },
  webdav: { url: "", username: "" },
};

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
    const saved = JSON.parse(localStorage.getItem(SYNC_CONFIG_KEY) || "null") as Partial<SyncMetadata> | null;
    return {
      github: { ...DEFAULT_SYNC_METADATA.github, ...saved?.github },
      webdav: { ...DEFAULT_SYNC_METADATA.webdav, ...saved?.webdav },
    };
  } catch {
    return structuredClone(DEFAULT_SYNC_METADATA);
  }
}

function metadataFromForm(): SyncMetadata {
  return {
    github: {
      owner: byId<HTMLInputElement>("githubOwner").value.trim(),
      repo: byId<HTMLInputElement>("githubRepo").value.trim(),
      branch: byId<HTMLInputElement>("githubBranch").value.trim() || "main",
      path: byId<HTMLInputElement>("githubPath").value.trim() || "english-review/snapshot-v4.json",
    },
    webdav: {
      url: byId<HTMLInputElement>("webdavUrl").value.trim(),
      username: byId<HTMLInputElement>("webdavUsername").value.trim(),
    },
  };
}

async function persistSecret(key: string, value: string): Promise<void> {
  if (value) await saveSecret(key, value);
  else await deleteSecret(key);
}

async function saveSyncSettings(): Promise<void> {
  const metadata = metadataFromForm();
  localStorage.setItem(SYNC_CONFIG_KEY, JSON.stringify(metadata));
  await Promise.all([
    persistSecret("github-token", byId<HTMLInputElement>("githubToken").value),
    persistSecret("webdav-password", byId<HTMLInputElement>("webdavPassword").value),
  ]);
  setSyncStatus("同步设置已保存。", "good");
}

async function loadSyncForm(): Promise<void> {
  const metadata = readMetadata();
  byId<HTMLInputElement>("githubOwner").value = metadata.github.owner;
  byId<HTMLInputElement>("githubRepo").value = metadata.github.repo;
  byId<HTMLInputElement>("githubBranch").value = metadata.github.branch;
  byId<HTMLInputElement>("githubPath").value = metadata.github.path;
  byId<HTMLInputElement>("webdavUrl").value = metadata.webdav.url;
  byId<HTMLInputElement>("webdavUsername").value = metadata.webdav.username;
  const [token, password] = await Promise.all([loadSecret("github-token"), loadSecret("webdav-password")]);
  byId<HTMLInputElement>("githubToken").value = token;
  byId<HTMLInputElement>("webdavPassword").value = password;
}

async function synchronize(): Promise<void> {
  if (store.readOnly) throw new Error("当前快照只读，不能同步回写");
  await saveSyncSettings();
  const metadata = metadataFromForm();
  const token = byId<HTMLInputElement>("githubToken").value;
  const password = byId<HTMLInputElement>("webdavPassword").value;
  const transports: SyncTransport[] = [];

  if (metadata.github.owner || metadata.github.repo || token) {
    if (!metadata.github.owner || !metadata.github.repo || !token) throw new Error("GitHub 设置不完整");
    transports.push(new GitHubTransport({ ...metadata.github, token }));
  }
  if (metadata.webdav.url || metadata.webdav.username || password) {
    if (!metadata.webdav.url.startsWith("https://")) throw new Error("WebDAV 必须使用 HTTPS 文件地址");
    if (!metadata.webdav.username || !password) throw new Error("WebDAV 设置不完整");
    transports.push(new WebDavTransport({ ...metadata.webdav, password }));
  }
  if (!transports.length) throw new Error("请至少配置一个同步镜像");

  setSyncStatus("正在读取并合并各镜像……");
  const result = await syncMirrors(store.getSnapshot(), transports);
  const issue = incompatibility(result.snapshot);
  if (issue) throw new Error(`远端快照只能只读查看：${issue}`);
  store.replaceSnapshot(result.snapshot);
  const lines = result.statuses.map((status) => {
    const read = status.read === "ok" ? "读取成功" : status.read === "missing" ? "新建" : "读取失败";
    const write = status.write === "ok" ? "写入成功" : status.write === "failed" ? "写入失败" : "未写入";
    return `${status.name}: ${read}，${write}${status.message ? `（${status.message}）` : ""}`;
  });
  const summary = `${result.complete ? "同步完成" : "部分成功；失败镜像将在下次补写"}\n${lines.join("\n")}`;
  sessionStorage.setItem("english-review:last-sync", JSON.stringify({ summary, complete: result.complete }));
  legacyRuntime.applyBundle(store.project());
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
  downloadJson(store.getSnapshot(), `英语单词背诵-v4-${new Date().toISOString().slice(0, 10)}.json`);
};

byId<HTMLButtonElement>("importV4Btn").onclick = () => byId<HTMLInputElement>("importV4File").click();
byId<HTMLInputElement>("importV4File").onchange = async (event) => {
  const input = event.currentTarget as HTMLInputElement;
  const file = input.files?.[0];
  input.value = "";
  if (!file) return;
  try {
    const text = await file.text();
    const snapshot = JSON.parse(text) as V4Snapshot;
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
    legacyRuntime.applyBundle(store.project());
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
byId<HTMLButtonElement>("saveSyncBtn").onclick = () => {
  void saveSyncSettings().catch((error) => setSyncStatus(String(error), "bad"));
};
byId<HTMLButtonElement>("syncNowBtn").onclick = () => {
  void synchronize().catch((error) => setSyncStatus(error instanceof Error ? error.message : String(error), "bad"));
};

byId<HTMLButtonElement>("speakBtn").onclick = () => {
  const word = legacyRuntime.getCurrentWord();
  if (!word) return;
  void speakEnglish(word).catch((error) => alert(error instanceof Error ? error.message : String(error)));
};

byId<HTMLElement>("versionChip").textContent = `${APP_VERSION} · ${ALGORITHM_VERSION}`;
void loadSyncForm().catch((error) => setSyncStatus(`凭据读取失败：${String(error)}`, "bad"));

const lastSync = sessionStorage.getItem("english-review:last-sync");
if (lastSync) {
  sessionStorage.removeItem("english-review:last-sync");
  const parsed = JSON.parse(lastSync) as { summary: string; complete: boolean };
  byId<HTMLElement>("syncPanel").hidden = false;
  setSyncStatus(parsed.summary, parsed.complete ? "good" : "bad");
}

if (store.readOnly) {
  const snapshot = store.getSnapshot();
  const projected = JSON.stringify(store.project());
  const live = JSON.stringify(legacyRuntime.getBundle());
  const marker = `english-review:readonly-view:${snapshot.updatedAt}`;
  if (projected !== live && sessionStorage.getItem(marker) !== "applied") {
    sessionStorage.setItem(marker, "applied");
    legacyRuntime.applyBundle(store.project());
  } else {
    markReadOnly(incompatibility(snapshot) ?? "快照要求更高版本");
  }
} else {
  const projected = JSON.stringify(store.project());
  const live = JSON.stringify(legacyRuntime.getBundle());
  if (projected !== live) legacyRuntime.applyBundle(store.project());
}
