export const NATIVE_CREDENTIAL_PASSWORD_KEY = "sync-credential-password-verifier-v1";
export const NATIVE_PASSWORD_PBKDF2_ITERATIONS = 310_000;

export interface NativePasswordVerifier {
  version: 1;
  kdf: "PBKDF2-SHA256";
  iterations: number;
  salt: string;
  hash: string;
}

interface NativePasswordOptions {
  crypto?: Crypto;
  iterations?: number;
}

function passwordCrypto(override?: Crypto): Crypto {
  const implementation = override ?? globalThis.crypto;
  if (!implementation?.subtle || !implementation.getRandomValues) {
    throw new Error("当前环境不支持原生凭据密码验证");
  }
  return implementation;
}

function toBase64(value: Uint8Array): string {
  let binary = "";
  for (let offset = 0; offset < value.length; offset += 0x8000) {
    binary += String.fromCharCode(...value.subarray(offset, offset + 0x8000));
  }
  return btoa(binary);
}

function fromBase64(value: string): Uint8Array {
  try {
    return Uint8Array.from(atob(value), (character) => character.charCodeAt(0));
  } catch {
    throw new Error("原生凭据密码记录无效");
  }
}

function validateVerifier(value: unknown): asserts value is NativePasswordVerifier {
  const verifier = value as Partial<NativePasswordVerifier> | null;
  if (
    !verifier ||
    verifier.version !== 1 ||
    verifier.kdf !== "PBKDF2-SHA256" ||
    !Number.isSafeInteger(verifier.iterations) ||
    Number(verifier.iterations) < 100_000 ||
    Number(verifier.iterations) > 5_000_000 ||
    typeof verifier.salt !== "string" ||
    typeof verifier.hash !== "string"
  ) {
    throw new Error("原生凭据密码记录无效");
  }
}

export function parseNativePasswordVerifier(value: NativePasswordVerifier | string): NativePasswordVerifier {
  let parsed: unknown = value;
  try {
    if (typeof value === "string") parsed = JSON.parse(value);
  } catch {
    throw new Error("原生凭据密码记录无效");
  }
  validateVerifier(parsed);
  return parsed;
}

async function derivePasswordHash(
  crypto: Crypto,
  password: string,
  salt: Uint8Array,
  iterations: number,
): Promise<Uint8Array> {
  if (!password) throw new Error("凭据密码不能为空");
  const material = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(password) as BufferSource,
    "PBKDF2",
    false,
    ["deriveBits"],
  );
  const bits = await crypto.subtle.deriveBits(
    { name: "PBKDF2", hash: "SHA-256", salt: salt as BufferSource, iterations },
    material,
    256,
  );
  return new Uint8Array(bits);
}

export async function createNativePasswordVerifier(
  password: string,
  options: NativePasswordOptions = {},
): Promise<NativePasswordVerifier> {
  const crypto = passwordCrypto(options.crypto);
  const iterations = options.iterations ?? NATIVE_PASSWORD_PBKDF2_ITERATIONS;
  if (!Number.isSafeInteger(iterations) || iterations < 100_000 || iterations > 5_000_000) {
    throw new Error("原生凭据密码参数无效");
  }
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const hash = await derivePasswordHash(crypto, password, salt, iterations);
  return {
    version: 1,
    kdf: "PBKDF2-SHA256",
    iterations,
    salt: toBase64(salt),
    hash: toBase64(hash),
  };
}

export async function verifyNativePassword(
  verifierValue: NativePasswordVerifier | string,
  password: string,
  options: Pick<NativePasswordOptions, "crypto"> = {},
): Promise<boolean> {
  const verifier = parseNativePasswordVerifier(verifierValue);
  const salt = fromBase64(verifier.salt);
  const expected = fromBase64(verifier.hash);
  if (salt.length !== 16 || expected.length !== 32) throw new Error("原生凭据密码记录无效");
  const actual = await derivePasswordHash(passwordCrypto(options.crypto), password, salt, verifier.iterations);
  if (actual.length !== expected.length) return false;
  let difference = 0;
  for (let index = 0; index < actual.length; index += 1) difference |= actual[index] ^ expected[index];
  return difference === 0;
}
