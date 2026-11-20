package com.timeapp.shell;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.webkit.HttpAuthHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 整个 App 就这一个界面。
 *
 * 它干的事：开一个全屏 WebView，加载你电脑上跑的那个 Flask 服务。
 * **没有地址栏，没有标签页，没有前进后退按钮**——这就是它跟浏览器的全部区别，
 * 也是"看起来像个 App"的来源。
 *
 * 排程逻辑一行都不在这儿，全在电脑上的 plan.py 里。这个 App 只是个窗口。
 */
public class MainActivity extends Activity {

    /** logcat 里的标签，方便过滤。 */
    private static final String TAG = "MainActivity";

    // SharedPreferences = 安卓自带的键值对存储，App 私有，别的 App 读不到。
    // 名字随便起，这里是 "time-shell"。
    //
    // 这几个常量**故意不加 private**：PlanClient 和 PlanSync 也要用同一份，
    // 各写各的字符串迟早会打错一个字母，然后表现为"读不到地址"这种莫名其妙的故障。
    static final String PREFS = "time-shell";
    static final String KEY_URL = "url";
    static final String KEY_USER = "user";
    static final String KEY_PASS = "pass";
    /** 上次同步成功的时间戳。用来做节流，别一旋屏就往服务器打一次。 */
    static final String KEY_LAST_SYNC = "cal_last_sync";

    /** 记录"到底问没问过日历权限"。原因见 ensureCalendarPermission 的注释。 */
    private static final String KEY_CAL_ASKED = "cal_asked";

    /** 权限回调的请求码。随便取，只要跟别的请求不撞。 */
    private static final int REQ_CALENDAR = 1001;
    private static final int REQ_NOTIFY = 1002;

    /** 两次同步之间的最小间隔。10 分钟。 */
    private static final long SYNC_THROTTLE_MS = 10L * 60 * 1000;

    /** 第一次打开时预填的地址，省得在手机上敲一长串。 */
    private static final String DEFAULT_URL = "http://192.168.1.100:8000";

    private WebView web;
    private View settingsPanel;
    private EditText urlBox, userBox, passBox;
    private TextView hint;
    private SharedPreferences prefs;

    /**
     * 有没有一个权限请求正在等回调。
     *
     * 系统同一时间只允许一个权限请求在飞。第二个请求不但不弹框，
     * 还会**立刻回调一个空数组**。不加这个标记的话，连续触发两次
     * 就会把第二次的空回调误当成"用户拒绝了"。
     */
    private boolean permRequestInFlight;

    /**
     * 同步用的后台线程池。单线程——同步之间没必要并发，
     * 而且串行能天然避免"两次同步同时改日历"。
     */
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        web = findViewById(R.id.web);
        settingsPanel = findViewById(R.id.settings);
        urlBox = findViewById(R.id.url);
        userBox = findViewById(R.id.user);
        passBox = findViewById(R.id.pass);
        hint = findViewById(R.id.hint);

        // 上次启动如果崩过，这次**只显示那段堆栈**，然后直接返回。
        //
        // 为什么不显示完继续正常启动：因为如果崩溃原因还在，
        // 继续走下去会在同一个地方再崩一次——用户只能看到一个闪一下就没的画面，
        // 根本来不及看内容。停在这里，App 就稳稳地开着，堆栈能看清、能截图。
        if (showLastCrashIfAny()) {
            return;
        }

        WebSettings s = web.getSettings();
        // 你的页面靠 JS 拉数据、渲染列表，必须开。WebView 默认是关的。
        s.setJavaScriptEnabled(true);
        // 页面里用没用到 localStorage 另说，开了不亏——有些库会偷偷用
        s.setDomStorageEnabled(true);

        web.setWebViewClient(new WebViewClient() {

            /**
             * 服务器返回 401 时走这里。
             *
             * 不重写这个方法的话，WebView 会弹一个**系统自带的**账号密码框，
             * 又丑又要每次输。我们改成从存好的口令里直接填。
             */
            @Override
            public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler,
                                                  String host, String realm) {
                String pass = prefs.getString(KEY_PASS, "");
                if (pass.isEmpty()) {
                    // 没存口令就别瞎试，直接把人引到设置页
                    handler.cancel();
                    showSettings("这台服务器要口令，填在下面");
                    return;
                }
                handler.proceed(prefs.getString(KEY_USER, ""), pass);
            }

            /**
             * 服务器回了 4xx/5xx 走这里。**专门把 401 挑出来**——
             * 这是这个壳里最容易让人查错方向的一处。
             *
             * 不重写这个方法的话，口令打错和"服务器没开"在用户眼里**长得
             * 一模一样**：主文档加载失败，最后都会走到下面的 onReceivedError，
             * 弹出一句"连不上 X，检查地址、WiFi，以及服务器有没有在跑"。
             * 于是用户去查 WiFi（好的）、去看服务（在跑），
             * **永远想不到问题出在口令上**。
             *
             * 不看 HTTP 状态码，就分不清这两件事。
             */
            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse errorResponse) {
                if (request.isForMainFrame()
                        && errorResponse.getStatusCode() == 401) {
                    showSettings("口令不对（用户名随便填，口令是 .env 里的 APP_PASSWORD）");
                    // showSettings 会把存着的旧口令回填进去，这里再清掉——
                    // 错的口令留在框里只会让人反复点「连接」
                    passBox.setText("");
                }
            }

            /**
             * 真正的连接类失败（地址写错、服务器没起、不在同一个网）走这里。
             *
             * 只处理**主页面**的失败。图片、接口请求挂掉不该弹设置页——
             * 那种情况页面本身是好的，用户看到的是页面里的错误提示。
             */
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) {
                    showSettings("连不上 " + request.getUrl().getHost()
                            + "，检查地址、WiFi，以及服务器有没有在跑");
                }
            }

            /**
             * 页面加载完成后清掉浏览历史。
             *
             * 不清的话返回键会失效：设置面板里每点一次「连接」就调一次
             * loadUrl，而 WebView 对每一次导航都新建一条历史记录——
             * **哪怕 URL 完全一样**。点过两次之后 canGoBack() 就恒为真，
             * 返回键从此变成"把同一个页面重新加载一遍"，永远退不出 App；
             * 而且每次都会把 viewDay 重置回今天、AI 框里没提交的字也没了。
             *
             * 顺带一提：这个页面本身不产生历史记录（翻日期走的是 JS 状态，
             * 不是导航），所以历史里攒下的**全都是**「连接」留下的重复项。
             */
            @Override
            public void onPageFinished(WebView view, String url) {
                view.clearHistory();
            }
        });

        findViewById(R.id.gear).setOnClickListener(v -> showSettings(null));
        findViewById(R.id.save).setOnClickListener(v -> save());

        String url = prefs.getString(KEY_URL, "");
        if (url.isEmpty()) {
            showSettings("第一次用，先确认服务器地址");
        } else {
            web.loadUrl(url);
            // 地址配好了，顺便把日历权限问一下、然后同步一次。
            // 地址还没配的（第一次启动）先不问——用户连服务器都还没连上，
            // 这时候弹个日历权限框只会让人莫名其妙。
            safeStartCalendar();
        }

        // 注册后台任务。同一个 id 重复注册是覆盖，所以放 onCreate 里是安全的。
        safeScheduleSync();

        // 把闹钟按快照重排一遍（重启或强制停止会清掉它们）。
        safeStartRinging();
    }

    /**
     * 日历同步是**增强功能**，它坏掉不该让整个 App 打不开。
     *
     * 网页那部分（连服务器、看行程）才是主干。所以启动路径上这两处
     * 都包一层 catch——出问题就降级成"日历不工作"，而不是"App 打不开"。
     *
     * 注意捕的是 Throwable 不是 Exception：类加载失败（NoClassDefFoundError）、
     * 链接失败（VerifyError）这类是 Error，不继承 Exception。
     * 它们同样是"某个功能用不了"，同样不该把 App 带走。
     */
    private void safeStartCalendar() {
        try {
            ensureCalendarPermission();
        } catch (Throwable t) {
            Log.w(TAG, "启动日历同步失败（不影响网页）", t);
        }
    }

    private void safeScheduleSync() {
        try {
            SyncScheduler.schedule(this);
        } catch (Throwable t) {
            Log.w(TAG, "注册后台同步失败（不影响网页）", t);
        }
    }

    /**
     * 响铃相关的启动检查。和日历一样，**坏掉不该让整个 App 打不开**。
     *
     * 做两件事：
     *   1. 按快照把闹钟重排一遍。重启会清掉所有闹钟，App 被强制停止过也会
     *      ——打开 App 就恢复。这一步不需要联网，快照里存了日期和时刻。
     *   2. 精确闹钟权限没了的话提示一句（正常情况下不该发生：
     *      Android 13+ 用的 USE_EXACT_ALARM 是安装即授予、用户撤不掉的）。
     */
    private void safeStartRinging() {
        try {
            RingScheduler.rearmFromSnapshot(this);
            if (!RingScheduler.canUseExactAlarms(this)) {
                toast("精确响铃没开，提醒时间可能不准");
            }
        } catch (Throwable t) {
            Log.w(TAG, "重排闹钟失败（不影响其它功能）", t);
        }
    }

    /**
     * 申请通知权限。Android 13+ 才需要，13 以下直接返回。
     *
     * 为什么单独抽出来、而不是在 onCreate 里直接调：
     * **系统同一时间只允许一个权限请求在飞**，第二个请求不但不弹框，
     * 还会立刻回调一个空数组。如果日历和通知两个请求挨着发，
     * 通知那个大概率会被丢掉，用户永远看不到这个框。
     * 所以这个方法是"等日历那边问完了再问"。
     */
    private void requestNotifyPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return;      // 33 以下没这个权限，声明了就有
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        if (permRequestInFlight) return;             // 已经有一个在飞了
        permRequestInFlight = true;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
    }

    /**
     * 上次崩溃如果留了记录，把它显示出来，返回 true。
     *
     * 配套的是 TimeApp 里那个未捕获异常处理器——它负责"崩之前存一份"，
     * 这里负责"下次打开时给你看"。看完就把文件删掉，不会每次都弹。
     *
     * 返回 true 时调用方会**直接结束 onCreate**：如果崩溃原因还在，
     * 继续往下走会在同一个地方再崩一次，用户只能看到一个闪一下就没的画面，
     * 根本来不及看堆栈。停在这里反而能把错误看清楚。
     */
    private boolean showLastCrashIfAny() {
        File f = new File(getFilesDir(), TimeApp.CRASH_FILE);
        if (!f.exists()) return false;

        String text;
        try {
            byte[] buf = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int n = in.read(buf);
                text = new String(buf, 0, Math.max(n, 0), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();     // 只显示一次
        }

        String html = "<html><head><meta name='viewport' "
                + "content='width=device-width,initial-scale=1'></head>"
                + "<body style='font:12px monospace;padding:14px;line-height:1.5;"
                + "white-space:pre-wrap;word-break:break-all;background:#fff8f8'>"
                + "<h3 style='margin:0 0 10px'>上次打开时崩了</h3>"
                + "<div style='color:#666;margin-bottom:12px'>下面这段就是原因，"
                + "截图发给修的人。关掉重开 App 就会恢复正常。</div>"
                + TextUtils.htmlEncode(text)
                + "</body></html>";

        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
        settingsPanel.setVisibility(View.GONE);
        return true;
    }

    /** 显示设置面板。message 非空时在下面显示一行提示。 */
    private void showSettings(String message) {
        urlBox.setText(prefs.getString(KEY_URL, DEFAULT_URL));
        // 用户名随便填——服务端只认口令，用户名是什么都行
        userBox.setText(prefs.getString(KEY_USER, "time"));
        passBox.setText(prefs.getString(KEY_PASS, ""));

        if (message == null) {
            hint.setVisibility(View.GONE);
        } else {
            hint.setText(message);
            hint.setVisibility(View.VISIBLE);
        }
        settingsPanel.setVisibility(View.VISIBLE);
    }

    private void save() {
        String url = urlBox.getText().toString().trim();
        if (url.isEmpty()) {
            toast("地址不能空");
            return;
        }
        // 补上协议头。人在手机上多半只输 "192.168.1.100:8000"，
        // 而 WebView 拿到没有协议的字符串会当成搜索词，加载出一张搜索结果页。
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }

        prefs.edit()
                .putString(KEY_URL, url)
                .putString(KEY_USER, userBox.getText().toString().trim())
                // trim() 不是随手加的：上面 URL 和用户名都 trim 了，就这一行漏了。
                // 手机上粘贴口令时很容易带进首尾空格或换行，而服务端读 .env 时
                // （config.py 里的 value.strip()）**一定**是去过空白的——
                // 所以带空白的口令永远匹配不上。
                // 而口令框是圆点显示，你看不出里面多了个空格，
                // 只会反复怀疑自己记错了口令。
                //
                // 去掉首尾空白是安全的：服务端那边既然 strip 过，
                // 合法口令就不可能以空白开头或结尾。
                .putString(KEY_PASS, passBox.getText().toString().trim())
                .apply();

        settingsPanel.setVisibility(View.GONE);
        web.loadUrl(url);

        // 地址或口令改了，说明用户可能刚从"连不上"里恢复过来。
        // 这里也走一遍日历那条路——没权限就问，有权限就同步。
        ensureCalendarPermission();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    // ==================== 日历同步 ====================
    //
    // 这一整块是"增强功能"：把服务器上排好的行程写进手机日历，
    // 让系统日历到点弹提醒。**用户拒绝权限就当它不存在**，
    // 网页该怎么用还怎么用——不弹全屏、不挡路、不反复纠缠。

    /** 两个日历权限都拿到了才算数。 */
    private boolean hasCalendarPermission() {
        return checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 权限的总入口：有权限就同步，没权限就按情况决定要不要问。
     *
     * **这里有个很容易写错的地方：** 判断"是不是被永久拒绝了"不能只看
     * shouldShowRequestPermissionRationale()。它在"从来没问过"的时候
     * **也是 false**。只看它的话，新用户第一次打开 App 就会被直接支到设置页，
     * 而那个权限框本来是可以正常弹出来的。
     *
     * 所以要自己用 prefs 记一个"问过没有"：
     *     没问过            → 弹框
     *     问过但还能弹      → 弹框（用户上次拒了，但没勾"不再询问"）
     *     问过且系统不让弹了 → 才算永久拒绝，只提示一句，不弹
     *
     * （还有第三种情况：App 几个月没被打开，系统会自动撤销权限并清掉
     *  "不再询问"的标记，之后就又能弹了。所以"永久拒绝"这条路必须留着，
     *   不能靠它兜底。）
     */
    private void ensureCalendarPermission() {
        if (hasCalendarPermission()) {
            syncNow(true);
            requestNotifyPermissionIfNeeded();
            return;
        }

        boolean asked = prefs.getBoolean(KEY_CAL_ASKED, false);
        boolean canStillAsk = shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR)
                || shouldShowRequestPermissionRationale(Manifest.permission.WRITE_CALENDAR);

        if (!asked || canStillAsk) {
            requestCalendarPermission();
        } else {
            // 永久拒绝了。只提示一句，不弹全屏页、不挡着网页。
            toast("日历权限被关掉了，日程暂时不会写进系统日历");
        }
    }

    /** 弹权限框。**只能在主线程、Activity 在前台的时候调。** */
    private void requestCalendarPermission() {
        if (permRequestInFlight) return;   // 已经有一个在飞了，再发会被系统丢掉
        permRequestInFlight = true;
        prefs.edit().putBoolean(KEY_CAL_ASKED, true).apply();
        requestPermissions(new String[]{
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR
        }, REQ_CALENDAR);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        // 通知权限的回调：给不给都无所谓。
        // 拒绝的后果只是"通知栏上看不到停止按钮"，铃声照响，
        // 而且锁屏上还有 RingActivity 那个兜底入口。这里不用提示用户。
        if (requestCode == REQ_NOTIFY) {
            permRequestInFlight = false;
            return;
        }
        if (requestCode != REQ_CALENDAR) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }
        permRequestInFlight = false;

        // 空数组 = 这次交互被取消了（比如正好有别的权限框在弹），
        // **不等于用户拒绝**。当成什么都没发生。
        if (grantResults.length == 0) return;

        // 按**权限名**逐个对，别只看 grantResults[0]。
        // 用户可以只给 READ 不给 WRITE，两个数组是平行的但顺序不保证。
        boolean readOk = false, writeOk = false;
        for (int i = 0; i < permissions.length && i < grantResults.length; i++) {
            boolean granted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
            if (Manifest.permission.READ_CALENDAR.equals(permissions[i])) readOk = granted;
            if (Manifest.permission.WRITE_CALENDAR.equals(permissions[i])) writeOk = granted;
        }

        if (readOk && writeOk) {
            syncNow(true);
        } else {
            // 降级：功能静默缺席，别的照常。
            toast("没给日历权限，日程不会同步到系统日历");
        }
        // 日历这个框关掉了，轮到通知那个（顺序不能反，见 requestNotifyPermissionIfNeeded）
        requestNotifyPermissionIfNeeded();
    }

    /**
     * 触发一次同步。**必须从主线程调**（内部会碰 prefs 和 UI）。
     *
     * 真正的网络和数据库操作全丢到 bg 线程里——主线程发 HTTP 会崩。
     *
     * @param showSuccess 成功时要不要弹提示。手动触发（点权限、改设置）要弹，
     *                    被动触发（切回前台）不弹，否则每次切回来都弹一下很烦。
     */
    private void syncNow(boolean showSuccess) {
        if (!hasCalendarPermission()) return;

        // 节流：10 分钟内同步过就不再同步。
        // 不加这个的话，旋屏、切后台再回来、改个设置，都会打一次服务器。
        long last = prefs.getLong(KEY_LAST_SYNC, 0);
        if (System.currentTimeMillis() - last < SYNC_THROTTLE_MS) return;

        bg.execute(() -> {
            final PlanSync.Result r = PlanSync.run(this);
            runOnUiThread(() -> {
                if (r.ok) {
                    if (showSuccess) toast(r.message);
                } else {
                    toast("日历同步失败：" + r.message);
                }
            });
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 用户可能刚从系统设置里把权限打开，或者刚把手机关历里的「日程」取消勾选。
        // 轻量重查一次。注意**不要**在这里 reload 网页——那会把用户正在做的事冲掉。
        if (hasCalendarPermission()) syncNow(false);

        // 闹钟权限的复查**必须放在这里**，不能靠广播。
        // 官方的 SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED 广播
        // 不在"隐式广播豁免名单"里，后台静态接收器根本收不到——
        // 要是把它当生命线，用户授权回来之后功能会一直不生效。
        safeStartRinging();
    }

    @Override
    protected void onDestroy() {
        bg.shutdownNow();
        super.onDestroy();
    }

    /** 跳到本 App 的系统设置页，让用户手动开权限。只在用户主动点的时候调。 */
    private void openAppSettings() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        // 官方要求的格式就是这个 "package:包名"
        i.setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("这台机器没有应用设置页");
        }
    }

    /**
     * 返回键：设置面板开着就先关面板，网页能后退就先后退，
     * 都不行才真的退出 App。不写这个的话，网页里翻了两页，
     * 一按返回键 App 直接没了。
     */
    @Override
    public void onBackPressed() {
        if (settingsPanel.getVisibility() == View.VISIBLE) {
            settingsPanel.setVisibility(View.GONE);

            // **藏完面板要检查底下有没有东西。**
            //
            // 第一次装完打开 App 时，onCreate 只调了 showSettings()，
            // 从来没调过 loadUrl —— 底下是个空的 WebView。
            // 这时候按返回键（或者 Android 13+ 的侧滑返回，很容易误触），
            // 面板一藏就露出**一整屏纯白**，只剩右上角那行 40% 透明的「设置」。
            // 而且没有任何代码路径会把页面加载回来，除非用户自己再点一次设置。
            //
            // 所以这里补一下：没加载过页面、但地址是配好的，就顺手连一次。
            if (web.getUrl() == null) {
                String url = prefs.getString(KEY_URL, "");
                if (!url.isEmpty()) {
                    web.loadUrl(url);
                    safeStartCalendar();
                } else {
                    // 地址都还没配，藏了面板就真的什么都没有了 —— 把面板放回去
                    showSettings("第一次用，先确认服务器地址");
                }
            }
            return;
        }
        if (web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }
}
