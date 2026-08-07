import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const SETTINGS_DATA_FILES = [
  "types.ts",
  "model.ts",
  "storage.ts",
  "shortcuts.ts",
  "drafts.ts",
  "assets.ts",
  "transports.ts",
  "controller.ts",
];

describe("platform settings data isolation", () => {
  it("does not import or serialize word-library, learning-event or review models", () => {
    for (const file of SETTINGS_DATA_FILES) {
      const source = readFileSync(join(process.cwd(), "src", "settings", file), "utf8");
      expect(source, file).not.toMatch(/from\s+["'][^"']*(?:content|core\/events|review)\//);
      expect(source, file).not.toMatch(/\b(?:ContentSnapshotV1|LearningEvent|V4Snapshot)\b/);
    }
  });
});
