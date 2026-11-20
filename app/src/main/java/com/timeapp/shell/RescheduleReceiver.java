package com.timeapp.shell;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 系统事件之后把闹钟重新排一遍。
 *
 * 三个事件各自对应一个真实的失效场景：
 *
 *   BOOT_COMPLETED    **重启会清掉所有闹钟**（官方原文："all alarms are canceled
 *                     when a device shuts down"）。不重排的话，重启一次这个功能
 *                     就彻底没了，而且你不会知道。
 *
 *   TIMEZONE_CHANGED  改时区时，系统**不会**重算已经排好的 RTC 闹钟——它只发个广播。
 *                     不重排的话，闹钟会响在"同一个 UTC 瞬间、但错误的本地时间"上。
 *                     快照里存的是 "HH:MM" 而不是绝对毫秒，就是为了这一刻能算对。
 *
 *   TIME_SET          改系统时间。系统其实会按墙上时刻重算 RTC 闹钟（AOSP 源码级结论），
 *                     但重排一遍也无害，而且能顺手把已经过期的条目清掉。
 *
 * 【为什么这三个 action 能静态注册】
 * Android 8 起大部分隐式广播不允许静态注册（省电），但官方留了一份豁免名单，
 * 这三个都在名单里。别的（比如 SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED）
 * **不在**名单里——所以"用户刚授予精确闹钟权限"那件事不能靠广播兜底，
 * 得靠 MainActivity.onResume 复查。这条差点被当成生命线。
 *
 * 重排不需要联网：快照里存了日期和时刻，本地算毫秒即可。
 */
public class RescheduleReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            RingScheduler.rearmFromSnapshot(ctx);
        } catch (Exception e) {
            // onReceive 里抛异常 = 崩进程。重排失败不是什么大事，
            // 下次打开 App 还会再排一遍。
            TimeApp.recordCrash(ctx, e);
        }
    }
}
