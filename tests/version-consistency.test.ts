import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const read = (path: string): string => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");

describe("8.2.4 version consistency", () => {
  it("keeps web, content, Windows, Tauri, and Android versions aligned", () => {
    const packageJson = JSON.parse(read("package.json")) as { version: string };

    expect(packageJson.version).toBe("8.2.4");
    expect(read("src/core/config.ts")).toContain('APP_VERSION = "8.2.4"');
    expect(read("src/content/model.ts")).toContain('CONTENT_APP_VERSION = "8.2.4"');
    expect(read("src-tauri/tauri.conf.json")).toContain('"version": "8.2.4"');
    expect(read("src-tauri/Cargo.toml")).toContain('version = "8.2.4"');
    expect(read("android/app/build.gradle")).toContain('versionName "8.2.4"');
    expect(read("android/app/build.gradle")).toContain("versionCode 80204");
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
