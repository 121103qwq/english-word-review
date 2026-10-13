package cn.wordwatch.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import cn.wordwatch.app.core.GitHubClient;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class SyncCoordinator {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicReference<AtomicBoolean> AUTOMATIC_CANCEL = new AtomicReference<>();
    private static final ReentrantReadWriteLock SETTINGS_LOCK = new ReentrantReadWriteLock();

    public interface Callback { void done(String message, boolean success); }

    @FunctionalInterface
    public interface SettingsWriteAction<T> { T run() throws Exception; }

    public static boolean running() { return RUNNING.get(); }

    /**
     * Serializes settings/token changes against a running sync. The UI should fail fast and let
     * the user retry after the current sync finishes rather than block its thread.
     */
    public static <T> T withSettingsWriteLock(SettingsWriteAction<T> action) throws Exception {
        if (action == null) throw new IllegalArgumentException("settings write action is required");
        if (!SETTINGS_LOCK.writeLock().tryLock()) {
            throw new IllegalStateException("同步中，请稍后保存仓库配置");
        }
        try {
            return action.run();
        } finally {
            SETTINGS_LOCK.writeLock().unlock();
        }
    }

    public static boolean wifi(Context context) {
        return eligibleWifiNetwork(context) != null;
    }

    private static Network eligibleWifiNetwork(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        Network network = cm.getActiveNetwork();
        if (network == null) return null;
        NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
        return hasWifiOrEthernet(capabilities) ? network : null;
    }

    private static boolean hasWifiOrEthernet(NetworkCapabilities capabilities) {
        return capabilities != null && (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    private static boolean isStillOn(Network pinned, Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null || pinned == null || !pinned.equals(cm.getActiveNetwork())) return false;
        return hasWifiOrEthernet(cm.getNetworkCapabilities(pinned));
    }

    public static boolean start(Context context, boolean manual, Callback callback) {
        Context app = context.getApplicationContext();
        AppStore store = AppStore.get(app);
        if (!new CredentialVault(app).exists()) return false;
        Network pinnedNetwork = manual ? null : eligibleWifiNetwork(app);
        if (!manual && pinnedNetwork == null) return false;

        int pending = store.pendingCount();
        JSONObject state = store.snapshot();
        long minimumDelay = pending > 0 && state.optString("syncError").isEmpty()
                ? 30_000L : 15 * 60_000L;
        if (!manual && System.currentTimeMillis() - state.optLong("lastAttempt") < minimumDelay) return false;
        if (!RUNNING.compareAndSet(false, true)) return false;

        AtomicBoolean cancelled = manual ? null : new AtomicBoolean(false);
        if (cancelled != null) AUTOMATIC_CANCEL.set(cancelled);
        IO.execute(() -> {
            String message = "同步失败，离线记录已保留";
            boolean success = false;
            boolean readLockHeld = false;
            try {
                SETTINGS_LOCK.readLock().lock();
                readLockHeld = true;
                store.syncAttempt(null);
                JSONObject settings = store.settings();
                GitHubClient.ConnectionFactory connectionFactory = manual ? null
                        : url -> (HttpURLConnection) pinnedNetwork.openConnection(url);
                GitHubClient.HttpTransport transport = manual
                        ? new GitHubClient.HttpTransport()
                        : new GitHubClient.HttpTransport(connectionFactory);
                GitHubClient.RequestGuard requestGuard = manual ? () -> true
                        : () -> !cancelled.get() && isStillOn(pinnedNetwork, app);
                GitHubClient client = new GitHubClient(
                        GitHubClient.CONTENT_PREFIX, transport, Thread::sleep, requestGuard);
                GitHubClient.SyncResult result = client.sync(
                        settings.getString("owner"), settings.getString("repo"), settings.getString("branch"),
                        new CredentialVault(app).read(), store.deviceId(), store.events());

                String packWarning = result.warning;
                store.acceptSyncPackages(result.packages, result.events, result.acknowledgedEventIds, packWarning);
                String eventError = result.eventsComplete ? "" : failureMessage(result.eventFailure, result.eventWarning);
                if (!result.eventsComplete) store.syncAttempt(eventError);

                JSONObject stored = store.snapshot();
                StringBuilder display = new StringBuilder(result.eventsComplete
                        ? "记录已同步到 GitHub" : "部分记录未确认，仍保留在本机");
                if (!result.eventWarning.isEmpty()) display.append("；").append(result.eventWarning);
                String visiblePackWarning = stored.optString("packError", "");
                if (!visiblePackWarning.isEmpty()) display.append("；").append(visiblePackWarning);
                message = display.toString();
                success = result.eventsComplete && result.packFailure == null && visiblePackWarning.isEmpty();
            } catch (Exception e) {
                message = failureMessage(e instanceof GitHubClient.SyncException
                        ? (GitHubClient.SyncException) e : null, "离线记录仍保留在本机");
                try { store.syncAttempt(message); }
                catch (Exception ignored) { message = "本机保存失败，请勿卸载应用"; }
            } finally {
                if (readLockHeld) SETTINGS_LOCK.readLock().unlock();
                if (cancelled != null) AUTOMATIC_CANCEL.compareAndSet(cancelled, null);
                RUNNING.set(false);
                String resultMessage = message;
                boolean resultSuccess = success;
                if (callback != null) {
                    new Handler(Looper.getMainLooper()).post(() -> callback.done(resultMessage, resultSuccess));
                }
            }
        });
        return true;
    }

    /** Stops an automatic sync before its next HTTP request; manual sync is unaffected. */
    public static void cancelAutomaticSync() {
        AtomicBoolean cancellation = AUTOMATIC_CANCEL.get();
        if (cancellation != null) cancellation.set(true);
    }

    private static String failureMessage(GitHubClient.SyncException failure, String detail) {
        String classified;
        if (failure == null) {
            classified = detail == null || detail.isEmpty() ? "同步失败，离线记录已保留" : detail;
        } else {
            switch (failure.failureKind) {
                case NETWORK: classified = "暂时连不上 GitHub，离线记录已保留"; break;
                case AUTH: classified = "GitHub 授权失败，请检查 Token 有效期和仓库读写权限"; break;
                case RATE_LIMITED: classified = "GitHub 暂时限流，稍后会重试"; break;
                case NOT_FOUND: classified = "找不到仓库或云端文件，请检查仓库配置和路径"; break;
                case CONFLICT: classified = "云端同时有更新，记录保留在本机，稍后重试"; break;
                case CANCELLED: classified = "自动同步网络条件已变化，未确认记录保留在本机"; break;
                case TOO_LARGE: classified = "记录批次超过安全大小，未确认记录保留在本机"; break;
                case PROTOCOL: classified = "云端记录格式无法验证，未确认记录保留在本机"; break;
                case SERVER: classified = "GitHub 暂时不可用，稍后会重试"; break;
                default: classified = "同步失败，离线记录已保留"; break;
            }
        }
        if (detail == null || detail.isEmpty() || detail.equals(classified)
                || "离线记录仍保留在本机".equals(detail)) return classified;
        return classified + "；" + detail;
    }
}
