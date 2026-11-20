package com.timeapp.shell;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

/**
 * 注册 / 取消周期同步任务。
 *
 * 【先说清楚它的地位：这是**补充**，不是主干】
 *
 * 真正保证日历新鲜的，是"每次打开 App 时同步一次"。这个后台任务只是锦上添花。
 * 原因是 JobScheduler 的周期是"**每周期最多跑一次**"，不是"每周期一定跑一次"：
 *   - Doze（深度休眠）期间完全不跑，只在维护窗口跑，而维护窗口会越来越稀
 *   - 各家的省电策略还能进一步压制（小米要手动开自启动 + 电池无限制）
 *   - 每次同步覆盖 7 天，所以后台就算失约几天，日历里也还有东西
 *
 * 一句话：**别把"提醒会不会响"绑在这个任务上。**
 * 提醒是系统日历负责发的，这个任务只决定"数据有多新鲜"。
 */
final class SyncScheduler {

    private SyncScheduler() {}

    private static final int JOB_ID = 1001;

    /** 多久跑一次。6 小时——一次同步覆盖 7 天，没必要更频繁。 */
    private static final long PERIOD_MS = 6L * 60 * 60 * 1000;

    static void schedule(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js == null) return;

        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, SyncJobService.class))
                // 下限是 15 分钟。写更小的值不会报错，会被**静默抬到下限**。
                .setPeriodic(PERIOD_MS)

                // 必须有网才跑。另外：Android 10 以前 JobInfo 要求**至少一个约束**，
                // 一个都不设的话 build() 直接抛异常。
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)

                // 重启手机后任务还在。这个开关有个配套要求：
                // Manifest 里必须声明 RECEIVE_BOOT_COMPLETED，否则 schedule() 当场报错
                // （"requested job be persisted without holding RECEIVE_BOOT_COMPLETED permission"）。
                // 反过来，漏写 setPersisted 才是真正静默的——任务不写盘，重启就没了。
                .setPersisted(true)
                .build();

        // 同一个 id 再 schedule 是"覆盖"，不会重复。
        // 但别频繁调——API 30 起对高频 schedule 有限流。onCreate 调一次就够。
        js.schedule(job);
    }

    static void cancel(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js != null) js.cancel(JOB_ID);
    }
}
