import { describe, expect, it } from "vitest";
import {
  BrowserCredentialVault,
  clearBrowserCredentialEnvelope,
  sealBrowserCredentials,
  unlockBrowserCredentials,
} from "../src/security/browser-vault";

class MemoryStorage implements Pick<Storage, "getItem" | "setItem" | "removeItem"> {
  private values = new Map<string, string>();
  getItem(key: string): string | null { return this.values.get(key) ?? null; }
  setItem(key: string, value: string): void { this.values.set(key, value); }
  removeItem(key: string): void { this.values.delete(key); }
}

const credentials = {
  github: { token: "github-secret-token", owner: "owner", repo: "private-data" },
  webdav: [
    { id: "primary", url: "https://dav.example/a", username: "one", password: "dav-secret-one" },
    { id: "backup", url: "https://dav.example/b", username: "two", password: "dav-secret-two" },
  ],
};

describe("browser credential vault", () => {
  it("round-trips multiple mirror credentials with PBKDF2-SHA256 and AES-256-GCM", async () => {
    const envelope = await sealBrowserCredentials(credentials, "correct horse battery staple", { iterations: 100_000 });
    const serialized = JSON.stringify(envelope);
    expect(envelope.kdf.name).toBe("PBKDF2-SHA256");
    expect(envelope.cipher.name).toBe("AES-256-GCM");
    expect(serialized).not.toContain("github-secret-token");
    expect(serialized).not.toContain("dav-secret-one");
    await expect(unlockBrowserCredentials(serialized, "correct horse battery staple")).resolves.toEqual(credentials);
  });

  it("rejects a wrong password and authenticated-ciphertext tampering", async () => {
    const envelope = await sealBrowserCredentials(credentials, "right-password", { iterations: 100_000 });
    await expect(unlockBrowserCredentials(envelope, "wrong-password")).rejects.toThrow("主密码错误");
    const tampered = { ...envelope, ciphertext: `${envelope.ciphertext.slice(0, -2)}AA` };
    await expect(unlockBrowserCredentials(tampered, "right-password")).rejects.toThrow("主密码错误");
  });

  it("stores only the envelope and clearing credentials leaves unrelated local data intact", async () => {
    const storage = new MemoryStorage();
    storage.setItem("content", "keep-me");
    const vault = new BrowserCredentialVault<typeof credentials>(storage, "vault");
    await vault.save(credentials, "master-password");
    expect(vault.hasStoredCredentials()).toBe(true);
    await expect(vault.unlock("master-password")).resolves.toEqual(credentials);
    vault.clear();
    expect(vault.hasStoredCredentials()).toBe(false);
    expect(storage.getItem("content")).toBe("keep-me");

    storage.setItem("vault", "ciphertext");
    clearBrowserCredentialEnvelope(storage, "vault");
    expect(storage.getItem("vault")).toBeNull();
    expect(storage.getItem("content")).toBe("keep-me");
  });
});

