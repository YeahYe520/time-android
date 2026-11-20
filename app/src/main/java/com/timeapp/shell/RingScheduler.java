package com.timeapp.shell;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 闹钟的编排：排、取消、重排。
 *
 * 【这个类要同时对付四件事】
 *
 * 1. **重锚定**。v0.7 之后"今天"的行程每次刷新都会重新锚定——早上 9 点算出来的
 *    17:00 开会，下午 3 点再同步可能就变成 15:04 了。所以闹钟必须**每轮同步都全量重排**，
 *    否则会响在一个已经移动过的日程上。这是整个功能最核心的坑。
 *
 * 2. **重启和换时区**。重启之后系统会清掉所有闹钟；换时区系统**不会**重算 RTC 闹钟
 *    （它只会发个广播）。所以留了一份**快照**，里面存的是 `日期 + "HH:MM"` 这种
 *    墙上时间，而不是绝对毫秒——重排时现算，换时区后自然得到"新时区的 17:00"。
 *
 * 3. **requestCode 不能撞**。PendingIntent 的身份是「类型 + requestCode + Intent 是否相等」，
 *    extras 不参与判定。所以**绝不能直接拿服务器的事务 id 当 requestCode**——
 *    服务器每天从 1 开始编号，第二天的事务会和第一天撞号，然后静默地互相覆盖。
 *    用 "日期#id" 复合键映射到一个单调递增、永不复用的整数。
 *
 * 4. **低版本 API 不存在**。minSdk 26，而 canScheduleExactAlarms 是 31 才有的。
 *    不判版本直接调 = NoSuchMethodError——**这是 Error 不是 Exception**，
 *    catch(Exception) 接不住，直接崩。
 */
final class RingScheduler {

    private RingScheduler() {}

    private static final String TAG = "RingScheduler";

    /** 闹钟广播的 action，用来给 PendingIntent 定身份。 */
    static final String ACTION_RING = "com.timeapp.shell.action.RING";

    /** 快照存在哪。存的是 JSON 数组，每条 {key, rc, date, hhmm, name}。 */
    private static final String KEY_SNAPSHOT = "ring_snapshot";
    /** 下一个可用的 requestCode。单调递增，永不复用。 */
    private static final String KEY_RC_NEXT = "ring_rc_next";
    /** requestCode 映射的前缀，后面接 key。 */
    private static final String KEY_RC_PREFIX = "ring_rc_";
    /** 降级标志：没有精确闹钟权限时为 true，界面据此提示用户。 */
    static final String KEY_DEGRADED = "ring_degraded";

    private static final int RC_START = 1000;

    /**
     * 这台机器上能不能用精确闹钟。**全工程唯一的判断出口。**
     *
     *   API 26–30  不需要任何权限，永远 true
     *   API 31–32  SCHEDULE_EXACT_ALARM，安装即授予（用户可撤销）
     *   API 33+    USE_EXACT_ALARM，安装即授予且**不可撤销**
     *
     * 判版本这一步不是可选的：`canScheduleExactAlarms()` 是 API 31 新增的，
     * 在 26 上调用会抛 NoSuchMethodError。
     */
    static boolean canUseExactAlarms(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;   // 31 以下不需要权限
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        return am != null && am.canScheduleExactAlarms();
    }

    // ==================== 全量重排（每轮同步后调） ====================

    /**
     * 按最新拉到的行程，把未来所有"高优先级"事务的闹钟重排一遍。
     *
     * 幂等：同一个 PendingIntent 再 set 一次是**替换**旧闹钟（官方原文），
     * 所以"重排"不需要先取消，直接盖上去就行。
     *
     * @param dates 日期字符串，和 days 一一对应
     * @param days  每天的事务列表
     * @return 一共排上了几个闹钟（给同步结果提示用）
     */
    static int reschedule(Context ctx, List<String> dates, List<List<PlanClient.Item>> days) {
        SharedPreferences prefs = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return 0;

        long now = System.currentTimeMillis();
        boolean exact = canUseExactAlarms(ctx);
        prefs.edit().putBoolean(KEY_DEGRADED, !exact).apply();

        List<JSONObject> snapshot = new ArrayList<>();
        Set<String> wanted = new HashSet<>();

        for (int i = 0; i < days.size() && i < dates.size(); i++) {
            String date = dates.get(i);
            for (PlanClient.Item item : days.get(i)) {
                if (!item.isHighPriority()) continue;      // 只给"高"排铃

                long at = PlanTime.toMillis(date, item.start);
                // **只排未来的**。已经开始的条目如果也排，闹钟会在同步那一瞬间就炸响。
                if (at <= now) continue;

                String key = date + "#" + item.id;
                int rc = requestCodeFor(prefs, key);
                wanted.add(key);

                if (setOne(ctx, am, rc, key, item.name, at, exact)) {
                    try {
                        snapshot.add(new JSONObject()
                                .put("key", key)
                                .put("rc", rc)
                                .put("date", date)
                                .put("hhmm", item.start)
                                .put("name", item.name));
                    } catch (Exception ignored) { }
                }
            }
        }

        cancelStale(ctx, am, prefs, wanted);
        prefs.edit().putString(KEY_SNAPSHOT, new JSONArray(snapshot).toString()).apply();
        return snapshot.size();
    }

    /**
     * 排一个。返回是否成功。
     *
     * 两条路：
     *   有精确闹钟权限 -> setAlarmClock。它的投递时刻**系统绝不会调整**，
     *                     而且必要时会把设备从低功耗模式里拉出来。
     *   没有           -> setAndAllowWhileIdle。不精确，但"绝不早于给定时刻"触发，
     *                     Doze 里也能醒。代价是 Android 12+ 上可能晚至一小时。
     *
     * **降级路径没有后台启动前台服务的豁免**（AOSP 里非精确闹钟走的是
     * mOptsWithoutFgs 那条路），所以到点时 RingReceiver 会启动失败、改发兜底通知。
     * 这条链路是设计好的，不是意外。
     */
    private static boolean setOne(Context ctx, AlarmManager am, int rc, String key,
                                  String name, long at, boolean exact) {
        Intent intent = new Intent(ctx, RingReceiver.class)
                .setAction(ACTION_RING)
                .putExtra("key", key)
                // 名字直接放进 Intent：接收器拿到就能用，不用再去读存储——
                // 那 10 秒的豁免窗口经不起任何多余操作
                .putExtra("name", name);

        // FLAG_IMMUTABLE 必须显式写。targetSdk 31+ 不写可变性直接抛
        // IllegalArgumentException——是运行时异常，编译期看不出来。
        PendingIntent pi = PendingIntent.getBroadcast(ctx, rc, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        if (exact) {
            try {
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, null), pi);
                return true;
            } catch (SecurityException race) {
                // 权限在 canUseExactAlarms() 检查和这里之间被撤销了（极窄的竞态窗口）。
                // 不接住的话整个同步会挂。降级继续。
                Log.w(TAG, "精确闹钟权限刚被撤销，降级为近似闹钟", race);
            }
        }
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "排闹钟失败", e);
            return false;
        }
    }

    /**
     * 取消这一轮不再需要的闹钟（事务被删了、优先级改了、时间挪到今天之前了……）。
     *
     * 用 FLAG_NO_CREATE 取回 PendingIntent：不存在返回 null，**绝不会顺手新建一个**。
     * 取消用的 requestCode 和 Intent 必须和排的时候完全对得上，否则 cancel 不中——
     * 结果是幽灵响声：闹钟还在，到点还响，但你已经找不到它了。
     */
    private static void cancelStale(Context ctx, AlarmManager am,
                                    SharedPreferences prefs, Set<String> wanted) {
        Map<String, Integer> old = readSnapshotRc(prefs);
        for (Map.Entry<String, Integer> e : old.entrySet()) {
            if (wanted.contains(e.getKey())) continue;

            PendingIntent pi = PendingIntent.getBroadcast(ctx, e.getValue(),
                    new Intent(ctx, RingReceiver.class).setAction(ACTION_RING),
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (pi != null) {
                am.cancel(pi);
                pi.cancel();
            }
            // 顺手清掉这个 key 的 requestCode 映射，别让 prefs 无限膨胀。
            // 注意：rc 本身不复用（KEY_RC_NEXT 只增不减），清掉的只是"键 -> rc"这张表。
            prefs.edit().remove(KEY_RC_PREFIX + e.getKey()).apply();
        }
    }

    // ==================== 重启 / 换时区后重排 ====================

    /**
     * 按快照重排。用在重启、改时间、改时区之后。
     *
     * **不需要联网**：快照里存的是 `日期 + "HH:MM"`，用 PlanTime 现算成毫秒。
     * 这正是当初不存绝对毫秒的原因——换时区之后，同一个"17:00"会算成新时区的
     * 17:00，跟界面显示的对得上。
     */
    static void rearmFromSnapshot(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;

        String raw = prefs.getString(KEY_SNAPSHOT, "");
        if (raw.isEmpty()) return;

        boolean exact = canUseExactAlarms(ctx);
        prefs.edit().putBoolean(KEY_DEGRADED, !exact).apply();

        long now = System.currentTimeMillis();
        List<JSONObject> kept = new ArrayList<>();

        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String date = o.getString("date");
                String hhmm = o.getString("hhmm");
                long at = PlanTime.toMillis(date, hhmm);
                if (at <= now) continue;          // 已经过去的，丢掉

                String key = o.getString("key");
                int rc = o.getInt("rc");
                if (setOne(ctx, am, rc, key, o.optString("name", "高优先级事务"), at, exact)) {
                    kept.add(o);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "快照坏了，清掉重来", e);
        }

        prefs.edit().putString(KEY_SNAPSHOT, new JSONArray(kept).toString()).apply();
    }

    // ==================== requestCode 分配 ====================

    /**
     * 给一个 key 分配 requestCode。**同一个 key 永远拿到同一个值。**
     *
     * 这是防"幽灵响声 / 互相覆盖"的核心。不能直接拿服务器的事务 id 当 rc：
     * 服务器每天从 1 开始编号，第二天的事务会和第一天撞号。
     */
    private static int requestCodeFor(SharedPreferences prefs, String key) {
        int existing = prefs.getInt(KEY_RC_PREFIX + key, -1);
        if (existing >= 0) return existing;

        int next = Math.max(prefs.getInt(KEY_RC_NEXT, RC_START), RC_START);
        prefs.edit()
                .putInt(KEY_RC_PREFIX + key, next)
                .putInt(KEY_RC_NEXT, next + 1)   // 只增不减：一个 rc 这辈子不会分给第二个 key
                .apply();
        return next;
    }

    private static Map<String, Integer> readSnapshotRc(SharedPreferences prefs) {
        Map<String, Integer> out = new HashMap<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY_SNAPSHOT, ""));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.put(o.getString("key"), o.getInt("rc"));
            }
        } catch (Exception ignored) {
            // 没快照或者坏了，当成"之前一个闹钟都没有"
        }
        return out;
    }
}
