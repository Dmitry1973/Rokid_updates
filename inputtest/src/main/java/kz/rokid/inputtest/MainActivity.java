package kz.rokid.inputtest;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Shows every input event that reaches an app on the glasses: keys (with the
 * device that sent them), touch, scroll/generic motion and the Rokid button
 * broadcasts. Used to find out what the Rokid ring wheel and buttons send.
 * Exit: press Back twice within 1.5 s.
 */
public final class MainActivity extends Activity {
    private static final String TAG = "RokidInputTest";
    private static final int MAX_LINES = 13;
    private static final String[] BROADCASTS = {
            "com.android.action.ACTION_SPRITE_BUTTON_DOWN",
            "com.android.action.ACTION_SPRITE_BUTTON_UP",
            "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS",
            "com.android.action.ACTION_SPRITE_BUTTON_VERY_VERY_LONG_PRESS",
            "com.rokid.glass3.action.button.CLICK",
            "com.rokid.glass3.action.button.DOUBLE_CLICK",
            "com.rokid.glass3.action.button.LONG_PRESS",
    };

    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private TextView text;
    private long lastBackAt = -1L;
    private int counter;
    private long lastMoveLogAt;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction() == null ? "?" : intent.getAction();
            StringBuilder extras = new StringBuilder();
            if (intent.getExtras() != null) {
                for (String key : intent.getExtras().keySet()) {
                    extras.append(' ').append(key).append('=').append(intent.getExtras().get(key));
                }
            }
            add("BC " + action.substring(action.lastIndexOf('.') + 1) + extras);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        text = new TextView(this);
        text.setTextColor(Color.rgb(140, 255, 140));
        text.setBackgroundColor(Color.BLACK);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(13);
        text.setGravity(Gravity.TOP | Gravity.START);
        text.setPadding(16, 16, 16, 16);
        setContentView(text);
        add("Жмите кнопки, крутите колесо,");
        add("тапайте/свайпайте по дужке.");
        add("Выход: Назад 2 раза.");
    }

    @Override protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        for (String action : BROADCASTS) filter.addAction(action);
        registerReceiver(receiver, filter);
    }

    @Override protected void onPause() {
        try { unregisterReceiver(receiver); } catch (RuntimeException ignored) {}
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        String act = event.getAction() == KeyEvent.ACTION_DOWN ? "↓"
                : event.getAction() == KeyEvent.ACTION_UP ? "↑" : "m";
        String name = KeyEvent.keyCodeToString(event.getKeyCode()).replace("KEYCODE_", "");
        add(String.format(Locale.US, "KEY%s %s(%d) sc=%d r=%d %s", act, name, event.getKeyCode(),
                event.getScanCode(), event.getRepeatCount(), device(event.getDevice())));
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
            long now = SystemClock.elapsedRealtime();
            if (lastBackAt > 0L && now - lastBackAt < 1500L) finish();
            lastBackAt = now;
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
        add("GEN " + MotionEvent.actionToString(event.getActionMasked()).replace("ACTION_", "")
                + axes + " " + device(event.getDevice()));
        return true;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        long now = SystemClock.elapsedRealtime();
        if (action == MotionEvent.ACTION_MOVE && now - lastMoveLogAt < 250L) return true;
        if (action == MotionEvent.ACTION_MOVE) lastMoveLogAt = now;
        add(String.format(Locale.US, "TOUCH %s x=%.0f y=%.0f %s",
                MotionEvent.actionToString(action).replace("ACTION_", ""),
                event.getX(), event.getY(), device(event.getDevice())));
        return true;
    }

    private static String device(InputDevice device) {
        if (device == null) return "[?]";
        return "[" + device.getName() + "]";
    }

    private void add(String line) {
        Log.i(TAG, line);
        counter++;
        lines.addLast(counter + " " + line);
        while (lines.size() > MAX_LINES) lines.removeFirst();
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        runOnUiThread(() -> text.setText(sb.toString()));
    }
}
