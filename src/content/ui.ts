import { checkWord, lookupWord, type DictionaryEntry } from "../dictionary";
import type { LegacyBundle, LegacyLibrary, LegacyRuntimeApi, LegacyWord, RootStudyStore } from "../core/types";
import {
  applyWordOverride,
  createLibrary,
  createMp3Asset,
  migrateLegacyBundle,
  parseWordInput,
  resolveWord,
} from "./model";
import { ContentRepository, IndexedDbContentBackend } from "./storage";
import { commitContentMutation, type ContentSyncResult } from "../sync/content-sync";
import type { ContentTransport } from "../sync/content-transports";
import type {
  ContentSnapshotV1,
  CustomLibrary,
  CustomLibraryWord,
  RootComponent,
  StoredAudioAsset,
  WordOverride,
} from "./types";

interface ContentUiOptions {
  deviceId: string;
  legacyRuntime: LegacyRuntimeApi;
  getTransports?: () => ContentTransport[];
  onCommitted?: (snapshot: ContentSnapshotV1, result?: ContentSyncResult) => Promise<void> | void;
}

interface CheckedRow {
  word: string;
  entry?: DictionaryEntry;
  suggestions: string[];
  meaning: string;
  rootText: string;
}

const byId = <T extends HTMLElement>(id: string): T => {
  const element = document.getElementById(id);
  if (!element) throw new Error(`缺少界面元素：${id}`);
  return element as T;
};

function rootsFromDictionary(entry?: DictionaryEntry): RootComponent[] | undefined {
  if (!entry?.roots?.length) return undefined;
  return entry.roots.map((root) => ({
    root: root.form,
    meaning: root.meaningZh,
    source: root.inferred ? "inferred" : "engra",
    ...(root.meaningEn ? { note: root.meaningEn } : {}),
  }));
}

function dictionaryOverride(entry?: DictionaryEntry): WordOverride | undefined {
  if (!entry) return undefined;
  return {
    meaning: entry.translation,
    ...(entry.phonetic ? { pronunciation: entry.phonetic } : {}),
    ...(entry.roots?.length ? { roots: rootsFromDictionary(entry) } : {}),
  };
}

function rootsFromText(value: string, source: RootComponent["source"] = "manual"): RootComponent[] {
  return value.split(/\r?\n/u).map((line) => {
    const [root, ...meaning] = line.split(/[=＝]/u);
    return { root: root.trim(), meaning: meaning.join("=").trim(), source };
  }).filter((root) => root.root && root.meaning);
}

function rootsToText(roots?: RootComponent[]): string {
  return (roots ?? []).map((root) => `${root.root} = ${root.meaning}`).join("\n");
}

function cloneScores(word?: LegacyWord): LegacyWord {
  return {
    en: word?.en ?? "",
    zh: word?.zh ?? "",
    right: Number(word?.right) || 0,
    wrong: Number(word?.wrong) || 0,
    mastery: Number(word?.mastery) || 0,
    reverseRight: Number(word?.reverseRight) || 0,
    reverseWrong: Number(word?.reverseWrong) || 0,
    reverseMastery: Number(word?.reverseMastery) || 0,
    reverseReviewWeight: Number(word?.reverseReviewWeight) || 0,
    rareRight: Number(word?.rareRight) || 0,
    rareWrong: Number(word?.rareWrong) || 0,
    rareMastery: Number(word?.rareMastery) || 0,
    spellRight: Number(word?.spellRight) || 0,
    spellWrong: Number(word?.spellWrong) || 0,
    meaningRight: Number(word?.meaningRight) || 0,
    meaningWrong: Number(word?.meaningWrong) || 0,
  };
}

function materializeRootStudy(base: RootStudyStore, libraries: LegacyLibrary[]): RootStudyStore {
  const items = new Map(base.items.map((item) => [item.id, structuredClone(item)]));
  for (const library of libraries) {
    for (const word of library.words) {
      const roots = Array.isArray(word.roots) ? word.roots as Array<Record<string, unknown>> : [];
      for (const value of roots) {
        const root = String(value.root ?? value.form ?? "").trim();
        const meaning = String(value.meaning ?? value.meaningZh ?? value.zh ?? "").trim();
        if (!root || !meaning) continue;
        const id = `${root}\u0000${meaning}`;
        const item = items.get(id) ?? {
          id, root, meaning, words: [], choiceRight: 0, choiceWrong: 0, writeRight: 0, writeWrong: 0,
        };
        if (!item.words.includes(word.en)) item.words.push(word.en);
        items.set(id, item);
      }
    }
  }
  return { items: [...items.values()] };
}

export class ContentManagerUi {
  readonly backend = new IndexedDbContentBackend();
  readonly repository: ContentRepository;
  private snapshot!: ContentSnapshotV1;
  private rows: CheckedRow[] = [];
  private inputMode: "paste" | "table" = "paste";
  private editor?: { libraryId: string; word: string; audioIds: string[]; primaryAudioId?: string | null };

  constructor(private readonly options: ContentUiOptions) {
    this.repository = new ContentRepository(this.backend, options.deviceId);
  }

  async init(): Promise<void> {
    const stored = await this.backend.getCurrent();
    if (stored) this.snapshot = stored;
    else {
      this.snapshot = migrateLegacyBundle(this.options.legacyRuntime.getBundle(), this.options.deviceId);
      await this.backend.putCurrent(this.snapshot);
    }
    this.bind();
    this.setToday();
    this.ensureTableRows(4);
    this.render();
    await this.materialize(false);
  }

  getSnapshot(): ContentSnapshotV1 { return structuredClone(this.snapshot); }

  async acceptSynchronizedSnapshot(snapshot: ContentSnapshotV1): Promise<void> {
    await this.backend.putCurrent(snapshot);
    this.snapshot = structuredClone(snapshot);
    this.render();
    await this.materialize(true);
  }

  async replaceFromRemote(snapshot: ContentSnapshotV1): Promise<void> {
    await this.repository.replaceFromRemote(snapshot);
    this.snapshot = structuredClone(snapshot);
    this.render();
    await this.materialize(true);
  }

  private setToday(): void {
    const date = new Date();
    const pad = (value: number) => String(value).padStart(2, "0");
    byId<HTMLInputElement>("libraryDate").value = `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
  }

  private bind(): void {
    byId<HTMLButtonElement>("libraryManageBtn").onclick = () => this.toggleManager();
    byId<HTMLButtonElement>("closeLibraryManagerBtn").onclick = () => this.toggleManager(false);
    byId<HTMLButtonElement>("pasteInputModeBtn").onclick = () => this.setInputMode("paste");
    byId<HTMLButtonElement>("tableInputModeBtn").onclick = () => this.setInputMode("table");
    byId<HTMLButtonElement>("checkAndAddWordsBtn").onclick = () => void this.checkAndAdd();
    byId<HTMLSelectElement>("libraryTarget").onchange = () => this.applyTargetState();
    byId<HTMLButtonElement>("closeLibraryEditorBtn").onclick = () => this.closeEditor();
    byId<HTMLButtonElement>("saveWordEditBtn").onclick = () => void this.saveEditor();
    byId<HTMLInputElement>("editorAudioFiles").onchange = (event) => void this.addAudioFiles(event);
    byId<HTMLElement>("libraryEditor").onclick = (event) => {
      if (event.target === byId("libraryEditor")) this.closeEditor();
    };
  }

  private toggleManager(force?: boolean): void {
    const panel = byId<HTMLElement>("libraryManager");
    panel.hidden = force === undefined ? !panel.hidden : !force;
    if (!panel.hidden) byId<HTMLInputElement>("libraryDate").focus();
  }

  private setInputMode(mode: "paste" | "table"): void {
    this.inputMode = mode;
    byId("pasteInputMode").hidden = mode !== "paste";
    byId("tableInputMode").hidden = mode !== "table";
    byId("pasteInputModeBtn").classList.toggle("active", mode === "paste");
    byId("tableInputModeBtn").classList.toggle("active", mode === "table");
    (mode === "paste" ? byId<HTMLTextAreaElement>("libraryPasteInput") : byId<HTMLInputElement>("wordEntryRows").querySelector("input"))?.focus();
  }

  private ensureTableRows(count: number): void {
    const body = byId<HTMLTableSectionElement>("wordEntryRows");
    while (body.children.length < count) {
      const row = document.createElement("tr");
      const cell = document.createElement("td");
      const input = document.createElement("input");
      input.className = "table-word-input";
      input.placeholder = `单词 ${body.children.length + 1}`;
      input.spellcheck = false;
      input.addEventListener("keydown", (event) => {
        if (event.key !== "Enter" || event.isComposing) return;
        event.preventDefault();
        const index = [...body.querySelectorAll<HTMLInputElement>("input")].indexOf(input);
        this.ensureTableRows(index + 2);
        body.querySelectorAll<HTMLInputElement>("input")[index + 1]?.focus();
      });
      cell.append(input);
      const action = document.createElement("td");
      const clear = document.createElement("button");
      clear.type = "button";
      clear.textContent = "清空";
      clear.onclick = () => { input.value = ""; input.focus(); };
      action.append(clear);
      row.append(cell, action);
      body.append(row);
    }
  }

  private inputWords(): string[] {
    if (this.inputMode === "paste") return parseWordInput(byId<HTMLTextAreaElement>("libraryPasteInput").value);
    return parseWordInput([...byId("wordEntryRows").querySelectorAll<HTMLInputElement>("input")].map((input) => input.value).join("\n"));
  }

  private setStatus(message: string, bad = false): void {
    const status = byId<HTMLElement>("libraryStatus");
    status.textContent = message;
    status.className = `sync-status ${bad ? "bad" : "good"}`;
  }

  private async checkAndAdd(): Promise<void> {
    try {
      const date = byId<HTMLInputElement>("libraryDate").value;
      if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) throw new Error("请先填写有效日期");
      const words = this.rows.length ? this.rows.map((row) => row.word) : this.inputWords();
      if (!words.length) throw new Error("请至少输入一个英文单词");
      this.setStatus("正在本地检查单词……");
      const existing = new Map(this.rows.map((row) => [row.word, row]));
      this.rows = await Promise.all(words.map(async (word): Promise<CheckedRow> => {
        const checked = await checkWord(word);
        const old = existing.get(checked.normalized);
        return {
          word: checked.normalized,
          entry: checked.entry,
          suggestions: checked.suggestions,
          meaning: checked.entry?.translation ?? old?.meaning ?? "",
          rootText: checked.entry ? rootsToText(rootsFromDictionary(checked.entry)) : old?.rootText ?? "",
        };
      }));
      this.renderCheckRows();
      if (this.rows.every((row) => row.entry || row.meaning.trim())) await this.commitRows();
      else this.setStatus("请修正错误单词，或为用户词条填写中文释义。", true);
    } catch (error) {
      this.setStatus(error instanceof Error ? error.message : String(error), true);
    }
  }

  private renderCheckRows(): void {
    const host = byId("wordCheckResults");
    host.replaceChildren();
    if (!this.rows.length) return;
    const table = document.createElement("table");
    table.className = "word-check-table";
    table.innerHTML = "<thead><tr><th>英文</th><th>状态/建议</th><th>中文释义</th><th>词根</th></tr></thead>";
    const body = document.createElement("tbody");
    this.rows.forEach((row) => {
      const tr = document.createElement("tr");
      const word = document.createElement("td");
      word.textContent = row.word;
      const status = document.createElement("td");
      status.className = row.entry ? "word-status-ok" : "word-status-error";
      if (row.entry) status.textContent = "词典匹配";
      else {
        const message = document.createElement("div");
        message.textContent = "词典未收录";
        status.append(message);
        row.suggestions.forEach((candidate) => {
          const button = document.createElement("button");
          button.className = "suggestion-chip";
          button.type = "button";
          button.textContent = candidate;
          button.onclick = () => void this.acceptSuggestion(row, candidate);
          status.append(button);
        });
      }
      const meaningCell = document.createElement("td");
      const meaning = document.createElement("input");
      meaning.value = row.meaning;
      meaning.placeholder = "未知词必须填写中文";
      meaning.oninput = () => { row.meaning = meaning.value; };
      meaningCell.append(meaning);
      const rootCell = document.createElement("td");
      const roots = document.createElement("input");
      roots.value = row.rootText.replace(/\n/g, "; ");
      roots.placeholder = "root = 中文义";
      roots.oninput = () => { row.rootText = roots.value.replace(/;\s*/g, "\n"); };
      rootCell.append(roots);
      tr.append(word, status, meaningCell, rootCell);
      body.append(tr);
    });
    table.append(body);
    host.append(table);
  }

  private async acceptSuggestion(row: CheckedRow, candidate: string): Promise<void> {
    const result = await checkWord(candidate);
    row.word = result.normalized;
    row.entry = result.entry;
    row.suggestions = result.suggestions;
    row.meaning = result.entry?.translation ?? "";
    row.rootText = rootsToText(rootsFromDictionary(result.entry));
    this.renderCheckRows();
    if (this.rows.every((item) => item.entry || item.meaning.trim())) await this.commitRows();
  }

  private async commitRows(): Promise<void> {
    const date = byId<HTMLInputElement>("libraryDate").value;
    const target = byId<HTMLSelectElement>("libraryTarget").value;
    const name = byId<HTMLInputElement>("libraryName").value.trim();
    const additions: CustomLibraryWord[] = this.rows.map((row) => ({
      word: row.word,
      source: row.entry ? "dictionary" : "user",
      ...(!row.entry ? { override: { meaning: row.meaning.trim(), roots: rootsFromText(row.rootText) } } : {}),
    }));
    this.snapshot = await this.commitMutation((draft) => {
      let library: CustomLibrary;
      if (target === "new") {
        library = createLibrary({ date, name, words: [] });
        draft.libraries.unshift(library);
      } else {
        const existing = draft.libraries.find((item) => item.id === target);
        if (!existing) throw new Error("目标词库不存在");
        library = existing;
      }
      const known = new Set(library.words.map((entry) => entry.word.toLowerCase()));
      for (const entry of additions) if (!known.has(entry.word.toLowerCase())) library.words.push(entry);
      library.modifiedAt = new Date().toISOString();
      draft.activeLibraryId = library.id;
    });
    this.setStatus(`已在本地保存 ${additions.length} 个单词，正在同步……`);
    sessionStorage.setItem("english-review:content-status", "词库已保存并进入同步队列。");
    this.rows = [];
    await this.materialize(true);
  }

  private applyTargetState(): void {
    const isNew = byId<HTMLSelectElement>("libraryTarget").value === "new";
    byId<HTMLInputElement>("libraryDate").disabled = !isNew;
    byId<HTMLInputElement>("libraryName").disabled = !isNew;
  }

  private render(): void {
    const target = byId<HTMLSelectElement>("libraryTarget");
    const previous = target.value;
    target.replaceChildren(new Option("新建词库", "new"));
    const ordered = [...this.snapshot.libraries].sort((left, right) =>
      right.date.localeCompare(left.date) || right.modifiedAt.localeCompare(left.modifiedAt));
    for (const library of ordered) target.add(new Option(`${library.date} · ${library.name || `${library.words.length} 词`}`, library.id));
    target.value = [...target.options].some((option) => option.value === previous) ? previous : "new";
    this.applyTargetState();
    const list = byId("managedLibraryList");
    list.replaceChildren();
    if (!ordered.length) {
      list.textContent = "还没有手动词库。";
      return;
    }
    ordered.forEach((library) => {
      const details = document.createElement("details");
      details.className = "managed-library";
      if (library.id === this.snapshot.activeLibraryId) details.open = true;
      const summary = document.createElement("summary");
      summary.textContent = `${library.date} · ${library.name || "未命名词库"} · ${library.words.length} 个词${library.id === this.snapshot.activeLibraryId ? " · 当前" : ""}`;
      const body = document.createElement("div");
      body.className = "managed-library-body";
      const use = document.createElement("button");
      use.type = "button";
      use.textContent = "设为当前词库";
      use.disabled = library.id === this.snapshot.activeLibraryId;
      use.onclick = () => void this.activateLibrary(library.id);
      body.append(use);
      library.words.forEach((entry) => {
        const row = document.createElement("div");
        row.className = "managed-word-row";
        const word = document.createElement("strong");
        word.textContent = entry.word;
        const meaning = document.createElement("span");
        meaning.className = "managed-word-meaning";
        meaning.textContent = entry.override?.meaning || entry.legacyOverride?.meaning || "读取词典中……";
        void lookupWord(entry.word).then((dictionary) => {
          const resolved = resolveWord(entry, this.snapshot.globalOverrides[entry.word], dictionaryOverride(dictionary));
          meaning.textContent = resolved.meaning || "暂无中文释义";
        });
        const edit = document.createElement("button");
        edit.type = "button";
        edit.textContent = "修改";
        edit.onclick = () => void this.openEditor(library.id, entry.word);
        row.append(word, meaning, edit);
        body.append(row);
      });
      details.append(summary, body);
      list.append(details);
    });
  }

  private async activateLibrary(libraryId: string): Promise<void> {
    this.snapshot = await this.commitMutation((draft) => { draft.activeLibraryId = libraryId; });
    await this.materialize(true);
  }

  private async openEditor(libraryId: string, word: string): Promise<void> {
    const library = this.snapshot.libraries.find((item) => item.id === libraryId);
    const entry = library?.words.find((item) => item.word === word);
    if (!entry) return;
    const dictionary = await lookupWord(word);
    const resolved = resolveWord(entry, this.snapshot.globalOverrides[word], dictionaryOverride(dictionary));
    this.editor = {
      libraryId,
      word,
      audioIds: [...(resolved.audioAssetIds ?? [])],
      primaryAudioId: resolved.primaryAudioAssetId,
    };
    byId<HTMLInputElement>("editorWord").value = word;
    byId<HTMLTextAreaElement>("editorMeaning").value = resolved.meaning ?? "";
    byId<HTMLTextAreaElement>("editorRoots").value = rootsToText(resolved.roots);
    byId<HTMLInputElement>("editorPronunciation").value = resolved.pronunciation ?? "";
    byId<HTMLInputElement>("editorAudioFiles").value = "";
    byId<HTMLElement>("wordEditorStatus").textContent = "";
    await this.renderAudioList();
    byId<HTMLElement>("libraryEditor").hidden = false;
    byId<HTMLTextAreaElement>("editorMeaning").focus();
  }

  private closeEditor(): void {
    byId<HTMLElement>("libraryEditor").hidden = true;
    this.editor = undefined;
  }

  private async addAudioFiles(event: Event): Promise<void> {
    if (!this.editor) return;
    const input = event.currentTarget as HTMLInputElement;
    const status = byId<HTMLElement>("wordEditorStatus");
    try {
      for (const file of [...(input.files ?? [])]) {
        if (file.size > 20 * 1024 * 1024) throw new Error("MP3 文件不能超过 20 MiB");
        await assertDecodableAudio(file);
        const asset = await createMp3Asset(file, file.name);
        await this.repository.saveAsset(asset);
        this.snapshot.assets[asset.meta.id] = asset.meta;
        if (!this.editor.audioIds.includes(asset.meta.id)) this.editor.audioIds.push(asset.meta.id);
        this.editor.primaryAudioId ??= asset.meta.id;
      }
      status.textContent = "MP3 已保存到本地，保存单词后同步。";
      status.className = "sync-status good";
      await this.renderAudioList();
    } catch (error) {
      status.textContent = error instanceof Error ? error.message : String(error);
      status.className = "sync-status bad";
    } finally {
      input.value = "";
    }
  }

  private async renderAudioList(): Promise<void> {
    const host = byId("editorAudioList");
    host.replaceChildren();
    if (!this.editor?.audioIds.length) {
      host.textContent = "没有自定义 MP3，将使用浏览器或系统朗读。";
      return;
    }
    for (const [index, id] of this.editor.audioIds.entries()) {
      const asset = await this.backend.getAsset(id);
      const row = document.createElement("div");
      row.className = "audio-row";
      const primary = document.createElement("input");
      primary.type = "radio";
      primary.name = "primaryAudio";
      primary.checked = this.editor.primaryAudioId === id;
      primary.title = "设为主音频";
      primary.onchange = () => { if (this.editor) this.editor.primaryAudioId = id; };
      const name = document.createElement("span");
      name.textContent = asset?.meta.fileName || this.snapshot.assets[id]?.fileName || id;
      const play = document.createElement("button");
      play.type = "button";
      play.textContent = "试听";
      play.disabled = !asset;
      play.onclick = () => { if (asset) void playAsset(asset); };
      const remove = document.createElement("button");
      remove.type = "button";
      remove.textContent = "移除";
      remove.onclick = () => {
        if (!this.editor) return;
        this.editor.audioIds = this.editor.audioIds.filter((item) => item !== id);
        if (this.editor.primaryAudioId === id) this.editor.primaryAudioId = this.editor.audioIds[0] ?? null;
        void this.renderAudioList();
      };
      const up = document.createElement("button");
      up.type = "button";
      up.textContent = "上移";
      up.disabled = index === 0;
      up.onclick = () => {
        if (!this.editor || index === 0) return;
        [this.editor.audioIds[index - 1], this.editor.audioIds[index]] = [id, this.editor.audioIds[index - 1]];
        void this.renderAudioList();
      };
      const down = document.createElement("button");
      down.type = "button";
      down.textContent = "下移";
      down.disabled = index === this.editor.audioIds.length - 1;
      down.onclick = () => {
        if (!this.editor || index >= this.editor.audioIds.length - 1) return;
        [this.editor.audioIds[index], this.editor.audioIds[index + 1]] = [this.editor.audioIds[index + 1], id];
        void this.renderAudioList();
      };
      row.append(primary, name, play, up, down, remove);
      host.append(row);
    }
  }

  private async saveEditor(): Promise<void> {
    if (!this.editor) return;
    const status = byId<HTMLElement>("wordEditorStatus");
    try {
      const patch: WordOverride = {
        meaning: byId<HTMLTextAreaElement>("editorMeaning").value.trim(),
        roots: rootsFromText(byId<HTMLTextAreaElement>("editorRoots").value),
        pronunciation: byId<HTMLInputElement>("editorPronunciation").value.trim(),
        audioAssetIds: [...this.editor.audioIds],
        primaryAudioAssetId: this.editor.primaryAudioId ?? null,
      };
      const scope = document.querySelector<HTMLInputElement>('input[name="editorScope"]:checked')?.value === "global"
        ? { type: "global" as const }
        : { type: "library" as const, libraryId: this.editor.libraryId };
      const pendingAssets = await Promise.all(this.editor.audioIds.map((id) => this.backend.getAsset(id)));
      this.snapshot = await this.commitMutation((current) => {
        const changed = applyWordOverride(current, this.editor!.word, patch, scope);
        for (const asset of pendingAssets) if (asset) changed.assets[asset.meta.id] = asset.meta;
        return changed;
      });
      status.textContent = "已保存本地，正在同步……";
      status.className = "sync-status good";
      this.closeEditor();
      await this.materialize(true);
    } catch (error) {
      status.textContent = error instanceof Error ? error.message : String(error);
      status.className = "sync-status bad";
    }
  }

  private async commitMutation(
    mutate: (snapshot: ContentSnapshotV1) => ContentSnapshotV1 | void,
  ): Promise<ContentSnapshotV1> {
    const result = await commitContentMutation({
      persistence: this.backend,
      transports: this.options.getTransports?.() ?? [],
      deviceId: this.options.deviceId,
      mutate,
    });
    await this.options.onCommitted?.(result.snapshot, result);
    await this.repository.pruneUnreferencedAssets();
    this.render();
    return result.snapshot;
  }

  private async materialize(reload: boolean): Promise<void> {
    const live = this.options.legacyRuntime.getBundle();
    const liveLibraries = new Map([live.store.current, ...live.store.archives].map((library) => [library.id, library]));
    const libraries: LegacyLibrary[] = [];
    for (const library of this.snapshot.libraries) {
      const oldWords = new Map((liveLibraries.get(library.id)?.words ?? []).map((word) => [word.en.toLowerCase(), word]));
      const words: LegacyWord[] = [];
      for (const entry of library.words) {
        const dictionary = await lookupWord(entry.word);
        const resolved = resolveWord(entry, this.snapshot.globalOverrides[entry.word], dictionaryOverride(dictionary));
        const scores = cloneScores(oldWords.get(entry.word.toLowerCase()) ?? entry.legacyProgress);
        words.push({
          ...scores,
          en: entry.word,
          zh: resolved.meaning ?? "",
          roots: resolved.roots ?? [],
          phonetic: resolved.pronunciation ?? "",
          audioAssetIds: resolved.audioAssetIds ?? [],
          primaryAudioAssetId: resolved.primaryAudioAssetId ?? null,
        });
      }
      libraries.push({ id: library.id, date: library.date, words });
    }
    if (!libraries.length) return;
    const activeId = this.snapshot.activeLibraryId ?? libraries[0].id;
    window.__v8Bridge?.replaceLibraries(libraries, activeId);
    if (!reload) return;
    const current = libraries.find((library) => library.id === activeId) ?? libraries[0];
    const bundle: LegacyBundle = {
      ...live,
      store: { current, archives: libraries.filter((library) => library.id !== current.id) },
      rootStudyStore: materializeRootStudy(live.rootStudyStore, libraries),
    };
    this.options.legacyRuntime.applyBundle(bundle);
  }

  async playPrimaryForWord(word: string): Promise<boolean> {
    const library = this.snapshot.libraries.find((item) => item.id === this.snapshot.activeLibraryId);
    const entry = library?.words.find((item) => item.word.toLowerCase() === word.toLowerCase());
    if (!entry) return false;
    const dictionary = await lookupWord(entry.word);
    const resolved = resolveWord(entry, this.snapshot.globalOverrides[entry.word], dictionaryOverride(dictionary));
    const id = resolved.primaryAudioAssetId ?? resolved.audioAssetIds?.[0];
    if (!id) return false;
    const asset = await this.backend.getAsset(id);
    if (!asset) return false;
    await playAsset(asset);
    return true;
  }
}

async function playAsset(asset: StoredAudioAsset): Promise<void> {
  const url = URL.createObjectURL(new Blob([asset.bytes as BlobPart], { type: "audio/mpeg" }));
  const audio = new Audio(url);
  try { await audio.play(); } finally { audio.onended = () => URL.revokeObjectURL(url); }
}

async function assertDecodableAudio(file: File): Promise<void> {
  const AudioContextClass = window.AudioContext ??
    (window as typeof window & { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
  if (!AudioContextClass) return;
  const context = new AudioContextClass();
  try {
    await context.decodeAudioData((await file.arrayBuffer()).slice(0));
  } catch {
    throw new Error(`${file.name} 不是可解码的 MP3 音频`);
  } finally {
    await context.close().catch(() => undefined);
  }
}

export async function initContentManagerUi(options: ContentUiOptions): Promise<ContentManagerUi> {
  const manager = new ContentManagerUi(options);
  await manager.init();
  return manager;
}
