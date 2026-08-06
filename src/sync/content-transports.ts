import type { ContentSnapshotV1 } from "../content/types";
import { httpRequest, type HttpRequest } from "../platform/runtime";

export interface ContentRemoteDocument {
  snapshot: ContentSnapshotV1 | null;
  revision?: string;
}

export interface ContentWriteResult {
  revision?: string;
}

export interface ContentBackupRef {
  id: string;
  path: string;
  revision?: string;
  wallTime: number;
  logical: number;
  deviceId: string;
  revisionId: string;
}

export interface ContentAssetDocument {
  data: Uint8Array | null;
  revision?: string;
}

export interface ContentAssetRef {
  hash: string;
  revision?: string;
}

export interface ContentTransport {
  readonly id: string;
  readonly kind: "github" | "webdav";
  readonly label: string;
  readCurrent(): Promise<ContentRemoteDocument>;
  writeCurrent(snapshot: ContentSnapshotV1, revision?: string): Promise<ContentWriteResult>;
  writeBackup(snapshot: ContentSnapshotV1): Promise<ContentWriteResult>;
  listBackups(): Promise<ContentBackupRef[]>;
  readBackup(backup: ContentBackupRef): Promise<ContentSnapshotV1>;
  deleteBackup(backup: ContentBackupRef): Promise<void>;
  listAssets(): Promise<ContentAssetRef[]>;
  readAsset(hash: string): Promise<ContentAssetDocument>;
  writeAsset(hash: string, data: Uint8Array, revision?: string): Promise<ContentWriteResult>;
  deleteAsset(hash: string, revision?: string): Promise<void>;
}

export interface ContentGitHubConfig {
  id?: string;
  owner: string;
  repo: string;
  branch?: string;
  currentPath?: string;
  backupsPath?: string;
  assetsPath?: string;
  token: string;
}

export interface ContentWebDavConfig {
  id: string;
  name: string;
  rootUrl: string;
  username: string;
  password: string;
  enabled?: boolean;
  currentPath?: string;
  backupsPath?: string;
  assetsPath?: string;
}

function request(method: HttpRequest["method"], url: string, headers?: Record<string, string>, body?: string) {
  return httpRequest({ url, method, headers, body });
}

function parseSnapshot(text: string): ContentSnapshotV1 {
  const value = JSON.parse(text) as ContentSnapshotV1;
  if (
    !value ||
    value.schemaVersion !== 1 ||
    !value.revision ||
    !Number.isFinite(value.revision.wallTime) ||
    !Number.isFinite(value.revision.logical) ||
    typeof value.revision.deviceId !== "string" ||
    typeof value.revisionId !== "string"
  ) {
    throw new Error("远端文件不是有效的内容快照 v1");
  }
  return value;
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
  const chunkSize = 0x8000;
  let binary = "";
  for (let offset = 0; offset < value.length; offset += chunkSize) {
    binary += String.fromCharCode(...value.subarray(offset, offset + chunkSize));
  }
  return btoa(binary);
}

function base64ToBytes(value: string): Uint8Array {
  const binary = atob(value.replace(/\s/g, ""));
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

function validHash(hash: string): string {
  if (!/^[a-f\d]{64}$/i.test(hash)) throw new Error("音频资源哈希无效");
  return hash.toLowerCase();
}

async function verifyAssetHash(data: Uint8Array, expectedHash: string): Promise<Uint8Array> {
  const digest = await globalThis.crypto.subtle.digest("SHA-256", data as BufferSource);
  const actual = [...new Uint8Array(digest)].map((part) => part.toString(16).padStart(2, "0")).join("");
  if (actual !== expectedHash) throw new Error("远端音频资源哈希校验失败");
  return data;
}

function encodePath(path: string): string {
  return path.split("/").filter(Boolean).map(encodeURIComponent).join("/");
}

function normalizedPath(path: string): string {
  return path.replace(/^\/+|\/+$/g, "");
}

function backupFileName(snapshot: ContentSnapshotV1): string {
  const wallTime = String(snapshot.revision.wallTime).padStart(13, "0");
  const logical = String(snapshot.revision.logical).padStart(10, "0");
  return `${wallTime}-${logical}-${encodeURIComponent(snapshot.revision.deviceId)}__${encodeURIComponent(snapshot.revisionId)}.json`;
}

function backupRef(path: string, revision?: string): ContentBackupRef {
  const id = path.split("/").pop() ?? path;
  const match = id.match(/^(\d+)-(\d+)-(.+)__(.+)\.json$/);
  return {
    id,
    path,
    revision,
    wallTime: match ? Number(match[1]) : 0,
    logical: match ? Number(match[2]) : 0,
    deviceId: match ? decodeURIComponent(match[3]) : "",
    revisionId: match ? decodeURIComponent(match[4]) : id,
  };
}

function conflictError(message: string): Error {
  return new Error(`CONFLICT:${message}`);
}

export class ContentGitHubTransport implements ContentTransport {
  readonly id: string;
  readonly kind = "github" as const;
  readonly label = "GitHub";
  private readonly currentPath: string;
  private readonly backupsPath: string;
  private readonly assetsPath: string;

  constructor(private readonly config: ContentGitHubConfig) {
    this.id = config.id ?? "github";
    this.currentPath = normalizedPath(config.currentPath ?? "content/current.json");
    this.backupsPath = normalizedPath(config.backupsPath ?? "content/backups");
    this.assetsPath = normalizedPath(config.assetsPath ?? "content/assets");
  }

  private endpoint(path: string): string {
    return `https://api.github.com/repos/${encodeURIComponent(this.config.owner)}/${encodeURIComponent(this.config.repo)}/contents/${encodePath(path)}`;
  }

  private headers(): Record<string, string> {
    return {
      Accept: "application/vnd.github+json",
      Authorization: `Bearer ${this.config.token}`,
      "X-GitHub-Api-Version": "2022-11-28",
      "Content-Type": "application/json",
    };
  }

  private async readPath(path: string): Promise<{ text: string; sha: string } | null> {
    const response = await request(
      "GET",
      `${this.endpoint(path)}?ref=${encodeURIComponent(this.config.branch || "main")}`,
      this.headers(),
    );
    if (response.status === 404) return null;
    if (response.status < 200 || response.status >= 300) {
      throw new Error(`GitHub 读取失败（${response.status}）`);
    }
    const body = JSON.parse(response.body) as { content: string; sha: string };
    return { text: base64ToUtf8(body.content), sha: body.sha };
  }

  private async writePath(path: string, snapshot: ContentSnapshotV1, revision?: string): Promise<ContentWriteResult> {
    return this.writeTextPath(path, JSON.stringify(snapshot), `Sync content ${snapshot.modifiedAt}`, revision);
  }

  private async writeTextPath(path: string, text: string, message: string, revision?: string): Promise<ContentWriteResult> {
    const body: Record<string, unknown> = {
      message,
      content: utf8ToBase64(text),
      branch: this.config.branch || "main",
    };
    if (revision) body.sha = revision;
    const response = await request("PUT", this.endpoint(path), this.headers(), JSON.stringify(body));
    if (response.status < 200 || response.status >= 300) {
      if (response.status === 409 || response.status === 422) {
        throw conflictError(`GitHub 写入冲突（${response.status}）`);
      }
      throw new Error(`GitHub 写入失败（${response.status}）`);
    }
    const result = JSON.parse(response.body) as { content?: { sha?: string } };
    return { revision: result.content?.sha };
  }

  async readCurrent(): Promise<ContentRemoteDocument> {
    const result = await this.readPath(this.currentPath);
    return result ? { snapshot: parseSnapshot(result.text), revision: result.sha } : { snapshot: null };
  }

  writeCurrent(snapshot: ContentSnapshotV1, revision?: string): Promise<ContentWriteResult> {
    return this.writePath(this.currentPath, snapshot, revision);
  }

  async writeBackup(snapshot: ContentSnapshotV1): Promise<ContentWriteResult> {
    const path = `${this.backupsPath}/${backupFileName(snapshot)}`;
    try {
      return await this.writePath(path, snapshot);
    } catch (error) {
      if (!String(error instanceof Error ? error.message : error).startsWith("CONFLICT:")) throw error;
      const existing = await this.readPath(path);
      if (existing) return { revision: existing.sha };
      throw error;
    }
  }

  async listBackups(): Promise<ContentBackupRef[]> {
    const response = await request(
      "GET",
      `${this.endpoint(this.backupsPath)}?ref=${encodeURIComponent(this.config.branch || "main")}`,
      this.headers(),
    );
    if (response.status === 404) return [];
    if (response.status < 200 || response.status >= 300) {
      throw new Error(`GitHub 备份列表读取失败（${response.status}）`);
    }
    const files = JSON.parse(response.body) as Array<{ name: string; path: string; sha: string; type: string }>;
    return files.filter((file) => file.type === "file" && file.name.endsWith(".json"))
      .map((file) => backupRef(file.path, file.sha));
  }

  async readBackup(backup: ContentBackupRef): Promise<ContentSnapshotV1> {
    const result = await this.readPath(backup.path);
    if (!result) throw new Error(`GitHub 备份不存在：${backup.id}`);
    return parseSnapshot(result.text);
  }

  async deleteBackup(backup: ContentBackupRef): Promise<void> {
    const response = await request("DELETE", this.endpoint(backup.path), this.headers(), JSON.stringify({
      message: `Prune content backup ${backup.id}`,
      sha: backup.revision,
      branch: this.config.branch || "main",
    }));
    if (response.status === 404) return;
    if (response.status < 200 || response.status >= 300) {
      if (response.status === 409 || response.status === 422) {
        throw conflictError(`GitHub 删除备份冲突（${response.status}）`);
      }
      throw new Error(`GitHub 删除备份失败（${response.status}）`);
    }
  }

  async listAssets(): Promise<ContentAssetRef[]> {
    const response = await request(
      "GET",
      `${this.endpoint(this.assetsPath)}?ref=${encodeURIComponent(this.config.branch || "main")}`,
      this.headers(),
    );
    if (response.status === 404) return [];
    if (response.status < 200 || response.status >= 300) {
      throw new Error(`GitHub 音频列表读取失败（${response.status}）`);
    }
    const files = JSON.parse(response.body) as Array<{ name: string; sha: string; type: string }>;
    return files.flatMap((file) => {
      const match = file.type === "file" && file.name.match(/^([a-f\d]{64})\.mp3$/i);
      return match ? [{ hash: match[1].toLowerCase(), revision: file.sha }] : [];
    });
  }

  async readAsset(hash: string): Promise<ContentAssetDocument> {
    const normalized = validHash(hash);
    const path = `${this.assetsPath}/${normalized}.mp3`;
    const response = await request(
      "GET",
      `${this.endpoint(path)}?ref=${encodeURIComponent(this.config.branch || "main")}`,
      this.headers(),
    );
    if (response.status === 404) return { data: null };
    if (response.status < 200 || response.status >= 300) throw new Error(`GitHub 音频读取失败（${response.status}）`);
    const body = JSON.parse(response.body) as { content: string; sha: string };
    return { data: await verifyAssetHash(base64ToBytes(body.content), normalized), revision: body.sha };
  }

  async writeAsset(hash: string, data: Uint8Array, revision?: string): Promise<ContentWriteResult> {
    const normalized = validHash(hash);
    await verifyAssetHash(data, normalized);
    const body: Record<string, unknown> = {
      message: `Sync audio asset ${normalized}`,
      content: bytesToBase64(data),
      branch: this.config.branch || "main",
    };
    if (revision) body.sha = revision;
    const response = await request("PUT", this.endpoint(`${this.assetsPath}/${normalized}.mp3`), this.headers(), JSON.stringify(body));
    if (response.status < 200 || response.status >= 300) {
      if (response.status === 409 || response.status === 422) throw conflictError("GitHub 音频写入冲突");
      throw new Error(`GitHub 音频写入失败（${response.status}）`);
    }
    const result = JSON.parse(response.body) as { content?: { sha?: string } };
    return { revision: result.content?.sha };
  }

  async deleteAsset(hash: string, revision?: string): Promise<void> {
    const normalized = validHash(hash);
    const path = `${this.assetsPath}/${normalized}.mp3`;
    let sha = revision;
    if (!sha) sha = (await this.readPath(path))?.sha;
    if (!sha) return;
    const response = await request("DELETE", this.endpoint(path), this.headers(), JSON.stringify({
      message: `Delete audio asset ${normalized}`,
      sha,
      branch: this.config.branch || "main",
    }));
    if (response.status === 404) return;
    if (response.status < 200 || response.status >= 300) {
      if (response.status === 409 || response.status === 422) throw conflictError("GitHub 删除音频冲突");
      throw new Error(`GitHub 删除音频失败（${response.status}）`);
    }
  }
}

function joinUrl(rootUrl: string, path: string): string {
  return `${rootUrl.replace(/\/+$/, "")}/${encodePath(path)}`;
}

function xmlEntities(value: string): string {
  return value.replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">");
}

export class ContentWebDavTransport implements ContentTransport {
  readonly kind = "webdav" as const;
  readonly id: string;
  readonly label: string;
  private readonly currentPath: string;
  private readonly backupsPath: string;
  private readonly assetsPath: string;

  constructor(private readonly config: ContentWebDavConfig) {
    const root = new URL(config.rootUrl);
    if (root.protocol !== "https:") throw new Error("WebDAV 地址必须使用 HTTPS");
    if (root.username || root.password) throw new Error("WebDAV 用户名和密码不能写在地址中");
    if (!config.id.trim()) throw new Error("WebDAV 配置 ID 不能为空");
    this.id = config.id;
    this.label = config.name || "WebDAV";
    this.currentPath = normalizedPath(config.currentPath ?? "content/current.json");
    this.backupsPath = normalizedPath(config.backupsPath ?? "content/backups");
    this.assetsPath = normalizedPath(config.assetsPath ?? "content/assets");
  }

  private headers(): Record<string, string> {
    return {
      Authorization: `Basic ${utf8ToBase64(`${this.config.username}:${this.config.password}`)}`,
      "Content-Type": "application/json; charset=utf-8",
    };
  }

  private async ensureCollection(path = this.backupsPath): Promise<void> {
    const segments = path.split("/").filter(Boolean);
    for (let index = 1; index <= segments.length; index += 1) {
      const response = await request("MKCOL", joinUrl(this.config.rootUrl, segments.slice(0, index).join("/")), this.headers());
      if (![201, 301, 405].includes(response.status) && (response.status < 200 || response.status >= 300)) {
        throw new Error(`WebDAV 无法创建备份目录（${response.status}）`);
      }
    }
  }

  async readCurrent(): Promise<ContentRemoteDocument> {
    const response = await request("GET", joinUrl(this.config.rootUrl, this.currentPath), this.headers());
    if (response.status === 404) return { snapshot: null };
    if (response.status < 200 || response.status >= 300) {
      throw new Error(`WebDAV 读取失败（${response.status}）`);
    }
    return { snapshot: parseSnapshot(response.body), revision: response.headers.etag };
  }

  private async write(path: string, snapshot: ContentSnapshotV1, revision?: string): Promise<ContentWriteResult> {
    const headers = this.headers();
    if (revision) headers["If-Match"] = revision;
    else headers["If-None-Match"] = "*";
    const response = await request("PUT", joinUrl(this.config.rootUrl, path), headers, JSON.stringify(snapshot));
    if (response.status < 200 || response.status >= 300) {
      if (response.status === 409 || response.status === 412) {
        throw conflictError(`WebDAV 写入冲突（${response.status}）`);
      }
      throw new Error(`WebDAV 写入失败（${response.status}）`);
    }
    return { revision: response.headers.etag };
  }

  async writeCurrent(snapshot: ContentSnapshotV1, revision?: string): Promise<ContentWriteResult> {
    const parent = this.currentPath.split("/").slice(0, -1).join("/");
    if (parent) await this.ensureCollection(parent);
    return this.write(this.currentPath, snapshot, revision);
  }

  async writeBackup(snapshot: ContentSnapshotV1): Promise<ContentWriteResult> {
    await this.ensureCollection();
    const path = `${this.backupsPath}/${backupFileName(snapshot)}`;
    try {
      return await this.write(path, snapshot);
    } catch (error) {
      if (String(error instanceof Error ? error.message : error).startsWith("CONFLICT:")) return {};
      throw error;
    }
  }

  async listBackups(): Promise<ContentBackupRef[]> {
    await this.ensureCollection();
    const headers = { ...this.headers(), Depth: "1", "Content-Type": "application/xml; charset=utf-8" };
    const response = await request("PROPFIND", joinUrl(this.config.rootUrl, this.backupsPath), headers,
      '<?xml version="1.0"?><propfind xmlns="DAV:"><prop><getetag/></prop></propfind>');
    if (response.status === 404) return [];
    if (response.status !== 207 && (response.status < 200 || response.status >= 300)) {
      throw new Error(`WebDAV 备份列表读取失败（${response.status}）`);
    }
    const refs: ContentBackupRef[] = [];
    const blocks = response.body.match(/<(?:[A-Za-z]+:)?response\b[\s\S]*?<\/(?:[A-Za-z]+:)?response>/gi) ?? [];
    for (const block of blocks) {
      const href = block.match(/<(?:[A-Za-z]+:)?href\b[^>]*>([\s\S]*?)<\/(?:[A-Za-z]+:)?href>/i)?.[1];
      if (!href) continue;
      const pathname = decodeURIComponent(xmlEntities(href)).replace(/[?#].*$/, "");
      const name = pathname.split("/").filter(Boolean).pop() ?? "";
      if (!name.endsWith(".json")) continue;
      const etag = block.match(/<(?:[A-Za-z]+:)?getetag\b[^>]*>([\s\S]*?)<\/(?:[A-Za-z]+:)?getetag>/i)?.[1];
      refs.push(backupRef(`${this.backupsPath}/${name}`, etag ? xmlEntities(etag.trim()) : undefined));
    }
    return refs;
  }

  async readBackup(backup: ContentBackupRef): Promise<ContentSnapshotV1> {
    const response = await request("GET", joinUrl(this.config.rootUrl, backup.path), this.headers());
    if (response.status === 404) throw new Error(`WebDAV 备份不存在：${backup.id}`);
    if (response.status < 200 || response.status >= 300) {
      throw new Error(`WebDAV 备份读取失败（${response.status}）`);
    }
    return parseSnapshot(response.body);
  }

  async deleteBackup(backup: ContentBackupRef): Promise<void> {
    const headers = this.headers();
    if (backup.revision) headers["If-Match"] = backup.revision;
    const response = await request("DELETE", joinUrl(this.config.rootUrl, backup.path), headers);
    if (response.status === 404) return;
    if (response.status < 200 || response.status >= 300) {
      if (response.status === 409 || response.status === 412) {
        throw conflictError(`WebDAV 删除备份冲突（${response.status}）`);
      }
      throw new Error(`WebDAV 删除备份失败（${response.status}）`);
    }
  }

  async listAssets(): Promise<ContentAssetRef[]> {
    await this.ensureCollection(this.assetsPath);
    const headers = { ...this.headers(), Depth: "1", "Content-Type": "application/xml; charset=utf-8" };
    const response = await request("PROPFIND", joinUrl(this.config.rootUrl, this.assetsPath), headers,
      '<?xml version="1.0"?><propfind xmlns="DAV:"><prop><getetag/></prop></propfind>');
    if (response.status === 404) return [];
    if (response.status !== 207 && (response.status < 200 || response.status >= 300)) {
      throw new Error(`WebDAV 音频列表读取失败（${response.status}）`);
    }
    const refs: ContentAssetRef[] = [];
    const blocks = response.body.match(/<(?:[A-Za-z]+:)?response\b[\s\S]*?<\/(?:[A-Za-z]+:)?response>/gi) ?? [];
    for (const block of blocks) {
      const href = block.match(/<(?:[A-Za-z]+:)?href\b[^>]*>([\s\S]*?)<\/(?:[A-Za-z]+:)?href>/i)?.[1];
      if (!href) continue;
      const pathname = decodeURIComponent(xmlEntities(href)).replace(/[?#].*$/, "");
      const name = pathname.split("/").filter(Boolean).pop() ?? "";
      const match = name.match(/^([a-f\d]{64})\.mp3$/i);
      if (!match) continue;
      const etag = block.match(/<(?:[A-Za-z]+:)?getetag\b[^>]*>([\s\S]*?)<\/(?:[A-Za-z]+:)?getetag>/i)?.[1];
      refs.push({ hash: match[1].toLowerCase(), revision: etag ? xmlEntities(etag.trim()) : undefined });
    }
    return refs;
  }

  async readAsset(hash: string): Promise<ContentAssetDocument> {
    const normalized = validHash(hash);
    const response = await httpRequest({
      url: joinUrl(this.config.rootUrl, `${this.assetsPath}/${normalized}.mp3`),
      method: "GET",
      headers: this.headers(),
      responseType: "base64",
    });
    if (response.status === 404) return { data: null };
    if (response.status < 200 || response.status >= 300) throw new Error(`WebDAV 音频读取失败（${response.status}）`);
    return { data: await verifyAssetHash(base64ToBytes(response.body), normalized), revision: response.headers.etag };
  }

  async writeAsset(hash: string, data: Uint8Array, revision?: string): Promise<ContentWriteResult> {
    const normalized = validHash(hash);
    await verifyAssetHash(data, normalized);
    await this.ensureCollection(this.assetsPath);
    const headers = this.headers();
    if (revision) headers["If-Match"] = revision;
    else headers["If-None-Match"] = "*";
    headers["Content-Type"] = "audio/mpeg";
    const response = await httpRequest({
      url: joinUrl(this.config.rootUrl, `${this.assetsPath}/${normalized}.mp3`),
      method: "PUT",
      headers,
      bodyBase64: bytesToBase64(data),
    });
    if (response.status < 200 || response.status >= 300) {
      if (response.status === 409 || response.status === 412) throw conflictError("WebDAV 音频写入冲突");
      throw new Error(`WebDAV 音频写入失败（${response.status}）`);
    }
    return { revision: response.headers.etag };
  }

  async deleteAsset(hash: string, revision?: string): Promise<void> {
    const normalized = validHash(hash);
    const headers = this.headers();
    if (revision) headers["If-Match"] = revision;
    const response = await request("DELETE", joinUrl(this.config.rootUrl, `${this.assetsPath}/${normalized}.mp3`), headers);
    if (response.status === 404) return;
    if (response.status < 200 || response.status >= 300) {
      if (response.status === 409 || response.status === 412) throw conflictError("WebDAV 删除音频冲突");
      throw new Error(`WebDAV 删除音频失败（${response.status}）`);
    }
  }
}
