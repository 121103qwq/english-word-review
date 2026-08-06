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

  it("still detects a real value change", () => {
    const live = legacyBundle();
    const projected = structuredClone(live);
    const word = projected.store.current.words[0]!;
    word.right = (word.right ?? 0) + 1;

    expect(comparableLegacyBundle(projected)).not.toBe(comparableLegacyBundle(live));
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
});
