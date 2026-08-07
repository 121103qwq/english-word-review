import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import {
  migrateV820BundledLibrary,
  V746_BUNDLED_LIBRARY,
  V746_BUNDLED_LIBRARY_ID,
  V820_BUNDLED_LIBRARY_ID,
} from "../src/content/bundled-library";
import { createEmptyContentSnapshot } from "../src/content/model";
import { MemoryContentBackend } from "../src/content/storage";
import { commitContentMutation } from "../src/sync/content-sync";

const oldLibrary = (date = "2026-08-07") => ({
  id: V820_BUNDLED_LIBRARY_ID,
  date,
  words: [],
  createdAt: `${date}T00:00:00.000Z`,
  modifiedAt: `${date}T00:00:00.000Z`,
});

describe("v7.4.6 bundled library migration", () => {
  it("keeps the exact 39 supplied words, meanings, roots, and hints", () => {
    expect(V746_BUNDLED_LIBRARY).toHaveLength(39);
    expect(V746_BUNDLED_LIBRARY[0]).toEqual({
      word: "programme",
      meaning: "节目；计划；方案；程序；编制节目；制定计划",
      root: "pro- / gram",
      rootMeaning: "向前；写、记录",
      rootHint: "pro-（向前）+ gram（写、记录）→把将要做的内容先写下来并排成一套安排",
    });
    expect(V746_BUNDLED_LIBRARY.at(-1)?.word).toBe("income");
    expect(new Set(V746_BUNDLED_LIBRARY.map((entry) => entry.word)).size).toBe(39);
    expect(V746_BUNDLED_LIBRARY.every((entry) => entry.meaning && entry.root && entry.rootMeaning && entry.rootHint)).toBe(true);
    expect(V746_BUNDLED_LIBRARY_ID).toBe(V746_BUNDLED_LIBRARY.map((entry) => entry.word).join("|"));
  });

  it("switches only the exact 8.2.0 built-in library and remains idempotent", () => {
    const snapshot = createEmptyContentSnapshot("device-a", {
      now: new Date("2026-08-07T00:00:00.000Z"),
      uuid: () => "before",
    });
    snapshot.libraries = [oldLibrary()];
    snapshot.activeLibraryId = V820_BUNDLED_LIBRARY_ID;

    expect(migrateV820BundledLibrary(snapshot, new Date("2026-08-08T00:00:00.000Z"))).toBe(true);
    expect(snapshot.activeLibraryId).toBe(V746_BUNDLED_LIBRARY_ID);
    expect(snapshot.libraries.map((library) => library.id)).toEqual([V746_BUNDLED_LIBRARY_ID, V820_BUNDLED_LIBRARY_ID]);
    expect(snapshot.libraries[0].words).toHaveLength(39);
    expect(snapshot.libraries[0].words[0].legacyProgress?.right).toBe(0);
    expect(migrateV820BundledLibrary(snapshot, new Date("2026-08-09T00:00:00.000Z"))).toBe(false);
    expect(snapshot.libraries.filter((library) => library.id === V746_BUNDLED_LIBRARY_ID)).toHaveLength(1);
  });

  it("never replaces a user-created active library", () => {
    const snapshot = createEmptyContentSnapshot("device-a");
    snapshot.activeLibraryId = "user-library";
    snapshot.libraries = [{ ...oldLibrary(), id: "user-library" }];
    expect(migrateV820BundledLibrary(snapshot)).toBe(false);
    expect(snapshot.activeLibraryId).toBe("user-library");
    expect(snapshot.libraries).toHaveLength(1);
  });

  it("backs up and advances the revision through the existing content mutation path", async () => {
    const backend = new MemoryContentBackend();
    const snapshot = createEmptyContentSnapshot("device-a", {
      now: new Date("2026-08-07T00:00:00.000Z"),
      uuid: () => "before",
    });
    snapshot.libraries = [oldLibrary()];
    snapshot.activeLibraryId = V820_BUNDLED_LIBRARY_ID;
    await backend.putCurrent(snapshot);

    const result = await commitContentMutation({
      persistence: backend,
      transports: [],
      deviceId: "device-a",
      now: () => new Date("2026-08-08T00:00:00.000Z"),
      uuid: () => "after",
      mutate: (draft) => { migrateV820BundledLibrary(draft, new Date("2026-08-08T00:00:00.000Z")); },
    });

    expect(result.snapshot.activeLibraryId).toBe(V746_BUNDLED_LIBRARY_ID);
    expect(result.snapshot.revisionId).toBe("after");
    expect(result.snapshot.revision.wallTime).toBeGreaterThan(snapshot.revision.wallTime);
    expect(await backend.listBackups()).toHaveLength(1);
  });

  it("wires the startup migration to the same mirror-aware commit function", () => {
    const source = readFileSync(new URL("../src/content/ui.ts", import.meta.url), "utf8");
    const html = readFileSync(new URL("../index.html", import.meta.url), "utf8");
    expect(source).toContain("this.snapshot.activeLibraryId !== V820_BUNDLED_LIBRARY_ID");
    expect(source.match(/await this\.migrateBundledLibraryIfNeeded\(\)/g)).toHaveLength(3);
    expect(source).toContain("await commitContentMutation({");
    expect(source).toContain("transports: this.options.getTransports?.() ?? []");
    expect(html).toContain("customRoots.find(item => item.note)?.note");
  });
});
