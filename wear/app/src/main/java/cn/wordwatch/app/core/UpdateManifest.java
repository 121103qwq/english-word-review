package cn.wordwatch.app.core;

import org.json.JSONObject;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** A deliberately small, versioned release contract. APK signing remains the authority. */
public final class UpdateManifest {
    public static final String REPOSITORY = "121103qwq/english-word-review";
    public static final String PACKAGE = "cn.wordwatch.app";
    public static final long MAX_APK_BYTES = 32L * 1024 * 1024;
    public final long versionCode, assetId, size;
    public final int minSdk;
    public final String versionName, sha256, notes;
    public final JSONObject json;

    public UpdateManifest(JSONObject value) throws Exception {
        if (number(value, "schemaVersion") != 1 || !"stable".equals(value.getString("channel"))
                || !PACKAGE.equals(value.getString("packageName"))) throw new IllegalArgumentException("unsupported update contract");
        versionCode = number(value, "versionCode"); assetId = number(value, "apkAssetId"); size = number(value, "apkSize");
        long sdk = number(value, "minSdk");
        if (versionCode < 1 || assetId < 1 || size < 1 || size > MAX_APK_BYTES || sdk < 26 || sdk > 100) throw new IllegalArgumentException("invalid update bounds");
        minSdk = (int) sdk; versionName = value.getString("versionName"); sha256 = value.getString("apkSha256").toLowerCase(Locale.ROOT);
        notes = value.optString("notes", "");
        if (versionName.trim().isEmpty() || versionName.length() > 80 || !sha256.matches("[a-f0-9]{64}") || notes.length() > 4000) throw new IllegalArgumentException("invalid update metadata");
        json = new JSONObject(value.toString());
    }
    private static long number(JSONObject json, String key) throws Exception {
        Object n = json.get(key);
        if (!(n instanceof Integer || n instanceof Long)) throw new IllegalArgumentException("update integer required");
        return ((Number) n).longValue();
    }
    public boolean isNewer(long installedCode) { return versionCode > installedCode; }
    public String assetUrl() { return "https://api.github.com/repos/" + REPOSITORY + "/releases/assets/" + assetId; }
    public static void validateDownloadUri(URI uri) {
        Set<String> hosts = new java.util.HashSet<>(java.util.Arrays.asList("api.github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com"));
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443) || !hosts.contains(uri.getHost()))
            throw new IllegalArgumentException("untrusted download destination");
    }
}
