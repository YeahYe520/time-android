package com.timeapp.shell;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 后台同步任务本体。
 *
 * 【这个类唯一容易写错的地方：onStartJob 跑在主线程】
 *
 * 它是主线程回调。在里面直接发 HTTP 会抛 NetworkOnMainThreadException，
 * 在主线程抛异常 = 崩掉整个 App 进程。而且 Android 14 起，
 * onStartJob 如果几秒内不返回，系统会直接判定 ANR，弹出"应用无响应"。
 *
 * 所以这个方法的正确姿势是：**只丢一个线程出去，然后立刻 return true**。
 */
public class SyncJobService extends JobService {

    private static final String TAG = "SyncJob";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    /** onStopJob 被调用后置 true，用来抑制之后那次多余的 jobFinished。 */
    private volatile boolean stopped;

    @Override
    public boolean onStartJob(JobParameters params) {
        stopped = false;

        // 把活儿丢给工作线程。params 被这个 lambda 捕获，
        // 所以它不会被 GC 掉——这一点很重要：如果 JobParameters 被回收了却没有
        // 调 jobFinished，系统会认为这个任务永远没结束，占着 wakelock 不放。
        worker.execute(() -> {
            PlanSync.Result r = null;
            try {
                // 注意：后台任务里**不能**申请权限（Android 10 起后台不能启动 Activity，
                // 而权限对话框本身就是一个 Activity，永远弹不出来）。
                // PlanSync 里只做 checkSelfPermission，没有权限就直接跳过。
                r = PlanSync.run(SyncJobService.this);
            } catch (Throwable t) {
                Log.w(TAG, "同步崩了", t);
            }

            boolean ok = r != null && r.ok;
            Log.i(TAG, "结束：" + (r == null ? "异常" : r.message));

            // 契约：onStartJob 返回 true 就等于"我欠系统一次 jobFinished"。
            // 第二个参数 needsReschedule：只有"值得重试的失败"才传 true。
            // 用户没给权限这种失败重试一万次也没用，传 false。
            if (!stopped) {
                jobFinished(params, r != null && r.retry && !ok);
            }
        });

        return true;   // "还没干完，等下我会叫你 jobFinished"
    }

    /**
     * 系统要收回这个任务了（比如约束不再满足、或者超时）。
     *
     * 官方文档明确：onStopJob 被调用之后**不需要**再调 jobFinished。
     * 所以这里把 stopped 立起来，让工作线程里那个回调自己闭嘴。
     *
     * 返回值：true = 按退避策略尽快重排这次任务。
     */
    @Override
    public boolean onStopJob(JobParameters params) {
        stopped = true;
        return true;
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
