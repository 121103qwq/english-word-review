import { httpRequest, type HttpRequest, type HttpResponse, type RuntimePlatform } from "./runtime";

export const RELEASES_LATEST_API = "https://api.github.com/repos/121103qwq/english-word-review/releases/latest";

interface GitHubReleaseAsset {
  name?: unknown;
  browser_download_url?: unknown;
}

interface GitHubReleaseResponse {
  tag_name?: unknown;
  name?: unknown;
  html_url?: unknown;
  assets?: unknown;
}

export interface AvailableUpdate {
  version: string;
  title: string;
  releaseUrl: string;
  downloadUrl: string;
  assetName?: string;
}

type RequestRelease = (request: HttpRequest) => Promise<HttpResponse>;

export function parseVersion(value: string): [number, number, number] | null {
  const match = /^v?(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$/u.exec(value.trim());
  return match ? [Number(match[1]), Number(match[2]), Number(match[3])] : null;
}

export function compareReleaseVersions(left: string, right: string): number {
  const a = parseVersion(left);
  const b = parseVersion(right);
  if (!a || !b) return 0;
  for (let index = 0; index < 3; index += 1) {
    if (a[index] !== b[index]) return a[index] - b[index];
  }
  return 0;
}

function preferredAssetSuffix(platform: RuntimePlatform): string {
  if (platform === "windows") return "-setup.exe";
  if (platform === "android") return "-android8-plus.apk";
  return ".html";
}

export function parseAvailableUpdate(
  payload: unknown,
  currentVersion: string,
  platform: RuntimePlatform,
): AvailableUpdate | null {
  if (!payload || typeof payload !== "object") return null;
  const release = payload as GitHubReleaseResponse;
  if (typeof release.tag_name !== "string" || typeof release.html_url !== "string") return null;
  const parsed = parseVersion(release.tag_name);
  if (!parsed || compareReleaseVersions(release.tag_name, currentVersion) <= 0) return null;
  const version = parsed.join(".");
  const suffix = preferredAssetSuffix(platform);
  const assets = Array.isArray(release.assets) ? release.assets as GitHubReleaseAsset[] : [];
  const asset = assets.find((candidate) =>
    typeof candidate.name === "string" &&
    candidate.name.endsWith(suffix) &&
    typeof candidate.browser_download_url === "string" &&
    candidate.browser_download_url.startsWith("https://"));
  return {
    version,
    title: typeof release.name === "string" && release.name.trim() ? release.name.trim() : `英语单词速记 v${version}`,
    releaseUrl: release.html_url,
    downloadUrl: typeof asset?.browser_download_url === "string" ? asset.browser_download_url : release.html_url,
    ...(typeof asset?.name === "string" ? { assetName: asset.name } : {}),
  };
}

export class GitHubReleaseUpdateChecker {
  private inFlight?: Promise<AvailableUpdate | null>;
  private cached?: { checkedAt: number; result: AvailableUpdate | null };

  constructor(
    private readonly currentVersion: string,
    private readonly platform: RuntimePlatform,
    private readonly request: RequestRelease = httpRequest,
    private readonly now: () => number = Date.now,
    private readonly cacheMs = 5 * 60 * 1000,
  ) {}

  check(): Promise<AvailableUpdate | null> {
    if (this.cached && this.now() - this.cached.checkedAt < this.cacheMs) return Promise.resolve(this.cached.result);
    if (this.inFlight) return this.inFlight;
    this.inFlight = this.fetch().then((result) => {
      this.cached = { checkedAt: this.now(), result };
      return result;
    }).finally(() => { this.inFlight = undefined; });
    return this.inFlight;
  }

  private async fetch(): Promise<AvailableUpdate | null> {
    const response = await this.request({
      url: RELEASES_LATEST_API,
      method: "GET",
      headers: { Accept: "application/vnd.github+json" },
      timeoutMs: 6_000,
    });
    if (response.status !== 200) throw new Error(`GitHub Release API 返回 ${response.status}`);
    return parseAvailableUpdate(JSON.parse(response.body) as unknown, this.currentVersion, this.platform);
  }
}
