import { describe, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";
import {
  GitHubReleaseUpdateChecker,
  compareReleaseVersions,
  parseAvailableUpdate,
  parseVersion,
} from "../src/platform/update-check";

const release = {
  tag_name: "v8.4.0",
  name: "英语单词速记 v8.4.0",
  html_url: "https://github.com/121103qwq/english-word-review/releases/tag/v8.4.0",
  assets: [
    { name: "english-word-review-8.4.0.html", browser_download_url: "https://github.com/121103qwq/english-word-review/releases/download/v8.4.0/app.html" },
    { name: "english-word-review-8.4.0-setup.exe", browser_download_url: "https://github.com/121103qwq/english-word-review/releases/download/v8.4.0/setup.exe" },
    { name: "english-word-review-8.4.0-android8-plus.apk", browser_download_url: "https://github.com/121103qwq/english-word-review/releases/download/v8.4.0/app.apk" },
  ],
};

describe("GitHub Release update checker", () => {
  it("parses and compares three-part release versions", () => {
    expect(parseVersion("v8.4.0")).toEqual([8, 4, 0]);
    expect(parseVersion("latest")).toBeNull();
    expect(compareReleaseVersions("8.4.0", "8.3.2")).toBeGreaterThan(0);
    expect(compareReleaseVersions("8.3.2", "8.3.2")).toBe(0);
  });

  it.each([
    ["windows", "english-word-review-8.4.0-setup.exe"],
    ["android", "english-word-review-8.4.0-android8-plus.apk"],
    ["html", "english-word-review-8.4.0.html"],
  ] as const)("selects the %s asset", (platform, assetName) => {
    expect(parseAvailableUpdate(release, "8.3.2", platform)?.assetName).toBe(assetName);
  });

  it("does not prompt for the current or an older version", () => {
    expect(parseAvailableUpdate(release, "8.4.0", "html")).toBeNull();
    expect(parseAvailableUpdate(release, "9.0.0", "html")).toBeNull();
  });

  it("deduplicates concurrent checks and caches the result for five minutes", async () => {
    let resolveRequest!: (value: { status: number; headers: Record<string, string>; body: string }) => void;
    const request = vi.fn(() => new Promise<{ status: number; headers: Record<string, string>; body: string }>((resolve) => {
      resolveRequest = resolve;
    }));
    let now = 1_000;
    const checker = new GitHubReleaseUpdateChecker("8.3.2", "android", request, () => now);
    const first = checker.check();
    const second = checker.check();
    expect(request).toHaveBeenCalledTimes(1);
    resolveRequest({ status: 200, headers: {}, body: JSON.stringify(release) });
    expect((await first)?.version).toBe("8.4.0");
    expect(await second).toEqual(await first);
    await checker.check();
    expect(request).toHaveBeenCalledTimes(1);
    now += 5 * 60 * 1000;
    const refreshed = checker.check();
    expect(request).toHaveBeenCalledTimes(2);
    resolveRequest({ status: 200, headers: {}, body: JSON.stringify(release) });
    await refreshed;
  });

  it("is wired only to startup and opening data-sync settings", () => {
    const main = readFileSync("src/main.ts", "utf8");
    expect(main.match(/void checkForApplicationUpdate\(\)/gu)).toHaveLength(2);
    expect(main).not.toContain("setInterval");
    const html = readFileSync("index.html", "utf8");
    expect(html).toContain('id="updateModal"');
    expect(html).toContain('id="updateNowBtn"');
  });
});
