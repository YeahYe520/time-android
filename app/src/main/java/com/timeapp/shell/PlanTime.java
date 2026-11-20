package com.timeapp.shell;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 时间换算的**唯一出口**。
 *
 * 为什么非要单独搞一个类？因为同步这件事里，"时间"出现在三个地方，
 * 三处只要有一处算法不一样，日历上的钟点就会错，而且**不会报错**：
 *
 *   1. 算事件的毫秒时间戳（DTSTART / DTEND）
 *   2. 写事件的 EVENT_TIMEZONE 字段
 *   3. 决定"今天"是哪天（拉数据时的 date 参数）
 *
 * 所以三个地方全部走这一个类的 zone()，改时区语义只改一行。
 *
 * 【时区语义】服务器发的 "17:00" 是**哪个 17:00**？
 *
 *   zone() 返回 systemDefault()（当前采用）：
 *       手机在哪，"17:00" 就是哪的 17:00。你出国了，"吃饭" 还是当地 17:00。
 *
 *   换成 ZoneId.of("Asia/Shanghai")：
 *       "17:00" 永远指上海的 17:00。你在德国，日历上会显示 10:00。
 *
 * 对个人作息来说前者才对——你饿了就是饿了，不会因为出国就改到上午十点吃饭。
 */
final class PlanTime {

    /** 私有构造：这个类全是静态方法，不该被 new 出来。 */
    private PlanTime() {}

    static ZoneId zone() {
        return ZoneId.systemDefault();
    }

    /**
     * "2026-11-19" + "17:00" → 毫秒时间戳。
     *
     * 为什么不用 Calendar.getInstance()？它有个隐蔽的坑：
     * getInstance() 拿到的对象**带着当前时刻的秒和毫秒**。你只设了年月日时分，
     * 秒和毫秒还是"现在"的值，于是事件开始时间变成 17:00:37.482 这种。
     * 日历上看不出来，但两条本该首尾相接的事件会差出几百毫秒。
     *
     * LocalDate/LocalTime 没有这个问题——它们从零构造，没有"当前时刻"的概念。
     *
     * java.time 需要 API 26，本工程 minSdk 正好是 26，不用额外配置。
     */
    static long toMillis(String date, String hhmm) {
        LocalDate day = LocalDate.parse(date);    // "2026-11-19"

        // "24:00" 是个特例，java.time **不认它**——一天只到 23:59。
        //
        // 服务器用 24:00 表示"当天结束"，也就是第二天的 00:00。
        // 不特判的话 LocalTime.parse("24:00") 会抛 DateTimeParseException，
        // 而它是在同步的循环里抛的，会被外层 catch 接住 → **整轮同步全失败**，
        // 一条事件都写不进去。
        //
        // 什么时候会出现：服务器的自动排程窗口右端点就是 24:00，
        // 一个没定时间的事务正好填满当天最后一个空档时，它的 end 就是 1440 → "24:00"。
        if ("24:00".equals(hhmm)) {
            return day.plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli();
        }

        return day.atTime(LocalTime.parse(hhmm))  // "17:00"
                .atZone(zone())                   // 按上面的语义贴上时区
                .toInstant()
                .toEpochMilli();
    }

    /** 相对今天偏移 offset 天的日期字符串。offset=0 就是今天。 */
    static String day(int offset) {
        return LocalDate.now(zone()).plusDays(offset).toString();
    }
}
