package cn.wordwatch.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** Explicit, non-exported callback for the system package installer. */
public final class UpdateResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != UpdateCoordinator.prefs(context).getInt("installSession", -2)) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            try {
                if (confirmation == null) throw new IllegalStateException();
                UpdateCoordinator.pendingConfirmation(confirmation);
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(confirmation);
                UpdateCoordinator.status(context, "请在系统界面确认安装；若没有弹出，请回到更新页点确认安装");
            } catch (Exception unsupported) { UpdateCoordinator.status(context, "此手表无法打开安装界面，请使用 USB 覆盖安装发布页 APK"); }
        } else {
            UpdateCoordinator.pendingConfirmation(null);
            UpdateCoordinator.prefs(context).edit().remove("installSession").apply();
            if (status == PackageInstaller.STATUS_SUCCESS) UpdateCoordinator.status(context, "更新安装完成");
            else UpdateCoordinator.status(context, "更新未安装或已取消，原有应用和学习记录已保留");
        }
    }
}
