import { Capacitor, CapacitorHttp, registerPlugin } from "@capacitor/core";
import { invoke } from "@tauri-apps/api/core";

export interface HttpRequest {
  url: string;
  method: "GET" | "PUT";
  headers?: Record<string, string>;
  body?: string;
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
}

const NativeBridge = registerPlugin<NativeBridgePlugin>("EnglishReviewNative");

function isTauri(): boolean {
  return "__TAURI_INTERNALS__" in window;
}

function isCapacitorNative(): boolean {
  return Capacitor.isNativePlatform();
}

export async function httpRequest(request: HttpRequest): Promise<HttpResponse> {
  if (isTauri()) {
    return invoke<HttpResponse>("native_http_request", { request });
  }
  if (isCapacitorNative()) {
    const response = await CapacitorHttp.request({
      url: request.url,
      method: request.method,
      headers: request.headers,
      data: request.body,
      responseType: "text",
    });
    return {
      status: response.status,
      headers: Object.fromEntries(Object.entries(response.headers ?? {}).map(([key, value]) => [key.toLowerCase(), String(value)])),
      body: typeof response.data === "string" ? response.data : JSON.stringify(response.data),
    };
  }
  const response = await fetch(request.url, {
    method: request.method,
    headers: request.headers,
    body: request.body,
  });
  return {
    status: response.status,
    headers: Object.fromEntries([...response.headers.entries()].map(([key, value]) => [key.toLowerCase(), value])),
    body: await response.text(),
  };
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
