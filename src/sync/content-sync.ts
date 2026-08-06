import { compareContentRevision, nextHybridClock } from "../content/model";
import { CONTENT_BACKUP_LIMIT, OUTBOX_RETRY_DELAYS_MS } from "../content/storage";
import type { ContentPersistence, ContentStorageBackend } from "../content/storage";
import type { ContentBackup, ContentOutboxItem, ContentSnapshotV1, StoredAudioAsset } from "../content/types";
import type { ContentBackupRef, ContentRemoteDocument, ContentTransport } from "./content-transports";

export { compareContentRevision };

export interface ContentMirrorStatus {
  id: string;
  kind: ContentTransport["kind"];
  label: string;
  read: "ok" | "missing" | "failed";
  backup: "ok" | "failed" | "skipped";
  write: "ok" | "failed" | "skipped";
  prune: "ok" | "failed" | "skipped";
  assets: "ok" | "failed" | "skipped";
  queued: boolean;
  message?: string;
}

export interface ContentSyncResult {
  snapshot: ContentSnapshotV1;
  statuses: ContentMirrorStatus[];
  complete: boolean;
  source: "local" | string;
}

export interface ContentSyncOptions {
  persistence: ContentPersistence;
  transports: ContentTransport[];
  now?: () => Date;
  uuid?: () => string;
}

export interface CommitContentMutationOptions extends ContentSyncOptions {
  deviceId: string;
  mutate(snapshot: ContentSnapshotV1): ContentSnapshotV1 | void | Promise<ContentSnapshotV1 | void>;
}

interface ReadableMirror {
  transport: ContentTransport;
  document: ContentRemoteDocument;
  status: ContentMirrorStatus;
}

type AssetPersistence = ContentPersistence & Partial<Pick<ContentStorageBackend, "getAsset" | "putAsset">>;
type OutboxPersistence = ContentPersistence & Partial<Pick<ContentStorageBackend, "listOutbox" | "putOutbox" | "deleteOutbox">>;

const clone = <T>(value: T): T => structuredClone(value);
const defaultUuid = (): string => globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`;

function compareBackupRef(a: ContentBackupRef, b: ContentBackupRef): number {
  return a.wallTime - b.wallTime || a.logical - b.logical ||
    a.deviceId.localeCompare(b.deviceId) || a.revisionId.localeCompare(b.revisionId) || a.id.localeCompare(b.id);
}

function compareLocalBackup(a: ContentBackup, b: ContentBackup): number {
  return compareContentRevision(a.snapshot, b.snapshot) || a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id);
}

function initialStatus(transport: ContentTransport): ContentMirrorStatus {
  return {
    id: transport.id,
    kind: transport.kind,
    label: transport.label,
    read: "failed",
    backup: "skipped",
    write: "skipped",
    prune: "skipped",
    assets: "skipped",
    queued: false,
  };
}

function rawMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

export function friendlyContentSyncError(transport: Pick<ContentTransport, "kind" | "label">, error: unknown): string {
  const message = rawMessage(error).replace(/^CONFLICT:/, "");
  if (transport.kind === "github" && /timeout|timed out|etimedout|abort|network|failed to fetch|econn|socket/i.test(message)) {
    return "GitHub 暂不可用，已保存本地并排队";
  }
  if (transport.kind === "github") return `GitHub 同步失败，已保存本地并排队：${message}`;
  return `${transport.label || "WebDAV"} 暂不可用，已保存本地并排队：${message}`;
}

function assertDistinctMirrorIds(transports: ContentTransport[]): void {
  const ids = new Set<string>();
  for (const transport of transports) {
    if (!transport.id || ids.has(transport.id)) throw new Error(`同步镜像 ID 重复：${transport.id || "（空）"}`);
    ids.add(transport.id);
  }
}

async function readMirrors(transports: ContentTransport[]): Promise<{ readable: ReadableMirror[]; statuses: ContentMirrorStatus[] }> {
  const reads = await Promise.allSettled(transports.map((transport) => transport.readCurrent()));
  const statuses = transports.map(initialStatus);
  const readable: ReadableMirror[] = [];
  reads.forEach((result, index) => {
    const transport = transports[index];
    const status = statuses[index];
    if (result.status === "fulfilled") {
      status.read = result.value.snapshot ? "ok" : "missing";
      readable.push({ transport, document: result.value, status });
    } else {
      status.message = friendlyContentSyncError(transport, result.reason);
    }
  });
  return { readable, statuses };
}

function selectLatest(
  local: ContentSnapshotV1 | undefined,
  mirrors: ReadableMirror[],
): { snapshot: ContentSnapshotV1; source: "local" | string } {
  const candidates: Array<{ snapshot: ContentSnapshotV1; source: "local" | string }> = [];
  if (local) candidates.push({ snapshot: local, source: "local" });
  for (const mirror of mirrors) {
    if (mirror.document.snapshot) candidates.push({ snapshot: mirror.document.snapshot, source: mirror.transport.id });
  }
  if (!candidates.length) throw new Error("本地和远端均没有可用的内容快照");
  return candidates.reduce((latest, candidate) =>
    compareContentRevision(candidate.snapshot, latest.snapshot) > 0 ? candidate : latest);
}

function createBackup(snapshot: ContentSnapshotV1, createdAt: string): ContentBackup {
  return { id: snapshot.revisionId, createdAt, snapshot: clone(snapshot) };
}

async function pruneLocalBackups(persistence: ContentPersistence): Promise<void> {
  const backups = (await persistence.listBackups()).sort(compareLocalBackup);
  for (const backup of backups.slice(0, Math.max(0, backups.length - CONTENT_BACKUP_LIMIT))) {
    await persistence.deleteBackup(backup.id);
  }
}

async function saveLocalBackup(persistence: ContentPersistence, snapshot: ContentSnapshotV1, now: Date): Promise<void> {
  await persistence.saveBackup(createBackup(snapshot, now.toISOString()));
  await pruneLocalBackups(persistence);
}

async function pruneRemoteBackups(transport: ContentTransport): Promise<ContentBackupRef[]> {
  const backups = (await transport.listBackups()).sort(compareBackupRef);
  const expired = backups.slice(0, Math.max(0, backups.length - CONTENT_BACKUP_LIMIT));
  for (const backup of expired) {
    await transport.deleteBackup(backup);
  }
  return backups.slice(expired.length);
}

function referencedAssets(snapshot: ContentSnapshotV1): Set<string> {
  const referenced = new Set<string>();
  const collect = (override: { audioAssetIds?: string[]; primaryAudioAssetId?: string | null } | undefined) => {
    override?.audioAssetIds?.forEach((id) => referenced.add(id));
    if (override?.primaryAudioAssetId) referenced.add(override.primaryAudioAssetId);
  };
  Object.values(snapshot.globalOverrides).forEach(collect);
  for (const library of snapshot.libraries) {
    for (const word of library.words) {
      collect(word.legacyOverride);
      collect(word.override);
    }
  }
  return referenced;
}

function pruneSnapshotAssetManifest(snapshot: ContentSnapshotV1): void {
  const referenced = referencedAssets(snapshot);
  for (const assetId of Object.keys(snapshot.assets)) {
    if (!referenced.has(assetId)) delete snapshot.assets[assetId];
  }
}

async function pruneRemoteAssets(
  transport: ContentTransport,
  current: ContentSnapshotV1,
  retainedBackups: ContentBackupRef[],
): Promise<void> {
  const referenced = referencedAssets(current);
  for (const backup of retainedBackups) {
    for (const assetId of referencedAssets(await transport.readBackup(backup))) referenced.add(assetId);
  }
  for (const asset of await transport.listAssets()) {
    if (!referenced.has(asset.hash)) await transport.deleteAsset(asset.hash, asset.revision);
  }
}

async function enqueue(
  persistence: ContentPersistence,
  snapshot: ContentSnapshotV1,
  pendingMirrorIds: string[],
  now: Date,
): Promise<void> {
  if (!pendingMirrorIds.length) return;
  const item: ContentOutboxItem = {
    id: snapshot.revisionId,
    createdAt: now.toISOString(),
    snapshot: clone(snapshot),
    pendingMirrorIds: [...new Set(pendingMirrorIds)],
    attempt: 0,
    nextAttemptAt: new Date(now.getTime() + OUTBOX_RETRY_DELAYS_MS[0]).toISOString(),
  };
  await persistence.enqueueOutbox(item);
}

async function syncAssetsToMirror(
  snapshot: ContentSnapshotV1,
  persistence: AssetPersistence,
  transport: ContentTransport,
): Promise<void> {
  if (!persistence.getAsset) return;
  for (const assetId of Object.keys(snapshot.assets)) {
    const local = await persistence.getAsset(assetId);
    const remote = await transport.readAsset(assetId);
    if (local && !remote.data) await transport.writeAsset(assetId, local.bytes);
    else if (!local && remote.data && persistence.putAsset) {
      const meta = snapshot.assets[assetId];
      const stored: StoredAudioAsset = { meta, bytes: remote.data };
      await persistence.putAsset(stored);
    } else if (!local && !remote.data) {
      throw new Error(`音频资源缺失：${assetId}`);
    }
  }
}

async function hydrateLocalAssets(
  snapshot: ContentSnapshotV1,
  persistence: AssetPersistence,
  mirrors: ReadableMirror[],
): Promise<void> {
  if (!persistence.getAsset || !persistence.putAsset) return;
  for (const assetId of Object.keys(snapshot.assets)) {
    if (await persistence.getAsset(assetId)) continue;
    for (const mirror of mirrors) {
      try {
        const remote = await mirror.transport.readAsset(assetId);
        if (!remote.data) continue;
        await persistence.putAsset({ meta: snapshot.assets[assetId], bytes: remote.data });
        break;
      } catch {
        // The per-mirror convergence step reports the actionable failure.
      }
    }
  }
}

async function overwriteMirror(
  mirror: ReadableMirror,
  snapshot: ContentSnapshotV1,
  persistence: AssetPersistence,
  backupBeforeOverwrite: boolean,
): Promise<void> {
  if (backupBeforeOverwrite && mirror.document.snapshot) {
    await mirror.transport.writeBackup(mirror.document.snapshot);
    mirror.status.backup = "ok";
  }
  await syncAssetsToMirror(snapshot, persistence, mirror.transport);
  mirror.status.assets = "ok";
  try {
    await mirror.transport.writeCurrent(snapshot, mirror.document.revision);
  } catch (error) {
    if (!rawMessage(error).startsWith("CONFLICT:")) throw error;
    const refreshed = await mirror.transport.readCurrent();
    if (refreshed.snapshot && compareContentRevision(refreshed.snapshot, snapshot) > 0) {
      throw new NewerRemoteSnapshotError(refreshed.snapshot);
    }
    await mirror.transport.writeCurrent(snapshot, refreshed.revision);
  }
  mirror.status.write = "ok";
  if (mirror.status.backup === "ok") {
    const retainedBackups = await pruneRemoteBackups(mirror.transport);
    await pruneRemoteAssets(mirror.transport, snapshot, retainedBackups);
    mirror.status.prune = "ok";
  }
}

class NewerRemoteSnapshotError extends Error {
  constructor(readonly snapshot: ContentSnapshotV1) {
    super("同步期间远端出现更新版本");
  }
}

/**
 * Startup convergence is strict last-write-wins. A replaced local or remote
 * current snapshot is backed up before it is overwritten.
 */
export async function syncContentStartup(options: ContentSyncOptions): Promise<ContentSyncResult> {
  assertDistinctMirrorIds(options.transports);
  const now = (options.now ?? (() => new Date()))();
  const local = await options.persistence.getCurrent();
  const { readable, statuses } = await readMirrors(options.transports);
  let latest = selectLatest(local, readable);

  await hydrateLocalAssets(latest.snapshot, options.persistence as AssetPersistence, readable);

  if (local && compareContentRevision(latest.snapshot, local) > 0) {
    await saveLocalBackup(options.persistence, local, now);
  }
  await options.persistence.setCurrent(latest.snapshot);

  const pending = statuses.filter((status) => status.read === "failed").map((status) => status.id);
  for (const mirror of readable) {
    const remote = mirror.document.snapshot;
    if (remote && compareContentRevision(remote, latest.snapshot) === 0) {
      try {
        await syncAssetsToMirror(latest.snapshot, options.persistence as AssetPersistence, mirror.transport);
        mirror.status.assets = "ok";
        mirror.status.write = "ok";
      } catch (error) {
        mirror.status.assets = "failed";
        mirror.status.message = friendlyContentSyncError(mirror.transport, error);
        mirror.status.queued = true;
        pending.push(mirror.transport.id);
      }
      continue;
    }
    try {
      await overwriteMirror(mirror, latest.snapshot, options.persistence as AssetPersistence, Boolean(remote));
    } catch (error) {
      if (error instanceof NewerRemoteSnapshotError && compareContentRevision(error.snapshot, latest.snapshot) > 0) {
        await saveLocalBackup(options.persistence, latest.snapshot, now);
        latest = { snapshot: error.snapshot, source: mirror.transport.id };
        await options.persistence.setCurrent(latest.snapshot);
        pending.push(...options.transports.map((transport) => transport.id));
      }
      mirror.status.write = "failed";
      mirror.status.message = friendlyContentSyncError(mirror.transport, error);
      mirror.status.queued = true;
      pending.push(mirror.transport.id);
    }
  }

  const uniquePending = [...new Set(pending)];
  for (const status of statuses) status.queued ||= uniquePending.includes(status.id);
  await enqueue(options.persistence, latest.snapshot, uniquePending, now);
  return {
    snapshot: clone(latest.snapshot),
    statuses,
    complete: uniquePending.length === 0,
    source: latest.source,
  };
}

/**
 * Checks every mirror, backs up the newest pre-mutation state, applies one
 * local atomic mutation, then writes the resulting snapshot to each mirror.
 */
export async function commitContentMutation(options: CommitContentMutationOptions): Promise<ContentSyncResult> {
  assertDistinctMirrorIds(options.transports);
  const now = (options.now ?? (() => new Date()))();
  const uuid = options.uuid ?? defaultUuid;
  const local = await options.persistence.getCurrent();
  const { readable, statuses } = await readMirrors(options.transports);
  const latest = selectLatest(local, readable);

  await hydrateLocalAssets(latest.snapshot, options.persistence as AssetPersistence, readable);

  if (local && compareContentRevision(latest.snapshot, local) > 0) {
    await saveLocalBackup(options.persistence, local, now);
  }
  await options.persistence.setCurrent(latest.snapshot);
  await saveLocalBackup(options.persistence, latest.snapshot, now);

  const pending = statuses.filter((status) => status.read === "failed").map((status) => status.id);
  for (const mirror of readable) {
    try {
      await mirror.transport.writeBackup(latest.snapshot);
      mirror.status.backup = "ok";
    } catch (error) {
      mirror.status.backup = "failed";
      mirror.status.message = friendlyContentSyncError(mirror.transport, error);
      mirror.status.queued = true;
      pending.push(mirror.transport.id);
    }
  }

  const draft = clone(latest.snapshot);
  const mutationResult = await options.mutate(draft);
  let changed = mutationResult ?? draft;
  pruneSnapshotAssetManifest(changed);
  changed.appVersion = "8.1.0";
  changed.revision = nextHybridClock(latest.snapshot.revision, options.deviceId, now.getTime());
  changed.revisionId = uuid();
  changed.modifiedAt = now.toISOString();
  await options.persistence.setCurrent(changed);

  for (const mirror of readable) {
    if (mirror.status.backup === "failed") continue;
    try {
      await syncAssetsToMirror(changed, options.persistence as AssetPersistence, mirror.transport);
      mirror.status.assets = "ok";
      try {
        await mirror.transport.writeCurrent(changed, mirror.document.revision);
      } catch (error) {
        if (!rawMessage(error).startsWith("CONFLICT:")) throw error;
        const refreshed = await mirror.transport.readCurrent();
        if (refreshed.snapshot && compareContentRevision(refreshed.snapshot, changed) > 0) {
          throw new NewerRemoteSnapshotError(refreshed.snapshot);
        }
        await mirror.transport.writeCurrent(changed, refreshed.revision);
      }
      mirror.status.write = "ok";
      if (mirror.status.backup === "ok") {
        const retainedBackups = await pruneRemoteBackups(mirror.transport);
        await pruneRemoteAssets(mirror.transport, changed, retainedBackups);
        mirror.status.prune = "ok";
      }
    } catch (error) {
      if (error instanceof NewerRemoteSnapshotError && compareContentRevision(error.snapshot, changed) > 0) {
        await saveLocalBackup(options.persistence, changed, now);
        changed = clone(error.snapshot);
        await options.persistence.setCurrent(changed);
        pending.push(...options.transports.map((transport) => transport.id));
      }
      mirror.status.write = "failed";
      mirror.status.message = friendlyContentSyncError(mirror.transport, error);
      mirror.status.queued = true;
      pending.push(mirror.transport.id);
    }
  }

  const uniquePending = [...new Set(pending)];
  for (const status of statuses) status.queued ||= uniquePending.includes(status.id);
  await enqueue(options.persistence, changed, uniquePending, now);
  return {
    snapshot: clone(changed),
    statuses,
    complete: uniquePending.length === 0,
    source: "local",
  };
}

export interface RetryOutboxResult {
  attempted: number;
  completed: number;
  pending: number;
}

/** Retries due outbox records; callers may invoke this at 15s/1m/5m and startup. */
export async function retryContentOutbox(
  persistence: OutboxPersistence,
  transports: ContentTransport[],
  now = new Date(),
): Promise<RetryOutboxResult> {
  if (!persistence.listOutbox || !persistence.putOutbox || !persistence.deleteOutbox) {
    return { attempted: 0, completed: 0, pending: 0 };
  }
  const transportById = new Map(transports.map((transport) => [transport.id, transport]));
  const items = await persistence.listOutbox();
  let attempted = 0;
  let completed = 0;
  const followups = new Map<string, { snapshot: ContentSnapshotV1; mirrorIds: Set<string> }>();
  for (const item of items) {
    if (new Date(item.nextAttemptAt).getTime() > now.getTime()) continue;
    attempted += 1;
    const stillPending: string[] = [];
    for (const mirrorId of item.pendingMirrorIds) {
      const transport = transportById.get(mirrorId);
      if (!transport) {
        stillPending.push(mirrorId);
        continue;
      }
      try {
        const remote = await transport.readCurrent();
        const canonical = remote.snapshot && compareContentRevision(remote.snapshot, item.snapshot) > 0
          ? remote.snapshot
          : item.snapshot;
        if (compareContentRevision(canonical, item.snapshot) > 0) {
          const current = await persistence.getCurrent();
          if (!current || compareContentRevision(canonical, current) > 0) {
            if (current) await saveLocalBackup(persistence, current, now);
            await persistence.setCurrent(canonical);
          }
          const followup = followups.get(canonical.revisionId) ?? { snapshot: canonical, mirrorIds: new Set<string>() };
          for (const candidate of transports) {
            if (candidate.id !== transport.id) followup.mirrorIds.add(candidate.id);
          }
          followups.set(canonical.revisionId, followup);
        }
        await syncAssetsToMirror(canonical, persistence as AssetPersistence, transport);
        if (!remote.snapshot || compareContentRevision(remote.snapshot, canonical) < 0) {
          if (remote.snapshot) await transport.writeBackup(remote.snapshot);
          await transport.writeCurrent(canonical, remote.revision);
        }
        const retainedBackups = await pruneRemoteBackups(transport);
        await pruneRemoteAssets(transport, canonical, retainedBackups);
      } catch {
        stillPending.push(mirrorId);
      }
    }
    if (!stillPending.length) {
      await persistence.deleteOutbox(item.id);
      completed += 1;
    } else {
      const attempt = item.attempt + 1;
      const delay = OUTBOX_RETRY_DELAYS_MS[Math.min(attempt, OUTBOX_RETRY_DELAYS_MS.length - 1)];
      await persistence.putOutbox({
        ...item,
        pendingMirrorIds: stillPending,
        attempt,
        nextAttemptAt: new Date(now.getTime() + delay).toISOString(),
      });
    }
  }
  for (const followup of followups.values()) {
    await enqueue(persistence, followup.snapshot, [...followup.mirrorIds], now);
  }
  return { attempted, completed, pending: (await persistence.listOutbox()).length };
}
