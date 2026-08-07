import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import type { LegacyWord } from "../src/core/types";
import {
  comparableLegacyBundle,
  decideLegacyProjection,
  STARTUP_RECONCILE_MARKER_KEY,
} from "../src/platform/legacy-bundle";
import { legacyBundle, MemoryStorage } from "./fixtures";

describe("legacy bundle startup comparison", () => {
  it("treats reordered object fields and root items as the same data", () => {
    const live = legacyBundle();
    const projected = structuredClone(live);
    const word = projected.store.current.words[0]!;
    projected.store.current.words[0] = Object.fromEntries(
      Object.entries(word).reverse(),
    ) as unknown as LegacyWord;

    const root = structuredClone(projected.rootStudyStore.items[0]!);
    root.id = `z-${root.id}`;
    live.rootStudyStore.items.push(structuredClone(root));
    projected.rootStudyStore.items.unshift(root);

    expect(JSON.stringify(projected)).not.toBe(JSON.stringify(live));
    expect(comparableLegacyBundle(projected)).toBe(comparableLegacyBundle(live));
  });

  it("ignores ordering in libraries, words, roots, intensive words, and root-related words", () => {
    const live = legacyBundle();
    live.store.archives = [
      { id: "archive-a", date: "2026-08-04", words: structuredClone(live.store.current.words.slice(0, 2)) },
      { id: "archive-b", date: "2026-08-05", words: structuredClone(live.store.current.words.slice(2)) },
    ];
    live.store.current.words[0]!.roots = [
      { root: "cept", meaning: "拿；取", source: "engra" },
      { root: "ac", meaning: "向；加强", source: "inferred" },
    ];
    live.intensiveStore.words.push({
      en: "except",
      zh: "除……之外；不包括",
      spellRight: 1,
      spellWrong: 0,
      meaningRight: 0,
      meaningWrong: 1,
    });
    live.rootStudyStore.items.push({
      id: "voc\0声音；呼喊",
      root: "voc",
      meaning: "声音；呼喊",
      words: ["vocal", "vocation"],
      choiceRight: 1,
      choiceWrong: 0,
      writeRight: 0,
      writeWrong: 1,
    });
    live.rootStudyStore.items[0]!.words = ["capture", "captain"];

    const projected = structuredClone(live);
    projected.store.archives.reverse();
    projected.store.current.words.reverse();
    projected.store.archives.forEach((library) => library.words.reverse());
    projected.intensiveStore.words.reverse();
    const accept = projected.store.current.words.find((word) => word.en === "accept")!;
    (accept.roots as unknown[]).reverse();
    projected.rootStudyStore.items.reverse();
    projected.rootStudyStore.items.find((item) => item.id.startsWith("cap"))!.words = [
      "captain",
      "capture",
      "capture",
    ];

    expect(comparableLegacyBundle(projected)).toBe(comparableLegacyBundle(live));
  });

  it("recomputes derived mastery and treats missing optional counts as zero", () => {
    const live = legacyBundle();
    const projected = structuredClone(live);
    Object.assign(live.store.current.words[0]!, {
      right: 2,
      wrong: 1,
      mastery: 0,
      reverseRight: 1,
      reverseWrong: 1,
      reverseMastery: 100,
      rareRight: 0,
      rareWrong: 2,
      rareMastery: 50,
      spellRight: 0,
      spellWrong: 0,
      meaningRight: 0,
      meaningWrong: 0,
    });
    Object.assign(projected.store.current.words[0]!, {
      right: 2,
      wrong: 1,
      mastery: 99,
      reverseRight: 1,
      reverseWrong: 1,
      reverseMastery: 0,
      rareRight: 0,
      rareWrong: 2,
      rareMastery: 100,
    });

    expect(comparableLegacyBundle(projected)).toBe(comparableLegacyBundle(live));
  });

  it("still detects a real value change", () => {
    const live = legacyBundle();
    const projected = structuredClone(live);
    const word = projected.store.current.words[0]!;
    word.right = (word.right ?? 0) + 1;

    expect(comparableLegacyBundle(projected)).not.toBe(comparableLegacyBundle(live));
  });

  it("keeps raw learning statistics and reverse weight strict", () => {
    const live = legacyBundle();
    const mutations: Array<(bundle: ReturnType<typeof legacyBundle>) => void> = [
      (bundle) => { bundle.store.current.words[0]!.reverseRight = 1; },
      (bundle) => { bundle.store.current.words[0]!.rareWrong = 1; },
      (bundle) => { bundle.store.current.words[0]!.reverseReviewWeight = 0.5; },
      (bundle) => { bundle.intensiveStore.words[0]!.spellRight = 1; },
      (bundle) => { bundle.rootStudyStore.items[0]!.choiceWrong = 1; },
    ];

    for (const mutate of mutations) {
      const projected = structuredClone(live);
      mutate(projected);
      expect(comparableLegacyBundle(projected)).not.toBe(comparableLegacyBundle(live));
    }
  });

  it("detects corrected Chinese root semantics and root metadata", () => {
    const live = legacyBundle();
    live.store.current.words[0]!.roots = [{ root: "prov", meaning: "普罗夫", source: "legacy" }];
    const projected = structuredClone(live);
    projected.store.current.words[0]!.roots = [{ root: "prov", meaning: "好；检验；证明", source: "engra" }];

    expect(comparableLegacyBundle(projected)).not.toBe(comparableLegacyBundle(live));
    expect(decideLegacyProjection(projected, live, new MemoryStorage())).toBe("apply");
  });

  it("keeps library identity, word content, selection, and settings strict", () => {
    const live = legacyBundle();
    const mutations: Array<(bundle: ReturnType<typeof legacyBundle>) => void> = [
      (bundle) => { bundle.store.current.id = "different-library"; },
      (bundle) => { bundle.store.current.date = "2026-08-07"; },
      (bundle) => { bundle.store.current.words[0]!.en = "changed"; },
      (bundle) => { bundle.store.current.words[0]!.zh = "改变"; },
      (bundle) => { bundle.intensiveStore.reviewedLibraryId = "different-library"; },
      (bundle) => { bundle.settings.rootVisible = false; },
    ];

    for (const mutate of mutations) {
      const projected = structuredClone(live);
      mutate(projected);
      expect(comparableLegacyBundle(projected)).not.toBe(comparableLegacyBundle(live));
    }
  });

  it("allows at most one reload for the same projected snapshot", () => {
    const storage = new MemoryStorage();
    const projected = legacyBundle();
    const live = structuredClone(projected);
    live.store.current.words[0]!.zh = "legacy normalization changed this value";

    expect(decideLegacyProjection(projected, live, storage)).toBe("apply");
    expect(storage.getItem(STARTUP_RECONCILE_MARKER_KEY)).toBeTruthy();
    expect(decideLegacyProjection(projected, live, storage)).toBe("continue");

    const changedProjection = structuredClone(projected);
    changedProjection.store.current.words[0]!.right = 1;
    expect(decideLegacyProjection(changedProjection, live, storage)).toBe("apply");
  });

  it("fails open instead of reloading forever when marker storage is unavailable", () => {
    const projected = legacyBundle();
    const live = structuredClone(projected);
    live.store.current.words[0]!.zh = "different";
    const unavailable = {
      getItem: () => null,
      setItem: () => { throw new Error("quota exceeded"); },
    };

    expect(decideLegacyProjection(projected, live, unavailable)).toBe("continue");
  });

  it("keeps v8-managed pipe-id libraries out of the legacy daily rollover", () => {
    const source = readFileSync(new URL("../index.html", import.meta.url), "utf8");

    expect(source).toContain("!v8ManagedLibraryIds.has(store.current.id)");
    expect(source).toContain("localStorage.setItem(V8_MANAGED_LIBRARY_IDS_KEY");
  });

  it("performs the protected second reconciliation after content materialization", () => {
    const source = readFileSync(new URL("../src/main.ts", import.meta.url), "utf8");
    const materialize = source.indexOf("contentManager = await initContentManagerUi");
    const reconcile = source.indexOf("const materializedDecision = decideLegacyProjection", materialize);
    const review = source.indexOf("initReviewUi({ store, legacyRuntime })", reconcile);

    expect(materialize).toBeGreaterThanOrEqual(0);
    expect(reconcile).toBeGreaterThan(materialize);
    expect(review).toBeGreaterThan(reconcile);
    expect(source.slice(reconcile, review)).toContain("legacyRuntime.applyBundle(materializedProjection)");
    expect(source.slice(reconcile, review)).toContain("return;");
  });

  it("checks semantic equality before a content-triggered legacy reload", () => {
    const source = readFileSync(new URL("../src/content/ui.ts", import.meta.url), "utf8");
    const materialize = source.indexOf("private async materialize(reload: boolean)");
    const compare = source.indexOf("comparableLegacyBundle(bundle)", materialize);
    const apply = source.indexOf("this.options.legacyRuntime.applyBundle(bundle)", materialize);

    expect(materialize).toBeGreaterThanOrEqual(0);
    expect(compare).toBeGreaterThan(materialize);
    expect(apply).toBeGreaterThan(compare);
  });
});
