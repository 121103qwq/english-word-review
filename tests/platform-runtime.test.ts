import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  invoke: vi.fn(),
  isNativePlatform: vi.fn(),
  nativeSaveTextFile: vi.fn(),
  nativeSpeak: vi.fn(),
}));

vi.mock("@tauri-apps/api/core", () => ({ invoke: mocks.invoke }));
vi.mock("@capacitor/core", () => ({
  Capacitor: { isNativePlatform: mocks.isNativePlatform },
  registerPlugin: () => ({ saveTextFile: mocks.nativeSaveTextFile, speak: mocks.nativeSpeak }),
}));

import { saveTextFile, speakEnglish } from "../src/platform/runtime";

describe("platform text export", () => {
  beforeEach(() => {
    mocks.invoke.mockReset();
    mocks.isNativePlatform.mockReset().mockReturnValue(false);
    mocks.nativeSaveTextFile.mockReset();
    mocks.nativeSpeak.mockReset();
  });

  it("uses Android native TTS even when WebView exposes speechSynthesis", async () => {
    const browserSpeak = vi.fn();
    vi.stubGlobal("window", { speechSynthesis: { cancel: vi.fn(), speak: browserSpeak } });
    vi.stubGlobal("speechSynthesis", { cancel: vi.fn(), speak: browserSpeak });
    vi.stubGlobal("SpeechSynthesisUtterance", vi.fn());
    mocks.isNativePlatform.mockReturnValue(true);
    mocks.nativeSpeak.mockResolvedValue(undefined);

    await speakEnglish("apple", { rate: 9 });

    expect(mocks.nativeSpeak).toHaveBeenCalledWith({ text: "apple", locale: "en-US", rate: 2 });
    expect(browserSpeak).not.toHaveBeenCalled();
  });

  it("opens the Tauri save command on Windows", async () => {
    vi.stubGlobal("window", { __TAURI_INTERNALS__: {} });
    mocks.invoke.mockResolvedValue({ saved: true, path: "C:\\exports\\progress.json" });

    const result = await saveTextFile("progress.json", "{}", "application/json");

    expect(result.saved).toBe(true);
    expect(mocks.invoke).toHaveBeenCalledWith("save_text_file", {
      filename: "progress.json",
      content: "{}",
      mimeType: "application/json",
    });
  });

  it("opens the Capacitor native save prompt on Android", async () => {
    vi.stubGlobal("window", {});
    mocks.isNativePlatform.mockReturnValue(true);
    mocks.nativeSaveTextFile.mockResolvedValue({ saved: false });

    const result = await saveTextFile("progress.json", "{}", "application/json");

    expect(result).toEqual({ saved: false });
    expect(mocks.nativeSaveTextFile).toHaveBeenCalledWith({
      filename: "progress.json",
      content: "{}",
      mimeType: "application/json",
    });
  });

  it("keeps anchor downloads as the HTML fallback", async () => {
    const link = { href: "", download: "", hidden: false, click: vi.fn(), remove: vi.fn() };
    const append = vi.fn();
    const createObjectURL = vi.fn(() => "blob:test");
    const revokeObjectURL = vi.fn();
    vi.stubGlobal("window", {
      setTimeout: (callback: () => void) => {
        callback();
        return 1;
      },
    });
    vi.stubGlobal("document", { body: { append }, createElement: vi.fn(() => link) });
    vi.stubGlobal("URL", { createObjectURL, revokeObjectURL });

    const result = await saveTextFile("progress.json", "{}", "application/json");

    expect(result).toEqual({ saved: true });
    expect(link.download).toBe("progress.json");
    expect(append).toHaveBeenCalledWith(link);
    expect(link.click).toHaveBeenCalledOnce();
    expect(link.remove).toHaveBeenCalledOnce();
    expect(revokeObjectURL).toHaveBeenCalledWith("blob:test");
  });
});
