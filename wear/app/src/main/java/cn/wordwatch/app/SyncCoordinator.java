package cn.wordwatch.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import cn.wordwatch.app.core.GitHubClient;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SyncCoordinator {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    public interface Callback { void done(String message, boolean success); }
    public static boolean running() { return RUNNING.get(); }
    public static boolean wifi(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities cap = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return cap != null && (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }
    public static boolean start(Context context, boolean manual, Callback callback) {
        Context app = context.getApplicationContext(); AppStore store = AppStore.get(app);
        if (!new CredentialVault(app).exists() || (!manual && !wifi(app))) return false;
        JSONObject state = store.snapshot();
        boolean pending = state.optLong("seq") > state.optLong("lastSyncedSeq");
        long minimumDelay = pending && state.optString("syncError").isEmpty() ? 30_000L : 15 * 60_000L;
        if (!manual && System.currentTimeMillis() - state.optLong("lastAttempt") < minimumDelay) return false;
        if (!RUNNING.compareAndSet(false, true)) return false;
        IO.execute(() -> {
            String message; boolean success = false;
            try {
                store.syncAttempt(null); JSONObject settings = store.settings();
                GitHubClient.SyncResult result = new GitHubClient().sync(settings.getString("owner"), settings.getString("repo"),
                    settings.getString("branch"), new CredentialVault(app).read(), store.deviceId(), store.events());
                JSONObject currentSettings = store.settings();
                for (String key : new String[]{"owner", "repo", "branch"})
                    if (!settings.getString(key).equals(currentSettings.getString(key))) throw new IllegalStateException("仓库配置已变化，请重新同步");
                store.acceptSync(result.pack, result.events); message = "同步完成 · 记录已保存到 GitHub"; success = true;
            } catch (Exception e) {
                message = "同步失败，请检查网络和学习包；离线记录已保留";
                if (e instanceof GitHubClient.SyncException) {
                    int status = ((GitHubClient.SyncException) e).statusCode;
                    if (status == 401 || status == 403) message = "GitHub 授权失败，请检查 Token 有效期和仓库读写权限";
                    else if (status == 404) message = "找不到仓库或学习包，请检查配置与访问权限";
                    else if (status == 409 || status == 422) message = "云端同时有更新，请稍后再同步；离线记录已保留";
                } else if (e instanceof java.net.SocketTimeoutException || e instanceof java.net.UnknownHostException) {
                    message = "暂时连不上 GitHub，离线记录已保留";
                }
                try { store.syncAttempt(message); } catch (Exception ignored) { message = "本机保存失败，请勿卸载应用"; }
            }
            RUNNING.set(false); String resultMessage = message; boolean resultSuccess = success;
            if (callback != null) new Handler(Looper.getMainLooper()).post(() -> callback.done(resultMessage, resultSuccess));
        });
        return true;
    }
}
