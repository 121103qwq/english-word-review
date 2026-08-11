import { beforeEach, describe, expect, it, vi } from "vitest";
import { DEVICE_ID_KEY } from "../src/core/config";
import { EventStore } from "../src/core/events";
import type { ContentSnapshotV1 } from "../src/content/types";
import { createLibrarySyncBatch } from "../src/sync/library-files";
import { legacyBundle, MemoryStorage } from "./fixtures";

const mocks = vi.hoisted(() => ({ httpRequest: vi.fn() }));
vi.mock("../src/platform/runtime", () => ({ httpRequest: mocks.httpRequest }));

import { LibraryFileWebDavTransport } from "../src/sync/library-file-transports";

function batch() {
  const storage = new MemoryStorage();
  storage.setItem(DEVICE_ID_KEY, "device");
  const learning = EventStore.open(legacyBundle(), storage).getSnapshot();
  const content: ContentSnapshotV1 = {
    schemaVersion: 1, appVersion: "8.2.4", dictionaryVersion: "test",
    revision: { wallTime: 1, logical: 0, deviceId: "device" }, revisionId: "content",
    modifiedAt: "2026-08-10T10:00:00.000Z", activeLibraryId: "daily-a",
    libraries: [{
      id: "daily-a", date: "2026-08-10", createdAt: "2026-08-10T10:00:00.000Z",
      modifiedAt: "2026-08-10T10:00:00.000Z", words: [{ word: "accept", source: "dictionary" }],
    }], globalOverrides: {}, assets: {},
  };
  return createLibrarySyncBatch(content, learning, { deviceCode: "8F3A21CD", location: "家里" }, {
    now: new Date(2026, 7, 10, 18, 25), batchId: "batch-A",
  });
}

describe("manual library WebDAV transport", () => {
  beforeEach(() => mocks.httpRequest.mockReset());

  it("supports anonymous HTTPS WebDAV without sending an Authorization header", async () => {
    mocks.httpRequest.mockResolvedValueOnce({ status: 201, body: "", headers: {} })
      .mockResolvedValueOnce({ status: 201, body: "", headers: { etag: '"new"' } });
    const transport = new LibraryFileWebDavTransport({
      id: "anonymous", name: "匿名镜像", rootUrl: "https://dav.example/public",
    });

    await transport.writeLibraryFile(batch().files[0]);

    expect(mocks.httpRequest.mock.calls[0][0].method).toBe("MKCOL");
    expect(mocks.httpRequest.mock.calls[0][0].headers.Authorization).toBeUndefined();
    expect(mocks.httpRequest.mock.calls[1][0].headers.Authorization).toBeUndefined();
    expect(mocks.httpRequest.mock.calls[1][0].headers["If-None-Match"]).toBe("*");
  });
});
