package com.timeapp.shell;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次同步的编排。**这是整个功能的主流程**，其他类都是它的零件。
 *
 * 流程：
 *   查权限 → 拉未来 7 天（任何一天失败就整轮放弃，日历一个字都不碰）
 *          → 确保日历存在 → 清空 → 逐条写事件 + 提醒
 *
 * 【为什么是"全有或全无"】
 * 如果拉到第 4 天失败就先把前 3 天写进去，你会得到一本**残缺的日历**：
 * 有的事件在、有的不在，而你看不出来是"那天真没安排"还是"同步挂了一半"。
 * 宁可整轮不写，保留上一次的完整快照——至少它是自洽的。
 *
 * 【为什么一次拉 7 天】
 * 后台任务什么时候被系统放行是不确定的（Doze 期间完全不跑）。
 * 一次覆盖 7 天，就算后台失约好几天，日历里也还有东西。
 */
final class PlanSync {

    private PlanSync() {}

    private static final String TAG = "PlanSync";

    /** 一次同步的结果。 */
    static final class Result {
        final boolean ok;        // 成功了吗
        final boolean retry;     // 失败了的话，值不值得重试（网络问题值得，权限问题不值得）
        final String message;    // 给人看的一句话
        final int inserted;      // 写了多少条事件

        Result(boolean ok, boolean retry, String message, int inserted) {
            this.ok = ok;
            this.retry = retry;
            this.message = message;
            this.inserted = inserted;
        }
    }

    /** 一次同步覆盖几天（含今天）。 */
    private static final int SYNC_DAYS = 7;

    static Result run(Context ctx) {
        // ---- 1. 权限 ----
        // 两个都要：READ 用来查日历存不存在，WRITE 用来写。
        // 只申请 WRITE 的话，那次 query 会抛 SecurityException。
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR)
                    != PackageManager.PERMISSION_GRANTED
                || ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR)
                    != PackageManager.PERMISSION_GRANTED) {
            // 权限问题重试也没用——用户不点，永远不会好。所以 retry=false。
            return new Result(false, false, "没有日历权限", 0);
        }

        // ---- 2. 拉数据（先全拉齐，再动日历）----
        List<String> dates = new ArrayList<>();
        List<List<PlanClient.Item>> days = new ArrayList<>();

        for (int i = 0; i < SYNC_DAYS; i++) {
            String date = PlanTime.day(i);
            try {
                days.add(PlanClient.fetchDay(ctx, date));
                dates.add(date);
            } catch (Exception ex) {
                // 任何一天失败：直接退，日历没被碰过，还是上一次的完整快照。
                return new Result(false, true, "拉 " + date + " 失败：" + ex.getMessage(), 0);
            }
        }

        // ---- 3. 写日历 ----
        try {
            long calId = CalendarStore.ensureCalendar(ctx);
            CalendarStore.clearAllEvents(ctx, calId);

            int n = 0;
            for (int i = 0; i < days.size(); i++) {
                String date = dates.get(i);
                for (PlanClient.Item it : days.get(i)) {
                    long startMs = PlanTime.toMillis(date, it.start);
                    long endMs = PlanTime.toMillis(date, it.end);
                    // 防守：服务器理论上不会给 end <= start 的条目，
                    // 真给了就跳过，别写一条时长为负的事件进去。
                    if (endMs <= startMs) continue;

                    long id = CalendarStore.insertEvent(ctx, calId, it.name, startMs, endMs);
                    CalendarStore.insertReminder(ctx, id);
                    if (id >= 0) n++;
                }
            }

            SharedPreferences prefs =
                    ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
            prefs.edit().putLong(MainActivity.KEY_LAST_SYNC, System.currentTimeMillis()).apply();

            // 排响铃闹钟。**必须每轮同步都重排**，不能只在第一次排——
            // "今天"的行程每次刷新都会重新锚定：早上 9 点算出来的 17:00 开会，
            // 下午 3 点再同步可能就变成 15:04 了。不重排的话闹钟会响在错的时刻。
            //
            // 单独包一层 try/catch：响铃是增强功能，它坏掉不该把日历同步拖下水。
            // 捕 Throwable 而不是 Exception——类加载失败那类 Error 同样不该把
            // 整个同步带走。
            int alarms = 0;
            try {
                alarms = RingScheduler.reschedule(ctx, dates, days);
            } catch (Throwable t) {
                Log.w(TAG, "排响铃闹钟失败（不影响日历）", t);
            }

            return new Result(true, false,
                    "已同步 " + n + " 条到日历"
                            + (alarms > 0 ? "，" + alarms + " 条高优先级会响铃" : ""),
                    n);

        } catch (Exception ex) {
            // 写日历中途失败（比如用户正好在这时候删了日历）。
            // retry=true 是因为下一次同步会重新 ensureCalendar，能自愈。
            return new Result(false, true, "写日历失败：" + ex.getMessage(), 0);
        }
    }
}
