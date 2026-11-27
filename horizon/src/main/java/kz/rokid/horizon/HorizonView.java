package kz.rokid.horizon;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Glass-cockpit style attitude display for the mono green HUD (black = transparent).
 * All text sits at the top of the screen: the bottom of the HUD is hard to read.
 */
final class HorizonView extends View {
    static final int PAGE_HORIZON = 0;
    static final int PAGE_SENSORS = 1;

    private static final int GREEN = Color.rgb(0, 255, 0);
    private static final float PX_PER_DEG = 6f;

    // State set by the activity.
    int page = PAGE_HORIZON;
    boolean hasData;
    double pitch;            // degrees, zeroed
    double roll;             // degrees, zeroed
    double slip;             // degrees, + = ball right
    double heading = Double.NaN;
    boolean headingMagnetic;
    double turnRate = Double.NaN; // deg/s, + = right
    double gLoad = Double.NaN;
    boolean hasPressure;
    float pressureHpa = Float.NaN;
    double altM = Double.NaN;
    boolean altRelative;
    double vsMs = Double.NaN;
    String sourceName = "";
    float[] up = new float[3];
    String zeroNote = "";
    String flash;
    long flashUntil;
    final List<String> sensorLines = new ArrayList<String>();
    String fileNote;

    // menu
    boolean menuOpen;
    int menuIndex;
    final List<String> menuItems = new ArrayList<String>();

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thin = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ground = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint black = new Paint();
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ladder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

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
        black.setColor(Color.BLACK);
        big.setColor(GREEN);
        big.setTextSize(40f);
        big.setFakeBoldText(true);
        mid.setColor(GREEN);
        mid.setTextSize(25f);
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
        if (menuOpen) drawMenu(c);
    }

    private void textRight(Canvas c, String s, float right, float y, Paint p) {
        c.drawText(s, right - p.measureText(s), y, p);
    }

    private void textCenter(Canvas c, String s, float cx, float y, Paint p) {
        c.drawText(s, cx - p.measureText(s) / 2f, y, p);
    }

    private void drawHorizon(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        float cxw = w / 2f;

        // ---- row 1: labels
        float y = 22;
        c.drawText("PITCH", 14, y, small);
        textCenter(c, headingMagnetic ? "HDG" : "HDG отн.", cxw, y, small);
        textRight(c, "ROLL", w - 14, y, small);
        // ---- row 2: big numbers
        y = 62;
        if (hasData) {
            c.drawText(String.format(Locale.US, "%+.1f°", pitch), 14, y, big);
            textRight(c, String.format(Locale.US, "%+.1f°", roll), w - 14, y, big);
        } else {
            c.drawText("нет данных", 14, y, mid);
        }
        textCenter(c, Double.isNaN(heading) ? "---" : String.format(Locale.US, "%03d", Math.round(heading) % 360), cxw, y, big);
        // ---- row 3: turn rate, slip, G
        y += 30;
        String tr = Double.isNaN(turnRate) ? "TURN --" : String.format(Locale.US, "TURN %+.1f°/s", turnRate);
        c.drawText(tr, 14, y, mid);
        textRight(c, Double.isNaN(gLoad) ? "G --" : String.format(Locale.US, "G %.2f", gLoad), w - 14, y, mid);
        // ---- row 4: baro
        y += 28;
        if (hasPressure) {
            String alt = Double.isNaN(altM) ? "ALT --"
                    : String.format(Locale.US, "%s %d ft (%d m)", altRelative ? "ALT отн." : "ALT",
                    Math.round(altM * 3.28084), Math.round(altM));
            c.drawText(alt, 14, y, mid);
            textRight(c, Double.isNaN(vsMs) ? "VS --"
                    : String.format(Locale.US, "VS %+d fpm", Math.round(vsMs * 196.85 / 10.0) * 10), w - 14, y, mid);
            y += 24;
            c.drawText(String.format(Locale.US, "%.1f hPa", pressureHpa), 14, y, small);
            textRight(c, sourceName, w - 14, y, small);
        } else {
            c.drawText(sourceName, 14, y, small);
        }
        long now = System.currentTimeMillis();
        if (flash != null && now < flashUntil) {
            y += 26;
            c.drawText(flash, 14, y, mid);
        }

        // ---- attitude indicator, kept high on the screen
        float top = hasPressure ? 200f : 180f;
        float r = Math.min(w / 2f - 30f, Math.min(180f, (h - top - 20f) / 2f));
        float cx = cxw;
        float cy = top + r;

        c.save();
        path.reset();
        path.addCircle(cx, cy, r, Path.Direction.CW);
        c.clipPath(path);
        c.save();
        c.rotate((float) -roll, cx, cy);
        float hy = cy + (float) (pitch * PX_PER_DEG);
        c.drawRect(cx - 2 * r, hy, cx + 2 * r, cy + 3 * r, ground);
        c.drawLine(cx - 2 * r, hy, cx + 2 * r, hy, stroke);
        for (int d = -90; d <= 90; d += 5) {
            if (d == 0) continue;
            float ly = hy - d * PX_PER_DEG;
            if (ly < cy - r - 20 || ly > cy + r + 20) continue;
            float half = (d % 10 == 0) ? 55f : 25f;
            if (d > 0) {
                c.drawLine(cx - half, ly, cx + half, ly, thin);
            } else {
                float seg = half / 3f;
                for (float x = -half; x < half; x += seg * 2) c.drawLine(cx + x, ly, cx + x + seg, ly, thin);
            }
            if (d % 10 == 0) {
                String t = String.valueOf(Math.abs(d));
                c.drawText(t, cx - half - 22, ly + 7, ladder);
                c.drawText(t, cx + half + 22, ly + 7, ladder);
            }
        }
        c.restore();
        c.restore();
        c.drawCircle(cx, cy, r, thin);

        // roll scale rotates with the horizon; pointer and slip indicator stay fixed (G1000 style)
        c.save();
        c.rotate((float) -roll, cx, cy);
        int[] ticks = {-60, -45, -30, -20, -10, 0, 10, 20, 30, 45, 60};
        for (int i = 0; i < ticks.length; i++) {
            int t = ticks[i];
            double a = Math.toRadians(t - 90);
            float len = (t % 30 == 0) ? 20f : 12f;
            c.drawLine(cx + (float) (Math.cos(a) * r), cy + (float) (Math.sin(a) * r),
                    cx + (float) (Math.cos(a) * (r - len)), cy + (float) (Math.sin(a) * (r - len)),
                    t == 0 ? stroke : thin);
        }
        c.restore();
        float py = cy - r + 22;
        path.reset();
        path.moveTo(cx, py);
        path.lineTo(cx - 11, py + 16);
        path.lineTo(cx + 11, py + 16);
        path.close();
        c.drawPath(path, fill);
        // slip / skid: trapezoid under the pointer, full deflection at 10°
        double s = Math.max(-10, Math.min(10, slip));
        float sx = cx + (float) (s * 2.6);
        path.reset();
        path.moveTo(sx - 11, py + 20);
        path.lineTo(sx + 11, py + 20);
        path.lineTo(sx + 15, py + 27);
        path.lineTo(sx - 15, py + 27);
        path.close();
        c.drawPath(path, Math.abs(slip) > 5 ? stroke : fill);

        // fixed aircraft symbol
        float wing = r * 0.42f;
        path.reset();
        path.moveTo(cx - wing - 28, cy);
        path.lineTo(cx - 28, cy);
        path.lineTo(cx, cy + 20);
        path.lineTo(cx + 28, cy);
        path.lineTo(cx + wing + 28, cy);
        stroke.setStrokeWidth(6f);
        c.drawPath(path, stroke);
        stroke.setStrokeWidth(4f);
        c.drawCircle(cx, cy, 4f, fill);

        // turn rate bar under the bezel: marks at standard rate (3°/s)
        if (!Double.isNaN(turnRate)) {
            float ty = cy + r + 14;
            float std = 60f;
            c.drawLine(cx - std, ty - 6, cx - std, ty + 6, thin);
            c.drawLine(cx + std, ty - 6, cx + std, ty + 6, thin);
            double tr2 = Math.max(-6, Math.min(6, turnRate));
            float tx = cx + (float) (tr2 / 3.0 * std);
            c.drawLine(cx, ty, tx, ty, stroke);
        }
    }

    private void drawSensors(Canvas c) {
        float w = getWidth();
        float y = 32;
        c.drawText("Датчики: " + sensorLines.size(), 12, y, mid);
        y += 24;
        c.drawText("колесо — горизонт   OK — меню", 12, y, small);
        y += 22;
        c.drawText("Источник: " + sourceName, 12, y, small);
        y += 22;
        c.drawText(String.format(Locale.US, "вверх (x,y,z): %+.2f %+.2f %+.2f", up[0], up[1], up[2]), 12, y, small);
        y += 22;
        c.drawText(hasPressure ? String.format(Locale.US, "давление: %.2f hPa", pressureHpa) : "датчика давления нет", 12, y, small);
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
            while (s.length() > 4 && small.measureText(s) > w - 24) s = s.substring(0, s.length() - 2);
            c.drawText(s, 12, y, small);
            y += 22;
        }
    }

    private void drawMenu(Canvas c) {
        float w = getWidth();
        float rowH = 38;
        float boxH = 74 + rowH * menuItems.size();
        c.drawRect(0, 0, w, boxH, black);
        c.drawLine(0, boxH, w, boxH, thin);
        c.drawText("МЕНЮ  " + zeroNote, 14, 28, small);
        c.drawText("колесо — выбор, OK — выполнить", 14, 52, small);
        float y = 74;
        for (int i = 0; i < menuItems.size(); i++) {
            float base = y + rowH - 10;
            if (i == menuIndex) {
                c.drawRect(8, y + 2, w - 8, y + rowH - 2, thin);
                c.drawText("›", 16, base, mid);
            }
            c.drawText(menuItems.get(i), 38, base, mid);
            y += rowH;
        }
    }
}
