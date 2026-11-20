package com.timeapp.shell;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 从服务器拉某一天的行程。
 *
 * 服务器返回的 JSON 长这样：
 * {
 *   "day": "2026-11-19",
 *   "scheduled": [
 *     {"id":1, "name":"吃饭", "minutes":60, "priority":"高",
 *      "start":"17:00", "end":"18:00", "auto":true}
 *   ],
 *   "blocked": [ ... ]
 * }
 *
 * 我们只要 scheduled，而且只要 name / start / end 三样。
 * blocked（没排上的）不写进日历——它们没有确定时间，写了也没法提醒。
 */
final class PlanClient {

    private PlanClient() {}

    /** 一条已排好的事务。时间已经是 "HH:MM" 文本，不用再算。 */
    static final class Item {
        /** 服务器上的事务编号。**只在同一天之内唯一**——排闹钟时不能直接拿它当
         *  PendingIntent 的 requestCode，跨天会撞号，见 RingScheduler。 */
        final int id;
        final String name;
        /** "高" / "中" / "低"。高优先级的会在开始时刻响铃。 */
        final String priority;
        final String start;
        final String end;

        Item(int id, String name, String priority, String start, String end) {
            this.id = id;
            this.name = name;
            this.priority = priority;
            this.start = start;
            this.end = end;
        }

        boolean isHighPriority() {
            return "高".equals(priority);
        }
    }

    /**
     * 拉一天。失败抛 IOException——调用方据此决定"整轮放弃"。
     *
     * 注意这个方法会阻塞，**绝不能在主线程调**。它要么跑在
     * MainActivity 的后台线程池里，要么跑在 SyncJobService 的工作线程里。
     */
    static List<Item> fetchDay(Context ctx, String date) throws IOException {
        SharedPreferences p = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);

        String base = p.getString(MainActivity.KEY_URL, "").trim();
        while (base.endsWith("/")) {                 // 用户可能填成 http://x:8000/
            base = base.substring(0, base.length() - 1);
        }
        if (base.isEmpty()) {
            throw new IOException("还没配服务器地址");
        }

        HttpURLConnection c = (HttpURLConnection) new URL(base + "/api/plan?date=" + date).openConnection();
        try {
            // 超时不设的话默认是 0 = **永不超时**。手机掉出 WiFi 之后，
            // 这个连接会一直挂着，在后台任务里会白占配额和电量。
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setRequestMethod("GET");

            String cred = p.getString(MainActivity.KEY_USER, "")
                    + ":" + p.getString(MainActivity.KEY_PASS, "");

            // Base64.NO_WRAP 是**必须的**，不是可选的。
            // 默认的 Base64.DEFAULT 每 76 个字符插一个换行——HTTP 头里不允许换行，
            // 服务端收到一个截断的凭据，回 401。
            // 症状看起来就和"口令打错了"一模一样，能查很久。
            c.setRequestProperty("Authorization", "Basic " + Base64.encodeToString(
                    cred.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));

            int code = c.getResponseCode();
            // 非 2xx 的时候要读 errorStream，getInputStream() 会直接抛异常，
            // 那样就拿不到服务端返回的错误说明了。
            InputStream in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            String body = readAll(in);

            if (code != 200) {
                throw new IOException("HTTP " + code);
            }

            JSONObject root = new JSONObject(body);
            JSONArray arr = root.optJSONArray("scheduled");

            List<Item> out = new ArrayList<>();
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String s = o.optString("start", "");
                String e = o.optString("end", "");
                if (s.isEmpty() || e.isEmpty()) {
                    continue;      // 没有确定时间的不写日历
                }
                out.add(new Item(
                        o.optInt("id", -1),
                        o.optString("name", "（未命名）"),
                        o.optString("priority", ""),
                        s, e));
            }
            return out;

        } catch (org.json.JSONException ex) {
            // 服务器返回了非 JSON（比如登录页、网关错误页）。
            // 包成 IOException 让调用方的统一错误处理接住。
            throw new IOException("服务器返回的不是预期格式：" + ex.getMessage());
        } finally {
            c.disconnect();
        }
    }

    /** UTF-8 读全部内容。中文必须显式指定 UTF-8，否则会乱码。 */
    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
