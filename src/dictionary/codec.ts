function base64Bytes(value: string): Uint8Array {
  if (typeof atob === "function") {
    const binary = atob(value);
    return Uint8Array.from(binary, (character) => character.charCodeAt(0));
  }
  return Uint8Array.from(Buffer.from(value, "base64"));
}

export async function decodeGzipJson<T>(encoded: string): Promise<T> {
  const bytes = base64Bytes(encoded);
  if (typeof DecompressionStream !== "undefined") {
    const stream = new Blob([bytes as BlobPart])
      .stream()
      .pipeThrough(new DecompressionStream("gzip"));
    return JSON.parse(await new Response(stream).text()) as T;
  }
  return JSON.parse(new TextDecoder().decode(ungzip(bytes))) as T;
}
import { ungzip } from "pako";

