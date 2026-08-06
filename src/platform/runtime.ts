import { Capacitor, registerPlugin } from "@capacitor/core";
import { invoke } from "@tauri-apps/api/core";

export interface HttpRequest {
  url: string;
  method: "GET" | "PUT" | "DELETE" | "HEAD" | "MKCOL" | "PROPFIND";
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

interface NativeBridgePlugin {
  saveSecret(options: { key: string; value: string }): Promise<void>;
  loadSecret(options: { key: string }): Promise<{ value: string | null }>;
  deleteSecret(options: { key: string }): Promise<void>;
  speak(options: { text: string; locale: string; rate: number }): Promise<void>;
  httpRequest(options: { request: HttpRequest }): Promise<HttpResponse>;
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

export async function speakEnglish(text: string): Promise<void> {
  if ("speechSynthesis" in window && typeof SpeechSynthesisUtterance !== "undefined") {
    speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = "en-US";
    utterance.rate = 0.85;
    speechSynthesis.speak(utterance);
    return;
  }
  if (isTauri()) await invoke("speak_text", { text, locale: "en-US", rate: 0.85 });
  else if (isCapacitorNative()) await NativeBridge.speak({ text, locale: "en-US", rate: 0.85 });
  else throw new Error("当前环境不支持语音朗读");
}
