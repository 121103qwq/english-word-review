import type {
  LibraryCreationDraftV1,
  LibraryDraftInputMode,
  LibraryDraftRow,
  PlatformKind,
} from "./types";
import type { SettingsStorageLike } from "./storage";

export const LIBRARY_DRAFT_STORAGE_KEY_PREFIX = "english-word-review-library-draft-v1";

export function libraryDraftStorageKey(platform: PlatformKind): string {
  return `${LIBRARY_DRAFT_STORAGE_KEY_PREFIX}:${platform}`;
}

export function createEmptyLibraryDraft(platform: PlatformKind, now = new Date()): LibraryCreationDraftV1 {
  const pad = (value: number) => String(value).padStart(2, "0");
  return {
    schemaVersion: 1,
    platform,
    date: `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`,
    name: "",
    targetLibraryId: "new",
    inputMode: "paste",
    pasteInput: "",
    tableWords: [""],
    checkedRows: [],
    modifiedAt: now.toISOString(),
  };
}

function requiredString(value: unknown, label: string): string {
  if (typeof value !== "string") throw new Error(`${label}格式无效`);
  return value;
}

function draftRow(value: unknown): LibraryDraftRow {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("词库草稿检查行格式无效");
  const source = value as Record<string, unknown>;
  return {
    word: requiredString(source.word, "草稿单词"),
    meaning: requiredString(source.meaning, "草稿释义"),
    rootText: requiredString(source.rootText, "草稿词根"),
  };
}

export function validateLibraryDraft(value: unknown, expectedPlatform?: PlatformKind): LibraryCreationDraftV1 {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("词库草稿格式无效");
  const source = value as Record<string, unknown>;
  if (source.schemaVersion !== 1) throw new Error("不支持该词库草稿版本");
  if (source.platform !== "windows" && source.platform !== "android" && source.platform !== "html") {
    throw new Error("词库草稿平台无效");
  }
  if (expectedPlatform && source.platform !== expectedPlatform) throw new Error("词库草稿平台不匹配");
  const inputMode = source.inputMode;
  if (inputMode !== "paste" && inputMode !== "table") throw new Error("词库草稿输入方式无效");
  const tableWords = source.tableWords;
  const checkedRows = source.checkedRows;
  if (!Array.isArray(tableWords) || !tableWords.every((item) => typeof item === "string")) throw new Error("词库草稿表格无效");
  if (!Array.isArray(checkedRows)) throw new Error("词库草稿检查结果无效");
  const modifiedAt = requiredString(source.modifiedAt, "草稿修改时间");
  if (Number.isNaN(Date.parse(modifiedAt))) throw new Error("草稿修改时间无效");
  return {
    schemaVersion: 1,
    platform: source.platform,
    date: requiredString(source.date, "草稿日期"),
    name: requiredString(source.name, "草稿名称"),
    targetLibraryId: requiredString(source.targetLibraryId, "目标词库"),
    inputMode: inputMode as LibraryDraftInputMode,
    pasteInput: requiredString(source.pasteInput, "粘贴内容"),
    tableWords: [...tableWords],
    checkedRows: checkedRows.map(draftRow),
    modifiedAt,
  };
}

export function isLibraryDraftEmpty(draft: LibraryCreationDraftV1): boolean {
  return !draft.name.trim()
    && draft.targetLibraryId === "new"
    && !draft.pasteInput.trim()
    && draft.tableWords.every((word) => !word.trim())
    && draft.checkedRows.length === 0;
}

/** Local-only draft persistence. Drafts are never included in setting sync files. */
export class LibraryDraftStorage {
  private readonly key: string;

  constructor(
    readonly platform: PlatformKind,
    private readonly storage: SettingsStorageLike = globalThis.localStorage,
    private readonly now: () => Date = () => new Date(),
  ) {
    if (!storage) throw new Error("当前环境不支持词库草稿存储");
    this.key = libraryDraftStorageKey(platform);
  }

  load(): LibraryCreationDraftV1 | undefined {
    const raw = this.storage.getItem(this.key);
    if (!raw) return undefined;
    try {
      return validateLibraryDraft(JSON.parse(raw), this.platform);
    } catch (error) {
      throw new Error(`本机词库草稿已损坏：${error instanceof Error ? error.message : String(error)}`);
    }
  }

  save(draft: LibraryCreationDraftV1): LibraryCreationDraftV1 {
    const next = validateLibraryDraft({ ...draft, platform: this.platform, modifiedAt: this.now().toISOString() }, this.platform);
    this.storage.setItem(this.key, JSON.stringify(next));
    return next;
  }

  update(update: (draft: LibraryCreationDraftV1) => void): LibraryCreationDraftV1 {
    const next = structuredClone(this.load() ?? createEmptyLibraryDraft(this.platform, this.now()));
    update(next);
    return this.save(next);
  }

  clear(): void {
    this.storage.removeItem(this.key);
  }
}
