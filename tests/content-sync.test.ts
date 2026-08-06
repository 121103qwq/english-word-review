import { describe, expect, it } from "vitest";
import { MemoryContentBackend } from "../src/content/storage";
import type { ContentSnapshotV1 } from "../src/content/types";
import {
  commitContentMutation,
  compareContentRevision,
  friendlyContentSyncError,
  retryContentOutbox,
  syncContentStartup,
} from "../src/sync/content-sync";
import type {
  ContentAssetDocument,
  ContentBackupRef,
  ContentRemoteDocument,
  ContentTransport,
} from "../src/sync/content-transports";

function snapshot(wallTime: number, logical: number, deviceId: string, revisionId: string): ContentSnapshotV1 {
  return {
    schemaVersion: 1,
    appVersion: "8.1.0",
    dictionaryVersion: "test",
    revision: { wallTime, logical, deviceId },
    revisionId,
    modifiedAt: new Date(wallTime).toISOString(),
    activeLibraryId: null,
    libraries: [],
    globalOverrides: {},
    assets: {},
  };
}

class FakeContentTransport implements ContentTransport {
  readonly label: string;
  currentRevision = 0;
  backups: Array<{ snapshot: ContentSnapshotV1; ref: ContentBackupRef }> = [];
  assets = new Map<string, Uint8Array>();
  failRead = false;
  failWrite = false;

  constructor(
    readonly id: string,
    readonly kind: "github" | "webdav",
    public current: ContentSnapshotV1 | null,
  ) {
    this.label = kind === "github" ? "GitHub" : `WebDAV ${id}`;
  }

  async readCurrent(): Promise<ContentRemoteDocument> {
    if (this.failRead) throw new Error("request timeout after 30s");
    return { snapshot: this.current && structuredClone(this.current), revision: `r${this.currentRevision}` };
  }

  async writeCurrent(value: ContentSnapshotV1): Promise<{ revision?: string }> {
    if (this.failWrite) throw new Error("request timeout after 30s");
    this.current = structuredClone(value);
    this.currentRevision += 1;
    return { revision: `r${this.currentRevision}` };
  }

  async writeBackup(value: ContentSnapshotV1): Promise<{ revision?: string }> {
    if (!this.backups.some((item) => item.snapshot.revisionId === value.revisionId)) {
      const ref: ContentBackupRef = {
        id: value.revisionId,
        path: value.revisionId,
        revision: `b${this.backups.length}`,
        wallTime: value.revision.wallTime,
        logical: value.revision.logical,
        deviceId: value.revision.deviceId,
        revisionId: value.revisionId,
      };
      this.backups.push({ snapshot: structuredClone(value), ref });
    }
    return {};
  }

  async listBackups(): Promise<ContentBackupRef[]> {
    return this.backups.map((item) => ({ ...item.ref }));
  }

  async readBackup(backup: ContentBackupRef): Promise<ContentSnapshotV1> {
    const found = this.backups.find((item) => item.ref.id === backup.id);
    if (!found) throw new Error("backup missing");
    return structuredClone(found.snapshot);
  }

  async deleteBackup(backup: ContentBackupRef): Promise<void> {
    this.backups = this.backups.filter((item) => item.ref.id !== backup.id);
  }

  async listAssets(): Promise<Array<{ hash: string }>> {
    return [...this.assets.keys()].map((hash) => ({ hash }));
  }

  async readAsset(hash: string): Promise<ContentAssetDocument> {
    return { data: this.assets.get(hash) ?? null };
  }

  async writeAsset(hash: string, data: Uint8Array): Promise<{}> {
    this.assets.set(hash, new Uint8Array(data));
    return {};
  }

  async deleteAsset(hash: string): Promise<void> {
    this.assets.delete(hash);
  }
}

describe("content snapshot mirror synchronization", () => {
  it("compares all LWW fields in wallTime/logical/deviceId/revisionId order", () => {
    expect(compareContentRevision(snapshot(2, 0, "a", "a"), snapshot(1, 99, "z", "z"))).toBeGreaterThan(0);
    expect(compareContentRevision(snapshot(2, 2, "a", "a"), snapshot(2, 1, "z", "z"))).toBeGreaterThan(0);
    expect(compareContentRevision(snapshot(2, 2, "b", "a"), snapshot(2, 2, "a", "z"))).toBeGreaterThan(0);
    expect(compareContentRevision(snapshot(2, 2, "b", "z"), snapshot(2, 2, "b", "a"))).toBeGreaterThan(0);
  });

  it("downloads the newest cloud snapshot and backs up every value it overwrites", async () => {
    const persistence = new MemoryContentBackend();
    const local = snapshot(10, 0, "local", "local-old");
    const newest = snapshot(30, 0, "cloud", "remote-new");
    const oldRemote = snapshot(20, 0, "dav", "remote-old");
    await persistence.setCurrent(local);
    const github = new FakeContentTransport("github", "github", newest);
    const webdav = new FakeContentTransport("dav-1", "webdav", oldRemote);

    const result = await syncContentStartup({ persistence, transports: [github, webdav], now: () => new Date(40) });

    expect(result.source).toBe("github");
    expect((await persistence.getCurrent())?.revisionId).toBe("remote-new");
    expect((await persistence.listBackups()).map((backup) => backup.snapshot.revisionId)).toContain("local-old");
    expect(webdav.backups.map((backup) => backup.snapshot.revisionId)).toContain("remote-old");
    expect(webdav.current?.revisionId).toBe("remote-new");
    expect(result.complete).toBe(true);
  });

  it("keeps an offline edit locally, queues failed mirrors, and hides GitHub timeout text", async () => {
    const persistence = new MemoryContentBackend();
    await persistence.setCurrent(snapshot(10, 0, "local", "before"));
    const github = new FakeContentTransport("github", "github", null);
    github.failRead = true;

    const result = await commitContentMutation({
      persistence,
      transports: [github],
      deviceId: "local",
      now: () => new Date(20),
      uuid: () => "after",
      mutate(value) {
        value.globalOverrides.apple = { meaning: "苹果" };
      },
    });

    expect(result.complete).toBe(false);
    expect(result.snapshot.globalOverrides.apple.meaning).toBe("苹果");
    expect(result.statuses[0].message).toBe("GitHub 暂不可用，已保存本地并排队");
    expect(result.statuses[0].message).not.toMatch(/timeout/i);
    const outbox = await persistence.listOutbox();
    expect(outbox).toHaveLength(1);
    expect(outbox[0].pendingMirrorIds).toEqual(["github"]);
    expect(outbox[0].nextAttemptAt).toBe(new Date(15_020).toISOString());
  });

  it("retains exactly the newest 30 local and remote backups", async () => {
    const persistence = new MemoryContentBackend();
    await persistence.setCurrent(snapshot(1, 0, "local", "r0"));
    const webdav = new FakeContentTransport("dav", "webdav", snapshot(1, 0, "local", "r0"));

    for (let index = 1; index <= 31; index += 1) {
      await commitContentMutation({
        persistence,
        transports: [webdav],
        deviceId: "local",
        now: () => new Date(index + 1),
        uuid: () => `r${index}`,
        mutate() {},
      });
    }

    expect(await persistence.listBackups()).toHaveLength(30);
    expect(webdav.backups).toHaveLength(30);
    expect((await persistence.listBackups()).some((backup) => backup.snapshot.revisionId === "r0")).toBe(false);
    expect(webdav.backups.some((backup) => backup.snapshot.revisionId === "r0")).toBe(false);
  });

  it("deletes remote audio only after current and retained backups stop referencing it", async () => {
    const persistence = new MemoryContentBackend();
    const old = snapshot(1, 0, "local", "old");
    old.assets.orphan = {
      id: "orphan", sha256: "orphan", fileName: "old.mp3", byteLength: 3,
      mimeType: "audio/mpeg", createdAt: new Date(1).toISOString(),
    };
    old.globalOverrides.apple = { audioAssetIds: ["orphan"], primaryAudioAssetId: "orphan" };
    await persistence.setCurrent(old);
    const webdav = new FakeContentTransport("dav", "webdav", structuredClone(old));
    webdav.assets.set("orphan", new Uint8Array([1, 2, 3]));

    for (let index = 1; index <= 31; index += 1) {
      await commitContentMutation({
        persistence,
        transports: [webdav],
        deviceId: "local",
        now: () => new Date(index + 1),
        uuid: () => `audio-r${index}`,
        mutate(value) {
          delete value.globalOverrides.apple;
        },
      });
    }

    expect(webdav.assets.has("orphan")).toBe(false);
  });

  it("retries due outbox entries and removes them after success", async () => {
    const persistence = new MemoryContentBackend();
    const value = snapshot(10, 0, "local", "queued");
    await persistence.enqueueOutbox({
      id: "queued",
      createdAt: new Date(10).toISOString(),
      snapshot: value,
      pendingMirrorIds: ["dav"],
      attempt: 0,
      nextAttemptAt: new Date(20).toISOString(),
    });
    const webdav = new FakeContentTransport("dav", "webdav", snapshot(5, 0, "dav", "old-remote"));

    const result = await retryContentOutbox(persistence, [webdav], new Date(20));

    expect(result).toEqual({ attempted: 1, completed: 1, pending: 0 });
    expect(webdav.current?.revisionId).toBe("queued");
    expect(webdav.backups.map((backup) => backup.snapshot.revisionId)).toContain("old-remote");
  });

  it("sanitizes only GitHub network failures without hiding useful HTTP errors", () => {
    expect(friendlyContentSyncError({ kind: "github", label: "GitHub" }, new Error("ETIMEDOUT")))
      .toBe("GitHub 暂不可用，已保存本地并排队");
    expect(friendlyContentSyncError({ kind: "github", label: "GitHub" }, new Error("HTTP 401")))
      .toContain("401");
  });
});
