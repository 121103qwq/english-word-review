package cn.wordwatch.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

public final class SyncJob extends JobService {
    public static void schedule(Context context) {
        if (!new CredentialVault(context).exists()) return;
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler.getPendingJob(2401) == null) scheduler.schedule(new JobInfo.Builder(2401, new ComponentName(context, SyncJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).setPersisted(true).setPeriodic(6 * 60 * 60_000L).build());
    }
    @Override public boolean onStartJob(JobParameters params) {
        return SyncCoordinator.start(this, false, (message, success) -> jobFinished(params, !success));
    }
    @Override public boolean onStopJob(JobParameters params) { return true; }
}
