package kz.rokid.horizon;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.view.WindowManager;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Artificial horizon demo for Rokid Glasses: pitch / roll from the IMU,
 * pressure in hPa when the glasses have a barometer, and a list of all sensors.
 *
 * Controls (keys only, no double tap, no temple hold):
 *  - OK (temple tap, ring wheel press, ring right button): horizon page = set zero,
 *    sensor page = next attitude source;
 *  - wheel / swipe: switch between the horizon and the sensor list;
 *  - Back: exit.
 */
public class MainActivity extends Activity implements SensorEventListener {
    private static final int SRC_GAME_RV = 0;
    private static final int SRC_RV = 1;
    private static final int SRC_ACC_GYRO = 2;
    private static final int SRC_ACC = 3;
    private static final String[] SRC_NAMES = {
            "Game Rotation Vector", "Rotation Vector", "Акселерометр + гироскоп", "Акселерометр"
    };

    private SensorManager sm;
    private Sensor gameRv, rv, acc, gyro, pressure;
    private final List<Integer> sources = new ArrayList<Integer>();
    private int sourceIndex;

    private HorizonView view;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaSession mediaSession;

    // attitude state
    private final float[] rot = new float[9];
    private final double[] upVec = {0, 1, 0};
    private boolean haveUp;
    private long lastGyroNs;
    private final float[] accLp = new float[3];
    private boolean accInit;
    private double pitchRaw, rollRaw;
    private double pitchZero, rollZero;

    private long lastPageSwitch;
    private long swallowEnterUntil;
    private long lastRedraw;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new HorizonView(this);
        setContentView(view);

        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        gameRv = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        rv = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        pressure = sm.getDefaultSensor(Sensor.TYPE_PRESSURE);
        if (gameRv != null) sources.add(SRC_GAME_RV);
        if (rv != null) sources.add(SRC_RV);
        if (acc != null && gyro != null) sources.add(SRC_ACC_GYRO);
        if (acc != null) sources.add(SRC_ACC);

        SharedPreferences p = getSharedPreferences("horizon", MODE_PRIVATE);
        pitchZero = p.getFloat("pitchZero", 0f);
        rollZero = p.getFloat("rollZero", 0f);
        int savedSrc = p.getInt("source", -1);
        sourceIndex = Math.max(0, sources.indexOf(savedSrc));

        view.hasPressure = pressure != null;
        buildSensorList(p);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startSensors();
        claimMediaButtons();
    }

    @Override
    protected void onPause() {
        super.onPause();
        sm.unregisterListener(this);
        if (mediaSession != null) mediaSession.setActive(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
    }

    // ------------------------------------------------------------------ sensors

    private int currentSource() {
        return sources.isEmpty() ? -1 : sources.get(sourceIndex);
    }

    private void startSensors() {
        sm.unregisterListener(this);
        haveUp = false;
        accInit = false;
        lastGyroNs = 0;
        int src = currentSource();
        view.sourceName = src < 0 ? "нет IMU" : SRC_NAMES[src];
        view.hasData = false;
        int rate = SensorManager.SENSOR_DELAY_GAME;
        if (src == SRC_GAME_RV) sm.registerListener(this, gameRv, rate);
        else if (src == SRC_RV) sm.registerListener(this, rv, rate);
        else if (src == SRC_ACC_GYRO) {
            sm.registerListener(this, acc, rate);
            sm.registerListener(this, gyro, rate);
        } else if (src == SRC_ACC) sm.registerListener(this, acc, rate);
        if (pressure != null) sm.registerListener(this, pressure, SensorManager.SENSOR_DELAY_NORMAL);
        view.invalidate();
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        int type = e.sensor.getType();
        int src = currentSource();
        if (type == Sensor.TYPE_PRESSURE) {
            view.pressureHpa = e.values[0];
        } else if (type == Sensor.TYPE_GAME_ROTATION_VECTOR || type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rot, e.values);
            // Third row of R = world Z (up) in device coordinates.
            setUp(rot[6], rot[7], rot[8]);
        } else if (type == Sensor.TYPE_ACCELEROMETER) {
            float a = 0.15f;
            if (!accInit) {
                accLp[0] = e.values[0];
                accLp[1] = e.values[1];
                accLp[2] = e.values[2];
                accInit = true;
            } else {
                for (int i = 0; i < 3; i++) accLp[i] += a * (e.values[i] - accLp[i]);
            }
            if (src == SRC_ACC) {
                setUp(accLp[0], accLp[1], accLp[2]);
            } else if (src == SRC_ACC_GYRO) {
                if (!haveUp) {
                    setUp(accLp[0], accLp[1], accLp[2]);
                } else {
                    // complementary filter: slowly pull the gyro estimate towards gravity
                    double n = norm(e.values[0], e.values[1], e.values[2]);
                    if (n > 7 && n < 12.5) {
                        double k = 0.02;
                        setUp(upVec[0] + k * (e.values[0] / n - upVec[0]),
                                upVec[1] + k * (e.values[1] / n - upVec[1]),
                                upVec[2] + k * (e.values[2] / n - upVec[2]));
                    }
                }
            }
        } else if (type == Sensor.TYPE_GYROSCOPE && src == SRC_ACC_GYRO) {
            if (lastGyroNs != 0 && haveUp) {
                double dt = (e.timestamp - lastGyroNs) * 1e-9;
                if (dt > 0 && dt < 0.2) {
                    // a world-fixed vector seen from the rotating device: du/dt = -w x u
                    double wx = e.values[0], wy = e.values[1], wz = e.values[2];
                    double ux = upVec[0], uy = upVec[1], uz = upVec[2];
                    double cx = wy * uz - wz * uy;
                    double cy = wz * ux - wx * uz;
                    double cz = wx * uy - wy * ux;
                    setUp(ux - cx * dt, uy - cy * dt, uz - cz * dt);
                }
            }
            lastGyroNs = e.timestamp;
            return; // redraw on accelerometer events
        }
        redraw();
    }

    private static double norm(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }

    private void setUp(double x, double y, double z) {
        double n = norm(x, y, z);
        if (n < 1e-6) return;
        upVec[0] = x / n;
        upVec[1] = y / n;
        upVec[2] = z / n;
        haveUp = true;
        pitchRaw = Attitude.pitchDeg(upVec[0], upVec[1], upVec[2]);
        rollRaw = Attitude.rollDeg(upVec[0], upVec[1], upVec[2]);
        view.up[0] = (float) upVec[0];
        view.up[1] = (float) upVec[1];
        view.up[2] = (float) upVec[2];
        view.pitch = pitchRaw - pitchZero;
        view.roll = Attitude.wrap180(rollRaw - rollZero);
        view.hasData = true;
    }

    private void redraw() {
        long now = System.currentTimeMillis();
        if (now - lastRedraw < 30) return;
        lastRedraw = now;
        view.invalidate();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // ------------------------------------------------------------------ sensor list

    private static String typeName(Sensor s) {
        String t = s.getStringType();
        if (t == null) return String.valueOf(s.getType());
        int dot = t.lastIndexOf('.');
        return dot >= 0 ? t.substring(dot + 1) : t;
    }

    private void buildSensorList(SharedPreferences p) {
        List<Sensor> all = sm.getSensorList(Sensor.TYPE_ALL);
        StringBuilder file = new StringBuilder();
        file.append("Rokid sensors, ").append(Build.MODEL).append(" / ").append(Build.DISPLAY)
                .append(", API ").append(Build.VERSION.SDK_INT).append('\n');
        file.append("count: ").append(all.size()).append("\n\n");
        for (int i = 0; i < all.size(); i++) {
            Sensor s = all.get(i);
            view.sensorLines.add(typeName(s) + " · " + s.getName());
            file.append(String.format(Locale.US,
                    "%2d. type=%d (%s)%s\n    name=%s\n    vendor=%s v%d\n    range=%.3f res=%.5f power=%.2fmA minDelay=%dus%s\n",
                    i + 1, s.getType(), s.getStringType(), s.isWakeUpSensor() ? " wakeup" : "",
                    s.getName(), s.getVendor(), s.getVersion(),
                    s.getMaximumRange(), s.getResolution(), s.getPower(), s.getMinDelay(),
                    s.isDynamicSensor() ? " dynamic" : ""));
        }
        // Save once (per sensor count) so it can be sent from the phone.
        String key = "savedList_" + all.size();
        if (!p.getBoolean(key, false)) {
            String name = "Horizon_sensors_"
                    + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt";
            if (saveToDownloads(name, file.toString())) {
                p.edit().putBoolean(key, true).apply();
                view.fileNote = "список: Download/" + name;
            }
        }
    }

    private boolean saveToDownloads(String name, String text) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return false;
            OutputStream out = getContentResolver().openOutputStream(uri);
            if (out == null) return false;
            try {
                out.write(text.getBytes("UTF-8"));
            } finally {
                out.close();
            }
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    // ------------------------------------------------------------------ keys

    private void flash(String text) {
        view.flash = text;
        view.flashUntil = System.currentTimeMillis() + 1500;
        view.invalidate();
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                view.invalidate();
            }
        }, 1600);
    }

    private long lastOk;

    private void onOk() {
        long now = System.currentTimeMillis();
        if (now - lastOk < 350) return; // one press can arrive as two keys
        lastOk = now;
        if (view.page == HorizonView.PAGE_HORIZON) {
            if (!haveUp) return;
            pitchZero = pitchRaw;
            rollZero = rollRaw;
            getSharedPreferences("horizon", MODE_PRIVATE).edit()
                    .putFloat("pitchZero", (float) pitchZero)
                    .putFloat("rollZero", (float) rollZero).apply();
            view.pitch = 0;
            view.roll = 0;
            flash(String.format(Locale.US, "НОЛЬ (смещение %+.1f° / %+.1f°)", pitchZero, rollZero));
        } else {
            if (sources.size() < 2) {
                flash("другого источника нет");
                return;
            }
            sourceIndex = (sourceIndex + 1) % sources.size();
            getSharedPreferences("horizon", MODE_PRIVATE).edit()
                    .putInt("source", currentSource()).apply();
            startSensors();
            flash("источник: " + SRC_NAMES[currentSource()]);
        }
    }

    private void switchPage() {
        long now = System.currentTimeMillis();
        // the wheel sends a burst of events per turn: one switch per burst
        if (now - lastPageSwitch < 600) {
            lastPageSwitch = now;
            return;
        }
        lastPageSwitch = now;
        view.page = view.page == HorizonView.PAGE_HORIZON ? HorizonView.PAGE_SENSORS : HorizonView.PAGE_HORIZON;
        view.invalidate();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                switchPage();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                // ring wheel press = PLAY_PAUSE + ENTER: act once, swallow the ENTER
                if (event.getRepeatCount() == 0) {
                    swallowEnterUntil = System.currentTimeMillis() + 400;
                    onOk();
                }
                return true;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (event.getRepeatCount() == 0 && System.currentTimeMillis() > swallowEnterUntil) onOk();
                return true;
            case KeyEvent.KEYCODE_NOTIFICATION:
                return true; // precedes every temple/ring gesture; ignore
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    /** Hold an active media session so the wheel press does not reach a phone player. */
    private void claimMediaButtons() {
        try {
            if (mediaSession == null) {
                mediaSession = new MediaSession(this, "HorizonButtons");
                mediaSession.setCallback(new MediaSession.Callback() {
                    @Override public boolean onMediaButtonEvent(Intent intent) {
                        KeyEvent key = intent == null ? null
                                : (KeyEvent) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                        if (key != null && key.getAction() == KeyEvent.ACTION_DOWN && key.getRepeatCount() == 0
                                && key.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                            swallowEnterUntil = System.currentTimeMillis() + 400;
                            onOk();
                        }
                        return true;
                    }
                }, handler);
            }
            mediaSession.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, 0L, 0f)
                    .build());
            mediaSession.setActive(true);
        } catch (Exception ignored) {
            // not critical
        }
    }
}
