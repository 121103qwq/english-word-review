import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import {
  createNativePasswordVerifier,
  parseNativePasswordVerifier,
  verifyNativePassword,
} from "../src/security/native-password";

describe("native credential password verifier", () => {
  it("requires a password and stores only a salted PBKDF2 verifier", async () => {
    await expect(createNativePasswordVerifier("", { iterations: 100_000 })).rejects.toThrow("不能为空");
    const verifier = await createNativePasswordVerifier("first-password", { iterations: 100_000 });
    const serialized = JSON.stringify(verifier);

    expect(verifier.kdf).toBe("PBKDF2-SHA256");
    expect(serialized).not.toContain("first-password");
    await expect(verifyNativePassword(verifier, "first-password")).resolves.toBe(true);
    await expect(verifyNativePassword(verifier, "wrong-password")).resolves.toBe(false);
  });

  it("accepts a serialized verifier and rejects damaged records", async () => {
    const verifier = await createNativePasswordVerifier("secret", { iterations: 100_000 });
    expect(parseNativePasswordVerifier(JSON.stringify(verifier))).toEqual(verifier);
    expect(() => parseNativePasswordVerifier("not-json")).toThrow("密码记录无效");
    await expect(verifyNativePassword({ ...verifier, salt: "bad" }, "secret")).rejects.toThrow("密码记录无效");
  });

  it("does not gate native secure storage behind an application password", () => {
    const html = readFileSync(new URL("../index.html", import.meta.url), "utf8");
    const main = readFileSync(new URL("../src/main.ts", import.meta.url), "utf8");
    const settingsController = readFileSync(new URL("../src/settings/controller.ts", import.meta.url), "utf8");

    expect(html).not.toContain('id="nativeCredentialPassword"');
    expect(html).not.toContain('id="nativeCredentialPasswordConfirm"');
    expect(main).not.toContain("requireNativeCredentialsUnlocked");
    expect(main).not.toContain("verifyNativePassword");
    expect(settingsController).not.toContain("canUseNativeSecrets");
  });
});
