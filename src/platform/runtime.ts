import { Capacitor, registerPlugin } from "@capacitor/core";
import { invoke } from "@tauri-apps/api/core";

export interface HttpRequest {
  url: string;
  method: "GET" | "POST" | "PUT" | "DELETE" | "HEAD" | "MKCOL" | "PROPFIND";
  headers?: Record<string, string>;
  body?: string;
  bodyBase64?: string;
  responseType?: "text" | "base64";
  timeoutMs?: number;
}

export interface HttpResponse {
  status: number;
  headers: Record<string, string>;
  body: string;
}

export interface SaveTextFileResult {
  saved: boolean;
  path?: string;
}

declare global {
  interface Window {
    __englishReviewSaveTextFile?: (
      filename: string,
      content: string,
      mimeType?: string,
    ) => Promise<SaveTextFileResult>;
  }
}

interface NativeBridgePlugin {
  saveSecret(options: { key: string; value: string }): Promise<void>;
  loadSecret(options: { key: string }): Promise<{ value: string | null }>;
  deleteSecret(options: { key: string }): Promise<void>;
  saveTextFile(options: { filename: string; content: string; mimeType: string }): Promise<SaveTextFileResult>;
  speak(options: { text: string; locale: string; rate: number }): Promise<void>;
  httpRequest(options: { request: HttpRequest }): Promise<HttpResponse>;
  openExternalUrl(options: { url: string }): Promise<void>;
}

const NativeBridge = registerPlugin<NativeBridgePlugin>("EnglishReviewNative");

function isTauri(): boolean {
  return "__TAURI_INTERNALS__" in window;
}

function isCapacitorNative(): boolean {
  return Capacitor.isNativePlatform();
}

export function isNativeRuntime(): boolean {
  return isTauri() || isCapacitorNative();
}

export type RuntimePlatform = "windows" | "android" | "html";

export function getRuntimePlatform(): RuntimePlatform {
  if (isTauri()) return "windows";
  if (isCapacitorNative()) return "android";
  return "html";
}

export async function saveTextFile(
  filename: string,
  content: string,
  mimeType = "application/json",
): Promise<SaveTextFileResult> {
  if (isTauri()) {
    return invoke<SaveTextFileResult>("save_text_file", { filename, content, mimeType });
  }
  if (isCapacitorNative()) {
    return NativeBridge.saveTextFile({ filename, content, mimeType });
  }

  const url = URL.createObjectURL(new Blob([content], { type: `${mimeType};charset=utf-8` }));
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.hidden = true;
  document.body.append(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1_000);
  return { saved: true };
}

export async function httpRequest(request: HttpRequest): Promise<HttpResponse> {
  if (isTauri()) {
    return invoke<HttpResponse>("native_http_request", { request });
  }
  if (isCapacitorNative()) {
    return NativeBridge.httpRequest({ request });
  }
  const body = request.bodyBase64
    ? Uint8Array.from(atob(request.bodyBase64), (character) => character.charCodeAt(0))
    : request.body;
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), request.timeoutMs ?? 12_000);
  try {
    const response = await fetch(request.url, { method: request.method, headers: request.headers, body, signal: controller.signal });
    let responseBody: string;
    if (request.responseType === "base64") {
      const bytes = new Uint8Array(await response.arrayBuffer());
      let binary = "";
      for (let offset = 0; offset < bytes.length; offset += 0x8000) {
        binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000));
      }
      responseBody = btoa(binary);
    } else {
      responseBody = await response.text();
    }
    return {
      status: response.status,
      headers: Object.fromEntries([...response.headers.entries()].map(([key, value]) => [key.toLowerCase(), value])),
      body: responseBody,
    };
  } finally {
    window.clearTimeout(timeout);
  }
}

export async function saveSecret(key: string, value: string): Promise<void> {
  if (isTauri()) await invoke("save_secret", { key, value });
  else if (isCapacitorNative()) await NativeBridge.saveSecret({ key, value });
  else sessionStorage.setItem(`english-review:${key}`, value);
}

export async function loadSecret(key: string): Promise<string> {
  if (isTauri()) return (await invoke<string | null>("load_secret", { key })) ?? "";
  if (isCapacitorNative()) return (await NativeBridge.loadSecret({ key })).value ?? "";
  return sessionStorage.getItem(`english-review:${key}`) ?? "";
}

export async function deleteSecret(key: string): Promise<void> {
  if (isTauri()) await invoke("delete_secret", { key });
  else if (isCapacitorNative()) await NativeBridge.deleteSecret({ key });
  else sessionStorage.removeItem(`english-review:${key}`);
}

export interface SpeakEnglishOptions {
  rate?: number;
  voice?: string;
}

export async function openExternalUrl(url: string): Promise<void> {
  const parsed = new URL(url);
  if (parsed.protocol !== "https:") throw new Error("只允许打开 HTTPS 更新地址");
  if (isTauri()) {
    await invoke("open_external_url", { url: parsed.href });
    return;
  }
  if (isCapacitorNative()) {
    await NativeBridge.openExternalUrl({ url: parsed.href });
    return;
  }
  const opened = window.open(parsed.href, "_blank", "noopener,noreferrer");
  if (!opened) {
    const link = document.createElement("a");
    link.href = parsed.href;
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    link.click();
  }
}

export async function speakEnglish(text: string, options: SpeakEnglishOptions = {}): Promise<void> {
  const rate = Math.max(0.5, Math.min(2, options.rate ?? 0.85));
  // Android WebView may expose the Web Speech API even when it has no usable
  // synthesis service. Prefer the platform bridges so a present-but-inert web
  // implementation cannot swallow the request.
  if (isCapacitorNative()) {
    await NativeBridge.speak({ text, locale: "en-US", rate });
    return;
  }
  if (isTauri()) {
    await invoke("speak_text", { text, locale: "en-US", rate });
    return;
  }
  if ("speechSynthesis" in window && typeof SpeechSynthesisUtterance !== "undefined") {
    speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = "en-US";
    utterance.rate = rate;
    if (options.voice) {
      const voice = speechSynthesis.getVoices().find((candidate) => candidate.name === options.voice);
      if (voice) utterance.voice = voice;
    }
    speechSynthesis.speak(utterance);
    return;
  }
  throw new Error("当前环境不支持语音朗读");
}
