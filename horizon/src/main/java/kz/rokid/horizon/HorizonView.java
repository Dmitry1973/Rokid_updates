package kz.rokid.horizon;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Draws the attitude indicator for the mono green HUD (black = transparent).
 * All text sits at the top of the screen: the bottom of the HUD is hard to read.
 */
final class HorizonView extends View {
    static final int PAGE_HORIZON = 0;
    static final int PAGE_SENSORS = 1;

    private static final int GREEN = Color.rgb(0, 255, 0);
    private static final float PX_PER_DEG = 6f;

    // State set by the activity.
    int page = PAGE_HORIZON;
    double pitch;          // degrees, already zeroed
    double roll;           // degrees, already zeroed
    boolean hasData;
    String sourceName = "";
    float pressureHpa = Float.NaN;
    boolean hasPressure;
    float[] up = new float[3];
    String flash;          // short message shown for a moment (e.g. "НОЛЬ")
    long flashUntil;
    final List<String> sensorLines = new ArrayList<String>();
    String fileNote;       // where the sensor list was saved

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thin = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ground = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ladder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    HorizonView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        stroke.setColor(GREEN);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(4f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        thin.set(stroke);
        thin.setStrokeWidth(2.5f);
        fill.setColor(GREEN);
        fill.setStyle(Paint.Style.FILL);
        ground.setColor(Color.argb(45, 0, 255, 0));
        ground.setStyle(Paint.Style.FILL);
        big.setColor(GREEN);
        big.setTextSize(46f);
        big.setFakeBoldText(true);
        mid.setColor(GREEN);
        mid.setTextSize(26f);
        small.setColor(GREEN);
        small.setTextSize(19f);
        ladder.setColor(GREEN);
        ladder.setTextSize(20f);
        ladder.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (page == PAGE_SENSORS) drawSensors(c);
        else drawHorizon(c);
    }

    private void drawHorizon(Canvas c) {
        float w = getWidth();
        float h = getHeight();

        // ---- text block at the top
        float y = 50;
        if (hasData) {
            c.drawText(String.format(Locale.US, "Т %+5.1f°", pitch), 14, y, big);
            String r = String.format(Locale.US, "К %+5.1f°", roll);
            c.drawText(r, w - 14 - big.measureText(r), y, big);
        } else {
            c.drawText("нет данных датчика", 14, y, mid);
        }
        y += 32;
        String line2 = sourceName;
        if (hasPressure) line2 += String.format(Locale.US, "   %.1f гПа", pressureHpa);
        c.drawText(line2, 14, y, mid);
        y += 26;
        c.drawText("OK — ноль   колесо — список датчиков", 14, y, small);
        long now = System.currentTimeMillis();
        if (flash != null && now < flashUntil) {
            y += 30;
            c.drawText(flash, 14, y, mid);
        }

        // ---- attitude indicator
        float cx = w / 2f;
        // Kept high on the screen: the bottom of the HUD is hard to read.
        float r = Math.min(w / 2f - 30f, Math.min(195f, (h - 160f) / 2f));
        float cy = 160f + r;

        c.save();
        path.reset();
        path.addCircle(cx, cy, r, Path.Direction.CW);
        c.clipPath(path);

        c.save();
        c.rotate((float) -roll, cx, cy);
        float shift = (float) (pitch * PX_PER_DEG);
        float hy = cy + shift;
        // ground: dim fill below the horizon line
        c.drawRect(cx - 2 * r, hy, cx + 2 * r, cy + 3 * r, ground);
        // horizon line
        c.drawLine(cx - 2 * r, hy, cx + 2 * r, hy, stroke);
        // pitch ladder: every 5°, labels every 10°
        for (int d = -90; d <= 90; d += 5) {
            if (d == 0) continue;
            float ly = hy - d * PX_PER_DEG;
            if (ly < cy - r - 20 || ly > cy + r + 20) continue;
            float half = (d % 10 == 0) ? 60f : 28f;
            if (d > 0) {
                c.drawLine(cx - half, ly, cx + half, ly, thin);
            } else {
                // below the horizon: dashed rungs
                float seg = half / 3f;
                for (float x = -half; x < half; x += seg * 2) {
                    c.drawLine(cx + x, ly, cx + x + seg, ly, thin);
                }
            }
            if (d % 10 == 0) {
                String t = String.valueOf(Math.abs(d));
                c.drawText(t, cx - half - 24, ly + 7, ladder);
                c.drawText(t, cx + half + 24, ly + 7, ladder);
            }
        }
        c.restore();
        c.restore(); // clip

        // bezel
        c.drawCircle(cx, cy, r, thin);

        // roll scale: rotates with the horizon, pointer stays fixed at the top
        c.save();
        c.rotate((float) -roll, cx, cy);
        int[] ticks = {-60, -45, -30, -20, -10, 0, 10, 20, 30, 45, 60};
        for (int i = 0; i < ticks.length; i++) {
            int t = ticks[i];
            double a = Math.toRadians(t - 90);
            float len = (t % 30 == 0) ? 22f : 13f;
            float x1 = cx + (float) (Math.cos(a) * r);
            float y1 = cy + (float) (Math.sin(a) * r);
            float x2 = cx + (float) (Math.cos(a) * (r - len));
            float y2 = cy + (float) (Math.sin(a) * (r - len));
            c.drawLine(x1, y1, x2, y2, t == 0 ? stroke : thin);
        }
        c.restore();
        path.reset();
        path.moveTo(cx, cy - r + 24);
        path.lineTo(cx - 11, cy - r + 42);
        path.lineTo(cx + 11, cy - r + 42);
        path.close();
        c.drawPath(path, fill);

        // fixed aircraft symbol
        float wing = r * 0.42f;
        path.reset();
        path.moveTo(cx - wing - 30, cy);
        path.lineTo(cx - 30, cy);
        path.lineTo(cx, cy + 22);
        path.lineTo(cx + 30, cy);
        path.lineTo(cx + wing + 30, cy);
        stroke.setStrokeWidth(6f);
        c.drawPath(path, stroke);
        stroke.setStrokeWidth(4f);
        c.drawCircle(cx, cy, 4f, fill);
    }

    private void drawSensors(Canvas c) {
        float w = getWidth();
        float y = 34;
        c.drawText("Датчики: " + sensorLines.size(), 12, y, mid);
        y += 24;
        c.drawText("OK — сменить источник   колесо — горизонт", 12, y, small);
        y += 24;
        c.drawText("Источник: " + sourceName, 12, y, small);
        y += 22;
        c.drawText(String.format(Locale.US, "вверх (x,y,z): %+.2f %+.2f %+.2f", up[0], up[1], up[2]), 12, y, small);
        y += 22;
        if (hasPressure) {
            c.drawText(String.format(Locale.US, "давление: %.2f гПа", pressureHpa), 12, y, small);
        } else {
            c.drawText("датчика давления нет", 12, y, small);
        }
        y += 22;
        if (fileNote != null) {
            c.drawText(fileNote, 12, y, small);
            y += 22;
        }
        long now = System.currentTimeMillis();
        if (flash != null && now < flashUntil) {
            c.drawText(flash, 12, y, mid);
            y += 28;
        }
        y += 6;
        c.drawLine(12, y - 14, w - 12, y - 14, thin);
        for (int i = 0; i < sensorLines.size(); i++) {
            if (y > getHeight() - 4) break;
            String s = sensorLines.get(i);
            // cut to the screen width
            while (s.length() > 4 && small.measureText(s) > w - 24) {
                s = s.substring(0, s.length() - 2);
            }
            c.drawText(s, 12, y, small);
            y += 22;
        }
    }
}
