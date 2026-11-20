package com.timeapp.shell;

import android.app.Application;
import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * 崩溃记录器。
 *
 * 【为什么需要这个类】
 * App 在手机上闪退时，堆栈只写进 logcat。而看 logcat 要么插数据线开 USB 调试，
 * 要么在开发者选项里翻——对一个自己用的 App 来说，这两条路都太绕了。
 *
 * 这个类做的事：**任何线程上任何没被捕获的异常，都把堆栈写进一个文件。**
 * 下次打开 App 时，MainActivity 会读这个文件并把堆栈显示在屏幕上，
 * 截图就能看。看完自动删掉，不会每次都弹。
 *
 * 【为什么放在 Application 而不是 Activity】
 * Application.onCreate() 比任何 Activity 都早跑。崩溃如果发生在 MainActivity
 * 的 onCreate 里（启动闪退基本都是这个位置），把捕获器装在 Activity 里就来不及了
 * ——异常可能在安装捕获器之前就抛出来了。
 *
 * 【原理】
 * 每个线程都有一个"未捕获异常处理器"。默认那个会打印堆栈然后杀掉进程。
 * Thread.setDefaultUncaughtExceptionHandler 换掉的是**所有线程**的默认值。
 * 我们只是在自己被调用时先存一份堆栈，然后**把控制权交回给原来的处理器**——
 * 不吞掉异常，该崩还是崩，只是崩之前留了份遗书。
 */
public class TimeApp extends Application {

    /** 堆栈存哪。放在 App 私有目录里，别的 App 读不到。 */
    static final String CRASH_FILE = "last_crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();

        // 通知渠道要在**任何通知被贴出去之前**建好。
        // 放在 Application 里最保险——它是整个进程最早跑的用户代码。
        RingNotifications.ensureChannels(this);

        // 先把系统原来那个处理器记下来——最后要交还给它。
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            writeCrash(this, error);

            // 交还给原来的处理器，让系统的崩溃流程照常走。
            // **不能在这里吞掉异常**——吞了进程会停在一个不一致的状态里。
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    /**
     * 把一个异常记进崩溃日志。给"接住了但不该默默吞掉"的地方用。
     *
     * 典型的例子是 RingReceiver 里接住的 ForegroundServiceStartNotAllowedException：
     * 接住是为了不让它崩进程，但那件事本身值得知道——不然表现就是
     * "闹钟到点了但一声没响"，而你在手机上完全看不出原因。
     */
    static void recordCrash(Context ctx, Throwable error) {
        writeCrash(ctx, error);
    }

    /**
     * 把堆栈写进文件（下次打开 App 时会显示在屏幕上）。
     *
     * 整个过程包在 try 里：崩溃处理器自己再抛异常的话，就什么都记不下来了，
     * 那是双重失败——最需要日志的时候恰恰最没有日志。
     */
    private static void writeCrash(Context ctx, Throwable error) {
        try {
            StringWriter buffer = new StringWriter();
            error.printStackTrace(new PrintWriter(buffer));
            try (FileOutputStream out =
                         new FileOutputStream(new File(ctx.getFilesDir(), CRASH_FILE))) {
                out.write(buffer.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
            // 记不下来就算了，不能因为这个再崩一次
        }
    }
}
