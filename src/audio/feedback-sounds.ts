export const MAX_FEEDBACK_SOUND_BYTES = 2 * 1024 * 1024;

export type FeedbackResult = "correct" | "wrong";
export type FeedbackSoundTheme = "silent" | "crisp" | "soft" | "minimal" | "custom";
export type FeedbackSoundMimeType = "audio/mpeg" | "audio/wav" | "audio/ogg";

export const FEEDBACK_SOUND_THEMES: ReadonlyArray<{
  id: FeedbackSoundTheme;
  label: string;
}> = [
  { id: "silent", label: "静音" },
  { id: "crisp", label: "清脆" },
  { id: "soft", label: "柔和" },
  { id: "minimal", label: "极简提示音" },
  { id: "custom", label: "自定义" },
];

export interface FeedbackSoundAsset {
  sha256: string;
  mimeType: FeedbackSoundMimeType;
  byteLength: number;
  fileName: string;
  bytes: Uint8Array;
}

export type FeedbackSoundDecoder = (bytes: Uint8Array, mimeType: FeedbackSoundMimeType) => Promise<boolean>;

function toHex(bytes: Uint8Array): string {
  return [...bytes].map((part) => part.toString(16).padStart(2, "0")).join("");
}

function signatureMatches(bytes: Uint8Array, mimeType: FeedbackSoundMimeType): boolean {
  if (mimeType === "audio/wav") {
    return bytes.length >= 12 &&
      String.fromCharCode(...bytes.subarray(0, 4)) === "RIFF" &&
      String.fromCharCode(...bytes.subarray(8, 12)) === "WAVE";
  }
  if (mimeType === "audio/ogg") return bytes.length >= 4 && String.fromCharCode(...bytes.subarray(0, 4)) === "OggS";
  return bytes.length >= 3 && (
    String.fromCharCode(...bytes.subarray(0, 3)) === "ID3" ||
    (bytes[0] === 0xff && (bytes[1] & 0xe0) === 0xe0)
  );
}

async function browserDecode(bytes: Uint8Array): Promise<boolean> {
  const AudioContextClass = globalThis.AudioContext ??
    (globalThis as typeof globalThis & { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
  if (!AudioContextClass) throw new Error("当前环境无法验证音效是否可解码");
  const context = new AudioContextClass();
  try {
    await context.decodeAudioData(bytes.slice().buffer);
    return true;
  } catch {
    return false;
  } finally {
    await context.close();
  }
}

export async function createCustomFeedbackSound(
  bytes: Uint8Array,
  fileName: string,
  mimeType: FeedbackSoundMimeType,
  decoder: FeedbackSoundDecoder = browserDecode,
): Promise<FeedbackSoundAsset> {
  if (!bytes.byteLength) throw new Error("音效文件不能为空");
  if (bytes.byteLength > MAX_FEEDBACK_SOUND_BYTES) throw new Error("单个音效不能超过 2 MiB");
  if (!signatureMatches(bytes, mimeType)) throw new Error("音效文件格式与内容不匹配");
  if (!(await decoder(bytes, mimeType))) throw new Error("音效文件无法解码");
  const digest = await globalThis.crypto.subtle.digest("SHA-256", bytes as BufferSource);
  const sha256 = toHex(new Uint8Array(digest));
  return { sha256, mimeType, byteLength: bytes.byteLength, fileName, bytes: new Uint8Array(bytes) };
}

function writeAscii(view: DataView, offset: number, value: string): void {
  for (let index = 0; index < value.length; index += 1) view.setUint8(offset + index, value.charCodeAt(index));
}

/** Generates tiny deterministic local WAV prompts; no network fetch is needed. */
function generateTone(frequencies: number[], durationMs: number, volume: number): Uint8Array {
  const sampleRate = 12_000;
  const samples = Math.max(1, Math.floor(sampleRate * durationMs / 1_000));
  const dataLength = samples * 2;
  const buffer = new ArrayBuffer(44 + dataLength);
  const view = new DataView(buffer);
  writeAscii(view, 0, "RIFF");
  view.setUint32(4, 36 + dataLength, true);
  writeAscii(view, 8, "WAVE");
  writeAscii(view, 12, "fmt ");
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  writeAscii(view, 36, "data");
  view.setUint32(40, dataLength, true);
  for (let index = 0; index < samples; index += 1) {
    const progress = index / samples;
    const frequency = frequencies[Math.min(frequencies.length - 1, Math.floor(progress * frequencies.length))];
    const envelope = Math.min(1, index / 80) * Math.min(1, (samples - index) / 160);
    const sample = Math.sin(2 * Math.PI * frequency * index / sampleRate) * volume * envelope;
    view.setInt16(44 + index * 2, Math.round(sample * 0x7fff), true);
  }
  return new Uint8Array(buffer);
}

const BUILT_IN: Record<Exclude<FeedbackSoundTheme, "silent" | "custom">, Record<FeedbackResult, Uint8Array>> = {
  crisp: {
    correct: generateTone([660, 880], 160, 0.28),
    wrong: generateTone([280, 190], 190, 0.24),
  },
  soft: {
    correct: generateTone([440, 554], 240, 0.14),
    wrong: generateTone([330, 247], 260, 0.12),
  },
  minimal: {
    correct: generateTone([720], 85, 0.20),
    wrong: generateTone([230], 100, 0.18),
  },
};

export interface PlayableFeedbackSound {
  bytes: Uint8Array;
  mimeType: FeedbackSoundMimeType;
}

export function getBuiltInFeedbackSound(
  theme: FeedbackSoundTheme,
  result: FeedbackResult,
): PlayableFeedbackSound | undefined {
  if (theme === "silent" || theme === "custom") return undefined;
  return { bytes: new Uint8Array(BUILT_IN[theme][result]), mimeType: "audio/wav" };
}

export type FeedbackSoundPlayer = (sound: PlayableFeedbackSound, volume: number) => Promise<void>;

async function browserPlayer(sound: PlayableFeedbackSound, volume: number): Promise<void> {
  const url = URL.createObjectURL(new Blob([sound.bytes as BlobPart], { type: sound.mimeType }));
  const audio = new Audio(url);
  let revoked = false;
  const cleanup = () => {
    if (revoked) return;
    revoked = true;
    URL.revokeObjectURL(url);
  };
  audio.addEventListener("ended", cleanup, { once: true });
  audio.addEventListener("error", cleanup, { once: true });
  globalThis.setTimeout(cleanup, 10_000);
  audio.volume = Math.max(0, Math.min(1, volume));
  try {
    await audio.play();
  } catch (error) {
    cleanup();
    throw error;
  }
}

export interface FeedbackSoundServiceOptions {
  theme?: FeedbackSoundTheme;
  volume?: number;
  custom?: Partial<Record<FeedbackResult, FeedbackSoundAsset>>;
  customFallbackTheme?: Exclude<FeedbackSoundTheme, "silent" | "custom">;
  player?: FeedbackSoundPlayer;
}

export class FeedbackSoundService {
  private theme: FeedbackSoundTheme;
  private volume: number;
  private custom: Partial<Record<FeedbackResult, FeedbackSoundAsset>>;
  private customFallbackTheme: Exclude<FeedbackSoundTheme, "silent" | "custom">;
  private readonly player: FeedbackSoundPlayer;

  constructor(options: FeedbackSoundServiceOptions = {}) {
    this.theme = options.theme ?? "silent";
    this.volume = Math.max(0, Math.min(1, options.volume ?? 0.6));
    this.custom = { ...options.custom };
    this.customFallbackTheme = options.customFallbackTheme ?? "minimal";
    this.player = options.player ?? browserPlayer;
  }

  configure(options: Omit<FeedbackSoundServiceOptions, "player">): void {
    if (options.theme) this.theme = options.theme;
    if (options.volume !== undefined) this.volume = Math.max(0, Math.min(1, options.volume));
    if (options.custom) this.custom = { ...options.custom };
    if (options.customFallbackTheme) this.customFallbackTheme = options.customFallbackTheme;
  }

  async play(result: FeedbackResult): Promise<boolean> {
    if (this.theme === "silent") return false;
    const custom = this.theme === "custom" ? this.custom[result] : undefined;
    const sound = custom
      ? { bytes: custom.bytes, mimeType: custom.mimeType }
      : getBuiltInFeedbackSound(this.theme === "custom" ? this.customFallbackTheme : this.theme, result);
    if (!sound) return false;
    await this.player(sound, this.volume);
    return true;
  }
}
