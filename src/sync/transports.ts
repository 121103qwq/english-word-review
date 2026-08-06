import { compactSnapshot } from "../core/events";
import { mergeSnapshots } from "../core/merge";
import type { V4Snapshot } from "../core/types";
import { httpRequest } from "../platform/runtime";

export interface RemoteDocument {
  snapshot: V4Snapshot | null;
  revision?: string;
}

export interface WriteResult {
  revision?: string;
}

export interface SyncTransport {
  name: "github" | "webdav";
  read(): Promise<RemoteDocument>;
  write(snapshot: V4Snapshot, revision?: string): Promise<WriteResult>;
}

export interface GitHubConfig {
  owner: string;
  repo: string;
  branch: string;
  path: string;
  token: string;
}

export interface WebDavConfig {
  url: string;
  username: string;
  password: string;
}

function parseSnapshot(text: string): V4Snapshot {
  const snapshot = JSON.parse(text) as V4Snapshot;
  if (snapshot.schemaVersion !== 4 || !snapshot.checkpoint || !Array.isArray(snapshot.events)) {
    throw new Error("远端文件不是有效的 v4 学习快照");
  }
  return snapshot;
}

function utf8ToBase64(value: string): string {
  const bytes = new TextEncoder().encode(value);
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function base64ToUtf8(value: string): string {
  const binary = atob(value.replace(/\s/g, ""));
  const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

export class GitHubTransport implements SyncTransport {
  readonly name = "github" as const;

  constructor(private readonly config: GitHubConfig) {}

  private endpoint(): string {
    const path = this.config.path.split("/").map(encodeURIComponent).join("/");
    return `https://api.github.com/repos/${encodeURIComponent(this.config.owner)}/${encodeURIComponent(this.config.repo)}/contents/${path}`;
  }

  private headers(): Record<string, string> {
    return {
      Accept: "application/vnd.github+json",
      Authorization: `Bearer ${this.config.token}`,
      "X-GitHub-Api-Version": "2022-11-28",
      "Content-Type": "application/json",
    };
  }

  async read(): Promise<RemoteDocument> {
    const response = await httpRequest({
      url: `${this.endpoint()}?ref=${encodeURIComponent(this.config.branch || "main")}`,
      method: "GET",
      headers: this.headers(),
    });
    if (response.status === 404) return { snapshot: null };
    if (response.status < 200 || response.status >= 300) throw new Error(`GitHub 读取失败（${response.status}）`);
    const body = JSON.parse(response.body) as { content: string; sha: string };
    return { snapshot: parseSnapshot(base64ToUtf8(body.content)), revision: body.sha };
  }

  async write(snapshot: V4Snapshot, revision?: string): Promise<WriteResult> {
    const body: Record<string, unknown> = {
      message: `Sync English Review ${snapshot.updatedAt}`,
      content: utf8ToBase64(JSON.stringify(snapshot)),
      branch: this.config.branch || "main",
    };
    if (revision) body.sha = revision;
    const response = await httpRequest({
      url: this.endpoint(),
      method: "PUT",
      headers: this.headers(),
      body: JSON.stringify(body),
    });
    if (response.status < 200 || response.status >= 300) {
      const conflict = response.status === 409 || response.status === 422 ? "CONFLICT:" : "";
      throw new Error(`${conflict}GitHub 写入失败（${response.status}）`);
    }
    const result = JSON.parse(response.body) as { content?: { sha?: string } };
    return { revision: result.content?.sha };
  }
}

export class WebDavTransport implements SyncTransport {
  readonly name = "webdav" as const;

  constructor(private readonly config: WebDavConfig) {}

  private headers(): Record<string, string> {
    const credentials = utf8ToBase64(`${this.config.username}:${this.config.password}`);
    return {
      Authorization: `Basic ${credentials}`,
      "Content-Type": "application/json; charset=utf-8",
    };
  }

  async read(): Promise<RemoteDocument> {
    const response = await httpRequest({ url: this.config.url, method: "GET", headers: this.headers() });
    if (response.status === 404) return { snapshot: null };
    if (response.status < 200 || response.status >= 300) throw new Error(`WebDAV 读取失败（${response.status}）`);
    return { snapshot: parseSnapshot(response.body), revision: response.headers.etag };
  }

  async write(snapshot: V4Snapshot, revision?: string): Promise<WriteResult> {
    const headers = this.headers();
    if (revision) headers["If-Match"] = revision;
    else headers["If-None-Match"] = "*";
    const response = await httpRequest({
      url: this.config.url,
      method: "PUT",
      headers,
      body: JSON.stringify(snapshot),
    });
    if (response.status < 200 || response.status >= 300) {
      const conflict = response.status === 409 || response.status === 412 ? "CONFLICT:" : "";
      throw new Error(`${conflict}WebDAV 写入失败（${response.status}）`);
    }
    return { revision: response.headers.etag };
  }
}

export interface MirrorStatus {
  name: string;
  read: "ok" | "missing" | "failed";
  write: "ok" | "failed" | "skipped";
  message?: string;
}

export interface SyncResult {
  snapshot: V4Snapshot;
  statuses: MirrorStatus[];
  complete: boolean;
}

export async function syncMirrors(local: V4Snapshot, transports: SyncTransport[]): Promise<SyncResult> {
  const reads = await Promise.allSettled(transports.map((transport) => transport.read()));
  const statuses: MirrorStatus[] = transports.map((transport, index) => {
    const result = reads[index];
    return result.status === "fulfilled"
      ? { name: transport.name, read: result.value.snapshot ? "ok" : "missing", write: "skipped" }
      : { name: transport.name, read: "failed", write: "skipped", message: String(result.reason?.message ?? result.reason) };
  });
  let canonical = mergeSnapshots(
    local,
    ...reads.map((result) => result.status === "fulfilled" ? result.value.snapshot : null),
  );
  if (transports.length > 1 && reads.every((result) => result.status === "fulfilled")) {
    canonical = compactSnapshot(canonical);
  }

  const writeAgainst = async (documents: typeof reads) => Promise.allSettled(
    transports.map((transport, index) => {
      const remote = documents[index];
      if (remote.status === "rejected") {
        return Promise.reject(new Error("读取失败，已跳过写入以避免覆盖远端数据"));
      }
      return transport.write(canonical, remote.value.revision);
    }),
  );

  let writes = await writeAgainst(reads);
  const hasConflict = writes.some((result) => result.status === "rejected" && String(result.reason?.message ?? result.reason).startsWith("CONFLICT:"));
  if (hasConflict) {
    const refreshed = await Promise.allSettled(transports.map((transport) => transport.read()));
    canonical = mergeSnapshots(
      canonical,
      ...refreshed.map((result) => result.status === "fulfilled" ? result.value.snapshot : null),
    );
    if (transports.length > 1 && refreshed.every((result) => result.status === "fulfilled")) {
      canonical = compactSnapshot(canonical);
    }
    writes = await writeAgainst(refreshed);
  }

  writes.forEach((result, index) => {
    if (reads[index].status === "rejected") {
      statuses[index].write = "skipped";
      return;
    }
    statuses[index].write = result.status === "fulfilled" ? "ok" : "failed";
    if (result.status === "rejected") {
      statuses[index].message = String(result.reason?.message ?? result.reason).replace(/^CONFLICT:/, "");
    }
  });
  return {
    snapshot: canonical,
    statuses,
    complete: reads.every((result) => result.status === "fulfilled") &&
      writes.every((result) => result.status === "fulfilled"),
  };
}
