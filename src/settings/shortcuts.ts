import type {
  ShortcutAction,
  ShortcutBinding,
  ShortcutContext,
  ShortcutMap,
  ShortcutSlots,
} from "./types";

export const SHORTCUT_ACTIONS = [
  "next-word",
  "speak-word",
  "undo-answer",
  "reveal-answer",
  "uncertain",
  "answer-known",
  "answer-unknown",
  "choice-1",
  "choice-2",
  "choice-3",
  "choice-4",
  "intensive-correct",
  "intensive-wrong",
  "intensive-submit",
  "root-choice-1",
  "root-choice-2",
  "root-choice-3",
  "root-choice-4",
  "root-submit",
  "review-submit",
  "review-known",
  "review-unknown",
  "mode-forward",
  "mode-reverse",
  "mode-rare",
  "mode-intensive",
  "mode-root",
  "mode-review",
  "toggle-wrong-only",
] as const satisfies readonly ShortcutAction[];

export const SHORTCUT_ACTION_CONTEXT: Readonly<Record<ShortcutAction, ShortcutContext>> = Object.freeze({
  "next-word": "global",
  "speak-word": "global",
  "undo-answer": "global",
  "reveal-answer": "forward",
  uncertain: "forward",
  "answer-known": "forward",
  "answer-unknown": "forward",
  "choice-1": "choice",
  "choice-2": "choice",
  "choice-3": "choice",
  "choice-4": "choice",
  "intensive-correct": "intensive",
  "intensive-wrong": "intensive",
  "intensive-submit": "intensive",
  "root-choice-1": "root",
  "root-choice-2": "root",
  "root-choice-3": "root",
  "root-choice-4": "root",
  "root-submit": "root",
  "review-submit": "review",
  "review-known": "review",
  "review-unknown": "review",
  "mode-forward": "mode",
  "mode-reverse": "mode",
  "mode-rare": "mode",
  "mode-intensive": "mode",
  "mode-root": "mode",
  "mode-review": "mode",
  "toggle-wrong-only": "mode",
});

export const SHORTCUT_ACTION_LABELS: Readonly<Record<ShortcutAction, string>> = Object.freeze({
  "next-word": "换一个",
  "speak-word": "朗读单词",
  "undo-answer": "撤销上次作答",
  "reveal-answer": "显示释义",
  uncertain: "记不清",
  "answer-known": "认识 / 正确",
  "answer-unknown": "不认识 / 错误",
  "choice-1": "选项 1",
  "choice-2": "选项 2",
  "choice-3": "选项 3",
  "choice-4": "选项 4",
  "intensive-correct": "强化判定正确",
  "intensive-wrong": "强化判定错误",
  "intensive-submit": "强化提交 / 下一题",
  "root-choice-1": "词根选项 1",
  "root-choice-2": "词根选项 2",
  "root-choice-3": "词根选项 3",
  "root-choice-4": "词根选项 4",
  "root-submit": "词根提交 / 下一题",
  "review-submit": "检查背诵提交 / 下一题",
  "review-known": "检查背诵记得",
  "review-unknown": "检查背诵没记得",
  "mode-forward": "切换到看英文说中文",
  "mode-reverse": "切换到看中文选英文",
  "mode-rare": "切换到熟词生义",
  "mode-intensive": "切换到错词强化",
  "mode-root": "切换到词根背诵",
  "mode-review": "切换到检查背诵",
  "toggle-wrong-only": "切换仅复习错词",
});

const binding = (code: string): ShortcutBinding => ({ code });
const slots = (primary: ShortcutBinding | null, alternate: ShortcutBinding | null = null): ShortcutSlots => [primary, alternate];

export function createDefaultShortcutMap(): ShortcutMap {
  return {
    "next-word": slots(binding("KeyN")),
    "speak-word": slots(binding("KeyR")),
    "undo-answer": slots(binding("KeyU")),
    "reveal-answer": slots(binding("Space")),
    uncertain: slots(binding("KeyW")),
    "answer-known": slots(binding("KeyA")),
    "answer-unknown": slots(binding("KeyD")),
    "choice-1": slots(binding("KeyA")),
    "choice-2": slots(binding("KeyS")),
    "choice-3": slots(binding("KeyD")),
    "choice-4": slots(binding("KeyF")),
    "intensive-correct": slots(binding("KeyA")),
    "intensive-wrong": slots(binding("KeyD")),
    "intensive-submit": slots(binding("Enter"), binding("Space")),
    "root-choice-1": slots(binding("KeyA")),
    "root-choice-2": slots(binding("KeyS")),
    "root-choice-3": slots(binding("KeyD")),
    "root-choice-4": slots(binding("KeyF")),
    "root-submit": slots(binding("Enter")),
    "review-submit": slots(binding("Enter")),
    "review-known": slots(binding("KeyA")),
    "review-unknown": slots(binding("KeyD")),
    "mode-forward": slots(null),
    "mode-reverse": slots(null),
    "mode-rare": slots(null),
    "mode-intensive": slots(null),
    "mode-root": slots(null),
    "mode-review": slots(null),
    "toggle-wrong-only": slots(null),
  };
}

export function normalizeShortcutBinding(value: ShortcutBinding): ShortcutBinding {
  return {
    code: value.code.trim(),
    ...(value.ctrl ? { ctrl: true } : {}),
    ...(value.shift ? { shift: true } : {}),
  };
}

const MODIFIER_CODES = new Set([
  "AltLeft", "AltRight", "ControlLeft", "ControlRight", "MetaLeft", "MetaRight", "ShiftLeft", "ShiftRight",
]);
const RESERVED_SINGLE_CODES = new Map<string, string>([
  ["F5", "F5 是浏览器刷新快捷键"],
  ["F11", "F11 是浏览器全屏快捷键"],
  ["Escape", "Escape 用于关闭弹窗或取消输入"],
  ["Tab", "Tab 用于键盘焦点导航"],
]);
const RESERVED_CTRL_CODES = new Map<string, string>([
  ["KeyW", "Ctrl+W 用于关闭页面"],
  ["KeyR", "Ctrl+R 用于刷新页面"],
  ["KeyP", "Ctrl+P 用于打印"],
  ["KeyT", "Ctrl+T 用于新建标签页"],
  ["KeyN", "Ctrl+N 用于新建窗口"],
  ["KeyL", "Ctrl+L 用于定位地址栏"],
  ["KeyF", "Ctrl+F 用于页面查找"],
  ["KeyS", "Ctrl+S 用于保存页面"],
  ["KeyO", "Ctrl+O 用于打开文件"],
]);

export interface ShortcutBindingValidation {
  valid: boolean;
  reason?: string;
}

export function validateShortcutBinding(value: ShortcutBinding): ShortcutBindingValidation {
  const normalized = normalizeShortcutBinding(value);
  if (!normalized.code) return { valid: false, reason: "请选择一个按键" };
  if (MODIFIER_CODES.has(normalized.code)) return { valid: false, reason: "修饰键不能单独绑定" };
  const singleReason = RESERVED_SINGLE_CODES.get(normalized.code);
  if (singleReason) return { valid: false, reason: singleReason };
  if (normalized.ctrl) {
    const ctrlReason = RESERVED_CTRL_CODES.get(normalized.code);
    if (ctrlReason) return { valid: false, reason: ctrlReason };
  }
  return { valid: true };
}

export function shortcutBindingKey(value: ShortcutBinding): string {
  const binding = normalizeShortcutBinding(value);
  return `${binding.ctrl ? "C" : "-"}${binding.shift ? "S" : "-"}:${binding.code}`;
}

/** Global and mode-switch shortcuts are active in every study context. */
export function shortcutContextsOverlap(left: ShortcutContext, right: ShortcutContext): boolean {
  return left === right || left === "global" || right === "global" || left === "mode" || right === "mode";
}

export interface ShortcutLocation {
  action: ShortcutAction;
  slot: 0 | 1;
}

export interface ShortcutConflict extends ShortcutLocation {
  binding: ShortcutBinding;
}

export function findShortcutConflicts(
  shortcuts: ShortcutMap,
  action: ShortcutAction,
  slot: 0 | 1,
  candidate: ShortcutBinding,
): ShortcutConflict[] {
  const key = shortcutBindingKey(candidate);
  const context = SHORTCUT_ACTION_CONTEXT[action];
  const conflicts: ShortcutConflict[] = [];
  for (const otherAction of SHORTCUT_ACTIONS) {
    if (!shortcutContextsOverlap(context, SHORTCUT_ACTION_CONTEXT[otherAction])) continue;
    shortcuts[otherAction].forEach((other, otherSlot) => {
      if (!other || (otherAction === action && otherSlot === slot)) return;
      if (shortcutBindingKey(other) === key) {
        conflicts.push({ action: otherAction, slot: otherSlot as 0 | 1, binding: normalizeShortcutBinding(other) });
      }
    });
  }
  return conflicts;
}

export interface ShortcutMapValidation {
  valid: boolean;
  reason?: string;
  conflict?: { left: ShortcutLocation; right: ShortcutLocation; binding: ShortcutBinding };
}

export function validateShortcutMap(shortcuts: ShortcutMap): ShortcutMapValidation {
  for (const action of SHORTCUT_ACTIONS) {
    const bindings = shortcuts[action];
    if (!Array.isArray(bindings) || bindings.length !== 2) {
      return { valid: false, reason: `${SHORTCUT_ACTION_LABELS[action]} 必须有两个快捷键槽位` };
    }
    for (let slot = 0; slot < 2; slot += 1) {
      const value = bindings[slot];
      if (!value) continue;
      const result = validateShortcutBinding(value);
      if (!result.valid) return { valid: false, reason: `${SHORTCUT_ACTION_LABELS[action]}：${result.reason}` };
      const conflict = findShortcutConflicts(shortcuts, action, slot as 0 | 1, value)[0];
      if (conflict) {
        return {
          valid: false,
          reason: `${SHORTCUT_ACTION_LABELS[action]} 与 ${SHORTCUT_ACTION_LABELS[conflict.action]} 使用了相同快捷键`,
          conflict: {
            left: { action, slot: slot as 0 | 1 },
            right: { action: conflict.action, slot: conflict.slot },
            binding: normalizeShortcutBinding(value),
          },
        };
      }
    }
  }
  return { valid: true };
}

export type ShortcutConflictStrategy = "reject" | "swap";
export type ShortcutMutationResult =
  | { status: "applied"; shortcuts: ShortcutMap; swapped?: ShortcutLocation }
  | { status: "conflict"; conflict: ShortcutConflict; allConflicts: ShortcutConflict[] }
  | { status: "rejected"; reason: string };

/**
 * Returns a new map. `swap` exchanges the occupied slot with the target slot;
 * the operation is rejected if that exchange would introduce a second conflict.
 */
export function setShortcutBinding(
  shortcuts: ShortcutMap,
  action: ShortcutAction,
  slot: 0 | 1,
  candidate: ShortcutBinding | null,
  strategy: ShortcutConflictStrategy = "reject",
): ShortcutMutationResult {
  const next = structuredClone(shortcuts);
  if (!candidate) {
    next[action][slot] = null;
    return { status: "applied", shortcuts: next };
  }
  const normalized = normalizeShortcutBinding(candidate);
  const validation = validateShortcutBinding(normalized);
  if (!validation.valid) return { status: "rejected", reason: validation.reason ?? "快捷键不可用" };
  const conflicts = findShortcutConflicts(next, action, slot, normalized);
  if (conflicts.length && strategy === "reject") {
    return { status: "conflict", conflict: conflicts[0], allConflicts: conflicts };
  }
  if (conflicts.length > 1) {
    return { status: "rejected", reason: "该按键与多个动作冲突，请先分别清除冲突" };
  }
  const oldTarget = next[action][slot];
  let swapped: ShortcutLocation | undefined;
  if (conflicts.length === 1) {
    swapped = { action: conflicts[0].action, slot: conflicts[0].slot };
    next[swapped.action][swapped.slot] = oldTarget ? normalizeShortcutBinding(oldTarget) : null;
  }
  next[action][slot] = normalized;
  const mapValidation = validateShortcutMap(next);
  if (!mapValidation.valid) return { status: "rejected", reason: mapValidation.reason ?? "交换后仍存在快捷键冲突" };
  return { status: "applied", shortcuts: next, ...(swapped ? { swapped } : {}) };
}

export interface KeyboardShortcutEvent {
  code: string;
  ctrlKey: boolean;
  shiftKey: boolean;
  altKey: boolean;
  metaKey: boolean;
  repeat?: boolean;
}

export function shortcutFromKeyboardEvent(event: KeyboardShortcutEvent): ShortcutBindingValidation & { binding?: ShortcutBinding } {
  if (event.altKey) return { valid: false, reason: "Alt 组合键不可绑定" };
  if (event.metaKey) return { valid: false, reason: "Windows/Command 组合键不可绑定" };
  const binding = normalizeShortcutBinding({ code: event.code, ctrl: event.ctrlKey, shift: event.shiftKey });
  const validation = validateShortcutBinding(binding);
  return validation.valid ? { valid: true, binding } : validation;
}

export function matchesShortcut(event: KeyboardShortcutEvent, binding: ShortcutBinding): boolean {
  if (event.altKey || event.metaKey || event.repeat) return false;
  const normalized = normalizeShortcutBinding(binding);
  return event.code === normalized.code && event.ctrlKey === Boolean(normalized.ctrl) && event.shiftKey === Boolean(normalized.shift);
}

const DISPLAY_NAMES: Readonly<Record<string, string>> = Object.freeze({
  Space: "空格",
  Enter: "Enter",
  ArrowUp: "↑",
  ArrowDown: "↓",
  ArrowLeft: "←",
  ArrowRight: "→",
});

export function formatShortcutBinding(value: ShortcutBinding): string {
  const binding = normalizeShortcutBinding(value);
  const code = DISPLAY_NAMES[binding.code]
    ?? binding.code.replace(/^Key/, "").replace(/^Digit/, "").replace(/^Numpad/, "小键盘 ");
  return [binding.ctrl ? "Ctrl" : "", binding.shift ? "Shift" : "", code].filter(Boolean).join("+");
}

export function isShortcutInputTarget(target: EventTarget | null): boolean {
  if (!(target instanceof Element)) return false;
  if (target.closest("[data-shortcuts-paused='true']")) return true;
  const editable = target.closest("input, textarea, select, [contenteditable='true'], [contenteditable='plaintext-only']");
  return editable !== null;
}
