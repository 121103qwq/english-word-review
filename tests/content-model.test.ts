import { describe, expect, it } from "vitest";
import {
  applyWordOverride,
  compareContentRevision,
  createEmptyContentSnapshot,
  createLibrary,
  createMp3Asset,
  MAX_MP3_BYTES,
  migrateLegacyBundle,
  parseWordInput,
  resolveWord,
} from "../src/content/model";
import type { CustomLibraryWord } from "../src/content/types";
import { legacyBundle } from "./fixtures";

describe("content model", () => {
  it("splits supported punctuation and newlines, normalizes case and deduplicates", () => {
    expect(parseWordInput(" Apple,built，KICK.counter。apple\ncat\r\ndog ")).toEqual([
      "apple", "built", "kick", "counter", "cat", "dog",
    ]);
  });

  it("uses the local day by default and allows multiple libraries on one date", () => {
    const now = new Date(2026, 7, 6, 23, 30);
    const first = createLibrary({}, { now, uuid: () => "one" });
    const second = createLibrary({}, { now, uuid: () => "two" });
    expect(first.date).toBe("2026-08-06");
    expect(second.date).toBe(first.date);
    expect(second.id).not.toBe(first.id);
    expect(() => createLibrary({ date: "not-a-date" })).toThrow(/日期/);
    expect(() => createLibrary({ date: "2026-02-30" })).toThrow(/日期/);
  });

  it("migrates legacy dates, meanings, progress, and matching root records", () => {
    const legacy = legacyBundle();
    legacy.rootStudyStore.items[0].words = ["accept"];
    const snapshot = migrateLegacyBundle(legacy, "device-a", {
      now: new Date("2026-08-06T10:00:00.000Z"),
      uuid: () => "revision",
    });
    expect(snapshot.libraries[0].date).toBe("2026-08-06");
    expect(snapshot.libraries[0].words[0].legacyOverride?.meaning).toBe(legacy.store.current.words[0].zh);
    expect(snapshot.libraries[0].words[0].legacyOverride?.roots?.[0].source).toBe("legacy");
    expect(snapshot.libraries[0].words[0].legacyProgress?.en).toBe("accept");
  });

  it("resolves fields local then global then legacy then dictionary", () => {
    const entry: CustomLibraryWord = {
      word: "apple",
      source: "dictionary",
      legacyOverride: { meaning: "旧释义", pronunciation: "legacy" },
      override: { pronunciation: "local" },
    };
    const resolved = resolveWord(
      entry,
      { meaning: "全局释义", roots: [{ root: "app", meaning: "靠近", source: "manual" }] },
      { meaning: "苹果", pronunciation: "dictionary" },
    );
    expect(resolved.meaning).toBe("全局释义");
    expect(resolved.pronunciation).toBe("local");
    expect(resolved.roots?.[0].root).toBe("app");
  });

  it("global changes clear the same local fields in every library", () => {
    const snapshot = createEmptyContentSnapshot("device-a", { uuid: () => "snapshot" });
    snapshot.libraries = ["a", "b"].map((id) => ({
      id, date: "2026-08-06", createdAt: "x", modifiedAt: "x",
      words: [{ word: "apple", source: "dictionary", override: { meaning: id, pronunciation: id } }],
    }));
    const changed = applyWordOverride(snapshot, "APPLE", { meaning: "苹果" }, { type: "global" });
    expect(changed.globalOverrides.apple.meaning).toBe("苹果");
    expect(changed.libraries.map((library) => library.words[0].override)).toEqual([
      { pronunciation: "a" }, { pronunciation: "b" },
    ]);
    expect(snapshot.libraries[0].words[0].override?.meaning).toBe("a");
  });

  it("compares HLC and uses revision id as the deterministic final tie-break", () => {
    const a = createEmptyContentSnapshot("a", { now: new Date(1000), uuid: () => "a" });
    const b = createEmptyContentSnapshot("a", { now: new Date(1000), uuid: () => "b" });
    expect(compareContentRevision(a, b)).toBeLessThan(0);
    b.revision.logical = 1;
    expect(compareContentRevision(a, b)).toBeLessThan(0);
  });

  it("content-addresses MP3 data and rejects invalid or oversized files", async () => {
    const mp3 = new Uint8Array([0x49, 0x44, 0x33, 4, 0, 0, 0, 0]);
    const first = await createMp3Asset(mp3, "voice.mp3", { now: new Date(0) });
    const second = await createMp3Asset(mp3, "copy.mp3", { now: new Date(1) });
    expect(first.meta.id).toBe(second.meta.id);
    expect(first.meta.byteLength).toBe(8);
    await expect(createMp3Asset(new Uint8Array([1, 2, 3]), "bad.mp3")).rejects.toThrow(/MP3/);
    await expect(createMp3Asset(new Uint8Array(MAX_MP3_BYTES + 1), "large.mp3")).rejects.toThrow(/20 MiB/);
  });
});
