package com.timeapp.shell;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

/**
 * 响铃的前台服务。真的把声音放出来，一直循环到用户按停。
 *
 * 【为什么必须是前台服务，不能直接在接收器里放音】
 * Android 17 起，后台放音/请求音频焦点/改音量，**必须有"可见的 Activity
 * 或一个非 SHORT_SERVICE 的前台服务"**，否则静默失效——不抛异常、不报错，
 * 就是没声音。前台服务是这个功能唯一的合法载体。
 *
 * 【onStartCommand 里的顺序是铁律，不能调换】
 *
 *   1. startForeground()   ← **立刻**，别做任何别的事
 *   2. requestAudioFocus()
 *   3. MediaPlayer 播放
 *
 * 反了会踩两个坑：
 *   - startForegroundService 等于向系统承诺"服务起来后会调 startForeground"。
 *     拖着不调，系统会杀掉服务并抛 ForegroundServiceDidNotStartInTimeException
 *     ——表现为**进程崩溃**，不是 ANR 对话框，很难联想到是这个原因。
 *   - Android 15+ 只有"顶层 App 或在前台服务里"才给音频焦点。顺序反了拿不到焦点。
 */
public class RingService extends Service {

    private static final String TAG = "RingService";

    static final String ACTION_START = "com.timeapp.shell.action.RING_START";
    static final String ACTION_STOP = "com.timeapp.shell.action.RING_STOP";
    static final String EXTRA_NAME = "name";

    /**
     * 响多久自动停。
     *
     * 这是个**安全阀**：万一通知被误划掉、deleteIntent 又没生效，
     * 铃声会一直响到你发现为止——那是很糟糕的体验，而且很费电。
     * 十分钟足够长了，真没注意到的话说明你不在手机旁边。
     */
    private static final long AUTO_STOP_MS = 10 * 60 * 1000L;

    private MediaPlayer player;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoStop = this::stopEverything;
    private String taskName = "高优先级事务";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopEverything();
            return START_NOT_STICKY;
        }

        if (intent != null) {
            String n = intent.getStringExtra(EXTRA_NAME);
            if (n != null && !n.isEmpty()) taskName = n;
        }

        // 已经在响了就别重来一遍——否则两次 ACTION_START 会叠出两个播放器，
        // 一个停了另一个还在响，而且找不到原因。
        if (player != null) return START_NOT_STICKY;

        startRinging();
        return START_NOT_STICKY;
    }

    private void startRinging() {
        // ---------- 1. 先变前台 ----------
        // 三参重载是 API 29 新增的。minSdk 26 直接调 = NoSuchMethodError，
        // 而那是 Error 不是 Exception，catch(Exception) 接不住。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(RingNotifications.ID_RING,
                    RingNotifications.buildRing(this, taskName),
                    // 这个类型必须和 manifest 里写的 foregroundServiceType 一致，
                    // 否则抛 IllegalArgumentException（"must be a subset flags of
                    // what is specified in manifest attribute"）
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(RingNotifications.ID_RING,
                    RingNotifications.buildRing(this, taskName));
        }

        // ---------- 2. 音频焦点 ----------
        audio = getSystemService(AudioManager.class);
        AudioAttributes attrs = new AudioAttributes.Builder()
                // USAGE_ALARM 是关键：走闹钟音量（音量键调的是它），
                // 勿扰模式下也更容易放行。Android 17 的 while-in-use 要求
                // 对"精确闹钟 + USAGE_ALARM"是开着的——正是我们这个形态。
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        if (audio != null) {
            // GAIN_TRANSIENT 而不是 MAY_DUCK：
            // 闹铃的语义是"打断，请你先别放了"，不是"我小声插一句你继续"。
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(change -> {
                        if (change == AudioManager.AUDIOFOCUS_LOSS) stopEverything();
                    })
                    .build();
            // 拿不到焦点也照响，不抛异常——所以不检查返回值
            audio.requestAudioFocus(focusRequest);

            // 闹钟音量是 0 的话，播了也一点声没有，而且界面上完全看不出来。
            // 先拉满。没有运行时权限，但"会切换勿扰的调整"在没有勿扰访问权限时
            // 会抛 SecurityException，所以整段包住。
            try {
                if (audio.getStreamVolume(AudioManager.STREAM_ALARM) == 0) {
                    audio.setStreamVolume(AudioManager.STREAM_ALARM,
                            audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
                }
            } catch (SecurityException ignored) {
            }
        }

        // ---------- 3. 播放 ----------
        Uri sound = RingNotifications.alarmSoundUri(this);
        if (sound == null) {
            Log.w(TAG, "设备上一个铃声都没有，没法响");
            stopEverything();
            return;
        }

        try {
            // 用 new MediaPlayer() 而不是 MediaPlayer.create()：
            // create 内部已经调过 prepare()，之后再改音频属性就不生效了。
            player = new MediaPlayer();
            player.setAudioAttributes(attrs);       // 必须在 prepare() 之前
            player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);   // 需要 WAKE_LOCK 权限
            player.setLooping(true);                // 一直响到用户按停，永远不触发 onCompletion
            player.setDataSource(this, sound);
            player.prepare();                       // 同步阻塞，会抛 IOException
            player.start();
            handler.postDelayed(autoStop, AUTO_STOP_MS);
        } catch (Exception e) {
            Log.w(TAG, "放音失败", e);
            TimeApp.recordCrash(this, e);
            RingNotifications.postFallback(this, taskName);   // 至少响一声
            stopEverything();
        }
    }

    /**
     * 停止。三条路都收敛到这里：
     *   通知上的"停止"按钮 / 用户划掉通知 / RingActivity 里的大按钮
     * 外加 10 分钟的自动超时。
     */
    private void stopEverything() {
        handler.removeCallbacks(autoStop);

        if (player != null) {
            try {
                player.stop();
            } catch (IllegalStateException ignored) {
                // 没在播的时候 stop() 会抛这个，无害
            }
            player.release();
            player = null;
        }
        if (audio != null && focusRequest != null) {
            audio.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);   // 撤掉前台通知
        stopSelf();
    }

    @Override
    public void onDestroy() {
        // 兜一遍。走 stopEverything 的正常路径时这里已经是空的；
        // 但如果服务是被 stopService 直接干掉的，这里就是唯一的清理机会——
        // 不清理的话播放器会漏，铃声会一直响下去。
        handler.removeCallbacks(autoStop);
        if (player != null) {
            try {
                player.stop();
            } catch (IllegalStateException ignored) {
            }
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    /** 不提供绑定——这个服务只用来放音，没有别的用途。 */
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
