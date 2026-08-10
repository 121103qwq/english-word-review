import { httpRequest, type HttpRequest } from "../platform/runtime";
import type { ContentSnapshotV1 } from "../content/types";
import type { ContentStorageBackend } from "../content/storage";
import type { ContentTransport } from "./content-transports";
import {
  LIBRARY_SYNC_BATCHES_PER_LOCATION,
  LIBRARY_SYNC_ROOT_PATH,
  compareLibraryCopies,
  librarySyncFileName,
  librarySyncMinute,
  parseLibrarySyncFile,
  sameSyncMinute,
  syncFileSlug,
  type LibraryContentSyncFileV1,
  type LibraryCopyComparison,
  type LibrarySyncBatchV1,
  type LibrarySyncFileV1,
} from "./library-files";
import {
  archiveLibrarySyncFiles,
  type LibrarySyncArchiveBackend,
} from "./library-archive";

export interface LibrarySyncFileRef {
  id: string;
  fileName: string;
  path: string;
  deviceCode: string;
  location: string;
  createdAt: string;
  batchId: string;
  kind: LibrarySyncFileV1["kind"];
  revision?: string;
}

export interface LibrarySyncRemoteDocument {
  file: LibrarySyncFileV1;
  rawText: string;
  revision?: string;
}

export interface LibrarySyncAssetDocument {
  data: Uint8Array | null;
  revision?: string;
}

export interface LibraryFileTransport {
  readonly id: string;
  readonly kind: "github" | "webdav";
  readonly label: string;
  listLibraryFiles(): Promise<LibrarySyncFileRef[]>;
  readLibraryFile(ref: LibrarySyncFileRef): Promise<LibrarySyncRemoteDocument>;
  writeLibraryFile(file: LibrarySyncFileV1, fileName?: string): Promise<LibrarySyncFileRef>;
  deleteLibraryFile(ref: LibrarySyncFileRef): Promise<void>;
  readLibraryAsset(hash: string): Promise<LibrarySyncAssetDocument>;
  writeLibraryAsset(hash: string, data: Uint8Array): Promise<void>;
}

export interface LibraryFileGitHubConfig {
  id?: string;
  owner: string;
  repo: string;
  branch?: string;
  token: string;
  rootPath?: string;
  assetsPath?: string;
}

export interface LibraryFileWebDavConfig {
  id: string;
  name: string;
  rootUrl: string;
  /** Anonymous WebDAV is supported: omit both fields to send no Authorization header. */
  username?: string;
  password?: string;
  rootPath?: string;
  assetsPath?: string;
}

function request(method: HttpRequest["method"], url: string, headers?: Record<string, string>, body?: string) {
  return httpRequest({ url, method, headers, body });
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

function validHash(hash: string): string {
  if (!/^[a-f\d]{64}$/i.test(hash)) throw new Error("音频资源哈希无效");
  return hash.toLowerCase();
}

async function verifyHash(data: Uint8Array, expected: string): Promise<Uint8Array> {
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", data as BufferSource));
  const actual = [...digest].map((part) => part.toString(16).padStart(2, "0")).join("");
  if (actual !== expected) throw new Error("音频资源哈希校验失败");
  return data;
}

function timestampFromStamp(value: string): string {
  const match = value.match(/^(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})$/);
  if (!match) return new Date(0).toISOString();
  const [, year, month, day, hour, minute] = match;
  return new Date(Number(year), Number(month) - 1, Number(day), Number(hour), Number(minute)).toISOString();
}

function refFromPath(path: string, revision?: string): LibrarySyncFileRef | null {
  const fileName = path.split("/").pop() ?? "";
  const match = fileName.match(/^([^_]+)_([^_]+)_(\d{8}-\d{4})_([^_]+)_(progress|library-.+)\.json$/i);
  if (!match) return null;
  return {
    id: path,
    fileName,
    path,
    deviceCode: match[1].toUpperCase(),
    location: match[2].replace(/-/g, " "),
    createdAt: timestampFromStamp(match[3]),
    batchId: match[4],
    kind: match[5].toLowerCase() === "progress" ? "learning-progress" : "library",
    revision,
  };
}

function xmlEntities(value: string): string {
  return value.replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">");
}

export class LibraryFileGitHubTransport implements LibraryFileTransport {
  readonly id: string;
  readonly kind = "github" as const;
  readonly label = "GitHub";
  private readonly rootPath: string;
  private readonly assetsPath: string;

  constructor(private readonly config: LibraryFileGitHubConfig) {
    this.id = config.id ?? "github-library-files";
    this.rootPath = normalizedPath(config.rootPath ?? LIBRARY_SYNC_ROOT_PATH);
    this.assetsPath = normalizedPath(config.assetsPath ?? `${LIBRARY_SYNC_ROOT_PATH}/assets`);
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
    const response = await request("GET", `${this.endpoint(path)}?ref=${encodeURIComponent(this.config.branch || "main")}`, this.headers());
    if (response.status === 404) return null;
    if (response.status < 200 || response.status >= 300) throw new Error(`GitHub 词库文件读取失败（${response.status}）`);
    const body = JSON.parse(response.body) as { content: string; sha: string };
    return { text: base64ToUtf8(body.content), sha: body.sha };
  }

  async listLibraryFiles(): Promise<LibrarySyncFileRef[]> {
    const response = await request("GET", `${this.endpoint(this.rootPath)}?ref=${encodeURIComponent(this.config.branch || "main")}`, this.headers());
    if (response.status === 404) return [];
    if (response.status < 200 || response.status >= 300) throw new Error(`GitHub 词库文件列表读取失败（${response.status}）`);
    const files = JSON.parse(response.body) as Array<{ name: string; path: string; sha: string; type: string }>;
    return files.flatMap((file) => {
      if (file.type !== "file") return [];
      const ref = refFromPath(file.path, file.sha);
      return ref ? [ref] : [];
    });
  }

  async readLibraryFile(ref: LibrarySyncFileRef): Promise<LibrarySyncRemoteDocument> {
    const result = await this.readPath(ref.path);
    if (!result) throw new Error(`GitHub 词库文件不存在：${ref.fileName}`);
    return { file: parseLibrarySyncFile(result.text), rawText: result.text, revision: result.sha };
  }

  async writeLibraryFile(file: LibrarySyncFileV1, fileName = librarySyncFileName(file)): Promise<LibrarySyncFileRef> {
    const path = `${this.rootPath}/${fileName}`;
    const response = await request("PUT", this.endpoint(path), this.headers(), JSON.stringify({
      message: `Upload library sync batch ${file.batchId}`,
      content: utf8ToBase64(JSON.stringify(file)),
      branch: this.config.branch || "main",
    }));
    if (response.status === 409 || response.status === 422) {
      const existing = await this.readPath(path);
      if (existing && JSON.stringify(parseLibrarySyncFile(existing.text)) === JSON.stringify(file)) {
        return refFromPath(path, existing.sha)!;
      }
    }
    if (response.status < 200 || response.status >= 300) throw new Error(`GitHub 词库文件写入失败（${response.status}）`);
    const body = JSON.parse(response.body) as { content?: { sha?: string } };
    const ref = refFromPath(path, body.content?.sha);
    if (!ref) throw new Error("词库同步文件名无效");
    return ref;
  }

  async deleteLibraryFile(ref: LibrarySyncFileRef): Promise<void> {
    let sha = ref.revision;
    if (!sha) sha = (await this.readPath(ref.path))?.sha;
    if (!sha) return;
    const response = await request("DELETE", this.endpoint(ref.path), this.headers(), JSON.stringify({
      message: `Prune library sync file ${ref.fileName}`,
      sha,
      branch: this.config.branch || "main",
    }));
    if (response.status !== 404 && (response.status < 200 || response.status >= 300)) {
      throw new Error(`GitHub 词库文件删除失败（${response.status}）`);
    }
  }

  async readLibraryAsset(hash: string): Promise<LibrarySyncAssetDocument> {
    const normalized = validHash(hash);
    const response = await httpRequest({
      url: `${this.endpoint(`${this.assetsPath}/${normalized}.mp3`)}?ref=${encodeURIComponent(this.config.branch || "main")}`,
      method: "GET",
      headers: { ...this.headers(), Accept: "application/vnd.github.raw+json" },
      responseType: "base64",
    });
    if (response.status === 404) return { data: null };
    if (response.status < 200 || response.status >= 300) throw new Error(`GitHub 音频读取失败（${response.status}）`);
    return { data: await verifyHash(base64ToBytes(response.body), normalized), revision: response.headers.etag };
  }

  async writeLibraryAsset(hash: string, data: Uint8Array): Promise<void> {
    const normalized = validHash(hash);
    await verifyHash(data, normalized);
    if ((await this.readLibraryAsset(normalized)).data) return;
    const response = await request("PUT", this.endpoint(`${this.assetsPath}/${normalized}.mp3`), this.headers(), JSON.stringify({
      message: `Upload library audio ${normalized}`,
      content: bytesToBase64(data),
      branch: this.config.branch || "main",
    }));
    if (response.status < 200 || response.status >= 300) throw new Error(`GitHub 音频写入失败（${response.status}）`);
  }
}

export class LibraryFileWebDavTransport implements LibraryFileTransport {
  readonly kind = "webdav" as const;
  readonly id: string;
  readonly label: string;
  private readonly rootPath: string;
  private readonly assetsPath: string;

  constructor(private readonly config: LibraryFileWebDavConfig) {
    const root = new URL(config.rootUrl);
    if (root.protocol !== "https:") throw new Error("WebDAV 地址必须使用 HTTPS");
    if (root.username || root.password) throw new Error("WebDAV 用户名和密码不能写在地址中");
    this.id = config.id;
    this.label = config.name || "WebDAV";
    this.rootPath = normalizedPath(config.rootPath ?? LIBRARY_SYNC_ROOT_PATH);
    this.assetsPath = normalizedPath(config.assetsPath ?? `${LIBRARY_SYNC_ROOT_PATH}/assets`);
  }

  private headers(contentType = "application/json; charset=utf-8"): Record<string, string> {
    const headers: Record<string, string> = { "Content-Type": contentType };
    if (this.config.username || this.config.password) {
      headers.Authorization = `Basic ${utf8ToBase64(`${this.config.username ?? ""}:${this.config.password ?? ""}`)}`;
    }
    return headers;
  }

  private async ensureCollection(path = this.rootPath): Promise<void> {
    const segments = path.split("/").filter(Boolean);
    for (let index = 1; index <= segments.length; index += 1) {
      const response = await request("MKCOL", joinUrl(this.config.rootUrl, segments.slice(0, index).join("/")), this.headers());
      if (![201, 301, 405].includes(response.status) && (response.status < 200 || response.status >= 300)) {
        throw new Error(`WebDAV 无法创建词库同步目录（${response.status}）`);
      }
    }
  }

  async listLibraryFiles(): Promise<LibrarySyncFileRef[]> {
    await this.ensureCollection();
    const response = await request("PROPFIND", joinUrl(this.config.rootUrl, this.rootPath), {
      ...this.headers("application/xml; charset=utf-8"), Depth: "1",
    }, '<?xml version="1.0"?><propfind xmlns="DAV:"><prop><getetag/></prop></propfind>');
    if (response.status === 404) return [];
    if (response.status !== 207 && (response.status < 200 || response.status >= 300)) {
      throw new Error(`WebDAV 词库文件列表读取失败（${response.status}）`);
    }
    const refs: LibrarySyncFileRef[] = [];
    const blocks = response.body.match(/<(?:[A-Za-z]+:)?response\b[\s\S]*?<\/(?:[A-Za-z]+:)?response>/gi) ?? [];
    for (const block of blocks) {
      const href = block.match(/<(?:[A-Za-z]+:)?href\b[^>]*>([\s\S]*?)<\/(?:[A-Za-z]+:)?href>/i)?.[1];
      if (!href) continue;
      const pathName = decodeURIComponent(xmlEntities(href)).replace(/[?#].*$/, "");
      const name = pathName.split("/").filter(Boolean).pop() ?? "";
      const etag = block.match(/<(?:[A-Za-z]+:)?getetag\b[^>]*>([\s\S]*?)<\/(?:[A-Za-z]+:)?getetag>/i)?.[1];
      const ref = refFromPath(`${this.rootPath}/${name}`, etag ? xmlEntities(etag.trim()) : undefined);
      if (ref) refs.push(ref);
    }
    return refs;
  }

  async readLibraryFile(ref: LibrarySyncFileRef): Promise<LibrarySyncRemoteDocument> {
    const response = await request("GET", joinUrl(this.config.rootUrl, ref.path), this.headers());
    if (response.status === 404) throw new Error(`WebDAV 词库文件不存在：${ref.fileName}`);
    if (response.status < 200 || response.status >= 300) throw new Error(`WebDAV 词库文件读取失败（${response.status}）`);
    return { file: parseLibrarySyncFile(response.body), rawText: response.body, revision: response.headers.etag };
  }

  async writeLibraryFile(file: LibrarySyncFileV1, fileName = librarySyncFileName(file)): Promise<LibrarySyncFileRef> {
    await this.ensureCollection();
    const path = `${this.rootPath}/${fileName}`;
    const response = await request("PUT", joinUrl(this.config.rootUrl, path), { ...this.headers(), "If-None-Match": "*" }, JSON.stringify(file));
    if (response.status === 409 || response.status === 412) {
      const existing = await request("GET", joinUrl(this.config.rootUrl, path), this.headers());
      if (existing.status >= 200 && existing.status < 300 &&
        JSON.stringify(parseLibrarySyncFile(existing.body)) === JSON.stringify(file)) {
        return refFromPath(path, existing.headers.etag)!;
      }
    }
    if (response.status < 200 || response.status >= 300) throw new Error(`WebDAV 词库文件写入失败（${response.status}）`);
    const ref = refFromPath(path, response.headers.etag);
    if (!ref) throw new Error("词库同步文件名无效");
    return ref;
  }

  async deleteLibraryFile(ref: LibrarySyncFileRef): Promise<void> {
    const headers = this.headers();
    if (ref.revision) headers["If-Match"] = ref.revision;
    const response = await request("DELETE", joinUrl(this.config.rootUrl, ref.path), headers);
    if (response.status !== 404 && (response.status < 200 || response.status >= 300)) {
      throw new Error(`WebDAV 词库文件删除失败（${response.status}）`);
    }
  }

  async readLibraryAsset(hash: string): Promise<LibrarySyncAssetDocument> {
    const normalized = validHash(hash);
    const response = await httpRequest({
      url: joinUrl(this.config.rootUrl, `${this.assetsPath}/${normalized}.mp3`),
      method: "GET",
      headers: this.headers("audio/mpeg"),
      responseType: "base64",
    });
    if (response.status === 404) return { data: null };
    if (response.status < 200 || response.status >= 300) throw new Error(`WebDAV 音频读取失败（${response.status}）`);
    return { data: await verifyHash(base64ToBytes(response.body), normalized), revision: response.headers.etag };
  }

  async writeLibraryAsset(hash: string, data: Uint8Array): Promise<void> {
    const normalized = validHash(hash);
    await verifyHash(data, normalized);
    if ((await this.readLibraryAsset(normalized)).data) return;
    await this.ensureCollection(this.assetsPath);
    const response = await httpRequest({
      url: joinUrl(this.config.rootUrl, `${this.assetsPath}/${normalized}.mp3`),
      method: "PUT",
      headers: { ...this.headers("audio/mpeg"), "If-None-Match": "*" },
      bodyBase64: bytesToBase64(data),
    });
    if (response.status < 200 || response.status >= 300) throw new Error(`WebDAV 音频写入失败（${response.status}）`);
  }
}

export interface LibraryFileSource {
  transport: LibraryFileTransport;
  ref: LibrarySyncFileRef;
}

export interface CloudLibraryFileChoice extends LibrarySyncFileRef {
  sources: LibraryFileSource[];
  file: LibrarySyncFileV1;
  comparison?: LibraryCopyComparison | "same-minute-progress-different";
  localFileName?: string;
}

export interface LocalLibraryFileChoice {
  fileName: string;
  file: LibrarySyncFileV1;
  existingCloudCopies: number;
}

export interface LibrarySyncChoiceOptions {
  archive?: LibrarySyncArchiveBackend;
}

export interface LegacyWholeSnapshotChoice {
  kind: "legacy-whole-content";
  transportId: string;
  label: string;
  snapshot: ContentSnapshotV1;
  readOnly: true;
}

export interface LibrarySyncChoiceModel {
  local: LocalLibraryFileChoice[];
  cloud: CloudLibraryFileChoice[];
  legacy: LegacyWholeSnapshotChoice[];
  errors: Array<{ transportId: string; message: string }>;
}

async function readChoice(choice: Omit<CloudLibraryFileChoice, "file">): Promise<LibrarySyncFileV1> {
  let lastError: unknown;
  for (const source of choice.sources) {
    try {
      return (await source.transport.readLibraryFile(source.ref)).file;
    } catch (error) {
      lastError = error;
    }
  }
  throw lastError instanceof Error ? lastError : new Error("所有词库同步镜像均不可用");
}

/**
 * Read-only inspection for the manual two-column chooser. No local or remote
 * state is modified until the caller explicitly uploads/downloads selections.
 */
export async function listLibrarySyncChoices(
  localBatch: LibrarySyncBatchV1,
  transports: LibraryFileTransport[],
  legacyTransports: ContentTransport[] = [],
  options: LibrarySyncChoiceOptions = {},
): Promise<LibrarySyncChoiceModel> {
  const errors: LibrarySyncChoiceModel["errors"] = [];
  const listings = await Promise.all(transports.map(async (transport) => {
    try {
      return { transport, refs: await transport.listLibraryFiles() };
    } catch (error) {
      errors.push({ transportId: transport.id, message: error instanceof Error ? error.message : String(error) });
      return { transport, refs: [] };
    }
  }));
  const merged = new Map<string, Omit<CloudLibraryFileChoice, "file">>();
  for (const { transport, refs } of listings) {
    for (const ref of refs) {
      const current = merged.get(ref.fileName);
      if (current) current.sources.push({ transport, ref });
      else merged.set(ref.fileName, { ...ref, sources: [{ transport, ref }] });
    }
  }
  const localFilesByName = new Map(localBatch.files.map((file) =>
    [librarySyncFileName(file), { file, fileName: librarySyncFileName(file) }]));
  for (const archived of await options.archive?.list() ?? []) {
    if (!localFilesByName.has(archived.fileName)) {
      localFilesByName.set(archived.fileName, { file: archived.file, fileName: archived.fileName });
    }
  }
  const localFiles = [...localFilesByName.values()];
  const cloud: CloudLibraryFileChoice[] = [];
  for (const metadata of merged.values()) {
    try {
      const file = await readChoice(metadata);
      const candidates = localFiles.filter((candidate) => {
        if (!sameSyncMinute(candidate.file, file) || candidate.file.kind !== file.kind) return false;
        if (file.kind === "library" && candidate.file.kind === "library") return candidate.file.library.id === file.library.id;
        return true;
      });
      let local = candidates[0];
      let comparison: CloudLibraryFileChoice["comparison"];
      if (file.kind === "library") {
        const ranked = candidates.flatMap((candidate) => candidate.file.kind === "library"
          ? [{ candidate, comparison: compareLibraryCopies(candidate.file, file) }]
          : []);
        const rank: Record<LibraryCopyComparison, number> = {
          identical: 0,
          "same-words-progress-different": 1,
          "same-words-content-different": 2,
          "different-words": 3,
        };
        ranked.sort((left, right) => rank[left.comparison] - rank[right.comparison]);
        if (ranked[0]) {
          local = ranked[0].candidate;
          comparison = ranked[0].comparison;
        }
      } else {
        const identical = candidates.find((candidate) => candidate.file.kind === "learning-progress" &&
          JSON.stringify(candidate.file.snapshot) === JSON.stringify(file.snapshot));
        if (identical) {
          local = identical;
          comparison = "identical";
        } else if (local?.file.kind === "learning-progress") {
          comparison = "same-minute-progress-different";
        }
      }
      cloud.push({ ...metadata, file, comparison, localFileName: local?.fileName });
    } catch (error) {
      errors.push({ transportId: metadata.sources[0]?.transport.id ?? "unknown", message: error instanceof Error ? error.message : String(error) });
    }
  }
  const existingNames = new Set(cloud.map((choice) => choice.fileName));
  const local = localFiles.map(({ fileName, file }) => ({
    fileName,
    file,
    existingCloudCopies: cloud.find((choice) => choice.fileName === fileName)?.sources.length ?? (existingNames.has(fileName) ? 1 : 0),
  }));
  const legacy: LegacyWholeSnapshotChoice[] = [];
  for (const transport of legacyTransports) {
    try {
      const document = await transport.readCurrent();
      if (document.snapshot) legacy.push({
        kind: "legacy-whole-content", transportId: transport.id, label: transport.label,
        snapshot: document.snapshot, readOnly: true,
      });
    } catch (error) {
      errors.push({ transportId: transport.id, message: error instanceof Error ? error.message : String(error) });
    }
  }
  cloud.sort((left, right) => right.createdAt.localeCompare(left.createdAt) || left.fileName.localeCompare(right.fileName));
  return { local, cloud, legacy, errors };
}

async function pruneLocation(transport: LibraryFileTransport, batch: LibrarySyncBatchV1): Promise<void> {
  const expectedLocation = syncFileSlug(batch.sourceDevice.location, "未设置地点").replace(/-/g, " ");
  const batches = new Map<string, { createdAt: string; refs: LibrarySyncFileRef[] }>();
  for (const ref of await transport.listLibraryFiles()) {
    if (ref.location !== expectedLocation) continue;
    const key = `${ref.deviceCode}|${ref.location}|${librarySyncMinute(ref.createdAt)}|${ref.batchId}`;
    const item = batches.get(key) ?? { createdAt: ref.createdAt, refs: [] };
    item.refs.push(ref);
    batches.set(key, item);
  }
  const sorted = [...batches.entries()].sort(([, left], [, right]) =>
    right.createdAt.localeCompare(left.createdAt));
  for (const [, { refs }] of sorted.slice(LIBRARY_SYNC_BATCHES_PER_LOCATION)) {
    for (const ref of refs) await transport.deleteLibraryFile(ref);
  }
}

export interface LibraryBatchMirrorStatus {
  transportId: string;
  label: string;
  status: "success" | "failed";
  uploadedFiles: number;
  uploadedAssets: number;
  omittedAssets: number;
  message?: string;
}

type LibraryAssetBackend = Pick<ContentStorageBackend, "getAsset" | "putAsset">;

export interface LibrarySyncTransferOptions {
  archive?: LibrarySyncArchiveBackend;
  assetBackend?: LibraryAssetBackend;
  /** MP3 transfer is opt-in; file metadata and references are always preserved. */
  includeAssets?: boolean;
}

export interface LibrarySyncAssetSelection {
  assets: Map<string, { id: string; sha256: string; byteLength: number }>;
  totalBytes: number;
}

/** Lets the chooser show attachment count/size before enabling includeAssets. */
export function librarySyncAssetSelection(files: LibrarySyncFileV1[]): LibrarySyncAssetSelection {
  const assets = new Map<string, { id: string; sha256: string; byteLength: number }>();
  for (const file of files) {
    if (file.kind !== "library") continue;
    for (const [id, meta] of Object.entries(file.assets)) {
      assets.set(id, { id, sha256: meta.sha256, byteLength: meta.byteLength });
    }
  }
  return { assets, totalBytes: [...assets.values()].reduce((sum, asset) => sum + asset.byteLength, 0) };
}

/** Explicit fan-out for files selected in the left column. */
export async function uploadLibrarySyncSelection(
  batch: LibrarySyncBatchV1,
  selectedFileNames: string[],
  transports: LibraryFileTransport[],
  options: LibrarySyncTransferOptions = {},
): Promise<LibraryBatchMirrorStatus[]> {
  const selected = new Set(selectedFileNames);
  const filesByName = new Map(batch.files
    .filter((file) => selected.has(librarySyncFileName(file)))
    .map((file) => [librarySyncFileName(file), file]));
  if (options.archive) {
    for (const fileName of selected) {
      if (filesByName.has(fileName)) continue;
      const archived = await options.archive.get(fileName);
      if (archived) filesByName.set(fileName, archived.file);
    }
  }
  const missingSelections = [...selected].filter((fileName) => !filesByName.has(fileName));
  const files = [...filesByName.values()];
  const assetSelection = librarySyncAssetSelection(files);
  const assets = assetSelection.assets;
  const statuses = await Promise.all(transports.map(async (transport): Promise<LibraryBatchMirrorStatus> => {
    let uploadedFiles = 0;
    let uploadedAssets = 0;
    try {
      if (missingSelections.length) throw new Error(`找不到所选本机同步文件：${missingSelections.join("、")}`);
      for (const file of files) {
        await transport.writeLibraryFile(file, librarySyncFileName(file));
        uploadedFiles += 1;
      }
      if (options.includeAssets) {
        if (!options.assetBackend && assets.size) throw new Error("未提供本地音频存储，无法上传所选词库的 MP3");
        for (const [id, { sha256 }] of assets) {
          const asset = await options.assetBackend?.getAsset(id);
          if (!asset) throw new Error(`本地缺少词库音频：${id}`);
          await transport.writeLibraryAsset(sha256, asset.bytes);
          uploadedAssets += 1;
        }
      }
      const locations = new Map(files.map((file) =>
        [`${file.sourceDevice.deviceCode}|${file.sourceDevice.location}`, file.sourceDevice]));
      for (const sourceDevice of locations.values()) {
        await pruneLocation(transport, { ...batch, sourceDevice });
      }
      return {
        transportId: transport.id, label: transport.label, status: "success", uploadedFiles, uploadedAssets,
        omittedAssets: options.includeAssets ? 0 : assets.size,
      };
    } catch (error) {
      return {
        transportId: transport.id, label: transport.label, status: "failed", uploadedFiles, uploadedAssets,
        omittedAssets: options.includeAssets ? 0 : assets.size,
        message: error instanceof Error ? error.message : String(error),
      };
    }
  }));
  if (options.archive && statuses.some((status) => status.status === "success")) {
    await archiveLibrarySyncFiles(options.archive, files);
  }
  return statuses;
}

/** Explicit download for files selected in the right column; it never applies them. */
export async function downloadLibrarySyncSelection(
  choices: CloudLibraryFileChoice[],
  selectedFileNames: string[],
  options: LibrarySyncTransferOptions = {},
): Promise<LibrarySyncFileV1[]> {
  const selected = new Set(selectedFileNames);
  const selectedChoices = choices.filter((choice) => selected.has(choice.fileName));
  const documents = await Promise.all(selectedChoices.map(async (choice) => {
    let lastError: unknown;
    for (const source of choice.sources) {
      try {
        const document = await source.transport.readLibraryFile(source.ref);
        return { choice, source, document };
      } catch (error) {
        lastError = error;
      }
    }
    throw lastError instanceof Error ? lastError : new Error("所有词库同步镜像均不可用");
  }));
  if (options.includeAssets) {
    if (!options.assetBackend) throw new Error("未提供本地音频存储，无法下载 MP3");
    for (const { source, document } of documents) {
      if (document.file.kind !== "library") continue;
      for (const [id, meta] of Object.entries(document.file.assets)) {
        if (await options.assetBackend.getAsset(id)) continue;
        const remote = await source.transport.readLibraryAsset(meta.sha256);
        if (!remote.data) throw new Error(`云端缺少词库音频：${id}`);
        await options.assetBackend.putAsset({ meta, bytes: remote.data });
      }
    }
  }
  if (options.archive) {
    for (const { choice, document } of documents) {
      await options.archive.put({
        fileName: choice.fileName,
        rawText: document.rawText,
        file: document.file,
        savedAt: new Date().toISOString(),
      });
    }
    await archiveLibrarySyncFiles(options.archive, []);
  }
  return documents.map(({ document }) => document.file);
}
