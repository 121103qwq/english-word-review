import { afterEach, describe, expect, it, vi } from "vitest";
import {
  createTtsCacheKey,
  DEFAULT_TTS_CACHE_BYTES,
  IndexedDbTtsCacheBackend,
  MAX_TTS_CACHE_BYTES,
  MemoryTtsCacheBackend,
  TtsCache,
} from "../src/audio/tts-cache";

function createPendingOpenRequest(): IDBOpenDBRequest {
  return {
    error: null,
    onblocked: null,
    onerror: null,
    onsuccess: null,
    onupgradeneeded: null,
    result: undefined,
    transaction: null,
  } as unknown as IDBOpenDBRequest;
}

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("MiMo TTS cache", () => {
  it("uses text, voice, rate, and model in a deterministic cache key", async () => {
    const base = { text: "apple", voice: "Mia", rate: 1, model: "mimo-v2.5-tts" };
    expect(await createTtsCacheKey(base)).toBe(await createTtsCacheKey({ ...base }));
    expect(await createTtsCacheKey(base)).not.toBe(await createTtsCacheKey({ ...base, voice: "Dean" }));
    expect(await createTtsCacheKey(base)).not.toBe(await createTtsCacheKey({ ...base, rate: 1.25 }));
  });

  it("defaults to 512 MiB and never permits a limit above 3 GiB", async () => {
    const cache = new TtsCache(new MemoryTtsCacheBackend());
    expect(cache.getMaxBytes()).toBe(DEFAULT_TTS_CACHE_BYTES);
    await cache.setMaxBytes(Number.MAX_SAFE_INTEGER);
    expect(cache.getMaxBytes()).toBe(MAX_TTS_CACHE_BYTES);
  });

  it("evicts the least recently used entries and refreshes access time", async () => {
    let time = 0;
    const backend = new MemoryTtsCacheBackend();
    const cache = new TtsCache(backend, {
      maxBytes: 6,
      now: () => new Date(time++ * 1_000),
    });
    await cache.put("old", new Uint8Array([1, 2, 3]));
    await cache.put("kept", new Uint8Array([4, 5, 6]));
    await cache.get("old");
    await cache.put("new", new Uint8Array([7, 8, 9]));

    expect(await backend.get("kept")).toBeUndefined();
    expect(await backend.get("old")).toBeDefined();
    expect(await backend.get("new")).toBeDefined();
  });

  it("does not cache a single result larger than the selected limit", async () => {
    const cache = new TtsCache(new MemoryTtsCacheBackend(), { maxBytes: 2 });
    expect(await cache.put("large", new Uint8Array([1, 2, 3]))).toBe(false);
    expect((await cache.stats()).entries).toBe(0);
  });

  it("evicts old TTS entries before writing when device storage is nearly full", async () => {
    const backend = new MemoryTtsCacheBackend();
    const cache = new TtsCache(backend, {
      maxBytes: 20,
      availableStorageBytes: async () => 1,
    });
    await backend.put({
      key: "old",
      bytes: new Uint8Array(5),
      mimeType: "audio/wav",
      byteLength: 5,
      createdAt: new Date(0).toISOString(),
      lastAccessedAt: new Date(0).toISOString(),
    });

    expect(await cache.put("new", new Uint8Array(4))).toBe(true);
    expect(await backend.get("old")).toBeUndefined();
    expect(await backend.get("new")).toBeDefined();
  });

  it("clears only its dedicated cache backend", async () => {
    const cache = new TtsCache(new MemoryTtsCacheBackend(), { maxBytes: 10 });
    await cache.put("a", new Uint8Array([1]));
    await cache.put("b", new Uint8Array([2]));
    await cache.clear();
    expect(await cache.stats()).toMatchObject({ entries: 0, totalBytes: 0 });
  });

  it("fails instead of remaining pending when opening IndexedDB is blocked", async () => {
    const request = createPendingOpenRequest();
    vi.stubGlobal("indexedDB", { open: vi.fn(() => request) });
    const backend = new IndexedDbTtsCacheBackend("blocked-tts-cache");
    const listing = backend.list();

    request.onblocked?.call(request, new Event("blocked") as IDBVersionChangeEvent);

    await expect(listing).rejects.toThrow("被其他页面阻止");
  });

  it("times out instead of remaining pending when IndexedDB never responds", async () => {
    vi.useFakeTimers();
    const request = createPendingOpenRequest();
    vi.stubGlobal("indexedDB", { open: vi.fn(() => request) });
    const backend = new IndexedDbTtsCacheBackend("unresponsive-tts-cache");
    const listing = backend.list();
    const rejection = expect(listing).rejects.toThrow("打开超时");

    await vi.advanceTimersByTimeAsync(10_000);

    await rejection;
  });
});
