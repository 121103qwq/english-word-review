import { beforeEach, describe, expect, it, vi } from "vitest";
import { createDefaultSyncedSettings } from "../src/settings/model";
import type {
  PlatformKind,
  PlatformSettingsSnapshotV1,
  SettingsAssetReference,
} from "../src/settings/types";
import {
  createSettingsAsset,
  MemorySettingsAssetBackend,
  selectSettingsAttachments,
  SettingsAssetRepository,
  type StoredSettingsAsset,
} from "../src/settings/assets";

const mocks = vi.hoisted(() => ({ httpRequest: vi.fn() }));
vi.mock("../src/platform/runtime", () => ({ httpRequest: mocks.httpRequest }));

import {
  listSettingsChoices,
  MIMO_PRIVATE_KEY_PATH,
  SETTINGS_ASSETS_PATH,
  settingsSnapshotFileName,
  SettingsGitHubTransport,
  type SettingsFileRef,
  type SettingsRemoteDocument,
  type SettingsTransport,
  SettingsWebDavTransport,
  uploadSettingsToMirrors,
} from "../src/settings/transports";

const response = (status: number, body = "", headers: Record<string, string> = {}) => ({ status, body, headers });
const toBase64 = (value: string) => btoa(String.fromCharCode(...new TextEncoder().encode(value)));

function snapshot(overrides: Partial<PlatformSettingsSnapshotV1> = {}): PlatformSettingsSnapshotV1 {
  return {
    schemaVersion: 1,
    appVersion: "8.2.0",
    platform: "windows",
    revisionId: "revision-a",
    modifiedAt: "2026-08-08T02:03:00.000Z",
    sourceDevice: { deviceCode: "8F3A21CD", deviceName: "卧室电脑", location: "家里" },
    settings: createDefaultSyncedSettings(),
    ...overrides,
  };
}

function ref(
  fileName: string,
  platform: PlatformKind = "windows",
  revision = "sha",
): SettingsFileRef {
  const parts = fileName.replace(/\.json$/, "").split("_");
  return {
    id: `settings/${platform}/${fileName}`,
    path: `settings/${platform}/${fileName}`,
    fileName,
    platform,
    deviceCode: parts[0],
    deviceName: parts[1],
    location: parts[2],
    modifiedAt: "2026-08-08T02:03:00.000Z",
    revision,
  };
}

class FakeSettingsTransport implements SettingsTransport {
  readonly kind = "github" as const;
  readonly label: string;
  refs: SettingsFileRef[] = [];
  uploadedSettings: string[] = [];
  uploadedAssets: StoredSettingsAsset[] = [];
  fail = false;

  constructor(readonly id: string) { this.label = id; }
  async listSettings(): Promise<SettingsFileRef[]> { if (this.fail) throw new Error("unavailable"); return this.refs; }
  async readSettings(item: SettingsFileRef): Promise<SettingsRemoteDocument> {
    return { snapshot: snapshot({ platform: item.platform }), rawText: JSON.stringify(snapshot()), revision: item.revision };
  }
  async writeSettings(value: PlatformSettingsSnapshotV1, fileName = settingsSnapshotFileName(value, "A7F2")) {
    if (this.fail) throw new Error("unavailable");
    this.uploadedSettings.push(fileName);
    return { ref: ref(fileName, value.platform), revision: "new" };
  }
  async deleteSettings(): Promise<void> {}
  async readAsset(): Promise<{ data: Uint8Array | null }> { return { data: null }; }
  async writeAsset(asset: StoredSettingsAsset): Promise<void> { this.uploadedAssets.push(asset); }
}

describe("manual platform settings transports", () => {
  beforeEach(() => mocks.httpRequest.mockReset());

  it("uses the device, location, minute, and anti-collision suffix in file names", () => {
    const fileName = settingsSnapshotFileName(snapshot(), "A7F2");
    expect(fileName).toMatch(/^8F3A21CD_卧室电脑_家里_\d{8}-\d{4}_A7F2\.json$/);
  });

  it("writes GitHub settings only under the platform settings directory", async () => {
    mocks.httpRequest
      .mockResolvedValueOnce(response(201, JSON.stringify({ content: { sha: "new-sha" } })))
      .mockResolvedValueOnce(response(200, "[]"));
    const transport = new SettingsGitHubTransport({
      owner: "owner",
      repo: "private-data",
      token: "token",
      randomSuffix: () => "A7F2",
    });

    await transport.writeSettings(snapshot());

    const write = mocks.httpRequest.mock.calls[0][0];
    expect(write.method).toBe("PUT");
    expect(write.url).toContain("/contents/settings/windows/");
    expect(write.url).not.toContain("/content/");
    expect(write.url).not.toContain("private-credentials");
    expect(write.headers.Authorization).toBe("Bearer token");
  });

  it("prunes the oldest GitHub file only after the 31st file in one location", async () => {
    const newestName = settingsSnapshotFileName(snapshot(), "A7F2");
    const files = [
      { name: newestName, path: `settings/windows/${newestName}`, sha: "new", type: "file" },
      ...Array.from({ length: 30 }, (_, index) => {
        const minute = String(index).padStart(2, "0");
        const name = `8F3A21CD_PC_家里_20260807-12${minute}_${String(index).padStart(4, "0")}.json`;
        return { name, path: `settings/windows/${name}`, sha: `sha-${index}`, type: "file" };
      }),
    ];
    mocks.httpRequest
      .mockResolvedValueOnce(response(201, JSON.stringify({ content: { sha: "new" } })))
      .mockResolvedValueOnce(response(200, JSON.stringify(files)))
      .mockResolvedValueOnce(response(200));
    const transport = new SettingsGitHubTransport({ owner: "o", repo: "r", token: "t", randomSuffix: () => "A7F2" });

    await transport.writeSettings(snapshot());

    const deletion = mocks.httpRequest.mock.calls.find(([request]) => request.method === "DELETE")?.[0];
    expect(deletion?.url).toContain(encodeURIComponent("8F3A21CD_PC_家里_20260807-1200_0000.json"));
  });

  it("lists WebDAV settings with PROPFIND and never scans content or credentials", async () => {
    const fileName = "8F3A21CD_PC_家里_20260808-0203_A7F2.json";
    mocks.httpRequest
      .mockResolvedValueOnce(response(405))
      .mockResolvedValueOnce(response(405))
      .mockResolvedValueOnce(response(207, `
        <d:multistatus xmlns:d="DAV:"><d:response><d:href>/root/settings/windows/${fileName}</d:href>
        <d:propstat><d:prop><d:getetag>"etag"</d:getetag></d:prop></d:propstat></d:response></d:multistatus>`));
    const transport = new SettingsWebDavTransport({
      id: "dav-a",
      name: "WebDAV A",
      rootUrl: "https://dav.example/root",
      username: "user",
      password: "password",
    });

    const listed = await transport.listSettings("windows");

    expect(listed).toHaveLength(1);
    expect(listed[0].location).toBe("家里");
    const propfind = mocks.httpRequest.mock.calls[2][0];
    expect(propfind.method).toBe("PROPFIND");
    expect(propfind.url).toContain("/settings/windows");
    expect(propfind.url).not.toContain("content");
    expect(propfind.url).not.toContain("private-credentials");
  });

  it("returns future settings as read-only raw data instead of applying them", async () => {
    const future = JSON.stringify({ ...snapshot(), schemaVersion: 2 });
    mocks.httpRequest.mockResolvedValueOnce(response(200, JSON.stringify({ content: toBase64(future), sha: "future" })));
    const transport = new SettingsGitHubTransport({ owner: "o", repo: "r", token: "t" });

    const document = await transport.readSettings(ref("8F3A21CD_PC_家里_20260808-0203_A7F2.json"));

    expect(document.snapshot).toBeNull();
    expect(document.schemaVersion).toBe(2);
    expect(document.rawText).toBe(future);
  });

  it("retrieves the MiMo key from one exact private GitHub path", async () => {
    mocks.httpRequest.mockResolvedValueOnce(response(200, JSON.stringify({
      content: toBase64(JSON.stringify({ apiKey: "private-key" })),
      sha: "key-sha",
    })));
    const transport = new SettingsGitHubTransport({ owner: "o", repo: "private", token: "token" });

    expect(await transport.readMimoApiKey()).toBe("private-key");
    const read = mocks.httpRequest.mock.calls[0][0];
    expect(read.url).toContain(MIMO_PRIVATE_KEY_PATH.split("/").join("/"));
    expect(read.url).not.toContain("settings/windows");
  });

  it("stores settings attachments in a separate content-addressed directory", async () => {
    const bytes = Uint8Array.from([0x49, 0x44, 0x33, 1, 2, 3]);
    const asset = await createSettingsAsset(bytes, "correct.mp3", "audio/mpeg");
    mocks.httpRequest
      .mockResolvedValueOnce(response(404))
      .mockResolvedValueOnce(response(201, JSON.stringify({ content: { sha: "asset" } })));
    const transport = new SettingsGitHubTransport({ owner: "o", repo: "r", token: "t" });

    await transport.writeAsset(asset);

    const write = mocks.httpRequest.mock.calls[1][0];
    expect(write.url).toContain(`/${SETTINGS_ASSETS_PATH}/`);
    expect(write.url).not.toContain("content/assets");
  });

  it("deduplicates mirror files and puts the current location first", async () => {
    const home = ref("8F3A21CD_PC_家里_20260807-0203_A7F2.json");
    const office = { ...ref("8F3A21CD_PC_办公室_20260808-0203_B7F2.json"), modifiedAt: "2026-08-08T02:03:00.000Z" };
    const first = new FakeSettingsTransport("github");
    const second = new FakeSettingsTransport("dav");
    first.refs = [office, home];
    second.refs = [home];

    const result = await listSettingsChoices([first, second], "windows", "家里");

    expect(result.choices.map((choice) => choice.location)).toEqual(["家里", "办公室"]);
    expect(result.choices[0].sources).toHaveLength(2);
  });

  it("uploads to every reachable mirror and reports partial success without an outbox", async () => {
    const github = new FakeSettingsTransport("github");
    const dav = new FakeSettingsTransport("dav");
    dav.fail = true;

    const result = await uploadSettingsToMirrors(snapshot(), [github, dav], [], false, "A7F2");

    expect(result.partialSuccess).toBe(true);
    expect(github.uploadedSettings).toHaveLength(1);
    expect(result.statuses).toEqual(expect.arrayContaining([
      expect.objectContaining({ transportId: "github", status: "success" }),
      expect.objectContaining({ transportId: "dav", status: "failed" }),
    ]));
  });

  it("requires explicit opt-in when pending settings attachments exceed 10 MiB", async () => {
    const makeAsset = (id: string): StoredSettingsAsset => ({
      reference: {
        sha256: id.repeat(64).slice(0, 64),
        mimeType: "audio/mpeg",
        byteLength: 6 * 1024 * 1024,
        fileName: `${id}.mp3`,
      },
      bytes: new Uint8Array(6 * 1024 * 1024),
    });
    const assets = [makeAsset("a"), makeAsset("b")];
    expect(selectSettingsAttachments(assets).selected).toHaveLength(0);
    expect(selectSettingsAttachments(assets).requiresLargeAttachmentOptIn).toBe(true);

    const transport = new FakeSettingsTransport("github");
    const deferred = await uploadSettingsToMirrors(snapshot(), [transport], assets, false, "A7F2");
    expect(deferred.requiresLargeAttachmentOptIn).toBe(true);
    expect(transport.uploadedAssets).toHaveLength(0);

    await uploadSettingsToMirrors(snapshot(), [transport], assets, true, "B7F2");
    expect(transport.uploadedAssets).toHaveLength(2);
  });

  it("keeps local settings assets in their own backend and verifies hashes", async () => {
    const asset = await createSettingsAsset(Uint8Array.from([0x49, 0x44, 0x33, 4]), "tone.mp3", "audio/mpeg");
    const backend = new MemorySettingsAssetBackend();
    const repository = new SettingsAssetRepository(backend);
    await repository.save(asset);
    await repository.save(asset);
    expect(await repository.list()).toHaveLength(1);

    const invalid: StoredSettingsAsset = {
      reference: { ...(asset.reference as SettingsAssetReference), sha256: "0".repeat(64) },
      bytes: asset.bytes,
    };
    await expect(repository.save(invalid)).rejects.toThrow(/哈希/);
  });
});
