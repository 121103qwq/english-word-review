import { describe, expect, it } from "vitest";
import type { LegacyWord } from "../src/core/types";
import { comparableLegacyBundle } from "../src/platform/legacy-bundle";
import { legacyBundle } from "./fixtures";

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
});
