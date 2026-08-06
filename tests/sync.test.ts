import { describe, expect, it } from "vitest";
import { DEVICE_ID_KEY } from "../src/core/config";
import { EventStore } from "../src/core/events";
import type { V4Snapshot } from "../src/core/types";
import { syncMirrors, type RemoteDocument, type SyncTransport } from "../src/sync/transports";
import { legacyBundle, MemoryStorage } from "./fixtures";

class FakeTransport implements SyncTransport {
  writes: V4Snapshot[] = [];
  readCount = 0;

  constructor(
    readonly name: "github" | "webdav",
    private remote: V4Snapshot | null,
    private readonly behavior: "ok" | "fail-read" | "fail-write" | "conflict-once" = "ok",
  ) {}

  async read(): Promise<RemoteDocument> {
    this.readCount += 1;
    if (this.behavior === "fail-read") throw new Error("cors");
    return { snapshot: this.remote, revision: `r${this.readCount}` };
  }

  async write(snapshot: V4Snapshot): Promise<{ revision?: string }> {
    if (this.behavior === "fail-write") throw new Error("offline");
    if (this.behavior === "conflict-once" && this.writes.length === 0) {
      this.writes.push(structuredClone(snapshot));
      throw new Error("CONFLICT:changed");
    }
    this.writes.push(structuredClone(snapshot));
    this.remote = structuredClone(snapshot);
    return { revision: `w${this.writes.length}` };
  }
}

function storeFor(deviceId: string): EventStore {
  const storage = new MemoryStorage();
  storage.setItem(DEVICE_ID_KEY, deviceId);
  return EventStore.open(legacyBundle(), storage);
}

describe("dual mirror convergence", () => {
  it("merges offline devices, writes a compact checkpoint, and is idempotent", async () => {
    const local = storeFor("local");
    const remote = storeFor("remote");
    local.recordAnswer({ area: "library", libraryId: "daily-a", itemId: "accept", mode: "reverse", correct: false });
    remote.recordAnswer({ area: "library", libraryId: "daily-a", itemId: "except", mode: "forward", correct: true });
    const github = new FakeTransport("github", remote.getSnapshot());
    const webdav = new FakeTransport("webdav", remote.getSnapshot());
    const first = await syncMirrors(local.getSnapshot(), [github, webdav]);
    expect(first.complete).toBe(true);
    expect(first.snapshot.events).toHaveLength(0);
    expect(first.snapshot.checkpoint.vector).toEqual({ local: 1, remote: 1 });
    expect(first.snapshot.checkpoint.data.store.current.words[0].reverseWrong).toBe(1);
    expect(first.snapshot.checkpoint.data.store.current.words[1].right).toBe(1);
    const second = await syncMirrors(first.snapshot, [github, webdav]);
    expect(second.snapshot.checkpoint.data).toEqual(first.snapshot.checkpoint.data);
  });

  it("reports partial success and keeps the canonical snapshot for later repair", async () => {
    const local = storeFor("local");
    local.recordAnswer({ area: "library", libraryId: "daily-a", itemId: "accept", mode: "forward", correct: true });
    const github = new FakeTransport("github", null);
    const webdav = new FakeTransport("webdav", null, "fail-write");
    const result = await syncMirrors(local.getSnapshot(), [github, webdav]);
    expect(result.complete).toBe(false);
    expect(result.statuses.map((status) => status.write)).toEqual(["ok", "failed"]);
    expect(result.snapshot.checkpoint.data.store.current.words[0].right).toBe(1);
  });

  it("never writes a mirror that could not be read", async () => {
    const local = storeFor("local");
    local.recordAnswer({ area: "library", libraryId: "daily-a", itemId: "accept", mode: "forward", correct: true });
    const github = new FakeTransport("github", null);
    const webdav = new FakeTransport("webdav", null, "fail-read");
    const result = await syncMirrors(local.getSnapshot(), [github, webdav]);
    expect(result.complete).toBe(false);
    expect(result.statuses[1]).toMatchObject({ read: "failed", write: "skipped" });
    expect(webdav.writes).toHaveLength(0);
    expect(result.snapshot.events).toHaveLength(1);
  });

  it("re-reads and retries once after a SHA/ETag conflict", async () => {
    const local = storeFor("local");
    local.recordAnswer({ area: "library", libraryId: "daily-a", itemId: "accept", mode: "forward", correct: true });
    const github = new FakeTransport("github", null, "conflict-once");
    const result = await syncMirrors(local.getSnapshot(), [github]);
    expect(result.complete).toBe(true);
    expect(github.readCount).toBe(2);
    expect(github.writes).toHaveLength(2);
  });
});
