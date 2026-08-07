import { describe, expect, it } from "vitest";
import { createEmptyContentSnapshot } from "../src/content/model";
import { ContentRepository, MemoryContentBackend, OUTBOX_RETRY_DELAYS_MS } from "../src/content/storage";

describe("content repository", () => {
  it("reads an 8.1.0 snapshot and stamps new mutations with 8.1.1", async () => {
    const backend = new MemoryContentBackend();
    const oldSnapshot = createEmptyContentSnapshot("device-a");
    oldSnapshot.appVersion = "8.1.0";
    await backend.putCurrent(oldSnapshot);
    const repository = new ContentRepository(backend, "device-a");

    expect((await repository.open()).appVersion).toBe("8.1.0");
    expect((await repository.commit(() => undefined)).appVersion).toBe("8.1.1");
  });

  it("backs up before each mutation and keeps exactly the newest 30", async () => {
    const backend = new MemoryContentBackend();
    let tick = 0;
    const repository = new ContentRepository(backend, "device-a", {
      now: () => new Date(1_000 + tick++),
      uuid: () => `id-${tick}`,
    });
    await repository.open();
    for (let index = 1; index <= 31; index += 1) {
      await repository.commit((snapshot) => {
        snapshot.activeLibraryId = `library-${index}`;
      });
    }
    const backups = (await backend.listBackups()).sort((a, b) => a.createdAt.localeCompare(b.createdAt));
    expect(backups).toHaveLength(30);
    expect(backups.at(-1)?.snapshot.activeLibraryId).toBe("library-30");
    expect(backups.some((backup) => backup.snapshot.activeLibraryId === null)).toBe(false);
    expect((await backend.getCurrent())?.activeLibraryId).toBe("library-31");
  });

  it("queues pending mirrors, deduplicates ids, and removes completed work", async () => {
    const backend = new MemoryContentBackend();
    let now = 10_000;
    const repository = new ContentRepository(backend, "device-a", {
      now: () => new Date(now),
      uuid: (() => { let id = 0; return () => `id-${++id}`; })(),
    });
    const changed = await repository.commit(() => undefined, ["github", "dav-a", "github"]);
    let item = (await backend.listOutbox())[0];
    expect(item.pendingMirrorIds).toEqual(["github", "dav-a"]);
    expect(new Date(item.nextAttemptAt).getTime() - now).toBe(OUTBOX_RETRY_DELAYS_MS[0]);
    now += 100;
    await repository.markOutboxAttempt(changed.revisionId);
    item = (await backend.listOutbox())[0];
    expect(item.attempt).toBe(1);
    expect(new Date(item.nextAttemptAt).getTime() - now).toBe(OUTBOX_RETRY_DELAYS_MS[1]);
    await repository.markMirrorComplete(changed.revisionId, "github");
    expect((await backend.listOutbox())[0].pendingMirrorIds).toEqual(["dav-a"]);
    await repository.markMirrorComplete(changed.revisionId, "dav-a");
    expect(await backend.listOutbox()).toEqual([]);
  });

  it("deduplicates stored audio by its content id", async () => {
    const backend = new MemoryContentBackend();
    const repository = new ContentRepository(backend, "device-a");
    const asset = {
      meta: { id: "hash", sha256: "hash", mimeType: "audio/mpeg" as const, byteLength: 3, fileName: "a.mp3", createdAt: "now" },
      bytes: new Uint8Array([1, 2, 3]),
    };
    await repository.saveAsset(asset);
    await repository.saveAsset({ ...asset, bytes: new Uint8Array([9]) });
    expect((await backend.getAsset("hash"))?.bytes).toEqual(new Uint8Array([1, 2, 3]));
  });

  it("keeps audio referenced by retained backups and removes only orphaned blobs", async () => {
    const backend = new MemoryContentBackend();
    const repository = new ContentRepository(backend, "device-a", {
      uuid: (() => { let id = 0; return () => `id-${++id}`; })(),
    });
    const makeAsset = (id: string) => ({
      meta: { id, sha256: id, mimeType: "audio/mpeg" as const, byteLength: 1, fileName: `${id}.mp3`, createdAt: "now" },
      bytes: new Uint8Array([1]),
    });
    await repository.saveAsset(makeAsset("kept"));
    await repository.saveAsset(makeAsset("orphan"));
    await repository.commit((snapshot) => {
      snapshot.globalOverrides.apple = { audioAssetIds: ["kept"], primaryAudioAssetId: "kept" };
    });
    expect(await repository.pruneUnreferencedAssets()).toEqual(["orphan"]);
    expect(await backend.getAsset("kept")).toBeDefined();
  });
});
