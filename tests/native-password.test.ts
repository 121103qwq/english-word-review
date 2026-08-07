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

  it("shows setup confirmation before the first native unlock and password-only input later", () => {
    const html = readFileSync(new URL("../index.html", import.meta.url), "utf8");
    const main = readFileSync(new URL("../src/main.ts", import.meta.url), "utf8");

    expect(html).toContain('id="nativeCredentialPasswordConfirm"');
    expect(html).toContain("首次设置凭据密码");
    expect(main).toContain('hasPassword ? "输入凭据密码" : "首次设置凭据密码"');
    expect(main).toContain("confirmLabel.hidden = hasPassword");
    expect(main).toContain("requireNativeCredentialsUnlocked()");
  });
});
