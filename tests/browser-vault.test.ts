import { describe, expect, it } from "vitest";
import {
  BROWSER_CREDENTIAL_STORAGE_KEY,
  BrowserCredentialStore,
  LEGACY_BROWSER_VAULT_STORAGE_KEY,
} from "../src/security/browser-vault";

class MemoryStorage implements Pick<Storage, "getItem" | "setItem" | "removeItem"> {
  private values = new Map<string, string>();
  getItem(key: string): string | null { return this.values.get(key) ?? null; }
  setItem(key: string, value: string): void { this.values.set(key, value); }
  removeItem(key: string): void { this.values.delete(key); }
}

const credentials = {
  githubToken: "github-secret-token",
  webdavPasswords: {
    primary: "dav-secret-one",
    backup: "dav-secret-two",
  },
};

describe("browser credential storage", () => {
  it("stores HTML credentials without encryption and loads them directly", () => {
    const storage = new MemoryStorage();
    const store = new BrowserCredentialStore<typeof credentials>(storage);

    store.save(credentials);

    expect(store.hasStoredCredentials()).toBe(true);
    expect(storage.getItem(BROWSER_CREDENTIAL_STORAGE_KEY)).toContain("github-secret-token");
    expect(store.load()).toEqual(credentials);
  });

  it("keeps an old encrypted envelope separate until credentials are saved again", () => {
    const storage = new MemoryStorage();
    storage.setItem(LEGACY_BROWSER_VAULT_STORAGE_KEY, "legacy-ciphertext");
    const store = new BrowserCredentialStore<typeof credentials>(storage);

    expect(store.load()).toBeUndefined();
    expect(store.hasLegacyEncryptedCredentials()).toBe(true);
    store.save(credentials);
    expect(store.hasLegacyEncryptedCredentials()).toBe(false);
  });

  it("clears current and legacy credentials without touching unrelated local data", () => {
    const storage = new MemoryStorage();
    storage.setItem("content", "keep-me");
    storage.setItem(LEGACY_BROWSER_VAULT_STORAGE_KEY, "legacy-ciphertext");
    const store = new BrowserCredentialStore<typeof credentials>(storage);
    store.save(credentials);
    storage.setItem(LEGACY_BROWSER_VAULT_STORAGE_KEY, "legacy-ciphertext");

    store.clear();

    expect(store.hasStoredCredentials()).toBe(false);
    expect(store.hasLegacyEncryptedCredentials()).toBe(false);
    expect(storage.getItem("content")).toBe("keep-me");
  });

  it("rejects malformed local credential data", () => {
    const storage = new MemoryStorage();
    storage.setItem(BROWSER_CREDENTIAL_STORAGE_KEY, "not-json");
    const store = new BrowserCredentialStore<typeof credentials>(storage);

    expect(() => store.load()).toThrow("HTML 本地凭据格式无效");
  });
});
