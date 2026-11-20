package com.timeapp.shell;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 闹钟到点时被叫醒的地方。
 *
 * 【这个类只能干一件事：立刻启动前台服务。别的什么都不许做。】
 *
 * 原因是那个豁免窗口只有 **约 10 秒**（AOSP 里的 ALLOW_WHILE_IDLE_WHITELIST_DURATION）。
 * 精确闹钟触发时，系统临时允许这个 App 从后台启动前台服务——但只有这 10 秒。
 * 在这之前如果你先读了 SharedPreferences 拿事务名、先查了个数据库、
 * 甚至只是 postDelayed 了一下，窗口就可能耗掉，然后 startForegroundService()
 * 会抛 ForegroundServiceStartNotAllowedException。
 *
 * 所以事务名是通过 Intent extra 直接传进来的，这里拿到就用，不做任何 I/O。
 *
 * 【为什么必须 catch 住异常】
 * ForegroundServiceStartNotAllowedException 是 RuntimeException。不接住的话
 * 它会穿出 onReceive 把进程干掉——表现形式是"手机上静悄悄什么都没发生，
 * 但崩溃日志里有一条记录"。而闹钟已经消耗掉了，不会重试。
 *
 * 接到之后走兜底通知（自带声音的备用渠道），至少响一声。
 * 什么时候会走到这条路：没有精确闹钟权限、排的是不精确闹钟的时候。
 * AOSP 里非精确闹钟走 mOptsWithoutFgs，**根本不带这个豁免**。
 */
public class RingReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String name = intent.getStringExtra("name");
        if (name == null || name.isEmpty()) name = "高优先级事务";

        Intent svc = new Intent(ctx, RingService.class)
                .setAction(RingService.ACTION_START)
                .putExtra(RingService.EXTRA_NAME, name);

        try {
            // startForegroundService 是 API 26 新增的，本工程 minSdk 正好 26，
            // 所以不用判版本。但服务起来之后必须在几秒内调 startForeground()，
            // 否则系统会杀掉服务并抛 ForegroundServiceDidNotStartInTimeException
            // ——那是进程崩溃，不是 ANR 对话框。见 RingService。
            ctx.startForegroundService(svc);
        } catch (Exception e) {
            RingNotifications.postFallback(ctx, name);
            TimeApp.recordCrash(ctx, e);
        }
    }
}
