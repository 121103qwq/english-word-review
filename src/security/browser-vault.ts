export const BROWSER_CREDENTIAL_STORAGE_KEY = "english-review:sync-credentials:v2";
export const LEGACY_BROWSER_VAULT_STORAGE_KEY = "english-review:encrypted-sync-credentials:v1";

type CredentialStorage = Pick<Storage, "getItem" | "setItem" | "removeItem">;

export class BrowserCredentialStore<T> {
  constructor(
    private readonly storage: CredentialStorage,
    private readonly storageKey = BROWSER_CREDENTIAL_STORAGE_KEY,
    private readonly legacyStorageKey = LEGACY_BROWSER_VAULT_STORAGE_KEY,
  ) {}

  hasStoredCredentials(): boolean {
    return this.storage.getItem(this.storageKey) !== null;
  }

  hasLegacyEncryptedCredentials(): boolean {
    return this.storage.getItem(this.legacyStorageKey) !== null;
  }

  save(credentials: T): void {
    this.storage.setItem(this.storageKey, JSON.stringify(credentials));
    this.storage.removeItem(this.legacyStorageKey);
  }

  load(): T | undefined {
    const serialized = this.storage.getItem(this.storageKey);
    if (!serialized) return undefined;
    try {
      const value = JSON.parse(serialized) as unknown;
      if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error();
      return value as T;
    } catch {
      throw new Error("HTML 本地凭据格式无效，请清除后重新保存");
    }
  }

  clear(): void {
    this.storage.removeItem(this.storageKey);
    this.storage.removeItem(this.legacyStorageKey);
  }
}
