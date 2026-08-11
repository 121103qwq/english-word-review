import { describe, expect, it } from "vitest";
import { DEVICE_ID_KEY } from "../src/core/config";
import { EventStore } from "../src/core/events";
import { mergeSnapshots } from "../src/core/merge";
import type { ContentSnapshotV1 } from "../src/content/types";
import { MemoryContentBackend } from "../src/content/storage";
import {
  compareLibraryCopies,
  createLibrarySyncBatch,
  inflateLearningProgressSnapshot,
  librarySyncFileName,
  mergeLibrarySyncFilesIntoContent,
  type LibrarySyncFileV1,
} from "../src/sync/library-files";
import {
  downloadLibrarySyncSelection,
  listLibrarySyncChoices,
  uploadLibrarySyncSelection,
  type LibraryFileTransport,
  type LibrarySyncFileRef,
} from "../src/sync/library-file-transports";
import { archiveLibrarySyncBatch, MemoryLibrarySyncArchive } from "../src/sync/library-archive";
import { legacyBundle, MemoryStorage } from "./fixtures";

function learning(deviceId: string, answered = false) {
  const storage = new MemoryStorage();
  storage.setItem(DEVICE_ID_KEY, deviceId);
  const store = EventStore.open(legacyBundle(), storage);
  if (answered) store.recordAnswer({
    area: "library", libraryId: "daily-a", itemId: "accept", mode: "forward", correct: true,
  });
  return store.getSnapshot();
}

function content(): ContentSnapshotV1 {
  return {
    schemaVersion: 1,
    appVersion: "8.2.4",
    dictionaryVersion: "test",
    revision: { wallTime: 1, logical: 0, deviceId: "local" },
    revisionId: "content-1",
    modifiedAt: "2026-08-10T10:10:00.000Z",
    activeLibraryId: "daily-a",
    libraries: [
      {
        id: "daily-a", date: "2026-08-10", name: "第一组", createdAt: "2026-08-10T10:00:00.000Z",
        modifiedAt: "2026-08-10T10:10:00.000Z",
        words: [
          { word: "accept", source: "dictionary" },
          { word: "except", source: "dictionary" },
        ],
      },
      {
        id: "daily-b", date: "2026-08-09", name: "第二组", createdAt: "2026-08-09T10:00:00.000Z",
        modifiedAt: "2026-08-09T10:10:00.000Z",
        words: [{ word: "expect", source: "dictionary" }],
      },
    ],
    globalOverrides: {},
    assets: {},
  };
}

class FakeLibraryTransport implements LibraryFileTransport {
  readonly kind = "github" as const;
  files = new Map<string, LibrarySyncFileV1>();
  assets = new Map<string, Uint8Array>();

  constructor(readonly id = "fake", readonly label = "Fake") {}

  async listLibraryFiles(): Promise<LibrarySyncFileRef[]> {
    return [...this.files.entries()].map(([fileName, file]) => ({
      id: fileName, fileName, path: fileName,
      deviceCode: file.sourceDevice.deviceCode,
      location: file.sourceDevice.location,
      createdAt: file.createdAt,
      batchId: file.batchId,
      kind: file.kind,
    }));
  }

  async readLibraryFile(ref: LibrarySyncFileRef) {
    const file = this.files.get(ref.fileName);
    if (!file) throw new Error("missing");
    return { file: structuredClone(file), rawText: JSON.stringify(file) };
  }

  async writeLibraryFile(file: LibrarySyncFileV1, fileName = librarySyncFileName(file)) {
    this.files.set(fileName, structuredClone(file));
    return (await this.listLibraryFiles()).find((ref) => ref.fileName === fileName)!;
  }

  async deleteLibraryFile(ref: LibrarySyncFileRef) {
    this.files.delete(ref.fileName);
  }

  async readLibraryAsset(hash: string) { return { data: this.assets.get(hash) ?? null }; }
  async writeLibraryAsset(hash: string, data: Uint8Array) { this.assets.set(hash, structuredClone(data)); }
}

describe("manual multi-file library sync", () => {
  it("creates one file per library plus one mergeable learning-progress file", () => {
    const batch = createLibrarySyncBatch(content(), learning("local"), {
      deviceCode: "8f3a21cd", deviceName: "卧室电脑", location: "家里",
    }, { now: new Date(2026, 7, 10, 18, 25), batchId: "batch-A" });

    expect(batch.files.map((file) => file.kind)).toEqual(["library", "library", "learning-progress"]);
    const names = batch.files.map(librarySyncFileName);
    expect(names[0]).toMatch(/^8F3A21CD_家里_20260810-1825_batch-A_library-daily-a-[a-f\d]{8}\.json$/);
    expect(names[1]).toMatch(/^8F3A21CD_家里_20260810-1825_batch-A_library-daily-b-[a-f\d]{8}\.json$/);
    expect(names[2]).toBe("8F3A21CD_家里_20260810-1825_batch-A_progress.json");
  });

  it("stores progress without legacy content or settings", () => {
    const local = learning("local");
    const remoteStorage = new MemoryStorage();
    remoteStorage.setItem(DEVICE_ID_KEY, "remote");
    const remoteStore = EventStore.open(legacyBundle(), remoteStorage);
    remoteStore.recordSetting("rootVisible", false);
    remoteStore.recordIntensiveSelection([{ en: "foreign", zh: "remote definition" }], "remote-library");
    remoteStore.recordAnswer({
      area: "library", libraryId: "daily-a", itemId: "accept", mode: "forward", correct: true,
    });
    const remote = remoteStore.getSnapshot();
    remote.checkpoint.data.store.current.words[0].zh = "remote definition";
    remote.checkpoint.data.settings.meaningMatchMode = "exact";

    const batch = createLibrarySyncBatch(content(), remote, {
      deviceCode: "8F3A21CD", location: "home",
    }, { now: new Date(2026, 7, 10, 18, 25), batchId: "progress-only" });
    const file = batch.files.find((item) => item.kind === "learning-progress");
    if (!file || file.kind !== "learning-progress") throw new Error("fixture error");
    expect(JSON.stringify(file)).not.toContain('"snapshot"');
    expect(JSON.stringify(file)).not.toContain('"settings"');
    expect(file.progress.events.map((event) => event.type)).toEqual(["answer"]);

    const merged = mergeSnapshots(local, inflateLearningProgressSnapshot(file.progress, local));
    expect(merged.checkpoint.data.store.current.words[0].zh).toBe(local.checkpoint.data.store.current.words[0].zh);
    expect(merged.checkpoint.data.settings).toEqual(local.checkpoint.data.settings);
    expect(merged.checkpoint.data.intensiveStore.words.some((word) => word.en === "foreign")).toBe(false);
    expect(merged.events.map((event) => event.type)).toEqual(["answer"]);
  });

  it("keeps file names unique when long legacy library ids share the same prefix", () => {
    const source = content();
    const prefix = "same-legacy-library-id-prefix-that-is-longer-than-forty-eight-characters-";
    source.libraries[0].id = `${prefix}a`;
    source.libraries[1].id = `${prefix}b`;
    const batch = createLibrarySyncBatch(source, learning("local"), {
      deviceCode: "8F3A21CD", location: "家里",
    }, { now: new Date(2026, 7, 10, 18, 25), batchId: "legacy" });

    expect(new Set(batch.files.map(librarySyncFileName)).size).toBe(batch.files.length);
  });

  it("keeps unrelated local libraries when applying an explicitly downloaded library", () => {
    const local = content();
    const remote = content();
    remote.libraries = [{ ...remote.libraries[0], name: "云端第一组" }];
    const file = createLibrarySyncBatch(remote, learning("remote"), {
      deviceCode: "8F3A21CD", location: "家里",
    }, { now: new Date(2026, 7, 10, 18, 25), batchId: "remote" }).files[0];
    if (file.kind !== "library") throw new Error("fixture error");

    mergeLibrarySyncFilesIntoContent(local, [file]);

    expect(local.libraries.map((library) => library.id)).toEqual(["daily-a", "daily-b"]);
    expect(local.libraries.find((library) => library.id === "daily-a")?.name).toBe("云端第一组");
    expect(local.libraries.find((library) => library.id === "daily-b")?.name).toBe("第二组");
  });

  it("keeps downloaded overrides local to the selected library", () => {
    const local = content();
    local.libraries[1].words.push({ word: "accept", source: "dictionary" });
    local.globalOverrides.accept = { meaning: "local global" };
    const remote = content();
    remote.globalOverrides.accept = { meaning: "remote selected" };
    const file = createLibrarySyncBatch(remote, learning("remote"), {
      deviceCode: "8F3A21CD", location: "home",
    }, { now: new Date(2026, 7, 10, 18, 25), batchId: "override" }).files[0];
    if (file.kind !== "library") throw new Error("fixture error");

    mergeLibrarySyncFilesIntoContent(local, [file]);

    const selected = local.libraries.find((library) => library.id === "daily-a")!.words[0];
    const untouched = local.libraries.find((library) => library.id === "daily-b")!.words.at(-1)!;
    expect(selected.override?.meaning).toBe("remote selected");
    expect(selected.ignoreGlobalOverride).toBe(true);
    expect(untouched.ignoreGlobalOverride).toBeUndefined();
    expect(local.globalOverrides.accept.meaning).toBe("local global");

    remote.globalOverrides = {};
    const withoutOverride = createLibrarySyncBatch(remote, learning("remote"), {
      deviceCode: "8F3A21CD", location: "home",
    }, { now: new Date(2026, 7, 10, 18, 26), batchId: "override-deleted" }).files[0];
    if (withoutOverride.kind !== "library") throw new Error("fixture error");
    mergeLibrarySyncFilesIntoContent(local, [withoutOverride]);
    const cleared = local.libraries.find((library) => library.id === "daily-a")!.words[0];
    expect(cleared.override).toBeUndefined();
    expect(cleared.ignoreGlobalOverride).toBe(true);
    expect(local.globalOverrides.accept.meaning).toBe("local global");
  });

  it("distinguishes same words with different progress", () => {
    const options = { now: new Date(2026, 7, 10, 18, 25), batchId: "same-minute" };
    const local = createLibrarySyncBatch(content(), learning("local", true), {
      deviceCode: "8F3A21CD", location: "家里",
    }, options).files[0];
    const cloud = createLibrarySyncBatch(content(), learning("remote", false), {
      deviceCode: "8F3A21CD", location: "家里",
    }, options).files[0];
    if (local.kind !== "library" || cloud.kind !== "library") throw new Error("fixture error");
    expect(local.wordFingerprint).toBe(cloud.wordFingerprint);
    expect(local.contentFingerprint).toBe(cloud.contentFingerprint);
    expect(compareLibraryCopies(local, cloud)).toBe("same-words-progress-different");
  });

  it("only uploads and downloads files explicitly selected by the chooser", async () => {
    const localBatch = createLibrarySyncBatch(content(), learning("local", true), {
      deviceCode: "8F3A21CD", location: "家里",
    }, { now: new Date(2026, 7, 10, 18, 25), batchId: "local-batch" });
    const remoteBatch = createLibrarySyncBatch(content(), learning("remote"), {
      deviceCode: "8F3A21CD", location: "家里",
    }, { now: new Date(2026, 7, 10, 18, 25), batchId: "remote-batch" });
    const transport = new FakeLibraryTransport();
    for (const file of remoteBatch.files) await transport.writeLibraryFile(file);

    const model = await listLibrarySyncChoices(localBatch, [transport]);
    const remoteLibrary = model.cloud.find((choice) => choice.file.kind === "library" && choice.file.library.id === "daily-a")!;
    expect(remoteLibrary.comparison).toBe("same-words-progress-different");

    const uploadName = model.local[0].fileName;
    const statuses = await uploadLibrarySyncSelection(localBatch, [uploadName], [transport]);
    expect(statuses[0]).toMatchObject({ status: "success", uploadedFiles: 1 });
    expect(transport.files.has(uploadName)).toBe(true);
    expect(transport.files.has(model.local[1].fileName)).toBe(false);

    const downloaded = await downloadLibrarySyncSelection(model.cloud, [remoteLibrary.fileName]);
    expect(downloaded).toHaveLength(1);
    expect(downloaded[0].kind).toBe("library");
  });

  it("compares a cloud file with an archived local copy from the same minute", async () => {
    const archived = createLibrarySyncBatch(content(), learning("local", true), {
      deviceCode: "8F3A21CD", location: "家里",
    }, { now: new Date(2026, 7, 10, 18, 25), batchId: "archived-local" });
    const current = createLibrarySyncBatch(content(), learning("local", true), {
      deviceCode: "8F3A21CD", location: "家里",
    }, { now: new Date(2026, 7, 11, 18, 25), batchId: "current" });
    const remote = createLibrarySyncBatch(content(), learning("remote"), {
      deviceCode: "8F3A21CD", location: "家里",
    }, { now: new Date(2026, 7, 10, 18, 25), batchId: "remote" });
    const archive = new MemoryLibrarySyncArchive();
    await archiveLibrarySyncBatch(archive, archived);
    const transport = new FakeLibraryTransport();
    for (const file of remote.files) await transport.writeLibraryFile(file);

    const model = await listLibrarySyncChoices(current, [transport], [], { archive });
    const cloudLibrary = model.cloud.find((choice) => choice.file.kind === "library" && choice.file.library.id === "daily-a")!;
    expect(cloudLibrary.localFileName).toContain("archived-loc");
    expect(cloudLibrary.comparison).toBe("same-words-progress-different");
    expect(model.local.length).toBe(current.files.length + archived.files.length);

    const historicalName = model.local.find((choice) => choice.fileName.includes("archived-loc") && choice.file.kind === "library")!.fileName;
    transport.files.delete(historicalName);
    const statuses = await uploadLibrarySyncSelection(current, [historicalName], [transport], { archive });
    expect(statuses[0]).toMatchObject({ status: "success", uploadedFiles: 1 });
    expect(transport.files.has(historicalName)).toBe(true);
  });

  it("prefers an identical archived copy over a different current copy from the same minute", async () => {
    const remote = createLibrarySyncBatch(content(), learning("remote"), {
      deviceCode: "8F3A21CD", location: "家里",
    }, { now: new Date(2026, 7, 10, 18, 25), batchId: "remote" });
    const current = createLibrarySyncBatch(content(), learning("local", true), {
      deviceCode: "8F3A21CD", location: "家里",
    }, { now: new Date(2026, 7, 10, 18, 25), batchId: "current" });
    const archived = createLibrarySyncBatch(content(), learning("remote"), {
      deviceCode: "8F3A21CD", location: "家里",
    }, { now: new Date(2026, 7, 10, 18, 25), batchId: "archived" });
    const archive = new MemoryLibrarySyncArchive();
    await archiveLibrarySyncBatch(archive, archived);
    const transport = new FakeLibraryTransport();
    for (const file of remote.files) await transport.writeLibraryFile(file);

    const model = await listLibrarySyncChoices(current, [transport], [], { archive });
    const cloudLibrary = model.cloud.find((choice) => choice.file.kind === "library" && choice.file.library.id === "daily-a")!;
    expect(cloudLibrary.comparison).toBe("identical");
    expect(cloudLibrary.localFileName).toContain("archived");
  });

  it("preserves global overrides and transfers referenced MP3 bytes only when selected", async () => {
    const bytes = new Uint8Array([0x49, 0x44, 0x33]);
    const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
    const hash = [...digest].map((part) => part.toString(16).padStart(2, "0")).join("");
    const sourceContent = content();
    sourceContent.globalOverrides.accept = {
      meaning: "接受（全局）", audioAssetIds: [hash], primaryAudioAssetId: hash,
    };
    sourceContent.assets[hash] = {
      id: hash, sha256: hash, mimeType: "audio/mpeg", byteLength: bytes.length,
      fileName: "accept.mp3", createdAt: "2026-08-10T10:00:00.000Z",
    };
    const batch = createLibrarySyncBatch(sourceContent, learning("local"), {
      deviceCode: "8F3A21CD", location: "家里",
    }, { now: new Date(2026, 7, 10, 18, 25), batchId: "audio" });
    const libraryFile = batch.files.find((file) => file.kind === "library" && file.library.id === "daily-a")!;
    if (libraryFile.kind !== "library") throw new Error("fixture error");
    expect(libraryFile.globalOverrides.accept.meaning).toBe("接受（全局）");
    expect(libraryFile.assets[hash].fileName).toBe("accept.mp3");

    const sourceAssets = new MemoryContentBackend();
    await sourceAssets.putAsset({ meta: sourceContent.assets[hash], bytes });
    const transport = new FakeLibraryTransport();
    const fileName = librarySyncFileName(libraryFile);
    const upload = await uploadLibrarySyncSelection(batch, [fileName], [transport], {
      assetBackend: sourceAssets, includeAssets: true,
    });
    expect(upload[0]).toMatchObject({ uploadedAssets: 1, omittedAssets: 0 });
    expect(transport.assets.get(hash)).toEqual(bytes);

    const model = await listLibrarySyncChoices(batch, [transport]);
    const destination = new MemoryContentBackend();
    await downloadLibrarySyncSelection(model.cloud, [fileName], {
      assetBackend: destination, includeAssets: true,
    });
    expect((await destination.getAsset(hash))?.bytes).toEqual(bytes);
  });

  it("downloads MP3 data from another mirror when the first JSON mirror is incomplete", async () => {
    const bytes = new Uint8Array([0x49, 0x44, 0x33, 1]);
    const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
    const hash = [...digest].map((part) => part.toString(16).padStart(2, "0")).join("");
    const source = content();
    source.globalOverrides.accept = { audioAssetIds: [hash], primaryAudioAssetId: hash };
    source.assets[hash] = {
      id: hash, sha256: hash, mimeType: "audio/mpeg", byteLength: bytes.length,
      fileName: "accept.mp3", createdAt: "2026-08-10T10:00:00.000Z",
    };
    const batch = createLibrarySyncBatch(source, learning("local"), {
      deviceCode: "8F3A21CD", location: "home",
    }, { now: new Date(2026, 7, 10, 18, 25), batchId: "fallback" });
    const file = batch.files.find((item) => item.kind === "library" && item.library.id === "daily-a")!;
    const fileName = librarySyncFileName(file);
    const incomplete = new FakeLibraryTransport("incomplete", "Incomplete");
    const complete = new FakeLibraryTransport("complete", "Complete");
    await incomplete.writeLibraryFile(file, fileName);
    await complete.writeLibraryFile(file, fileName);
    complete.assets.set(hash, bytes);

    const model = await listLibrarySyncChoices(batch, [incomplete, complete]);
    const destination = new MemoryContentBackend();
    await downloadLibrarySyncSelection(model.cloud, [fileName], {
      assetBackend: destination, includeAssets: true,
    });

    expect((await destination.getAsset(hash))?.bytes).toEqual(bytes);
  });

  it("retains 30 complete local batches per device and location", async () => {
    const archive = new MemoryLibrarySyncArchive();
    for (let index = 0; index < 31; index += 1) {
      const batch = createLibrarySyncBatch(content(), learning("local"), {
        deviceCode: "8F3A21CD", location: "家里",
      }, { now: new Date(2026, 6, index + 1, 12, 0), batchId: `batch-${index}` });
      await archiveLibrarySyncBatch(archive, batch);
    }
    const files = await archive.list();
    expect(new Set(files.map((item) => item.file.batchId)).size).toBe(30);
    expect(files.some((item) => item.file.batchId === "batch-0")).toBe(false);
    expect(files.filter((item) => item.file.batchId === "batch-30")).toHaveLength(3);
  });
});
