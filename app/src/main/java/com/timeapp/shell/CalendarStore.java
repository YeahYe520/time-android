package com.timeapp.shell;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

/**
 * 手机日历的读写。
 *
 * 这个类里的每一处写法都对应一个**已经踩过的坑**，注释里标了"为什么"。
 * 这些坑的共同特征是：**不报错，但功能不生效**——所以不能靠试错发现。
 *
 * 【身份说明】
 * 我们建一本**本地日历**（不跟任何账号同步），用一对固定的账号名/类型标识它：
 *      ACCOUNT_NAME = "com.timeapp.shell"
 *      ACCOUNT_TYPE = "LOCAL"
 * 这两个值是这本日历的"身份证"。**定下来就不能改**——改了下次就找不回原来那本，
 * 会新建一本，然后你手机里出现两本「日程」。
 */
final class CalendarStore {

    private CalendarStore() {}

    static final String ACCOUNT = "com.timeapp.shell";
    /** 值就是字符串 "LOCAL"。这是安卓给"不属于任何账号的本地日历"留的特殊类型。 */
    static final String ACCOUNT_TYPE = CalendarContract.ACCOUNT_TYPE_LOCAL;
    /** 内部名。用户看不见。 */
    static final String CAL_NAME = "timeapp";
    /** 用户在手机关历里看到的名字。 */
    static final String CAL_DISPLAY = "日程";
    /** 提前几分钟提醒。必须是正整数——原因见 insertReminder。 */
    static final int REMIND_MINUTES = 10;

    /**
     * 建日历 / 删日历专用的 URI。
     *
     * 安卓规定：**日历表（Calendars）只有"同步适配器"身份才能写**。
     * 普通 App 想建本地日历，得在 URI 上挂三个参数伪装成同步适配器：
     *      caller_is_syncadapter=true + account_name + account_type
     *
     * 三个**缺一不可**：
     *   少了 flag        → IllegalArgumentException("Only sync adapters may write to ...")
     *   少了 account 参数 → IllegalArgumentException("Sync adapters must specify an account ...")
     *
     * 注意：只用于"建"和"删整本日历"。**平时插事件、清事件都不用这个**
     * （那些是普通身份就能做的，用普通 URI）。
     */
    private static Uri calendarsAsSyncAdapter() {
        return CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
                .build();
    }

    /**
     * 确保「日程」这本日历存在，返回它的 id。
     *
     * 做四件事：
     *   1. 按身份查。**必须先查**——provider 对日历插入不做任何查重
     *      （AOSP 源码里那个插入分支第一行就是一句 "// TODO: verify that all required
     *      fields are present"），无脑 insert 会攒出一堆同名日历。
     *   2. 万一以前攒出了多余的，删掉只留第一本。
     *   3. 用户把它隐藏了 / 取消勾选了 → 改回来（自愈）。
     *   4. 一本都没有才新建。
     */
    static long ensureCalendar(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();
        long calId = -1;
        boolean needsSelfHeal = false;

        // **必须带 ACCOUNT_TYPE 一起查。** 官方的原话是：
        // "a given account is only considered unique given both its ACCOUNT_NAME
        //  and its ACCOUNT_TYPE"——只按名字查是不合法的假设。
        Cursor c = cr.query(
                CalendarContract.Calendars.CONTENT_URI,
                new String[]{
                        CalendarContract.Calendars._ID,
                        CalendarContract.Calendars.VISIBLE,
                        CalendarContract.Calendars.SYNC_EVENTS,
                },
                CalendarContract.Calendars.ACCOUNT_NAME + "=? AND "
                        + CalendarContract.Calendars.ACCOUNT_TYPE + "=?",
                new String[]{ACCOUNT, ACCOUNT_TYPE},
                null);

        try {
            while (c != null && c.moveToNext()) {
                if (calId < 0) {
                    calId = c.getLong(0);
                    needsSelfHeal = c.getInt(1) == 0 || c.getInt(2) == 0;
                } else {
                    // 多余的（历史遗留）。删掉，不然日历列表里一堆重影。
                    cr.delete(ContentUris.withAppendedId(
                            CalendarContract.Calendars.CONTENT_URI, c.getLong(0)), null, null);
                }
            }
        } finally {
            if (c != null) c.close();
        }

        if (calId < 0) {
            ContentValues v = new ContentValues();

            // --- 官方列的 7 个必填 ---
            v.put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT);
            v.put(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE);
            v.put(CalendarContract.Calendars.NAME, CAL_NAME);
            v.put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CAL_DISPLAY);
            v.put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF3F51B5);
            v.put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                    CalendarContract.Calendars.CAL_ACCESS_OWNER);
            v.put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT);

            // --- 下面两个是"生死线"，缺任何一个都会静默失效 ---

            // SYNC_EVENTS 在数据库里的默认值是 0。为 0 的话 provider 认为
            // "这本日历不同步"，于是**连 Instances 行都不生成**——
            // 事件数据在库里，但日历上什么都看不到，提醒也不排。不报错。
            v.put(CalendarContract.Calendars.SYNC_EVENTS, 1);

            // VISIBLE 决定"响不响"。排闹钟的那条 SQL 里有 WHERE VISIBLE=1，
            // 隐藏的日历收了事件也不会弹通知。也不报错。
            v.put(CalendarContract.Calendars.VISIBLE, 1);

            v.put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, PlanTime.zone().getId());

            Uri created = cr.insert(calendarsAsSyncAdapter(), v);
            if (created == null) {
                throw new IllegalStateException("建日历失败：provider 返回 null");
            }
            calId = ContentUris.parseId(created);

        } else if (needsSelfHeal) {
            // 用户把「日程」取消勾选了，或者系统把它标记成不同步了 → 改回来。
            // 不清事件，只把这两列修好。
            ContentValues fix = new ContentValues();
            fix.put(CalendarContract.Calendars.VISIBLE, 1);
            fix.put(CalendarContract.Calendars.SYNC_EVENTS, 1);
            cr.update(ContentUris.withAppendedId(
                    CalendarContract.Calendars.CONTENT_URI, calId), fix, null, null);
        }

        return calId;
    }

    /**
     * 清空这本日历里的所有事件。
     *
     * 两个要点：
     *
     * 1. **selection 绝不能是 null。** delete(Events.CONTENT_URI, null, null)
     *    会直接抛 IllegalArgumentException("Selection must be specified")——
     *    这是 provider 特意设的保护，防止误删用户全部日历。
     *    （但 Calendars 表的空 selection 删除**不拦**，所以永远别对日历做无条件删除。）
     *
     * 2. 删事件会**连带清掉**提醒、Instances、参与者——数据库里有触发器
     *    （EVENTS_CLEANUP_TRIGGER）负责级联。所以不用手动删 Reminders。
     *
     * 为什么用"清空事件"而不是"删掉整本日历再重建"：
     *   日历 id 会变（所有引用失效）、颜色和你手选的显示状态被重置、
     *   日历列表里这个日历会闪一下消失再出现，而且重建时万一忘了写
     *   VISIBLE=1，事件插进去了但整本日历是隐藏的——又一个静默失败。
     */
    static void clearAllEvents(Context ctx, long calId) {
        ctx.getContentResolver().delete(
                CalendarContract.Events.CONTENT_URI,
                CalendarContract.Events.CALENDAR_ID + "=?",
                new String[]{String.valueOf(calId)});
    }

    /** 插一条定时事件。返回事件 id，失败返回 -1。 */
    static long insertEvent(Context ctx, long calId, String title, long startMs, long endMs) {
        ContentValues e = new ContentValues();

        e.put(CalendarContract.Events.CALENDAR_ID, calId);
        e.put(CalendarContract.Events.TITLE, title);
        e.put(CalendarContract.Events.DTSTART, startMs);
        e.put(CalendarContract.Events.DTEND, endMs);

        // 时区字符串必须和上面算毫秒用的时区**同源**，所以两处都走 PlanTime.zone()。
        e.put(CalendarContract.Events.EVENT_TIMEZONE, PlanTime.zone().getId());

        // 【故意不写的两列】
        //
        // ALL_DAY：写了就变成"全天事件"，钟点信息全丢。
        //
        // HAS_ALARM：网上很多教程说要写这个"开关"，**其实写了也没用**。
        //   provider 在 insert 和 update 两条路径上都会把它从 ContentValues 里
        //   remove 掉（不报错）。真正决定有没有闹钟的，是 Reminders 表里有没有行。
        //
        // DURATION：和 DTEND 同时写会直接抛异常
        //   "Cannot have both DTEND and DURATION in an event"。我们只写 DTEND。

        Uri uri = ctx.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, e);
        if (uri == null) return -1;
        return Long.parseLong(uri.getLastPathSegment());
    }

    /**
     * 给事件挂一个"提前 N 分钟"的提醒。
     *
     * 这是整个功能里最容易静默失效的一步，两个字段都不能想当然：
     *
     * METHOD 必须写 METHOD_ALERT（值 1）。
     *   写 METHOD_DEFAULT（值 0）的话——**这恰好是数据库列的默认值**，
     *   看起来最"正常"——提醒行会成功存进去，日历里说不定还显示个小铃铛，
     *   但 AOSP 排闹钟的那条 SQL 只挑 METHOD=METHOD_ALERT 的行。
     *   于是永远不排、永远不响、**没有任何报错**。
     *
     * MINUTES 必须写正整数。
     *   写 -1（MINUTES_DEFAULT，意思是"用系统默认"）——这个值在 AOSP 里
     *   **根本没实现**（源码注释写着"可以加支持"，也就是还没加），
     *   会被当成字面量算成"事件开始后 1 分钟"。
     *   写 null 的话闹钟时间算不出来，条件恒假，同样静默不触发。
     */
    static void insertReminder(Context ctx, long eventId) {
        if (eventId < 0) return;
        ContentValues r = new ContentValues();
        r.put(CalendarContract.Reminders.EVENT_ID, eventId);
        r.put(CalendarContract.Reminders.MINUTES, REMIND_MINUTES);
        r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
        ctx.getContentResolver().insert(CalendarContract.Reminders.CONTENT_URI, r);
        // 插完这条，provider 会自动把事件的 HAS_ALARM 置 1，不用我们管。
    }
}
