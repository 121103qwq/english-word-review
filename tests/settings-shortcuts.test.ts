import { describe, expect, it } from "vitest";
import {
  createDefaultShortcutMap,
  findShortcutConflicts,
  formatShortcutBinding,
  matchesShortcut,
  setShortcutBinding,
  shortcutFromKeyboardEvent,
  validateShortcutMap,
} from "../src/settings/shortcuts";

describe("platform shortcut settings", () => {
  it("preserves existing defaults and leaves mode switches unbound", () => {
    const shortcuts = createDefaultShortcutMap();

    expect(shortcuts["reveal-answer"]).toEqual([{ code: "Space" }, null]);
    expect(shortcuts["answer-known"]).toEqual([{ code: "KeyA" }, null]);
    expect(shortcuts["choice-1"]).toEqual([{ code: "KeyA" }, null]);
    expect(shortcuts["intensive-submit"]).toEqual([{ code: "Enter" }, { code: "Space" }]);
    expect(shortcuts["root-submit"]).toEqual([{ code: "Enter" }, null]);
    expect(shortcuts["review-submit"]).toEqual([{ code: "Enter" }, null]);
    expect(shortcuts["review-known"]).toEqual([{ code: "KeyA" }, null]);
    expect(shortcuts["review-unknown"]).toEqual([{ code: "KeyD" }, null]);
    expect(shortcuts["mode-review"]).toEqual([null, null]);
    expect(shortcuts["toggle-wrong-only"]).toEqual([null, null]);
    expect(validateShortcutMap(shortcuts)).toEqual({ valid: true });
  });

  it("allows reuse between exclusive contexts but blocks global and mode conflicts", () => {
    const shortcuts = createDefaultShortcutMap();
    expect(findShortcutConflicts(shortcuts, "choice-1", 0, { code: "KeyA" })).toEqual([]);

    const globalConflict = setShortcutBinding(shortcuts, "speak-word", 0, { code: "KeyA" });
    expect(globalConflict.status).toBe("conflict");
    if (globalConflict.status === "conflict") expect(globalConflict.allConflicts.length).toBeGreaterThan(1);

    const modeConflict = setShortcutBinding(shortcuts, "mode-forward", 0, { code: "KeyA" });
    expect(modeConflict.status).toBe("conflict");
  });

  it("can exchange two conflicting slots and immediately removes the old binding", () => {
    const shortcuts = createDefaultShortcutMap();
    const result = setShortcutBinding(shortcuts, "answer-known", 0, { code: "KeyD" }, "swap");

    expect(result.status).toBe("applied");
    if (result.status !== "applied") return;
    expect(result.shortcuts["answer-known"][0]).toEqual({ code: "KeyD" });
    expect(result.shortcuts["answer-unknown"][0]).toEqual({ code: "KeyA" });
    expect(shortcuts["answer-known"][0]).toEqual({ code: "KeyA" });
  });

  it("rejects browser/OS shortcuts and accepts physical code bindings", () => {
    expect(setShortcutBinding(createDefaultShortcutMap(), "mode-forward", 0, { code: "F5" }).status).toBe("rejected");
    expect(setShortcutBinding(createDefaultShortcutMap(), "mode-forward", 0, { code: "KeyW", ctrl: true }).status).toBe("rejected");
    expect(shortcutFromKeyboardEvent({
      code: "KeyQ", ctrlKey: false, shiftKey: false, altKey: true, metaKey: false,
    })).toEqual({ valid: false, reason: "Alt 组合键不可绑定" });

    const physical = { code: "KeyA", ctrlKey: false, shiftKey: false, altKey: false, metaKey: false };
    expect(matchesShortcut(physical, { code: "KeyA" })).toBe(true);
    expect(matchesShortcut({ ...physical, code: "KeyQ" }, { code: "KeyA" })).toBe(false);
    expect(matchesShortcut({ ...physical, repeat: true }, { code: "KeyA" })).toBe(false);
  });

  it("supports two slots, modifiers and readable labels", () => {
    const shortcuts = createDefaultShortcutMap();
    const result = setShortcutBinding(shortcuts, "mode-forward", 1, { code: "Digit1", ctrl: true, shift: true });

    expect(result.status).toBe("applied");
    if (result.status !== "applied") return;
    expect(result.shortcuts["mode-forward"][1]).toEqual({ code: "Digit1", ctrl: true, shift: true });
    expect(formatShortcutBinding(result.shortcuts["mode-forward"][1]!)).toBe("Ctrl+Shift+1");
  });
});
