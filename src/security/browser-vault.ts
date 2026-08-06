export const BROWSER_VAULT_STORAGE_KEY = "english-review:encrypted-sync-credentials:v1";
export const BROWSER_VAULT_PBKDF2_ITERATIONS = 310_000;

export interface BrowserVaultEnvelope {
  version: 1;
  kdf: {
    name: "PBKDF2-SHA256";
    iterations: number;
    salt: string;
  };
  cipher: {
    name: "AES-256-GCM";
    iv: string;
  };
  ciphertext: string;
}

export interface BrowserVaultOptions {
  iterations?: number;
  crypto?: Crypto;
}

const AAD = new TextEncoder().encode("english-review-browser-vault:v1");

function browserCrypto(override?: Crypto): Crypto {
  const implementation = override ?? globalThis.crypto;
  if (!implementation?.subtle || !implementation.getRandomValues) {
    throw new Error("当前浏览器不支持安全凭据加密");
  }
  return implementation;
}

function toBase64(value: Uint8Array): string {
  const chunkSize = 0x8000;
  let binary = "";
  for (let offset = 0; offset < value.length; offset += chunkSize) {
    binary += String.fromCharCode(...value.subarray(offset, offset + chunkSize));
  }
  return btoa(binary);
}

function fromBase64(value: string): Uint8Array {
  try {
    const binary = atob(value);
    return Uint8Array.from(binary, (character) => character.charCodeAt(0));
  } catch {
    throw new Error("凭据密文格式无效");
  }
}

async function deriveKey(
  crypto: Crypto,
  password: string,
  salt: Uint8Array,
  iterations: number,
): Promise<CryptoKey> {
  if (!password) throw new Error("主密码不能为空");
  if (!Number.isSafeInteger(iterations) || iterations < 100_000 || iterations > 5_000_000) {
    throw new Error("凭据密文参数无效");
  }
  const material = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(password) as BufferSource,
    "PBKDF2",
    false,
    ["deriveKey"],
  );
  return crypto.subtle.deriveKey(
    { name: "PBKDF2", hash: "SHA-256", salt: salt as BufferSource, iterations },
    material,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"],
  );
}

export async function sealBrowserCredentials<T>(
  credentials: T,
  masterPassword: string,
  options: BrowserVaultOptions = {},
): Promise<BrowserVaultEnvelope> {
  const crypto = browserCrypto(options.crypto);
  const iterations = options.iterations ?? BROWSER_VAULT_PBKDF2_ITERATIONS;
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const key = await deriveKey(crypto, masterPassword, salt, iterations);
  const plaintext = new TextEncoder().encode(JSON.stringify(credentials));
  const ciphertext = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: iv as BufferSource, additionalData: AAD as BufferSource, tagLength: 128 },
    key,
    plaintext as BufferSource,
  );
  return {
    version: 1,
    kdf: { name: "PBKDF2-SHA256", iterations, salt: toBase64(salt) },
    cipher: { name: "AES-256-GCM", iv: toBase64(iv) },
    ciphertext: toBase64(new Uint8Array(ciphertext)),
  };
}

function validateEnvelope(value: unknown): asserts value is BrowserVaultEnvelope {
  const envelope = value as Partial<BrowserVaultEnvelope> | null;
  if (
    !envelope ||
    envelope.version !== 1 ||
    envelope.kdf?.name !== "PBKDF2-SHA256" ||
    envelope.cipher?.name !== "AES-256-GCM" ||
    typeof envelope.kdf.iterations !== "number" ||
    typeof envelope.kdf.salt !== "string" ||
    typeof envelope.cipher.iv !== "string" ||
    typeof envelope.ciphertext !== "string"
  ) {
    throw new Error("凭据密文格式无效");
  }
}

export async function unlockBrowserCredentials<T>(
  envelopeValue: BrowserVaultEnvelope | string,
  masterPassword: string,
  options: Pick<BrowserVaultOptions, "crypto"> = {},
): Promise<T> {
  let envelope: unknown = envelopeValue;
  try {
    if (typeof envelopeValue === "string") envelope = JSON.parse(envelopeValue);
  } catch {
    throw new Error("凭据密文格式无效");
  }
  validateEnvelope(envelope);
  const crypto = browserCrypto(options.crypto);
  const salt = fromBase64(envelope.kdf.salt);
  const iv = fromBase64(envelope.cipher.iv);
  const ciphertext = fromBase64(envelope.ciphertext);
  if (salt.length !== 16 || iv.length !== 12 || ciphertext.length < 17) throw new Error("凭据密文格式无效");
  try {
    const key = await deriveKey(crypto, masterPassword, salt, envelope.kdf.iterations);
    const plaintext = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: iv as BufferSource, additionalData: AAD as BufferSource, tagLength: 128 },
      key,
      ciphertext as BufferSource,
    );
    return JSON.parse(new TextDecoder().decode(plaintext)) as T;
  } catch {
    throw new Error("主密码错误或凭据密文已损坏");
  }
}

export class BrowserCredentialVault<T> {
  constructor(
    private readonly storage: Pick<Storage, "getItem" | "setItem" | "removeItem">,
    private readonly storageKey = BROWSER_VAULT_STORAGE_KEY,
    private readonly crypto?: Crypto,
  ) {}

  hasStoredCredentials(): boolean {
    return this.storage.getItem(this.storageKey) !== null;
  }

  async save(credentials: T, masterPassword: string): Promise<void> {
    const envelope = await sealBrowserCredentials(credentials, masterPassword, { crypto: this.crypto });
    this.storage.setItem(this.storageKey, JSON.stringify(envelope));
  }

  async unlock(masterPassword: string): Promise<T> {
    const serialized = this.storage.getItem(this.storageKey);
    if (!serialized) throw new Error("尚未保存同步凭据");
    return unlockBrowserCredentials<T>(serialized, masterPassword, { crypto: this.crypto });
  }

  clear(): void {
    this.storage.removeItem(this.storageKey);
  }
}

export function clearBrowserCredentialEnvelope(
  storage: Pick<Storage, "removeItem">,
  storageKey = BROWSER_VAULT_STORAGE_KEY,
): void {
  storage.removeItem(storageKey);
}
