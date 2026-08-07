import { httpRequest, type HttpRequest } from "../platform/runtime";
import type { PlatformKind, PlatformSettingsSnapshotV1, SettingsAssetReference } from "./types";
import {
  selectSettingsAttachments,
  type StoredSettingsAsset,
} from "./assets";

export const SETTINGS_FILES_PER_LOCATION = 30;
export const SETTINGS_ROOT_PATH = "settings";
export const SETTINGS_ASSETS_PATH = "settings/assets";
export const MIMO_PRIVATE_KEY_PATH = "private-credentials/mimo-api-key.json";

export interface SettingsFileRef {
  id: string;
  fileName: string;
  path: string;
  platform: PlatformKind;
  deviceCode: string;
  deviceName: string;
  location: string;
  modifiedAt: string;
  revision?: string;
}

export interface SettingsRemoteDocument {
  snapshot: PlatformSettingsSnapshotV1 | null;
  rawText: string;
  schemaVersion?: number;
  revision?: string;
}

export interface SettingsWriteResult {
  ref: SettingsFileRef;
  revision?: string;
}

export interface SettingsAssetDocument {
  data: Uint8Array | null;
  revision?: string;
}

export interface SettingsTransport {
  readonly id: string;
  readonly kind: "github" | "webdav";
  readonly label: string;
  listSettings(platform: PlatformKind): Promise<SettingsFileRef[]>;
  readSettings(ref: SettingsFileRef): Promise<SettingsRemoteDocument>;
  writeSettings(snapshot: PlatformSettingsSnapshotV1, fileName?: string): Promise<SettingsWriteResult>;
  deleteSettings(ref: SettingsFileRef): Promise<void>;
  readAsset(reference: SettingsAssetReference): Promise<SettingsAssetDocument>;
  writeAsset(asset: StoredSettingsAsset): Promise<void>;
}

export interface SettingsGitHubConfig {
  id?: string;
  owner: string;
  repo: string;
  branch?: string;
  token: string;
  settingsRootPath?: string;
  settingsAssetsPath?: string;
  privateCredentialsPath?: string;
  randomSuffix?: () => string;
}

export interface SettingsWebDavConfig {
  id: string;
  name: string;
  rootUrl: string;
  username: string;
  password: string;
  settingsRootPath?: string;
  settingsAssetsPath?: string;
  randomSuffix?: () => string;
}

function request(method: HttpRequest["method"], url: string, headers?: Record<string, string>, body?: string, bodyBase64?: string) {
  return httpRequest({ url, method, headers, body, bodyBase64 });
}

function normalizedPath(path: string): string {
  return path.replace(/^\/+|\/+$/g, "");
}

function encodePath(path: string): string {
  return path.split("/").filter(Boolean).map(encodeURIComponent).join("/");
}

function joinUrl(rootUrl: string, path: string): string {
  return `${rootUrl.replace(/\/+$/, "")}/${encodePath(path)}`;
}

function utf8ToBase64(value: string): string {
  const bytes = new TextEncoder().encode(value);
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function base64ToUtf8(value: string): string {
  const binary = atob(value.replace(/\s/g, ""));
  return new TextDecoder().decode(Uint8Array.from(binary, (character) => character.charCodeAt(0)));
}

function bytesToBase64(value: Uint8Array): string {
  let binary = "";
  for (let offset = 0; offset < value.length; offset += 0x8000) {
    binary += String.fromCharCode(...value.subarray(offset, offset + 0x8000));
  }
  return btoa(binary);
}

function base64ToBytes(value: string): Uint8Array {
  const binary = atob(value.replace(/\s/g, ""));
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

function randomSuffix(): string {
  const bytes = new Uint8Array(2);
  globalThis.crypto.getRandomValues(bytes);
  return [...bytes].map((part) => part.toString(16).padStart(2, "0")).join("").toUpperCase();
}

export function settingsFileSlug(value: string, fallback: string): string {
  const slug = value.normalize("NFKC").trim()
    .replace(/[<>:"/\\|?*\u0000-\u001f_]+/g, "-")
    .replace(/\s+/g, "-")
    .replace(/-+/g, "-")
    .replace(/^-|-$/g, "")
    .slice(0, 48);
  return slug || fallback;
}

function minuteStamp(value: string): string {
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) throw new Error("设置修改时间无效");
  const part = (number: number) => String(number).padStart(2, "0");
  return `${date.getFullYear()}${part(date.getMonth() + 1)}${part(date.getDate())}-${part(date.getHours())}${part(date.getMinutes())}`;
}

export function settingsSnapshotFileName(
  snapshot: PlatformSettingsSnapshotV1,
  suffix = randomSuffix(),
): string {
  const device = snapshot.sourceDevice;
  const code = settingsFileSlug(device.deviceCode, "DEVICE").toUpperCase();
  const name = settingsFileSlug(device.deviceName, "未命名设备");
  const location = settingsFileSlug(device.location, "未设置地点");
  const nonce = settingsFileSlug(suffix, "0000").slice(0, 8).toUpperCase();
  return `${code}_${name}_${location}_${minuteStamp(snapshot.modifiedAt)}_${nonce}.json`;
}

function timestampFromStamp(value: string): string {
  const match = value.match(/^(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})$/);
  if (!match) return new Date(0).toISOString();
  const [, year, month, day, hour, minute] = match;
  const date = new Date(Number(year), Number(month) - 1, Number(day), Number(hour), Number(minute));
  return date.toISOString();
}

function refFromFile(platform: PlatformKind, path: string, revision?: string): SettingsFileRef | null {
  const fileName = path.split("/").pop() ?? "";
  const match = fileName.match(/^([^_]+)_([^_]+)_([^_]+)_(\d{8}-\d{4})_([^_]+)\.json$/i);
  if (!match) return null;
  return {
    id: path,
    fileName,
    path,
    platform,
    deviceCode: match[1],
    deviceName: match[2].replace(/-/g, " "),
    location: match[3].replace(/-/g, " "),
    modifiedAt: timestampFromStamp(match[4]),
    revision,
  };
}

function supportedSnapshot(rawText: string, expectedPlatform: PlatformKind): SettingsRemoteDocument {
  const value = JSON.parse(rawText) as Partial<PlatformSettingsSnapshotV1> & { schemaVersion?: number };
  const schemaVersion = typeof value?.schemaVersion === "number" ? value.schemaVersion : undefined;
  if (
    schemaVersion !== 1 ||
    value.platform !== expectedPlatform ||
    typeof value.appVersion !== "string" ||
    typeof value.modifiedAt !== "string" ||
    typeof value.revisionId !== "string" ||
    !value.sourceDevice ||
    !value.settings
  ) {
    return { snapshot: null, rawText, schemaVersion };
  }
  return { snapshot: value as PlatformSettingsSnapshotV1, rawText, schemaVersion };
}

function extension(reference: SettingsAssetReference): string {
  if (reference.mimeType === "audio/wav") return "wav";
  if (reference.mimeType === "audio/ogg") return "ogg";
  return "mp3";
}

async function verifyAsset(data: Uint8Array, reference: SettingsAssetReference): Promise<Uint8Array> {
  if (data.byteLength !== reference.byteLength) throw new Error("设置附件长度校验失败");
  const digest = await globalThis.crypto.subtle.digest("SHA-256", data as BufferSource);
  const actual = [...new Uint8Array(digest)].map((part) => part.toString(16).padStart(2, "0")).join("");
  if (actual !== reference.sha256.toLowerCase()) throw new Error("设置附件哈希校验失败");
  return data;
}

function assertSnapshot(snapshot: PlatformSettingsSnapshotV1): void {
  if (snapshot.schemaVersion !== 1) throw new Error("只能上传设置快照 v1");
  if (!(["windows", "android", "html"] as string[]).includes(snapshot.platform)) throw new Error("设置平台无效");
}

async function pruneLocation(transport: SettingsTransport, snapshot: PlatformSettingsSnapshotV1): Promise<void> {
  const expected = settingsFileSlug(snapshot.sourceDevice.location, "未设置地点").replace(/-/g, " ");
  const refs = (await transport.listSettings(snapshot.platform))
    .filter((ref) => ref.location === expected)
    .sort((left, right) => right.modifiedAt.localeCompare(left.modifiedAt) || right.fileName.localeCompare(left.fileName));
  for (const expired of refs.slice(SETTINGS_FILES_PER_LOCATION)) await transport.deleteSettings(expired);
}

export class SettingsGitHubTransport implements SettingsTransport {
  readonly id: string;
  readonly kind = "github" as const;
  readonly label = "GitHub";
  private readonly settingsRoot: string;
  private readonly assetsPath: string;
  private readonly privateCredentialsPath: string;
  private readonly suffix: () => string;

  constructor(private readonly config: SettingsGitHubConfig) {
    this.id = config.id ?? "github-settings";
    this.settingsRoot = normalizedPath(config.settingsRootPath ?? SETTINGS_ROOT_PATH);
    this.assetsPath = normalizedPath(config.settingsAssetsPath ?? SETTINGS_ASSETS_PATH);
    this.privateCredentialsPath = normalizedPath(config.privateCredentialsPath ?? MIMO_PRIVATE_KEY_PATH);
    this.suffix = config.randomSuffix ?? randomSuffix;
  }

  private endpoint(path: string): string {
    return `https://api.github.com/repos/${encodeURIComponent(this.config.owner)}/${encodeURIComponent(this.config.repo)}/contents/${encodePath(path)}`;
  }

  private headers(contentType = "application/json"): Record<string, string> {
    return {
      Accept: "application/vnd.github+json",
      Authorization: `Bearer ${this.config.token}`,
      "X-GitHub-Api-Version": "2022-11-28",
      "Content-Type": contentType,
    };
  }

  private async readPath(path: string): Promise<{ text: string; sha: string } | null> {
    const response = await request("GET", `${this.endpoint(path)}?ref=${encodeURIComponent(this.config.branch || "main")}`, this.headers());
    if (response.status === 404) return null;
    if (response.status < 200 || response.status >= 300) throw new Error(`GitHub 设置读取失败（${response.status}）`);
    const body = JSON.parse(response.body) as { content: string; sha: string };
    return { text: base64ToUtf8(body.content), sha: body.sha };
  }

  async listSettings(platform: PlatformKind): Promise<SettingsFileRef[]> {
    const directory = `${this.settingsRoot}/${platform}`;
    const response = await request("GET", `${this.endpoint(directory)}?ref=${encodeURIComponent(this.config.branch || "main")}`, this.headers());
    if (response.status === 404) return [];
    if (response.status < 200 || response.status >= 300) throw new Error(`GitHub 设置列表读取失败（${response.status}）`);
    const files = JSON.parse(response.body) as Array<{ name: string; path: string; sha: string; type: string }>;
    return files.flatMap((file) => {
      if (file.type !== "file") return [];
      const ref = refFromFile(platform, file.path, file.sha);
      return ref ? [ref] : [];
    });
  }

  async readSettings(ref: SettingsFileRef): Promise<SettingsRemoteDocument> {
    const result = await this.readPath(ref.path);
    if (!result) throw new Error(`GitHub 设置文件不存在：${ref.fileName}`);
    return { ...supportedSnapshot(result.text, ref.platform), revision: result.sha };
  }

  async writeSettings(snapshot: PlatformSettingsSnapshotV1, fileName = settingsSnapshotFileName(snapshot, this.suffix())): Promise<SettingsWriteResult> {
    assertSnapshot(snapshot);
    const path = `${this.settingsRoot}/${snapshot.platform}/${fileName}`;
    const response = await request("PUT", this.endpoint(path), this.headers(), JSON.stringify({
      message: `Sync ${snapshot.platform} settings ${snapshot.modifiedAt}`,
      content: utf8ToBase64(JSON.stringify(snapshot)),
      branch: this.config.branch || "main",
    }));
    if (response.status < 200 || response.status >= 300) throw new Error(`GitHub 设置写入失败（${response.status}）`);
    const body = JSON.parse(response.body) as { content?: { sha?: string } };
    const ref = refFromFile(snapshot.platform, path, body.content?.sha);
    if (!ref) throw new Error("设置文件名无效");
    await pruneLocation(this, snapshot);
    return { ref, revision: body.content?.sha };
  }

  async deleteSettings(ref: SettingsFileRef): Promise<void> {
    let revision = ref.revision;
    if (!revision) revision = (await this.readPath(ref.path))?.sha;
    if (!revision) return;
    const response = await request("DELETE", this.endpoint(ref.path), this.headers(), JSON.stringify({
      message: `Prune settings file ${ref.fileName}`,
      sha: revision,
      branch: this.config.branch || "main",
    }));
    if (response.status === 404) return;
    if (response.status < 200 || response.status >= 300) throw new Error(`GitHub 设置删除失败（${response.status}）`);
  }

  async readAsset(reference: SettingsAssetReference): Promise<SettingsAssetDocument> {
    const path = `${this.assetsPath}/${reference.sha256}.${extension(reference)}`;
    const response = await request("GET", `${this.endpoint(path)}?ref=${encodeURIComponent(this.config.branch || "main")}`, this.headers());
    if (response.status === 404) return { data: null };
    if (response.status < 200 || response.status >= 300) throw new Error(`GitHub 设置附件读取失败（${response.status}）`);
    const body = JSON.parse(response.body) as { content: string; sha: string };
    return { data: await verifyAsset(base64ToBytes(body.content), reference), revision: body.sha };
  }

  async writeAsset(asset: StoredSettingsAsset): Promise<void> {
    await verifyAsset(asset.bytes, asset.reference);
    if ((await this.readAsset(asset.reference)).data) return;
    const path = `${this.assetsPath}/${asset.reference.sha256}.${extension(asset.reference)}`;
    const response = await request("PUT", this.endpoint(path), this.headers(), JSON.stringify({
      message: `Sync settings attachment ${asset.reference.sha256}`,
      content: bytesToBase64(asset.bytes),
      branch: this.config.branch || "main",
    }));
    if (response.status < 200 || response.status >= 300) throw new Error(`GitHub 设置附件写入失败（${response.status}）`);
  }

  /** Exact-path access only; normal settings/content listing never visits this directory. */
  async readMimoApiKey(): Promise<string | null> {
    const result = await this.readPath(this.privateCredentialsPath);
    if (!result) return null;
    try {
      const value = JSON.parse(result.text) as { apiKey?: unknown } | string;
      const key = typeof value === "string" ? value : value.apiKey;
      return typeof key === "string" && key.trim() ? key.trim() : null;
    } catch {
      const key = result.text.trim();
      return key || null;
    }
  }
}

function xmlEntities(value: string): string {
  return value.replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">");
}

export class SettingsWebDavTransport implements SettingsTransport {
  readonly id: string;
  readonly kind = "webdav" as const;
  readonly label: string;
  private readonly settingsRoot: string;
  private readonly assetsPath: string;
  private readonly suffix: () => string;

  constructor(private readonly config: SettingsWebDavConfig) {
    const root = new URL(config.rootUrl);
    if (root.protocol !== "https:") throw new Error("WebDAV 地址必须使用 HTTPS");
    if (root.username || root.password) throw new Error("WebDAV 用户名和密码不能写在地址中");
    if (!config.id.trim()) throw new Error("WebDAV 配置 ID 不能为空");
    this.id = config.id;
    this.label = config.name || "WebDAV";
    this.settingsRoot = normalizedPath(config.settingsRootPath ?? SETTINGS_ROOT_PATH);
    this.assetsPath = normalizedPath(config.settingsAssetsPath ?? SETTINGS_ASSETS_PATH);
    this.suffix = config.randomSuffix ?? randomSuffix;
  }

  private headers(contentType = "application/json; charset=utf-8"): Record<string, string> {
    return {
      Authorization: `Basic ${utf8ToBase64(`${this.config.username}:${this.config.password}`)}`,
      "Content-Type": contentType,
    };
  }

  private async ensureCollection(path: string): Promise<void> {
    const segments = path.split("/").filter(Boolean);
    for (let index = 1; index <= segments.length; index += 1) {
      const response = await request("MKCOL", joinUrl(this.config.rootUrl, segments.slice(0, index).join("/")), this.headers());
      if (![201, 301, 405].includes(response.status) && (response.status < 200 || response.status >= 300)) {
        throw new Error(`WebDAV 无法创建设置目录（${response.status}）`);
      }
    }
  }

  async listSettings(platform: PlatformKind): Promise<SettingsFileRef[]> {
    const directory = `${this.settingsRoot}/${platform}`;
    await this.ensureCollection(directory);
    const response = await request(
      "PROPFIND",
      joinUrl(this.config.rootUrl, directory),
      { ...this.headers("application/xml; charset=utf-8"), Depth: "1" },
      '<?xml version="1.0"?><propfind xmlns="DAV:"><prop><getetag/></prop></propfind>',
    );
    if (response.status === 404) return [];
    if (response.status !== 207 && (response.status < 200 || response.status >= 300)) {
      throw new Error(`WebDAV 设置列表读取失败（${response.status}）`);
    }
    const refs: SettingsFileRef[] = [];
    const blocks = response.body.match(/<(?:[A-Za-z]+:)?response\b[\s\S]*?<\/(?:[A-Za-z]+:)?response>/gi) ?? [];
    for (const block of blocks) {
      const href = block.match(/<(?:[A-Za-z]+:)?href\b[^>]*>([\s\S]*?)<\/(?:[A-Za-z]+:)?href>/i)?.[1];
      if (!href) continue;
      const pathname = decodeURIComponent(xmlEntities(href)).replace(/[?#].*$/, "");
      const fileName = pathname.split("/").filter(Boolean).pop() ?? "";
      if (!fileName.endsWith(".json")) continue;
      const etag = block.match(/<(?:[A-Za-z]+:)?getetag\b[^>]*>([\s\S]*?)<\/(?:[A-Za-z]+:)?getetag>/i)?.[1];
      const ref = refFromFile(platform, `${directory}/${fileName}`, etag ? xmlEntities(etag.trim()) : undefined);
      if (ref) refs.push(ref);
    }
    return refs;
  }

  async readSettings(ref: SettingsFileRef): Promise<SettingsRemoteDocument> {
    const response = await request("GET", joinUrl(this.config.rootUrl, ref.path), this.headers());
    if (response.status === 404) throw new Error(`WebDAV 设置文件不存在：${ref.fileName}`);
    if (response.status < 200 || response.status >= 300) throw new Error(`WebDAV 设置读取失败（${response.status}）`);
    return { ...supportedSnapshot(response.body, ref.platform), revision: response.headers.etag };
  }

  async writeSettings(snapshot: PlatformSettingsSnapshotV1, fileName = settingsSnapshotFileName(snapshot, this.suffix())): Promise<SettingsWriteResult> {
    assertSnapshot(snapshot);
    const directory = `${this.settingsRoot}/${snapshot.platform}`;
    await this.ensureCollection(directory);
    const path = `${directory}/${fileName}`;
    const headers = { ...this.headers(), "If-None-Match": "*" };
    const response = await request("PUT", joinUrl(this.config.rootUrl, path), headers, JSON.stringify(snapshot));
    if (response.status < 200 || response.status >= 300) throw new Error(`WebDAV 设置写入失败（${response.status}）`);
    const ref = refFromFile(snapshot.platform, path, response.headers.etag);
    if (!ref) throw new Error("设置文件名无效");
    await pruneLocation(this, snapshot);
    return { ref, revision: response.headers.etag };
  }

  async deleteSettings(ref: SettingsFileRef): Promise<void> {
    const headers = this.headers();
    if (ref.revision) headers["If-Match"] = ref.revision;
    const response = await request("DELETE", joinUrl(this.config.rootUrl, ref.path), headers);
    if (response.status === 404) return;
    if (response.status < 200 || response.status >= 300) throw new Error(`WebDAV 设置删除失败（${response.status}）`);
  }

  async readAsset(reference: SettingsAssetReference): Promise<SettingsAssetDocument> {
    const path = `${this.assetsPath}/${reference.sha256}.${extension(reference)}`;
    const response = await httpRequest({
      url: joinUrl(this.config.rootUrl, path),
      method: "GET",
      headers: this.headers("application/octet-stream"),
      responseType: "base64",
    });
    if (response.status === 404) return { data: null };
    if (response.status < 200 || response.status >= 300) throw new Error(`WebDAV 设置附件读取失败（${response.status}）`);
    return { data: await verifyAsset(base64ToBytes(response.body), reference), revision: response.headers.etag };
  }

  async writeAsset(asset: StoredSettingsAsset): Promise<void> {
    await verifyAsset(asset.bytes, asset.reference);
    if ((await this.readAsset(asset.reference)).data) return;
    await this.ensureCollection(this.assetsPath);
    const path = `${this.assetsPath}/${asset.reference.sha256}.${extension(asset.reference)}`;
    const response = await request(
      "PUT",
      joinUrl(this.config.rootUrl, path),
      { ...this.headers(asset.reference.mimeType), "If-None-Match": "*" },
      undefined,
      bytesToBase64(asset.bytes),
    );
    if (response.status < 200 || response.status >= 300) throw new Error(`WebDAV 设置附件写入失败（${response.status}）`);
  }
}

export interface SettingsFileSource {
  transport: SettingsTransport;
  ref: SettingsFileRef;
}

export interface SettingsFileChoice extends SettingsFileRef {
  sources: SettingsFileSource[];
}

/** Manual list operation. It never runs from content startup/outbox sync. */
export async function listSettingsChoices(
  transports: SettingsTransport[],
  platform: PlatformKind,
  currentLocation: string,
): Promise<{ choices: SettingsFileChoice[]; errors: Array<{ transportId: string; message: string }> }> {
  const errors: Array<{ transportId: string; message: string }> = [];
  const results = await Promise.all(transports.map(async (transport) => {
    try {
      return { transport, refs: await transport.listSettings(platform) };
    } catch (error) {
      errors.push({ transportId: transport.id, message: error instanceof Error ? error.message : String(error) });
      return { transport, refs: [] };
    }
  }));
  const merged = new Map<string, SettingsFileChoice>();
  for (const { transport, refs } of results) {
    for (const ref of refs) {
      const existing = merged.get(ref.fileName);
      if (existing) existing.sources.push({ transport, ref });
      else merged.set(ref.fileName, { ...ref, sources: [{ transport, ref }] });
    }
  }
  const current = settingsFileSlug(currentLocation, "未设置地点").replace(/-/g, " ");
  const choices = [...merged.values()].sort((left, right) => {
    const leftCurrent = left.location === current ? 1 : 0;
    const rightCurrent = right.location === current ? 1 : 0;
    return rightCurrent - leftCurrent || right.modifiedAt.localeCompare(left.modifiedAt) || left.fileName.localeCompare(right.fileName);
  });
  return { choices, errors };
}

export async function readSettingsChoice(choice: SettingsFileChoice): Promise<SettingsRemoteDocument> {
  let lastError: unknown;
  for (const source of choice.sources) {
    try {
      return await source.transport.readSettings(source.ref);
    } catch (error) {
      lastError = error;
    }
  }
  throw lastError instanceof Error ? lastError : new Error("所有设置镜像均不可用");
}

export interface SettingsMirrorUploadStatus {
  transportId: string;
  label: string;
  status: "success" | "failed";
  uploadedAttachments: number;
  omittedAttachments: number;
  message?: string;
}

export interface SettingsMirrorUploadResult {
  fileName: string;
  statuses: SettingsMirrorUploadStatus[];
  partialSuccess: boolean;
  requiresLargeAttachmentOptIn: boolean;
}

/** Manual fan-out only: no retry queue and no content outbox integration. */
export async function uploadSettingsToMirrors(
  snapshot: PlatformSettingsSnapshotV1,
  transports: SettingsTransport[],
  assets: StoredSettingsAsset[] = [],
  includeLargeAttachments = false,
  suffix = randomSuffix(),
): Promise<SettingsMirrorUploadResult> {
  assertSnapshot(snapshot);
  const fileName = settingsSnapshotFileName(snapshot, suffix);
  const selection = selectSettingsAttachments(assets, includeLargeAttachments);
  const statuses = await Promise.all(transports.map(async (transport): Promise<SettingsMirrorUploadStatus> => {
    try {
      await transport.writeSettings(snapshot, fileName);
      for (const asset of selection.selected) await transport.writeAsset(asset);
      return {
        transportId: transport.id,
        label: transport.label,
        status: "success",
        uploadedAttachments: selection.selected.length,
        omittedAttachments: selection.omitted.length,
      };
    } catch (error) {
      return {
        transportId: transport.id,
        label: transport.label,
        status: "failed",
        uploadedAttachments: 0,
        omittedAttachments: selection.omitted.length,
        message: error instanceof Error ? error.message : String(error),
      };
    }
  }));
  const successCount = statuses.filter((status) => status.status === "success").length;
  return {
    fileName,
    statuses,
    partialSuccess: successCount > 0 && successCount < statuses.length,
    requiresLargeAttachmentOptIn: selection.requiresLargeAttachmentOptIn,
  };
}
