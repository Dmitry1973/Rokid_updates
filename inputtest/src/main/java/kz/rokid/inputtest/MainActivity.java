package kz.rokid.inputtest;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * "Тест кнопок и кольца" 1.3: a guided wizard. It asks for one gesture at a
 * time, records every signal that arrives while that step is active and builds
 * a table "gesture → signal". On RV101 the temple sends keys only (no touch
 * events), so the wizard never needs touch to advance: steps move on by time.
 *
 * Exit: automatically after the last step, or Back three times within 2 s.
 * The report is saved as DCIM/Camera/InputTest_….png and Download/InputTest_….txt.
 *
 * No lambdas on purpose: the project must also build with plain javac + dx.
 */
public final class MainActivity extends Activity {
    private static final String TAG = "RokidInputTest";
    private static final long INTRO_MS = 5000L;
    private static final long WAIT_FIRST_MS = 8000L;
    private static final long COLLECT_AFTER_FIRST_MS = 2500L;
    private static final long AFTER_RESUME_MS = 1500L;

    static final String[] STEPS = {
            "ТАП по дужке (1 раз)",
            "ДВОЙНОЙ тап по дужке",
            "СВАЙП ВПЕРЁД по дужке",
            "СВАЙП НАЗАД по дужке",
            "ДЕРЖАТЬ палец на дужке 2 с",
            "Колесо кольца: 1 щелчок ВПЕРЁД",
            "Колесо кольца: 1 щелчок НАЗАД",
            "Нажать колесо кольца (ОК)",
            "Кнопка КАМЕРЫ на кольце, 1 раз\n(откроется системная камера)",
            "Кнопка НА ОЧКАХ, 1 раз\n(откроется системная камера)",
    };
    static final String[] SHORT = {
            "Тап", "2× тап", "Свайп вперёд", "Свайп назад", "Держать",
            "Колесо вперёд", "Колесо назад", "ОК кольца", "Камера кольца", "Кнопка очков",
    };

    private static final String[] BROADCASTS = {
            "com.android.action.ACTION_SPRITE_BUTTON_DOWN",
            "com.android.action.ACTION_SPRITE_BUTTON_UP",
            "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS",
            "com.android.action.ACTION_SPRITE_BUTTON_VERY_VERY_LONG_PRESS",
            "com.rokid.glass3.action.button.CLICK",
            "com.rokid.glass3.action.button.DOUBLE_CLICK",
            "com.rokid.glass3.action.button.LONG_PRESS",
    };

    private final Handler handler = new Handler();
    private final StringBuilder fullLog = new StringBuilder();
    private final StringBuilder[] results = new StringBuilder[STEPS.length];

    private LinearLayout panel;
    private TextView banner;
    private TextView stepView;
    private TextView timerView;
    private TextView tableView;
    private int step = -1;
    private long stepStartedAt;
    private long firstEventAt = -1L;
    private boolean exiting;
    private boolean finished;
    private boolean pausedBySystem;
    private long[] backTimes = {-10000L, -10000L, -10000L};
    private int backIndex;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (exiting) return;
            long now = SystemClock.elapsedRealtime();
            if (step < 0) {
                long left = INTRO_MS - (now - stepStartedAt);
                if (left <= 0) startStep(0);
                else timerView.setText("Начинаем через " + ((left + 999) / 1000) + " с");
            } else if (!finished) {
                long deadline = firstEventAt > 0 ? firstEventAt + COLLECT_AFTER_FIRST_MS
                        : stepStartedAt + WAIT_FIRST_MS;
                long left = deadline - now;
                if (left <= 0) {
                    if (results[step].length() == 0) results[step].append("ничего");
                    renderTable();
                    if (step + 1 < STEPS.length) startStep(step + 1);
                    else finishWizard();
                } else {
                    timerView.setText(firstEventAt > 0 ? "Принято, дальше через " + ((left + 999) / 1000) + " с"
                            : "Жду " + ((left + 999) / 1000) + " с…");
                }
            }
            handler.postDelayed(this, 200L);
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction() == null ? "?" : intent.getAction();
            String shortName = action.substring(action.lastIndexOf('.') + 1)
                    .replace("ACTION_SPRITE_BUTTON_", "BTN_");
            record("BC:" + shortName, "BC " + action);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        int green = Color.rgb(140, 255, 140);
        for (int i = 0; i < results.length; i++) results[i] = new StringBuilder();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.BLACK);
        panel.setPadding(dp(8), dp(6), dp(8), dp(4));
        // Content in the top ~80% of the HUD; the bottom edge is hard to see.
        root.addView(panel, new LinearLayout.LayoutParams(-1, 0, 4f));
        root.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1f));

        banner = new TextView(this);
        banner.setTextColor(Color.BLACK);
        banner.setBackgroundColor(green);
        banner.setTypeface(null, Typeface.BOLD);
        banner.setTextSize(12);
        banner.setGravity(Gravity.CENTER);
        banner.setPadding(dp(6), dp(3), dp(6), dp(3));
        banner.setText("Делайте то, что написано. Шаги идут сами.\nВыход: «Назад» 3 раза подряд");
        panel.addView(banner, new LinearLayout.LayoutParams(-1, -2));

        stepView = new TextView(this);
        stepView.setTextColor(Color.WHITE);
        stepView.setTypeface(null, Typeface.BOLD);
        stepView.setTextSize(17);
        stepView.setGravity(Gravity.CENTER);
        stepView.setPadding(0, dp(8), 0, dp(2));
        stepView.setMinLines(2);
        panel.addView(stepView, new LinearLayout.LayoutParams(-1, -2));

        timerView = new TextView(this);
        timerView.setTextColor(green);
        timerView.setTextSize(13);
        timerView.setGravity(Gravity.CENTER);
        timerView.setPadding(0, 0, 0, dp(6));
        panel.addView(timerView, new LinearLayout.LayoutParams(-1, -2));

        tableView = new TextView(this);
        tableView.setTextColor(green);
        tableView.setTypeface(Typeface.MONOSPACE);
        tableView.setTextSize(10.5f);
        panel.addView(tableView, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
        stepView.setText("Тест кнопок и кольца\nМастер из " + STEPS.length + " шагов");
        stepStartedAt = SystemClock.elapsedRealtime();
        renderTable();
        log("старт " + Build.MODEL + " / " + Build.DISPLAY);
        handler.post(tick);
    }

    @Override protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        for (String action : BROADCASTS) filter.addAction(action);
        registerReceiver(receiver, filter);
        if (pausedBySystem) {
            pausedBySystem = false;
            // Give the user a moment after returning from the system camera.
            if (step >= 0 && !finished && firstEventAt > 0) {
                firstEventAt = SystemClock.elapsedRealtime() - COLLECT_AFTER_FIRST_MS + AFTER_RESUME_MS;
            }
        }
    }

    @Override protected void onPause() {
        try { unregisterReceiver(receiver); } catch (RuntimeException ignored) {}
        if (!exiting && !isFinishing()) {
            pausedBySystem = true;
            record("СИСТЕМА", "app paused by the system");
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(tick);
        super.onDestroy();
    }

    private void startStep(int index) {
        step = index;
        stepStartedAt = SystemClock.elapsedRealtime();
        firstEventAt = -1L;
        stepView.setText("Шаг " + (index + 1) + "/" + STEPS.length + ":\n" + STEPS[index]);
        log("=== шаг " + (index + 1) + ": " + SHORT[index]);
        renderTable();
    }

    private void finishWizard() {
        finished = true;
        stepView.setText("Готово! Отчёт сохраняется.\nПришлите картинку InputTest");
        timerView.setText("");
        exitWithReport(4000L);
    }

    // ------------------------------------------------------------ input

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        String name = KeyEvent.keyCodeToString(code).replace("KEYCODE_", "");
        String raw = String.format(Locale.US, "KEY%s %s(%d) sc=%d r=%d %s",
                event.getAction() == KeyEvent.ACTION_DOWN ? "↓" : "↑", name, code,
                event.getScanCode(), event.getRepeatCount(), deviceName(event.getDevice()));
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            record(name + source(event.getDevice()), raw);
            if (code == KeyEvent.KEYCODE_BACK && tripleBack()) exitWithReport(0L);
        } else if (event.getAction() == KeyEvent.ACTION_DOWN && event.isLongPress()) {
            record(name + "-ДОЛГО" + source(event.getDevice()), raw);
        } else {
            log(raw);
        }
        return true;
    }

    private boolean tripleBack() {
        long now = SystemClock.elapsedRealtime();
        backTimes[backIndex % 3] = now;
        backIndex++;
        long oldest = Math.min(backTimes[0], Math.min(backTimes[1], backTimes[2]));
        return now - oldest < 2000L;
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            float v = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (v == 0f) v = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
            record("SCROLL" + (v > 0 ? "+" : "-") + source(event.getDevice()),
                    String.format(Locale.US, "GEN SCROLL %.1f %s", v, deviceName(event.getDevice())));
        } else {
            log("GEN " + MotionEvent.actionToString(event.getActionMasked()) + " " + deviceName(event.getDevice()));
        }
        return true;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) {
            record("TOUCH_" + (action == MotionEvent.ACTION_DOWN ? "DOWN" : "UP") + source(event.getDevice()),
                    String.format(Locale.US, "TOUCH %s x=%.0f y=%.0f %s", MotionEvent.actionToString(action),
                            event.getX(), event.getY(), deviceName(event.getDevice())));
        }
        return true;
    }

    /** Short tag for the table: "/д" = temple touchpad, "/к" = ring. */
    private static String source(InputDevice device) {
        if (device == null) return "";
        String n = device.getName();
        if (n == null) return "";
        if (n.contains("PSOC") || n.contains("TP")) return "/д";
        if (n.contains("RGR") || n.toLowerCase(Locale.US).contains("ring")) return "/к";
        return "/" + n;
    }

    private static String deviceName(InputDevice device) {
        return device == null ? "[?]" : "[" + device.getName() + "]";
    }

    // ------------------------------------------------------------ output

    private void record(String tag, String raw) {
        log(raw);
        if (step < 0 || finished) return;
        if (firstEventAt < 0) firstEventAt = SystemClock.elapsedRealtime();
        StringBuilder r = results[step];
        // Count repeats of the same tag: "ENTER/д×2".
        String text = r.toString();
        int at = (" " + text + " ").indexOf(" " + tag + " ");
        int atCount = text.indexOf(tag + "×");
        if (atCount >= 0 && (atCount == 0 || text.charAt(atCount - 1) == ' ')) {
            int end = atCount + tag.length() + 1;
            int stop = end;
            while (stop < text.length() && Character.isDigit(text.charAt(stop))) stop++;
            int n = Integer.parseInt(text.substring(end, stop)) + 1;
            r.replace(end, stop, String.valueOf(n));
        } else if (at >= 0) {
            r.insert(at + tag.length(), "×2");
        } else {
            if (r.length() > 0) r.append(' ');
            r.append(tag);
        }
        renderTable();
    }

    private void renderTable() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < STEPS.length; i++) {
            String mark = i == step && !finished ? "▶" : (results[i].length() > 0 ? "✓" : " ");
            sb.append(mark).append(String.format(Locale.US, "%-13s", SHORT[i]))
                    .append(results[i].length() > 0 ? results[i] : (i < step || finished ? "—" : ""));
            if (i < STEPS.length - 1) sb.append('\n');
        }
        sb.append("\n/д — дужка, /к — кольцо");
        tableView.setText(sb.toString());
    }

    private void log(String line) {
        Log.i(TAG, line);
        fullLog.append(new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()))
                .append(' ').append(line).append('\n');
    }

    // ------------------------------------------------------------ exit + report

    private void exitWithReport(long delayMs) {
        if (exiting) return;
        exiting = true;
        handler.removeCallbacks(tick);
        banner.setText("Тест кнопок и кольца — отчёт");
        renderTable();
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                saveSnapshot(stamp);
                saveTextLog(stamp);
                finish();
            }
        }, Math.max(200L, delayMs));
    }

    private void saveSnapshot(String stamp) {
        try {
            View v = panel;
            if (v.getWidth() == 0 || v.getHeight() == 0) return;
            Bitmap bitmap = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
            v.draw(new Canvas(bitmap));
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "InputTest_" + stamp + ".png");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return;
            try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out != null) bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            bitmap.recycle();
        } catch (Exception error) {
            Log.w(TAG, "Could not save snapshot", error);
        }
    }

    private void saveTextLog(String stamp) {
        try {
            StringBuilder report = new StringBuilder("Тест кнопок и кольца 1.3 ").append(stamp).append('\n');
            for (int i = 0; i < STEPS.length; i++) {
                report.append(SHORT[i]).append(": ").append(results[i].length() > 0 ? results[i] : "—").append('\n');
            }
            report.append("\nЖурнал:\n").append(fullLog);
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, "InputTest_" + stamp + ".txt");
            values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return;
            try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out != null) out.write(report.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception error) {
            Log.w(TAG, "Could not save text log", error);
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
