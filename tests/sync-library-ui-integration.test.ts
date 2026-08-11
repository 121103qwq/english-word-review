import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const root = resolve(import.meta.dirname, "..");
const main = readFileSync(resolve(root, "src/main.ts"), "utf8");
const html = readFileSync(resolve(root, "index.html"), "utf8");

describe("manual library sync architecture", () => {
  it("keeps startup content local and routes all cloud transfer through the explicit chooser", () => {
    expect(main).toContain("getTransports: () => []");
    expect(main).not.toMatch(/\bsyncContentStartup\s*\(/);
    expect(main).toContain("createLibrarySyncBatch(");
    expect(main).toContain("listLibrarySyncChoices(");
    expect(main).toContain("uploadLibrarySyncSelection(");
    expect(main).toContain("downloadLibrarySyncSelection(");
  });

  it("provides separate local-upload and cloud-download lists", () => {
    expect(html).toContain('id="dataSyncSelectionModal"');
    expect(html).toContain('id="dataSyncLocalList"');
    expect(html).toContain('id="dataSyncCloudList"');
    expect(html).toContain('id="dataSyncIncludeAssets"');
    expect(html).toContain('id="dataSyncSelectionApplyBtn"');
  });
});
