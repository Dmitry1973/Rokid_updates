package kz.rokid.horizon;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
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
 * Artificial horizon demo for Rokid Glasses: pitch / roll, slip, heading, turn rate,
 * G load from the IMU; baro altitude, vertical speed and hPa when there is a barometer;
 * a list of all sensors.
 *
 * Controls (keys only, no double tap, no temple hold):
 *  - OK (ring wheel press, right ring button, temple tap): open the menu / run the item;
 *  - wheel / swipe: switch horizon / sensor list, in the menu move the selection;
 *  - Back: close the menu, otherwise exit.
 *
 * No lambdas on purpose: the project must also build with plain javac + dx.
 */
public class MainActivity extends Activity implements SensorEventListener {
    private static final int SRC_GAME_RV = 0;
    private static final int SRC_RV = 1;
    private static final int SRC_ACC_GYRO = 2;
    private static final int SRC_ACC = 3;
    private static final String[] SRC_NAMES = {
            "Game RV (гиро+акс)", "Rotation Vector", "Акс. + гиро", "Акселерометр"
    };

    // menu actions
    private static final int M_ZERO = 0;
    private static final int M_RESET = 1;
    private static final int M_SOURCE = 2;
    private static final int M_ALT_ZERO = 3;
    private static final int M_ALT_STD = 4;
    private static final int M_SAVE = 5;
    private static final int M_PAGE = 6;
    private static final int M_CLOSE = 7;
    private final List<Integer> menuActions = new ArrayList<Integer>();

    private static final double STD_HPA = 1013.25;

    private SensorManager sm;
    private Sensor gameRv, rv, acc, gyro, pressure;
    private final List<Integer> sources = new ArrayList<Integer>();
    private int sourceIndex;

    private HorizonView view;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaSession mediaSession;
    private SharedPreferences prefs;
    private String sensorReport = "";

    // attitude state
    private final float[] rot = new float[9];
    private final double[] upVec = {0, 1, 0};
    private boolean haveUp;
    private long lastGyroNs;
    private final float[] accLp = new float[3];
    private boolean accInit;
    private double pitchRaw, rollRaw;
    private double pitchZero, rollZero;
    private double turnLp;
    private boolean turnInit;

    // baro state
    private double pressLp = Double.NaN;
    private double altLp = Double.NaN;
    private long altLastNs;
    private double vsLp;
    private double altZeroM = Double.NaN; // NaN = standard 1013.25

    private long lastPageSwitch;
    private long lastMenuMove;
    private long swallowEnterUntil;
    private long lastRedraw;
    private long lastOk;

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

        prefs = getSharedPreferences("horizon", MODE_PRIVATE);
        pitchZero = prefs.getFloat("pitchZero", 0f);
        rollZero = prefs.getFloat("rollZero", 0f);
        sourceIndex = Math.max(0, sources.indexOf(prefs.getInt("source", -1)));
        if (prefs.contains("altZeroM")) altZeroM = prefs.getFloat("altZeroM", 0f);

        view.hasPressure = pressure != null;
        view.altRelative = !Double.isNaN(altZeroM);
        updateZeroNote();
        buildSensorList();
        // Save the sensor list once after installing this version.
        String key = "savedList3";
        if (!prefs.getBoolean(key, false)) {
            if (saveSensorList()) prefs.edit().putBoolean(key, true).apply();
        }
        view.fileNote = prefs.getString("fileNote", null);
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
        turnInit = false;
        int src = currentSource();
        view.sourceName = src < 0 ? "нет IMU" : SRC_NAMES[src];
        view.hasData = false;
        view.heading = Double.NaN;
        view.headingMagnetic = src == SRC_RV;
        int rate = SensorManager.SENSOR_DELAY_GAME;
        if (src == SRC_GAME_RV) sm.registerListener(this, gameRv, rate);
        else if (src == SRC_RV) sm.registerListener(this, rv, rate);
        // accelerometer (slip, G) and gyro (turn rate) always, when present
        if (acc != null) sm.registerListener(this, acc, rate);
        if (gyro != null) sm.registerListener(this, gyro, rate);
        if (pressure != null) sm.registerListener(this, pressure, SensorManager.SENSOR_DELAY_GAME);
        view.invalidate();
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        int type = e.sensor.getType();
        int src = currentSource();
        if (type == Sensor.TYPE_PRESSURE) {
            onPressure(e.values[0], e.timestamp);
        } else if (type == Sensor.TYPE_GAME_ROTATION_VECTOR || type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rot, e.values);
            setUp(rot[6], rot[7], rot[8]);
            view.heading = Attitude.headingDeg(rot);
        } else if (type == Sensor.TYPE_ACCELEROMETER) {
            onAccel(e.values, src);
        } else if (type == Sensor.TYPE_GYROSCOPE) {
            onGyro(e.values, e.timestamp, src);
            return; // redraw on the other sensors
        }
        redraw();
    }

    private void onAccel(float[] v, int src) {
        float a = 0.12f;
        if (!accInit) {
            accLp[0] = v[0];
            accLp[1] = v[1];
            accLp[2] = v[2];
            accInit = true;
        } else {
            for (int i = 0; i < 3; i++) accLp[i] += a * (v[i] - accLp[i]);
        }
        double n = norm(accLp[0], accLp[1], accLp[2]);
        view.gLoad = n / SensorManager.GRAVITY_EARTH;
        view.slip = Attitude.wrap180(Attitude.slipDeg(accLp[0], accLp[1]) - rollZero);
        if (src == SRC_ACC) {
            setUp(accLp[0], accLp[1], accLp[2]);
        } else if (src == SRC_ACC_GYRO) {
            if (!haveUp) {
                setUp(accLp[0], accLp[1], accLp[2]);
            } else {
                double m = norm(v[0], v[1], v[2]);
                if (m > 7 && m < 12.5) {
                    // complementary filter: slowly pull the gyro estimate towards gravity
                    double k = 0.02;
                    setUp(upVec[0] + k * (v[0] / m - upVec[0]),
                            upVec[1] + k * (v[1] / m - upVec[1]),
                            upVec[2] + k * (v[2] / m - upVec[2]));
                }
            }
        }
    }

    private void onGyro(float[] v, long ts, int src) {
        double dt = lastGyroNs == 0 ? 0 : (ts - lastGyroNs) * 1e-9;
        lastGyroNs = ts;
        if (!haveUp) return;
        if (src == SRC_ACC_GYRO && dt > 0 && dt < 0.2) {
            // a world-fixed vector seen from the rotating device: du/dt = -w x u
            double ux = upVec[0], uy = upVec[1], uz = upVec[2];
            double cx = v[1] * uz - v[2] * uy;
            double cy = v[2] * ux - v[0] * uz;
            double cz = v[0] * uy - v[1] * ux;
            setUp(ux - cx * dt, uy - cy * dt, uz - cz * dt);
        }
        double t = Attitude.turnRateDeg(v[0], v[1], v[2], upVec[0], upVec[1], upVec[2]);
        if (!turnInit) {
            turnLp = t;
            turnInit = true;
        } else {
            turnLp += 0.08 * (t - turnLp);
        }
        view.turnRate = turnLp;
    }

    private void onPressure(float hpa, long ts) {
        if (Double.isNaN(pressLp)) pressLp = hpa;
        else pressLp += 0.1 * (hpa - pressLp);
        view.pressureHpa = (float) pressLp;
        double alt = Attitude.pressureAltitudeM(pressLp, STD_HPA);
        if (!Double.isNaN(altLp) && altLastNs != 0) {
            double dt = (ts - altLastNs) * 1e-9;
            if (dt > 0 && dt < 2) {
                double vs = (alt - altLp) / dt;
                vsLp += 0.05 * (vs - vsLp);
                view.vsMs = vsLp;
            }
        }
        altLp = alt;
        altLastNs = ts;
        view.altM = Double.isNaN(altZeroM) ? alt : alt - altZeroM;
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

    // ------------------------------------------------------------------ zero

    private void updateZeroNote() {
        if (pitchZero == 0 && rollZero == 0) view.zeroNote = "ноль: заводской";
        else view.zeroNote = String.format(Locale.US, "ноль: %+.1f° / %+.1f°", pitchZero, rollZero);
    }

    private void setZero(double p, double r) {
        pitchZero = p;
        rollZero = r;
        prefs.edit().putFloat("pitchZero", (float) p).putFloat("rollZero", (float) r).apply();
        if (haveUp) {
            view.pitch = pitchRaw - pitchZero;
            view.roll = Attitude.wrap180(rollRaw - rollZero);
        }
        updateZeroNote();
    }

    // ------------------------------------------------------------------ sensor list

    private static String typeName(Sensor s) {
        String t = s.getStringType();
        if (t == null) return String.valueOf(s.getType());
        int dot = t.lastIndexOf('.');
        return dot >= 0 ? t.substring(dot + 1) : t;
    }

    private void buildSensorList() {
        List<Sensor> all = sm.getSensorList(Sensor.TYPE_ALL);
        StringBuilder file = new StringBuilder();
        file.append("Rokid sensors, ").append(Build.MODEL).append(" / ").append(Build.DISPLAY)
                .append(", API ").append(Build.VERSION.SDK_INT).append('\n');
        file.append("count: ").append(all.size()).append("\n\n");
        for (int i = 0; i < all.size(); i++) {
            Sensor s = all.get(i);
            view.sensorLines.add(typeName(s) + " · " + s.getName());
            file.append(String.format(Locale.US,
                    "%2d. type=%d %s%s\n    %s | %s v%d\n    range=%.3f res=%.5f %.2fmA minDelay=%dus\n",
                    i + 1, s.getType(), typeName(s), s.isWakeUpSensor() ? " (wakeup)" : "",
                    s.getName(), s.getVendor(), s.getVersion(),
                    s.getMaximumRange(), s.getResolution(), s.getPower(), s.getMinDelay()));
        }
        sensorReport = file.toString();
    }

    /** Saves the list as a JPEG in DCIM/Camera (synced to the phone like photos) and a .txt in Download. */
    private boolean saveSensorList() {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String name = "RokidHorizon_sensors_" + stamp;
        String err = saveListPicture(name + ".jpg", sensorReport);
        saveToDownloads(name + ".txt", sensorReport);
        String note = err == null ? "список: галерея, " + name + ".jpg" : "не сохранилось: " + err;
        prefs.edit().putString("fileNote", note).apply();
        view.fileNote = note;
        return err == null;
    }

    /** Returns null on success, otherwise a short error text. Same path and flow as the camera's photos. */
    private String saveListPicture(String name, String text) {
        Uri uri = null;
        try {
            String[] lines = text.split("\n");
            int lineH = 30;
            int width = 1200;
            int height = Math.max(400, 40 + lines.length * lineH + 30);
            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.drawColor(Color.BLACK);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.rgb(0, 255, 0));
            paint.setTextSize(24f);
            paint.setTypeface(Typeface.MONOSPACE);
            float y = 40;
            for (int i = 0; i < lines.length; i++) {
                c.drawText(lines[i], 20, y, paint);
                y += lineH;
            }
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera");
            values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return "MediaStore отказал";
            OutputStream out = getContentResolver().openOutputStream(uri, "w");
            if (out == null) return "нет потока";
            try {
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, out);
            } finally {
                out.close();
                bmp.recycle();
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, ready, null, null);
            return null;
        } catch (Throwable ex) {
            if (uri != null) {
                try {
                    getContentResolver().delete(uri, null, null);
                } catch (Throwable ignored) {
                    // nothing more to do
                }
            }
            String m = ex.getClass().getSimpleName();
            return ex.getMessage() == null ? m : m + ": " + ex.getMessage();
        }
    }

    private void saveToDownloads(String name, String text) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return;
            OutputStream out = getContentResolver().openOutputStream(uri);
            if (out == null) return;
            try {
                out.write(text.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Exception ignored) {
            // the picture is the main copy
        }
    }

    // ------------------------------------------------------------------ menu

    private void openMenu() {
        menuActions.clear();
        view.menuItems.clear();
        addItem(M_ZERO, "Ноль здесь (текущее положение)");
        addItem(M_RESET, "Сброс нуля (заводской)");
        if (sources.size() > 1) addItem(M_SOURCE, "Источник: " + SRC_NAMES[currentSource()]);
        if (pressure != null) {
            addItem(M_ALT_ZERO, "Высота: 0 здесь");
            addItem(M_ALT_STD, "Высота: стандарт 1013.25");
        }
        addItem(M_SAVE, "Сохранить список датчиков");
        addItem(M_PAGE, view.page == HorizonView.PAGE_HORIZON ? "Показать список датчиков" : "Показать горизонт");
        addItem(M_CLOSE, "Закрыть");
        view.menuIndex = Math.min(view.menuIndex, view.menuItems.size() - 1);
        view.menuOpen = true;
        view.invalidate();
    }

    private void addItem(int action, String label) {
        menuActions.add(action);
        view.menuItems.add(label);
    }

    private void closeMenu() {
        view.menuOpen = false;
        view.invalidate();
    }

    private void runMenuItem() {
        int action = menuActions.get(view.menuIndex);
        switch (action) {
            case M_ZERO:
                if (!haveUp) {
                    flash("нет данных датчика");
                    break;
                }
                setZero(pitchRaw, rollRaw);
                closeMenu();
                flash("НОЛЬ выставлен");
                break;
            case M_RESET:
                setZero(0, 0);
                closeMenu();
                flash("ноль заводской");
                break;
            case M_SOURCE:
                sourceIndex = (sourceIndex + 1) % sources.size();
                prefs.edit().putInt("source", currentSource()).apply();
                startSensors();
                openMenu(); // refresh the label, stay in the menu
                break;
            case M_ALT_ZERO:
                if (Double.isNaN(altLp)) break;
                altZeroM = altLp;
                prefs.edit().putFloat("altZeroM", (float) altZeroM).apply();
                view.altRelative = true;
                closeMenu();
                flash("высота 0 здесь");
                break;
            case M_ALT_STD:
                altZeroM = Double.NaN;
                prefs.edit().remove("altZeroM").apply();
                view.altRelative = false;
                closeMenu();
                flash("высота по 1013.25");
                break;
            case M_SAVE:
                closeMenu();
                flash(saveSensorList() ? "список сохранён в галерею" : "не сохранилось, см. страницу датчиков");
                break;
            case M_PAGE:
                view.page = view.page == HorizonView.PAGE_HORIZON ? HorizonView.PAGE_SENSORS : HorizonView.PAGE_HORIZON;
                closeMenu();
                break;
            default:
                closeMenu();
                break;
        }
    }

    // ------------------------------------------------------------------ keys

    private void flash(String text) {
        view.flash = text;
        view.flashUntil = System.currentTimeMillis() + 2000;
        view.invalidate();
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                view.invalidate();
            }
        }, 2100);
    }

    private void onOk() {
        long now = System.currentTimeMillis();
        if (now - lastOk < 350) return; // one press can arrive as two keys
        lastOk = now;
        if (view.menuOpen) runMenuItem();
        else openMenu();
    }

    private void onWheel(int dir) {
        long now = System.currentTimeMillis();
        if (view.menuOpen) {
            // a wheel click / swipe arrives as a short burst: one step per burst
            if (now - lastMenuMove < 250) return;
            lastMenuMove = now;
            int n = view.menuItems.size();
            view.menuIndex = (view.menuIndex + dir + n) % n;
            view.invalidate();
            return;
        }
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
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                onWheel(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_UP:
                onWheel(-1);
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
            case KeyEvent.KEYCODE_BACK:
                if (view.menuOpen) {
                    closeMenu();
                    return true;
                }
                return super.onKeyDown(keyCode, event);
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
