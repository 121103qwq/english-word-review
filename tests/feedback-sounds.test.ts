import { describe, expect, it, vi } from "vitest";
import {
  createCustomFeedbackSound,
  FeedbackSoundService,
  getBuiltInFeedbackSound,
  MAX_FEEDBACK_SOUND_BYTES,
} from "../src/audio/feedback-sounds";
import type { FeedbackSoundPlayer } from "../src/audio/feedback-sounds";

describe("feedback sounds", () => {
  it("provides distinct local sounds for correct and wrong answers", () => {
    const correct = getBuiltInFeedbackSound("crisp", "correct");
    const wrong = getBuiltInFeedbackSound("crisp", "wrong");
    expect(correct?.mimeType).toBe("audio/wav");
    expect(correct?.bytes.subarray(0, 4)).toEqual(Uint8Array.from([82, 73, 70, 70]));
    expect(correct?.bytes).not.toEqual(wrong?.bytes);
    expect(getBuiltInFeedbackSound("silent", "correct")).toBeUndefined();
  });

  it("requires a matching header and successful decode for custom sounds", async () => {
    const bytes = Uint8Array.from([0x49, 0x44, 0x33, 1, 2, 3]);
    const decoder = vi.fn(async () => true);
    const sound = await createCustomFeedbackSound(bytes, "answer.mp3", "audio/mpeg", decoder);

    expect(sound.byteLength).toBe(bytes.byteLength);
    expect(sound.sha256).toMatch(/^[a-f\d]{64}$/);
    expect(decoder).toHaveBeenCalled();
    await expect(createCustomFeedbackSound(bytes, "wrong.ogg", "audio/ogg", decoder)).rejects.toThrow(/格式/);
    await expect(createCustomFeedbackSound(bytes, "bad.mp3", "audio/mpeg", async () => false)).rejects.toThrow(/无法解码/);
  });

  it("rejects a custom sound larger than 2 MiB", async () => {
    const bytes = new Uint8Array(MAX_FEEDBACK_SOUND_BYTES + 1);
    bytes.set([0x49, 0x44, 0x33]);
    await expect(createCustomFeedbackSound(bytes, "large.mp3", "audio/mpeg", async () => true)).rejects.toThrow(/2 MiB/);
  });

  it("defaults to silent and plays selected themes at 60 percent volume", async () => {
    const player = vi.fn<FeedbackSoundPlayer>(async () => undefined);
    const service = new FeedbackSoundService({ player });
    expect(await service.play("correct")).toBe(false);
    expect(player).not.toHaveBeenCalled();

    service.configure({ theme: "soft" });
    expect(await service.play("wrong")).toBe(true);
    expect(player.mock.calls[0][1]).toBe(0.6);
  });

  it("falls back to a built-in sound when a custom attachment is unavailable", async () => {
    const player = vi.fn<FeedbackSoundPlayer>(async () => undefined);
    const service = new FeedbackSoundService({ theme: "custom", player });
    expect(await service.play("correct")).toBe(true);
    expect(player.mock.calls[0][0].mimeType).toBe("audio/wav");
  });
});
