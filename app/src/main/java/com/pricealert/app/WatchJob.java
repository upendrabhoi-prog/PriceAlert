package com.pricealert.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Safety net run by Android about every 15 minutes, even if the phone has stopped the
 * monitoring service: restarts it when allowed, and otherwise checks prices and sends
 * any due alerts itself. Also keeps the home-screen widgets fresh.
 */
public class WatchJob extends JobService {
    private static final int JOB_ID = 4242;

    public static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null || js.getPendingJob(JOB_ID) != null) return;
            JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(c, WatchJob.class))
                    .setPeriodic(15 * 60_000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build();
            js.schedule(job);
        } catch (Exception ignored) { }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Context c = getApplicationContext();
        Store store = Store.get(c);
        if (store.monitoring() && PriceService.running == null) PriceService.start(c);
        new Thread(() -> {
            try {
                try { Sheets.maybeSync(c); } catch (Exception ignored) { }
                try { Backup.maybeRemind(c); } catch (Exception ignored) { }
                long now = System.currentTimeMillis();
                boolean marketOnly = store.settings().optBoolean("marketOnly", true);
                boolean stale = now - store.lastFetch().optLong("t", 0) > 5 * 60_000L;
                if (store.monitoring() && stale && (!marketOnly || Market.isOpen(now))) {
                    Checker.run(c, true);           // service isn't running: check and alert here
                } else {
                    Widgets.updateAll(c);
                }
            } catch (Exception ignored) {
            } finally {
                jobFinished(params, false);
            }
        }, "watch-job").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
