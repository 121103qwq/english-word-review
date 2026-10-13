package cn.wordwatch.app.core;

import org.json.JSONObject;
import org.junit.Test;
import java.net.URI;
import static org.junit.Assert.*;

public class UpdateManifestTest {
    private JSONObject valid() throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("channel", "stable").put("packageName", "cn.wordwatch.app")
            .put("versionCode", 4).put("versionName", "1.2.0").put("apkAssetId", 123).put("apkSize", 100000)
            .put("apkSha256", "a".repeat(64)).put("minSdk", 26);
    }
    @Test public void assetAddressCannotBeChosenByRemoteManifest() throws Exception {
        UpdateManifest m = new UpdateManifest(valid().put("url", "http://example.test/evil.apk"));
        assertEquals("https://api.github.com/repos/121103qwq/english-word-review/releases/assets/123", m.assetUrl());
        assertTrue(m.isNewer(3)); assertFalse(m.isNewer(4)); assertFalse(m.isNewer(5));
    }
    @Test public void rejectMismatchedPackageAndMalformedBounds() throws Exception {
        for (JSONObject invalid : new JSONObject[]{valid().put("packageName", "another.app"), valid().put("schemaVersion", 2), valid().put("apkSize", UpdateManifest.MAX_APK_BYTES + 1), valid().put("versionCode", true), valid().put("apkSha256", "invalid"), valid().put("apkAssetId", -1)}) {
            assertThrows(Exception.class, () -> new UpdateManifest(invalid));
        }
    }
    @Test public void redirectPolicyRejectsCredentialsHttpPortsAndUntrustedHosts() {
        for (String bad : new String[]{"http://api.github.com/file", "https://user:secret@api.github.com/file", "https://api.github.com.evil.test/file", "https://release-assets.githubusercontent.com:444/file", "https://example.test/file", "https://127.0.0.1/file"})
            assertThrows(IllegalArgumentException.class, () -> UpdateManifest.validateDownloadUri(URI.create(bad)));
        UpdateManifest.validateDownloadUri(URI.create("https://release-assets.githubusercontent.com/public/file?signature=opaque"));
    }
}
