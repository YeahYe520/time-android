package com.timeapp.shell;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 响铃时弹出来的"停止"页。
 *
 * 【为什么需要它】
 * 停止响铃有三条路，这条是**最后的保险**：
 *
 *   通知上的"停止"按钮   —— 最方便，但 Android 13+ 用户拒绝了通知权限就完全看不见
 *   划掉通知             —— 依赖 deleteIntent 生效（Android 14+ 允许划掉前台服务通知）
 *   这个页面             —— 只要屏幕能亮就一定能点，不依赖任何权限
 *
 * 所以这个类存在的意义就是"别的路都断了的时候，你还有办法让它闭嘴"。
 *
 * 【UI 全用代码搭，不写布局文件】
 * 这个页面总共就两行字加一个按钮，为它单独建一个 XML 不值当。
 * 而且它是个异常路径上的页面，代码放在一起反而好读。
 *
 * 【锁屏上显示】
 * manifest 里写了 showWhenLocked 和 turnScreenOn（都是 API 27+ 的属性，26 上被忽略）。
 * 为了覆盖 26，下面还设了两个对应的 window flag——虽然已废弃，但在旧系统上是
 * 唯一可行的办法。
 */
public class RingActivity extends Activity {

    static final String EXTRA_NAME = "name";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String name = getIntent().getStringExtra(EXTRA_NAME);
        if (name == null || name.isEmpty()) name = "高优先级事务";

        // API 26 上没有 manifest 那两个属性，用 window flag 顶上。
        // 废弃警告是意料之中的——这是为了兼容旧系统。
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#1C1B19"));
        int pad = dp(28);
        root.setPadding(pad, pad, pad, pad);

        TextView label = new TextView(this);
        label.setText("该做了");
        label.setTextColor(Color.parseColor("#9C9A94"));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setGravity(Gravity.CENTER);

        TextView title = new TextView(this);
        title.setText(name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(12), 0, dp(36));

        Button stop = new Button(this);
        stop.setText("停止响铃");
        stop.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        stop.setOnClickListener(v -> {
            // 把 ACTION_STOP 发给服务，而不是直接 finish()——
            // 光关掉这个页面铃声还在响，用户会以为按钮坏了。
            startService(new Intent(this, RingService.class)
                    .setAction(RingService.ACTION_STOP));
            finish();
        });

        root.addView(label);
        root.addView(title);
        root.addView(stop, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(64)));
        setContentView(root);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 返回键也当成"停止"——不然按了返回键铃声还在响，很困惑。 */
    @Override
    public void onBackPressed() {
        startService(new Intent(this, RingService.class).setAction(RingService.ACTION_STOP));
        super.onBackPressed();
    }

    /** 切走了就不用留着了。 */
    @Override
    protected void onPause() {
        super.onPause();
        if (!isChangingConfigurations()) finish();
    }
}
