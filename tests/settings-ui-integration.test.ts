import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const read = (path: string): string => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");

describe("settings UI integration safeguards", () => {
  it("keeps mode changes on the unified surface-switch path", () => {
    const html = read("index.html");
    expect(html).toContain("function prepareModeSurface(mode)");
    expect(html).toMatch(/prepareModeSurface\(mode\);\r?\n\s+target\.click\(\);/u);
    expect(html).toMatch(/prepareModeSurface\(mode\);\r?\n\s+updateTopModeUi\(mode\);/u);
    expect(html).toContain("$('topWrongOnlyToggle').disabled = !wrongOnlyAvailable");
  });

  it("restores the learning mode when the review panel closes", () => {
    const main = read("src/main.ts");
    const review = read("src/review/ui.ts");
    expect(review).toContain("onClose?: () => void");
    expect(review).toContain("options.onClose?.()");
    expect(review).toContain('if (settingsModal && !settingsModal.hidden) return;');
    expect(main).toContain('if (state.mode === "review") legacyRuntime.requestMode(state.studyMode);');
  });

  it("routes review and root answer shortcuts through the configurable layer", () => {
    const html = read("index.html");
    const controller = read("src/settings/controller.ts");
    expect(html).toContain('data-shortcut-action="review-submit"');
    expect(html).toContain('data-shortcut-action="review-known"');
    expect(html).toContain('data-shortcut-action="review-unknown"');
    expect(html).toContain('data-shortcut-action="root-submit"');
    expect(controller).toContain('"review-submit": "reviewSubmit"');
    expect(controller).toContain('"root-submit": "rootConfirm"');
  });

  it("routes skip and undo actions to the active learning surface", () => {
    const html = read("index.html");
    const controller = read("src/settings/controller.ts");
    expect(controller).toContain('if (context === "intensive") return "intensiveSkip"');
    expect(controller).toContain('if (context === "root") return "rootSkip"');
    expect(controller).toContain('return context === "intensive" ? "undoIntensive" : "undoNormal"');
    expect(html).toContain("if (action === 'undoNormal') return undoLastForMode(false)");
    expect(html).toContain("if (action === 'undoIntensive') return undoLastForMode(true)");
    expect(html).toContain("if (action === 'intensiveSkip')");
    expect(html).toContain("if (action === 'rootSkip')");
  });

  it("prunes abandoned settings audio while retaining active and draft references", () => {
    const controller = read("src/settings/controller.ts");
    expect(controller).toContain("private referencedSettingsAssetHashes(): string[]");
    expect(controller).toContain("await this.assets.prune(this.referencedSettingsAssetHashes())");
    expect(controller.match(/pruneUnusedSettingsAssets\(\)/g)?.length ?? 0).toBeGreaterThanOrEqual(6);
  });

  it("never bypasses current-question speech safety from the toolbar", () => {
    const main = read("src/main.ts");
    expect(main).toContain("await settingsController.speakWord();");
    expect(main).not.toContain("await settingsController.speakWord(word);");
  });

  it("previews an isolated speech draft without replacing the saved state", () => {
    const controller = read("src/settings/controller.ts");
    expect(controller).toContain('this.speakWord("example", previewState)');
    expect(controller).not.toContain("this.state = previewState");
  });

  it("honors cleared shortcut labels and every restart-confirmation setting", () => {
    const html = read("index.html");
    expect(html).toContain("Object.prototype.hasOwnProperty.call(shortcutLabels, action)");
    expect(html.match(/configuredConfirmRestart && !confirm/g)).toHaveLength(3);
  });

  it("keeps settings unavailable until its controller has bound persistence", () => {
    const html = read("index.html");
    const main = read("src/main.ts");
    expect(html).toMatch(/id="settingsOpenBtn"[^>]*disabled/);
    expect(main).toContain("settingsButton.disabled = false;");
  });

  it("embeds progress and content mirror controls inside the settings data-sync section", () => {
    const html = read("index.html");
    const main = read("src/main.ts");
    const dataSyncSection = html.match(/id="settingsSectionDataSync"[\s\S]*?<\/section>\s*<\/div>\s*<\/div>/)?.[0] ?? "";
    expect(dataSyncSection).toContain('id="syncPanel"');
    expect(html.match(/id="syncPanel"/g)).toHaveLength(1);
    expect(html).not.toContain("settingsOpenDataSyncBtn");
    expect(main).not.toContain('byId<HTMLElement>("syncPanel").hidden');
    expect(main).toContain('settingsUi.activateSection("data-sync")');
  });

  it("shows a dedicated speech button only in the Android runtime", () => {
    const html = read("index.html");
    const main = read("src/main.ts");
    expect(html).toMatch(/id="androidSpeakBtn"[^>]*hidden/);
    expect(main).toContain('androidSpeakButton.hidden = runtimePlatform !== "android"');
    expect(main).toContain('byId<HTMLButtonElement>("speakBtn").onclick = speakCurrentQuestion');
    expect(main).toContain("androidSpeakButton.onclick");
    expect(main).toContain("updateAndroidSpeakButton(question.safeToSpeak)");
  });

  it("hides word roots but expands the study card and controls in focus mode", () => {
    const html = read("index.html");
    const focusRule = html.match(/body\.focus-mode \.stats[^}]+/u)?.[0] ?? "";
    expect(focusRule).toContain(".root-sidebar");
    expect(html).toContain("width: min(1360px, calc(100% - 32px))");
    expect(html).toContain("body.focus-mode .app-shell");
    expect(html).toContain("min-height: clamp(420px, 62vh, 680px)");
    expect(html).toContain("body.focus-mode .forward-actions button, body.focus-mode .answers button");
  });
});
