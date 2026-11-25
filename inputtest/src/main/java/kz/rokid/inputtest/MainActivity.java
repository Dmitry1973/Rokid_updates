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
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * "Тест кнопок и кольца": shows, in plain Russian, which input signals reach an
 * app on the glasses. A checklist marks every kind of signal that arrived; the
 * raw log is underneath. Everything sits in the upper part of the HUD, which is
 * the part that is easy to read.
 *
 * Exit: hold a finger on the temple for 3 s (Back is only recorded). On exit a picture
 * of the results is saved to DCIM/Camera (it syncs to the phone with the photos)
 * and the full log to Download/.
 *
 * No lambdas on purpose: the project must also build with plain javac + dx.
 */
public final class MainActivity extends Activity {
    private static final String TAG = "RokidInputTest";
    private static final int LOG_LINES = 4;
    private static final long HOLD_MS = 1000L;
    private static final long EXIT_HOLD_MS = 3000L;
    private static final long DOUBLE_TAP_MS = 350L;
    private static final long TAP_MAX_MS = 450L;

    static final int C_TAP = 0;
    static final int C_DOUBLE = 1;
    static final int C_HOLD = 2;
    static final int C_SWIPE_FWD = 3;
    static final int C_SWIPE_BACK = 4;
    static final int C_SWIPE_VERT = 5;
    static final int C_WHEEL = 6;
    static final int C_OK = 7;
    static final int C_CAMERA = 8;
    static final int C_BROADCAST = 9;
    static final int C_LOST = 10;
    static final int C_BACK = 11;
    static final int C_DUPLICATE = 12;
    static final String[] CHECK_NAMES = {
            "Тап по дужке",
            "Двойной тап",
            "Удержание пальца 1 с",
            "Свайп вперёд (→)",
            "Свайп назад (←)",
            "Свайп вверх/вниз",
            "Колесо / стрелки",
            "Клавиша ОК (Enter)",
            "Клавиша камеры",
            "Сигнал кнопки очков",
            "Сработала системная камера",
            "Системный «Назад»",
            "Свайп = касание + клавиша",
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
    private final ArrayDeque<String> logLines = new ArrayDeque<>();
    private final StringBuilder fullLog = new StringBuilder();
    private final int[] counts = new int[CHECK_NAMES.length];
    private final String[] details = new String[CHECK_NAMES.length];

    private LinearLayout panel;
    private TextView banner;
    private TextView lastView;
    private TextView checksView;
    private TextView logView;
    private int counter;
    private boolean exiting;
    private boolean pausedBySystem;

    private float downX;
    private float downY;
    private long downAt;
    private boolean moved;
    private boolean holdFired;
    private boolean tapPending;
    private boolean touching;
    private long touchUpAt = -100000L;
    private long lastMoveLogAt;
    private String touchDevice = "";

    private final Runnable singleTap = new Runnable() {
        @Override public void run() {
            tapPending = false;
            mark(C_TAP, "ТАП по дужке", "TOUCH " + touchDevice);
        }
    };

    private final Runnable holdTick = new Runnable() {
        @Override public void run() {
            long held = SystemClock.elapsedRealtime() - downAt;
            if (!holdFired && held >= HOLD_MS) {
                holdFired = true;
                mark(C_HOLD, null, "HOLD " + touchDevice);
            }
            if (held >= EXIT_HOLD_MS) {
                exitWithReport();
                return;
            }
            if (held >= HOLD_MS) {
                long left = (EXIT_HOLD_MS - held + 999L) / 1000L;
                showLast("УДЕРЖАНИЕ. Держите ещё " + left + " с — ВЫХОД\n(отпустите, чтобы остаться)");
            }
            handler.postDelayed(this, 200L);
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction() == null ? "?" : intent.getAction();
            StringBuilder extras = new StringBuilder();
            if (intent.getExtras() != null) {
                for (String key : intent.getExtras().keySet()) {
                    extras.append(' ').append(key).append('=').append(intent.getExtras().get(key));
                }
            }
            String shortName = action.substring(action.lastIndexOf('.') + 1);
            mark(C_BROADCAST, "Кнопка очков: " + shortName, "BC " + shortName + extras);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        int green = Color.rgb(140, 255, 140);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        // Content lives in the top ~75% of the HUD; the bottom edge is hard to see.
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.BLACK);
        panel.setPadding(dp(8), dp(6), dp(8), dp(4));
        root.addView(panel, new LinearLayout.LayoutParams(-1, 0, 3f));
        root.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1f));

        banner = new TextView(this);
        banner.setTextColor(Color.BLACK);
        banner.setBackgroundColor(green);
        banner.setTypeface(null, Typeface.BOLD);
        banner.setTextSize(13);
        banner.setGravity(Gravity.CENTER);
        banner.setPadding(dp(6), dp(3), dp(6), dp(3));
        banner.setText("ВЫХОД: держите палец на дужке 3 с\n(«Назад» и 2× тап НЕ выходят)");
        panel.addView(banner, new LinearLayout.LayoutParams(-1, -2));

        lastView = new TextView(this);
        lastView.setTextColor(Color.WHITE);
        lastView.setTypeface(null, Typeface.BOLD);
        lastView.setTextSize(16);
        lastView.setGravity(Gravity.CENTER);
        lastView.setPadding(0, dp(6), 0, dp(6));
        lastView.setMinLines(2);
        panel.addView(lastView, new LinearLayout.LayoutParams(-1, -2));

        checksView = new TextView(this);
        checksView.setTextColor(green);
        checksView.setTypeface(Typeface.MONOSPACE);
        checksView.setTextSize(11f);
        panel.addView(checksView, new LinearLayout.LayoutParams(-1, -2));

        logView = new TextView(this);
        logView.setTextColor(Color.rgb(90, 170, 90));
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(9.5f);
        logView.setPadding(0, dp(4), 0, 0);
        panel.addView(logView, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
        showLast("Делайте действия по одному.\n✓ = сигнал дошёл до приложения");
        renderChecks();
        log("старт " + Build.MODEL + " / " + Build.DISPLAY);
    }

    @Override protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        for (String action : BROADCASTS) filter.addAction(action);
        registerReceiver(receiver, filter);
        if (pausedBySystem) {
            pausedBySystem = false;
            showLast("Система открывала свой экран\n(скорее всего, камеру). Вернулись.");
        }
    }

    @Override protected void onPause() {
        try { unregisterReceiver(receiver); } catch (RuntimeException ignored) {}
        cancelTouch();
        if (!exiting && !isFinishing()) {
            pausedBySystem = true;
            mark(C_LOST, null, "приложение свернула система (onPause)");
        }
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        log(hasFocus ? "фокус вернулся" : "фокус потерян");
    }

    // ------------------------------------------------------------ keys

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        String name = KeyEvent.keyCodeToString(code).replace("KEYCODE_", "");
        String dev = device(event.getDevice());
        String raw = String.format(Locale.US, "KEY%s %s(%d) sc=%d r=%d %s", down ? "↓" : "↑",
                name, code, event.getScanCode(), event.getRepeatCount(), dev);
        if (code == KeyEvent.KEYCODE_BACK) {
            // Not an exit: on Rokid the system may send Back for a double tap,
            // and we want to see that. Exit is only the 3 s hold.
            if (down && event.getRepeatCount() == 0) mark(C_BACK, "Системный «НАЗАД»\n" + dev, raw);
            else log(raw);
            return true;
        }
        if (!down || event.getRepeatCount() > 0) {
            log(raw);
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_TAB:
                if (touching || SystemClock.elapsedRealtime() - touchUpAt < 400L) {
                    mark(C_DUPLICATE, "Стрелка " + name + " от свайпа\n(дубль касания)", raw);
                } else {
                    mark(C_WHEEL, "Колесо/стрелка: " + name + "\n" + dev, raw);
                }
                break;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_SPACE:
                mark(C_OK, "Клавиша ОК: " + name + "\n" + dev, raw);
                break;
            case KeyEvent.KEYCODE_CAMERA:
            case KeyEvent.KEYCODE_FOCUS:
            case KeyEvent.KEYCODE_STEM_PRIMARY:
                mark(C_CAMERA, "Клавиша камеры: " + name + "\n" + dev, raw);
                break;
            default:
                showLast("Клавиша " + name + " (" + code + ")\n" + dev);
                log(raw);
        }
        return true;
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        StringBuilder axes = new StringBuilder();
        int[] ids = {MotionEvent.AXIS_VSCROLL, MotionEvent.AXIS_HSCROLL, MotionEvent.AXIS_SCROLL,
                MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_RELATIVE_X, MotionEvent.AXIS_RELATIVE_Y};
        for (int id : ids) {
            float v = event.getAxisValue(id);
            if (v != 0f) axes.append(' ').append(MotionEvent.axisToString(id).replace("AXIS_", ""))
                    .append('=').append(String.format(Locale.US, "%.1f", v));
        }
        String act = MotionEvent.actionToString(event.getActionMasked()).replace("ACTION_", "");
        String dev = device(event.getDevice());
        String raw = "GEN " + act + axes + " " + dev;
        if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            float v = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (v == 0f) v = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
            mark(C_WHEEL, "Прокрутка " + (v > 0 ? "▲" : "▼") + "\n" + dev, raw);
        } else {
            log(raw);
        }
        return true;
    }

    // ------------------------------------------------------------ touch

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        long now = SystemClock.elapsedRealtime();
        float slop = dp(20);
        touchDevice = device(event.getDevice());
        if (action == MotionEvent.ACTION_DOWN) {
            touching = true;
            downX = event.getX();
            downY = event.getY();
            downAt = now;
            moved = false;
            holdFired = false;
            handler.removeCallbacks(holdTick);
            handler.postDelayed(holdTick, 200L);
            log(String.format(Locale.US, "TOUCH DOWN x=%.0f y=%.0f %s", downX, downY, touchDevice));
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (!moved && (Math.abs(event.getX() - downX) > slop || Math.abs(event.getY() - downY) > slop)) {
                moved = true;
                handler.removeCallbacks(holdTick);
            }
            if (now - lastMoveLogAt > 300L) {
                lastMoveLogAt = now;
                log(String.format(Locale.US, "TOUCH MOVE x=%.0f y=%.0f", event.getX(), event.getY()));
            }
        } else if (action == MotionEvent.ACTION_UP) {
            touching = false;
            touchUpAt = now;
            handler.removeCallbacks(holdTick);
            float dx = event.getX() - downX;
            float dy = event.getY() - downY;
            log(String.format(Locale.US, "TOUCH UP dx=%.0f dy=%.0f %d мс", dx, dy, now - downAt));
            if (holdFired) {
                showLast("Отпустили — остаёмся в тесте");
            } else if (Math.abs(dx) > dp(40) && Math.abs(dx) >= Math.abs(dy)) {
                cancelTap();
                if (dx > 0) mark(C_SWIPE_FWD, "СВАЙП → (X+)", String.format(Locale.US, "swipe dx=%.0f", dx));
                else mark(C_SWIPE_BACK, "СВАЙП ← (X−)", String.format(Locale.US, "swipe dx=%.0f", dx));
            } else if (Math.abs(dy) > dp(40)) {
                cancelTap();
                mark(C_SWIPE_VERT, dy > 0 ? "СВАЙП ↓ (Y+)" : "СВАЙП ↑ (Y−)",
                        String.format(Locale.US, "swipe dy=%.0f", dy));
            } else if (!moved && now - downAt <= TAP_MAX_MS) {
                if (tapPending) {
                    cancelTap();
                    mark(C_DOUBLE, "ДВОЙНОЙ ТАП", "TOUCH " + touchDevice);
                } else {
                    tapPending = true;
                    handler.postDelayed(singleTap, DOUBLE_TAP_MS);
                }
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            touching = false;
            touchUpAt = now;
            handler.removeCallbacks(holdTick);
            log("TOUCH CANCEL");
        }
        return true;
    }

    private void cancelTap() {
        tapPending = false;
        handler.removeCallbacks(singleTap);
    }

    private void cancelTouch() {
        cancelTap();
        handler.removeCallbacks(holdTick);
    }

    // ------------------------------------------------------------ output

    private void mark(int check, String headline, String raw) {
        counts[check]++;
        details[check] = raw;
        if (headline != null) showLast(headline);
        log(raw);
        renderChecks();
    }

    private void showLast(String message) {
        lastView.setText(message);
    }

    private void renderChecks() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CHECK_NAMES.length; i++) {
            boolean seen = counts[i] > 0;
            sb.append(seen ? "✓ " : "○ ").append(CHECK_NAMES[i]);
            if (seen) sb.append(" ×").append(counts[i]);
            if (i < CHECK_NAMES.length - 1) sb.append('\n');
        }
        checksView.setText(sb.toString());
    }

    private void log(String line) {
        Log.i(TAG, line);
        counter++;
        String stamped = counter + " " + line;
        fullLog.append(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()))
                .append(' ').append(stamped).append('\n');
        logLines.addLast(stamped);
        while (logLines.size() > LOG_LINES) logLines.removeFirst();
        StringBuilder sb = new StringBuilder();
        for (String l : logLines) sb.append(l).append('\n');
        logView.setText(sb.toString());
    }

    private static String device(InputDevice device) {
        if (device == null) return "[?]";
        return "[" + device.getName() + "]";
    }

    // ------------------------------------------------------------ exit + report

    private void exitWithReport() {
        if (exiting) return;
        exiting = true;
        cancelTouch();
        showLast("Сохраняю отчёт и выхожу…");
        banner.setText("Тест кнопок и кольца — отчёт");
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                saveSnapshot(stamp);
                saveTextLog(stamp);
                finish();
            }
        }, 150L);
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
            StringBuilder report = new StringBuilder("Тест кнопок и кольца ").append(stamp).append('\n');
            for (int i = 0; i < CHECK_NAMES.length; i++) {
                report.append(counts[i] > 0 ? "[x] " : "[ ] ").append(CHECK_NAMES[i])
                        .append(" ×").append(counts[i]);
                if (details[i] != null) report.append("  — ").append(details[i]);
                report.append('\n');
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
