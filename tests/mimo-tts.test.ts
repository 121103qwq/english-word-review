import { describe, expect, it, vi } from "vitest";
import {
  MIMO_TTS_ENDPOINT,
  MIMO_TTS_MODEL,
  MimoApiKeyResolver,
  MimoTtsClient,
} from "../src/audio/mimo-tts";
import type { MimoTtsHttpRequest } from "../src/audio/mimo-tts";
import type { HttpResponse } from "../src/platform/runtime";

const wav = Uint8Array.from([
  0x52, 0x49, 0x46, 0x46, 4, 0, 0, 0, 0x57, 0x41, 0x56, 0x45,
]);
const base64 = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes));

function localKey(initial = "") {
  let value = initial;
  return {
    storage: {
      load: vi.fn(async () => value),
      save: vi.fn(async (next: string) => { value = next; }),
    },
    read: () => value,
  };
}

describe("MiMo v2.5 TTS", () => {
  it("uses a local key without accessing the private repository", async () => {
    const local = localKey("local-secret");
    const remote = { readMimoApiKey: vi.fn(async () => "remote-secret") };
    const resolver = new MimoApiKeyResolver(local.storage, remote);

    expect(await resolver.resolve()).toBe("local-secret");
    expect(remote.readMimoApiKey).not.toHaveBeenCalled();
  });

  it("automatically obtains a missing local key once and stores it locally", async () => {
    const local = localKey();
    const remote = { readMimoApiKey: vi.fn(async () => "remote-secret") };
    const resolver = new MimoApiKeyResolver(local.storage, remote);

    expect(await resolver.resolve()).toBe("remote-secret");
    expect(local.storage.save).toHaveBeenCalledWith("remote-secret");
    expect(await resolver.resolve()).toBe("remote-secret");
    expect(remote.readMimoApiKey).toHaveBeenCalledTimes(1);
  });

  it("calls the official chat/completions API and returns WAV audio", async () => {
    const request = vi.fn(async (_request: MimoTtsHttpRequest): Promise<HttpResponse> => ({
      status: 200,
      headers: {},
      body: JSON.stringify({ choices: [{ message: { audio: { data: base64(wav) } } }] }),
    }));
    const local = localKey("secret");
    const client = new MimoTtsClient(new MimoApiKeyResolver(local.storage), { request });

    const result = await client.synthesize("apple", { voice: "Chloe", rate: 1.4 });

    expect(result.status).toBe("ok");
    if (result.status === "ok") expect(result.bytes).toEqual(wav);
    const sent = request.mock.calls[0][0];
    expect(sent.url).toBe(MIMO_TTS_ENDPOINT);
    expect(sent.method).toBe("POST");
    expect(sent.timeoutMs).toBe(6_000);
    expect(sent.headers["api-key"]).toBe("secret");
    const body = JSON.parse(sent.body);
    expect(body.model).toBe(MIMO_TTS_MODEL);
    expect(body.audio).toEqual({ format: "wav", voice: "Chloe" });
    expect(body.messages.at(-1)).toEqual({ role: "assistant", content: "apple" });
    expect(body.messages[0].content).toContain("1.40");
  });

  it("signals immediate system fallback when no key is available", async () => {
    const request = vi.fn();
    const local = localKey();
    const client = new MimoTtsClient(new MimoApiKeyResolver(local.storage), { request });

    expect(await client.synthesize("apple")).toEqual({ status: "fallback", reason: "missing-key" });
    expect(request).not.toHaveBeenCalled();
  });

  it("suppresses repeated requests for the same word for one minute after a failure", async () => {
    let now = 1_000;
    const request = vi.fn(async (_request: MimoTtsHttpRequest): Promise<HttpResponse> => ({ status: 503, headers: {}, body: "" }));
    const local = localKey("secret");
    const client = new MimoTtsClient(new MimoApiKeyResolver(local.storage), { request, now: () => now });

    expect(await client.synthesize("Apple")).toMatchObject({ status: "fallback", reason: "http-error" });
    expect(await client.synthesize("apple")).toMatchObject({ status: "fallback", reason: "cooldown" });
    expect(request).toHaveBeenCalledTimes(1);
    now += 60_001;
    await client.synthesize("apple");
    expect(request).toHaveBeenCalledTimes(2);
  });

  it("converts timeout errors into a non-blocking fallback signal", async () => {
    const request = vi.fn(async (_request: MimoTtsHttpRequest): Promise<HttpResponse> => { throw new DOMException("aborted", "AbortError"); });
    const local = localKey("secret");
    const client = new MimoTtsClient(new MimoApiKeyResolver(local.storage), { request });
    expect(await client.synthesize("apple")).toEqual({ status: "fallback", reason: "timeout" });
  });
});
