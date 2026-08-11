import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const read = (path: string): string => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");

describe("8.4.1 version consistency", () => {
  it("keeps web, content, Windows, Tauri, and Android versions aligned", () => {
    const packageJson = JSON.parse(read("package.json")) as { version: string };

    expect(packageJson.version).toBe("8.4.1");
    expect(read("src/core/config.ts")).toContain('APP_VERSION = "8.4.1"');
    expect(read("src/content/model.ts")).toContain('CONTENT_APP_VERSION = "8.4.1"');
    expect(read("src-tauri/tauri.conf.json")).toContain('"version": "8.4.1"');
    expect(read("src-tauri/Cargo.toml")).toContain('version = "8.4.1"');
    expect(read("src-tauri/Cargo.lock")).toMatch(/name = "english-word-review"\r?\nversion = "8\.4\.1"/u);
    expect(read("src-tauri/src/lib.rs")).toContain('user_agent("EnglishWordReview/8.4.1")');
    expect(read("android/app/build.gradle")).toContain('versionName "8.4.1"');
    expect(read("index.html")).toContain('id="exportV4Btn">导出完整 8.4.1</button>');
    expect(read("index.html")).toContain('id="versionChip">8.4.1 · weighted-random-v1</span>');
    expect(read("android/app/build.gradle")).toContain("versionCode 80401");
  });

  it("derives every release artifact filename from package.json", () => {
    for (const path of [
      "scripts/package-html.ps1",
      "scripts/package-portable.ps1",
      "scripts/package-windows-installer.ps1",
      "scripts/package-android.ps1",
    ]) {
      const source = read(path);
      expect(source).toContain("package.json");
      expect(source).toContain("$version");
      expect(source).not.toContain("english-word-review-8.1.0");
    }
    expect(read("installer/windows.nsi")).toContain("english-word-review-${PRODUCT_VERSION}-setup.exe");
  });
});
