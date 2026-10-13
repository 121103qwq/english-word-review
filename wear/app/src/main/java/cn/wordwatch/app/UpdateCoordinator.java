package cn.wordwatch.app;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import cn.wordwatch.app.core.UpdateManifest;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** An anonymous GitHub release channel, separate from private study-data credentials. */
public final class UpdateCoordinator {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Intent confirmation;
    private static final String RELEASE = "https://api.github.com/repos/" + UpdateManifest.REPOSITORY + "/releases/latest";
    private UpdateCoordinator() {}
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("application-update", Context.MODE_PRIVATE); }
    public static boolean running() { return RUNNING.get(); }
    private static PackageInstaller.SessionInfo pendingSession(Context context) {
        int id = prefs(context).getInt("installSession", -1);
        if (id < 0) return null;
        try {
            PackageInstaller.SessionInfo session = context.getPackageManager().getPackageInstaller().getSessionInfo(id);
            return session != null && context.getPackageName().equals(session.getInstallerPackageName()) ? session : null;
        } catch (Exception unavailable) { return null; }
    }
    public static boolean hasConfirmation(Context context) { return confirmation != null || pendingSession(context) != null; }
    static void pendingConfirmation(Intent intent) { confirmation = intent; }
    public static void confirm(Activity activity) {
        Intent next = confirmation;
        try {
            if (next == null) {
                PackageInstaller.SessionInfo session = pendingSession(activity);
                if (session != null) next = session.createDetailsIntent();
            }
            if (next == null) throw new IllegalStateException("installer unavailable");
            activity.startActivity(next);
        }
        catch (Exception unavailable) { status(activity, "系统无法打开安装界面，请使用 USB 覆盖安装发布页 APK"); }
    }
    public static String message(Context c) { return prefs(c).getString("message", "从 GitHub 发布页检查新版应用"); }
    static void status(Context c, String value) { prefs(c).edit().putString("message", value).apply(); }
    static long version(PackageInfo p) { return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode; }
    private static UpdateManifest cached(Context c) throws Exception { return new UpdateManifest(new JSONObject(prefs(c).getString("manifest", "{}"))); }
    public static boolean available(Context c) {
        try { UpdateManifest m = cached(c); return m.isNewer(version(c.getPackageManager().getPackageInfo(c.getPackageName(), 0))) && m.minSdk <= Build.VERSION.SDK_INT; }
        catch (Exception absent) { return false; }
    }
    public static void check(Context context, Runnable callback) {
        if (!RUNNING.compareAndSet(false, true)) return;
        Context app = context.getApplicationContext(); status(app, "正在检查 GitHub 发布版本…"); callback.run();
        IO.execute(() -> {
            try {
                JSONObject release = readJson(RELEASE, false);
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) throw new IOException("unsupported release");
                JSONArray assets = release.getJSONArray("assets"); long manifestAsset = 0;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if ("wordwatch-update.json".equals(a.optString("name")) && a.optLong("size") <= 65536) manifestAsset = a.getLong("id");
                }
                if (manifestAsset <= 0) throw new IOException("missing manifest");
                UpdateManifest m = new UpdateManifest(readJson("https://api.github.com/repos/" + UpdateManifest.REPOSITORY + "/releases/assets/" + manifestAsset, true));
                boolean found = false;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.getLong("id") == m.assetId && a.getLong("size") == m.size && a.optString("name").endsWith(".apk")) found = true;
                }
                if (!found) throw new IOException("asset missing from release");
                prefs(app).edit().putString("manifest", m.json.toString()).apply();
                long installed = version(app.getPackageManager().getPackageInfo(app.getPackageName(), 0));
                if (!UpdateManifest.PACKAGE.equals(app.getPackageName())) status(app, "开发测试版不覆盖正式应用，请使用正式 APK");
                else if (!m.isNewer(installed)) status(app, "当前已是最新版本 · " + m.versionName);
                else if (m.minSdk > Build.VERSION.SDK_INT) status(app, "新版需要更高的手表系统，当前应用仍可继续使用");
                else status(app, "发现 " + m.versionName + "\n" + m.notes);
            } catch (Exception e) { status(app, failure(e)); }
            finally { RUNNING.set(false); MAIN.post(callback); }
        });
    }
    public static void install(Activity activity, Runnable callback) {
        if (RUNNING.get() || !available(activity)) return;
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            try { status(activity, "请在系统中允许词序安装更新，然后回来点下载并安装"); activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName()))); }
            catch (Exception unsupported) { status(activity, "此手表未提供安装授权页面，请用 USB 覆盖安装 GitHub 发布页的 APK"); }
            callback.run(); return;
        }
        if (!RUNNING.compareAndSet(false, true)) return;
        Context app = activity.getApplicationContext(); status(app, "下载并核对更新签名…"); callback.run();
        IO.execute(() -> {
            try {
                UpdateManifest m = cached(app); File dir = new File(app.getCacheDir(), "updates");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("storage");
                if (dir.getUsableSpace() < m.size * 3 + 1024 * 1024) throw new IOException("storage");
                File partial = new File(dir, "download.part.apk"), verified = new File(dir, "verified.apk");
                MessageDigest digest = MessageDigest.getInstance("SHA-256"); long total = 0;
                HttpURLConnection connection = open(m.assetUrl(), true);
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[16384]; int n;
                    while ((n = input.read(buffer)) != -1) { total += n; if (total > m.size) throw new SecurityException("size"); digest.update(buffer, 0, n); output.write(buffer, 0, n); }
                    output.getFD().sync();
                } finally { connection.disconnect(); }
                if (total != m.size || !hex(digest.digest()).equals(m.sha256)) throw new SecurityException("hash");
                verifyArchive(app, partial, m);
                try { Files.move(partial.toPath(), verified.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(partial.toPath(), verified.toPath(), StandardCopyOption.REPLACE_EXISTING); }
                PackageInstaller installer = app.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(UpdateManifest.PACKAGE); params.setSize(m.size);
                if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
                int id = installer.createSession(params);
                boolean committed = false;
                try (PackageInstaller.Session session = installer.openSession(id)) {
                    try (InputStream in = new FileInputStream(verified); OutputStream out = session.openWrite("base.apk", 0, m.size)) {
                        byte[] bytes = new byte[16384]; int n; while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n); session.fsync(out);
                    }
                    Intent result = new Intent(app, UpdateResultReceiver.class).setAction(app.getPackageName() + ".UPDATE_RESULT");
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                    if (!prefs(app).edit().putInt("installSession", id).commit()) throw new IOException("storage");
                    session.commit(PendingIntent.getBroadcast(app, id, result, flags).getIntentSender()); committed = true;
                    status(app, "更新已校验，等待系统确认安装");
                } finally {
                    if (!committed) {
                        installer.abandonSession(id);
                        prefs(app).edit().remove("installSession").apply();
                    }
                }
            } catch (Exception e) { status(app, failure(e)); }
            finally { RUNNING.set(false); MAIN.post(callback); }
        });
    }
    @SuppressWarnings("deprecation")
    static void verifyArchive(Context context, File file, UpdateManifest m) throws Exception {
        PackageManager pm = context.getPackageManager(); int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = pm.getPackageArchiveInfo(file.getAbsolutePath(), flags), installed = pm.getPackageInfo(context.getPackageName(), flags);
        if (archive == null || !UpdateManifest.PACKAGE.equals(archive.packageName) || !archive.packageName.equals(installed.packageName)
                || version(archive) != m.versionCode || !m.versionName.equals(archive.versionName) || !m.isNewer(version(installed)) || archive.applicationInfo == null
                || archive.applicationInfo.minSdkVersion != m.minSdk || m.minSdk > Build.VERSION.SDK_INT) throw new SecurityException("package");
        Signature[] a = Build.VERSION.SDK_INT >= 28 ? archive.signingInfo == null ? null : archive.signingInfo.getApkContentsSigners() : archive.signatures;
        Signature[] b = Build.VERSION.SDK_INT >= 28 ? installed.signingInfo == null ? null : installed.signingInfo.getApkContentsSigners() : installed.signatures;
        if (a == null || b == null || a.length == 0 || !signatures(a).equals(signatures(b))) throw new SecurityException("signer");
    }
    private static Set<String> signatures(Signature[] values) throws Exception {
        Set<String> result = new HashSet<>(); for (Signature s : values) result.add(hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))); return result;
    }
    static String hex(byte[] bytes) { StringBuilder b = new StringBuilder(); for (byte v : bytes) b.append(String.format(java.util.Locale.ROOT, "%02x", v & 255)); return b.toString(); }
    private static JSONObject readJson(String url, boolean asset) throws Exception {
        HttpURLConnection connection = open(url, asset);
        try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[8192]; int n;
            while ((n = in.read(bytes)) != -1) { if (out.size() + n > (asset ? 65536 : 262144)) throw new IOException("response too large"); out.write(bytes, 0, n); }
            return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
        } finally { connection.disconnect(); }
    }
    private static HttpURLConnection open(String initial, boolean asset) throws Exception {
        URI uri = URI.create(initial);
        for (int attempt = 0; attempt < 5; attempt++) {
            UpdateManifest.validateDownloadUri(uri);
            HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(10000); c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "WordWatch-Updater"); c.setRequestProperty("Accept", asset ? "application/octet-stream" : "application/vnd.github+json");
            int status = c.getResponseCode();
            if (status == 200) return c;
            if (asset && (status == 301 || status == 302 || status == 303 || status == 307 || status == 308)) {
                String destination = c.getHeaderField("Location"); c.disconnect();
                if (destination == null) throw new IOException("missing redirect"); uri = uri.resolve(destination); continue;
            }
            c.disconnect(); throw new IOException(status == 403 || status == 429 ? "rate limit" : status == 404 ? "not published" : "network response");
        }
        throw new IOException("redirect limit");
    }
    private static String failure(Exception e) {
        if (e instanceof SecurityException || e instanceof IllegalArgumentException) return "更新校验未通过，未安装；当前应用与记录保持完整";
        if ("storage".equals(e.getMessage())) return "可用空间不足，未安装更新";
        if ("rate limit".equals(e.getMessage())) return "GitHub 暂时限流，请稍后再检查";
        if ("not published".equals(e.getMessage())) return "尚未发布可用更新，当前应用可继续使用";
        return "暂时无法取得更新，请检查网络后重试；学习记录已保留";
    }
}
