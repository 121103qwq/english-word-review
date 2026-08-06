import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ContentSnapshotV1 } from "../src/content/types";

const mocks = vi.hoisted(() => ({ httpRequest: vi.fn() }));
vi.mock("../src/platform/runtime", () => ({ httpRequest: mocks.httpRequest }));

import { ContentGitHubTransport, ContentWebDavTransport } from "../src/sync/content-transports";

const value: ContentSnapshotV1 = {
  schemaVersion: 1,
  appVersion: "8.1.0",
  dictionaryVersion: "test",
  revision: { wallTime: 123, logical: 2, deviceId: "device" },
  revisionId: "revision",
  modifiedAt: new Date(123).toISOString(),
  activeLibraryId: null,
  libraries: [],
  globalOverrides: {},
  assets: {},
};

const response = (status: number, body = "", headers: Record<string, string> = {}) => ({ status, body, headers });

describe("content mirror transports", () => {
  beforeEach(() => mocks.httpRequest.mockReset());

  it("uses a GitHub file SHA when overwriting current.json", async () => {
    mocks.httpRequest
      .mockResolvedValueOnce(response(200, JSON.stringify({
        content: btoa(JSON.stringify(value)),
        sha: "old-sha",
      })))
      .mockResolvedValueOnce(response(200, JSON.stringify({ content: { sha: "new-sha" } })));
    const transport = new ContentGitHubTransport({ owner: "owner", repo: "repo", token: "token" });

    const current = await transport.readCurrent();
    const written = await transport.writeCurrent(value, current.revision);

    expect(current.snapshot?.revisionId).toBe("revision");
    expect(written.revision).toBe("new-sha");
    const write = mocks.httpRequest.mock.calls[1][0];
    expect(write.method).toBe("PUT");
    expect(JSON.parse(write.body).sha).toBe("old-sha");
    expect(write.headers.Authorization).toBe("Bearer token");
  });

  it("uses WebDAV ETag preconditions and create-only backup writes", async () => {
    mocks.httpRequest
      .mockResolvedValueOnce(response(200, JSON.stringify(value), { etag: '"old-etag"' }))
      .mockResolvedValueOnce(response(405))
      .mockResolvedValueOnce(response(204, "", { etag: '"new-etag"' }))
      .mockResolvedValueOnce(response(405))
      .mockResolvedValueOnce(response(405))
      .mockResolvedValueOnce(response(201, "", { etag: '"backup-etag"' }));
    const transport = new ContentWebDavTransport({
      id: "dav-1",
      name: "异地备份",
      rootUrl: "https://dav.example/root",
      username: "user",
      password: "password",
    });

    const current = await transport.readCurrent();
    await transport.writeCurrent(value, current.revision);
    await transport.writeBackup(value);

    expect(mocks.httpRequest.mock.calls[1][0].method).toBe("MKCOL");
    expect(mocks.httpRequest.mock.calls[2][0].headers["If-Match"]).toBe('"old-etag"');
    expect(mocks.httpRequest.mock.calls[3][0].method).toBe("MKCOL");
    expect(mocks.httpRequest.mock.calls[4][0].method).toBe("MKCOL");
    expect(mocks.httpRequest.mock.calls[5][0].headers["If-None-Match"]).toBe("*");
  });

  it("sends WebDAV assets as binary base64 without putting secrets in the URL", async () => {
    mocks.httpRequest
      .mockResolvedValueOnce(response(201))
      .mockResolvedValueOnce(response(201))
      .mockResolvedValueOnce(response(201, "", { etag: '"asset"' }));
    const transport = new ContentWebDavTransport({
      id: "dav-2",
      name: "容灾",
      rootUrl: "https://dav.example/root",
      username: "user@example.com",
      password: "top-secret",
    });
    const bytes = new Uint8Array([0x49, 0x44, 0x33]);
    const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
    const hash = [...digest].map((part) => part.toString(16).padStart(2, "0")).join("");

    await transport.writeAsset(hash, bytes);

    const write = mocks.httpRequest.mock.calls[2][0];
    expect(write.url.endsWith(`/content/assets/${hash}.mp3`)).toBe(true);
    expect(write.url).not.toContain("top-secret");
    expect(write.bodyBase64).toBe(btoa(String.fromCharCode(...bytes)));
    expect(write.headers["Content-Type"]).toBe("audio/mpeg");
  });
});
