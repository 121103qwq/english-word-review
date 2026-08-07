import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const read = (path: string): string => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");

describe("settings UI integration safeguards", () => {
  it("keeps mode changes on the unified surface-switch path", () => {
    const html = read("index.html");
    expect(html).toContain("function prepareModeSurface(mode)");
    expect(html).toContain("prepareModeSurface(mode);\n      target.click();");
    expect(html).toContain("prepareModeSurface(mode);\n        updateTopModeUi(mode);");
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

  it("never bypasses current-question speech safety from the toolbar", () => {
    const main = read("src/main.ts");
    expect(main).toContain("await settingsController.speakWord();");
    expect(main).not.toContain("await settingsController.speakWord(word);");
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
});
