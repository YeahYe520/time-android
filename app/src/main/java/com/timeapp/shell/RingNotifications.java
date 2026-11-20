package com.timeapp.shell;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/**
 * 通知渠道 + 通知的构造。
 *
 * 【为什么要有两个渠道】
 *
 * 主渠道 ring_high **故意不设声音**：正常路径下声音是前台服务里的 MediaPlayer
 * 循环播放的。如果渠道本身也带声音，通知一贴出来渠道会先响一声，和循环叠在一起，
 * 听起来像卡了一下。
 *
 * 备用渠道 ring_fallback **自带闹铃音**：只在"前台服务起不来"的降级路径下用
 * （比如精确闹钟权限被撤销了，走的是不精确闹钟，那玩意没有后台启动前台服务的豁免）。
 * 那时候至少得响一声，不能彻底没动静。
 *
 * 【渠道的三条铁律】——都是官方文档明说的，踩了都不报错只是"改了没用"
 *
 *   1. 渠道建完之后，**行为改不了**（名字和描述能改，声音和重要度不能）。
 *   2. 同一个 id 再 createNotificationChannel 是 no-op；**删了重建只会"原样复活"**
 *      旧设置，你的新设置不生效。将来真要换声音或重要度，只能启用一个新 id
 *      （比如 ring_high_v2），不能指望改这个。
 *   3. targetSdk 26+ 没有渠道就 post 通知 → **通知直接不出现**，系统只在 logcat
 *      里写一行错误，界面上什么都看不出来。
 *
 * 所以这个文件里的内容是"一次写对"的，改动要慎重。
 */
final class RingNotifications {

    private RingNotifications() {}

    /** 主渠道：响铃时的那个通知。渠道静音，声音由 RingService 里的 MediaPlayer 放。 */
    static final String CH_RING = "ring_high";
    /** 备用渠道：降级路径用，自带闹铃音。 */
    static final String CH_FALLBACK = "ring_fallback";

    /** 响铃通知的 id。停止时要用同一个 id 撤掉。 */
    static final int ID_RING = 2001;
    /** 兜底通知的 id。 */
    static final int ID_FALLBACK = 2002;

    /**
     * 建渠道。在 Application.onCreate 里调一次即可——重复调是 no-op，无害。
     */
    static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;   // 26 以下没有渠道概念

        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;

        // ---- 主渠道 ----
        NotificationChannel ring = new NotificationChannel(
                CH_RING, "任务响铃", NotificationManager.IMPORTANCE_HIGH);
        ring.setDescription("高优先级任务开始时响铃");
        // 静音：声音由前台服务自己放，见类注释
        ring.setSound(null, null);
        // 免打扰：能在勿扰模式下也响。两个前提——用户给了"勿扰访问"权限，
        // 且这个渠道**用户还没动过**（动过之后再设就没用了）。
        if (nm.isNotificationPolicyAccessGranted()) {
            ring.setBypassDnd(true);
        }
        nm.createNotificationChannel(ring);

        // ---- 备用渠道 ----
        Uri sound = alarmSoundUri(ctx);
        NotificationChannel fallback = new NotificationChannel(
                CH_FALLBACK, "任务响铃（备用提醒）", NotificationManager.IMPORTANCE_HIGH);
        fallback.setDescription("精确响铃没生效时的兜底提醒");
        // 走闹钟流：音量键调的是闹钟音量，勿扰下也更可能放行
        fallback.setSound(sound, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        nm.createNotificationChannel(fallback);
    }

    /**
     * 取系统默认闹铃的声音。
     *
     * **链式判空是必须的**：getActualDefaultRingtoneUri 可能返回 null
     * （用户没设过默认闹铃），getValidRingtoneUri 是"随便给一个能用的"，
     * 但它**自己也可能是 null**（设备上一个铃声都没有）。最后那道
     * DEFAULT_ALARM_ALERT_URI 是个符号常量，才真的一定非 null。
     */
    static Uri alarmSoundUri(Context ctx) {
        Uri uri = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM);
        if (uri == null) uri = RingtoneManager.getValidRingtoneUri(ctx);
        if (uri == null) uri = Settings.System.DEFAULT_ALARM_ALERT_URI;
        return uri;
    }

    /** 响铃时贴在通知栏的那条。带一个"停止"按钮。 */
    static Notification buildRing(Context ctx, String taskName) {
        PendingIntent stop = PendingIntent.getService(
                ctx, 0,
                new Intent(ctx, RingService.class).setAction(RingService.ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 点通知本体 → 打开全屏的"停止"页（锁屏上也能弹，见 manifest 的 showWhenLocked）
        PendingIntent open = PendingIntent.getActivity(
                ctx, 0,
                new Intent(ctx, RingActivity.class)
                        .putExtra(RingActivity.EXTRA_NAME, taskName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(ctx, CH_RING)
                : new Notification.Builder(ctx);

        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(taskName)
                .setContentText("高优先级事务开始了")
                .setCategory(Notification.CATEGORY_ALARM)   // 归类成闹钟，系统的处理策略不一样
                .setOngoing(true)                            // 用户划不掉——划掉就找不到停止按钮了
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(
                        null, "停止", stop).build());

        // 划掉通知也要停（双保险）。setOngoing(true) 之后其实划不掉，
        // 但 Android 14+ 允许用户划掉前台服务的通知，所以这条不是多余的。
        b.setDeleteIntent(stop);

        return b.build();
    }

    /**
     * 降级路径：前台服务起不来时，贴一条**自带声音**的普通通知。
     *
     * 这一条存在的意义就是"至少响一声"——它不像前台服务那样能循环，
     * 但总比彻底没动静强。
     */
    static void postFallback(Context ctx, String taskName) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(ctx, CH_FALLBACK)
                : new Notification.Builder(ctx);

        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(taskName)
                .setContentText("高优先级事务开始了（近似提醒）")
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true);

        try {
            nm.notify(ID_FALLBACK, b.build());
        } catch (SecurityException ignored) {
            // Android 13+ 用户拒绝了通知权限时会走到这里。
            // 注意：这不是致命错误——通知看不见，但 App 其它部分照常。
        }
    }
}
