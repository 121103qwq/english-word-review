import { httpRequest, type HttpResponse } from "../platform/runtime";

export const MIMO_TTS_ENDPOINT = "https://api.xiaomimimo.com/v1/chat/completions";
export const MIMO_TTS_MODEL = "mimo-v2.5-tts";
export const MIMO_TTS_TIMEOUT_MS = 6_000;
export const MIMO_FAILURE_COOLDOWN_MS = 60_000;
export const MIMO_ENGLISH_VOICES = ["Mia", "Chloe", "Milo", "Dean"] as const;

export type MimoEnglishVoice = typeof MIMO_ENGLISH_VOICES[number];

export interface MimoApiKeyStorage {
  load(): Promise<string>;
  save(value: string): Promise<void>;
}

export interface MimoPrivateKeySource {
  readMimoApiKey(): Promise<string | null>;
}

/**
 * Resolves the key lazily. Remote access occurs only when MiMo is selected by
 * the caller and this resolver is asked for a key.
 */
export class MimoApiKeyResolver {
  constructor(
    private readonly local: MimoApiKeyStorage,
    private readonly remote?: MimoPrivateKeySource,
  ) {}

  async resolve(): Promise<string> {
    const localValue = (await this.local.load()).trim();
    if (localValue) return localValue;
    if (!this.remote) return "";
    const remoteValue = (await this.remote.readMimoApiKey())?.trim() ?? "";
    if (!remoteValue) return "";
    await this.local.save(remoteValue);
    return remoteValue;
  }
}

export interface MimoTtsHttpRequest {
  url: string;
  method: "POST";
  headers: Record<string, string>;
  body: string;
  timeoutMs: number;
}

export type MimoTtsRequest = (request: MimoTtsHttpRequest) => Promise<HttpResponse>;

export interface MimoSynthesisOptions {
  voice?: MimoEnglishVoice;
  rate?: number;
}

export interface MimoSynthesisSuccess {
  status: "ok";
  bytes: Uint8Array;
  mimeType: "audio/wav";
  voice: MimoEnglishVoice;
  rate: number;
  model: typeof MIMO_TTS_MODEL;
}

export interface MimoSynthesisFallback {
  status: "fallback";
  reason: "missing-key" | "cooldown" | "timeout" | "http-error" | "invalid-response";
  retryAfterMs?: number;
}

export type MimoSynthesisResult = MimoSynthesisSuccess | MimoSynthesisFallback;

export interface MimoTtsClientOptions {
  request?: MimoTtsRequest;
  now?: () => number;
  timeoutMs?: number;
  cooldownMs?: number;
}

function defaultRequest(request: MimoTtsHttpRequest): Promise<HttpResponse> {
  // Native bridges accept arbitrary HTTP methods. runtime.ts will expose POST
  // explicitly during the 8.2 integration; the assertion keeps this module
  // isolated from that platform-owned file.
  return httpRequest(request as Parameters<typeof httpRequest>[0]);
}

function normalizedRate(value: number | undefined): number {
  if (!Number.isFinite(value)) return 1;
  return Math.max(0.5, Math.min(2, Number((value ?? 1).toFixed(2))));
}

function speedInstruction(rate: number): string | undefined {
  if (rate < 0.99) return `Speak clear English slowly at about ${rate.toFixed(2)} times the normal pace.`;
  if (rate > 1.01) return `Speak clear English quickly at about ${rate.toFixed(2)} times the normal pace.`;
  return undefined;
}

function fromBase64(value: string): Uint8Array {
  const binary = atob(value.replace(/\s/g, ""));
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

function isWav(bytes: Uint8Array): boolean {
  return bytes.length >= 12 &&
    String.fromCharCode(...bytes.subarray(0, 4)) === "RIFF" &&
    String.fromCharCode(...bytes.subarray(8, 12)) === "WAVE";
}

function failureReason(error: unknown): MimoSynthesisFallback["reason"] {
  const name = error instanceof Error ? error.name : "";
  const message = error instanceof Error ? error.message : String(error);
  return name === "AbortError" || /abort|timeout/i.test(message) ? "timeout" : "http-error";
}

/** Non-streaming MiMo v2.5 client. Callers handle the system/browser fallback. */
export class MimoTtsClient {
  private readonly request: MimoTtsRequest;
  private readonly now: () => number;
  private readonly timeoutMs: number;
  private readonly cooldownMs: number;
  private readonly failedUntil = new Map<string, number>();

  constructor(
    private readonly keys: MimoApiKeyResolver,
    options: MimoTtsClientOptions = {},
  ) {
    this.request = options.request ?? defaultRequest;
    this.now = options.now ?? (() => Date.now());
    this.timeoutMs = options.timeoutMs ?? MIMO_TTS_TIMEOUT_MS;
    this.cooldownMs = options.cooldownMs ?? MIMO_FAILURE_COOLDOWN_MS;
  }

  async synthesize(text: string, options: MimoSynthesisOptions = {}): Promise<MimoSynthesisResult> {
    const normalizedText = text.trim();
    if (!normalizedText) return { status: "fallback", reason: "invalid-response" };
    const voice = options.voice ?? "Mia";
    const rate = normalizedRate(options.rate);
    const cooldownKey = normalizedText.toLocaleLowerCase("en-US");
    const now = this.now();
    const failedUntil = this.failedUntil.get(cooldownKey) ?? 0;
    if (failedUntil > now) {
      return { status: "fallback", reason: "cooldown", retryAfterMs: failedUntil - now };
    }

    let apiKey: string;
    try {
      apiKey = await this.keys.resolve();
    } catch {
      this.failedUntil.set(cooldownKey, now + this.cooldownMs);
      return { status: "fallback", reason: "http-error" };
    }
    if (!apiKey) return { status: "fallback", reason: "missing-key" };

    const instruction = speedInstruction(rate);
    const messages = [
      ...(instruction ? [{ role: "user", content: instruction }] : []),
      { role: "assistant", content: normalizedText },
    ];
    try {
      const response = await this.request({
        url: MIMO_TTS_ENDPOINT,
        method: "POST",
        headers: { "api-key": apiKey, "Content-Type": "application/json" },
        body: JSON.stringify({
          model: MIMO_TTS_MODEL,
          messages,
          audio: { format: "wav", voice },
        }),
        timeoutMs: this.timeoutMs,
      });
      if (response.status < 200 || response.status >= 300) {
        this.failedUntil.set(cooldownKey, now + this.cooldownMs);
        return { status: "fallback", reason: "http-error" };
      }
      const body = JSON.parse(response.body) as {
        choices?: Array<{ message?: { audio?: { data?: string } } }>;
      };
      const encoded = body.choices?.[0]?.message?.audio?.data;
      if (!encoded) throw new Error("missing audio");
      const bytes = fromBase64(encoded);
      if (!isWav(bytes)) throw new Error("invalid wav");
      this.failedUntil.delete(cooldownKey);
      return { status: "ok", bytes, mimeType: "audio/wav", voice, rate, model: MIMO_TTS_MODEL };
    } catch (error) {
      this.failedUntil.set(cooldownKey, now + this.cooldownMs);
      const reason = error instanceof SyntaxError || /audio|wav/i.test(error instanceof Error ? error.message : "")
        ? "invalid-response"
        : failureReason(error);
      return { status: "fallback", reason };
    }
  }
}
