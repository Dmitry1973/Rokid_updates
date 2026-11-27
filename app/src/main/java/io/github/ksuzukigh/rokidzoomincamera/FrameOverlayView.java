package io.github.ksuzukigh.rokidzoomincamera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.view.View;

/**
 * Draws the centre reticle and the area that will end up in the file:
 * a dashed rectangle for the photo frame (16:9 is wide, 9:16 is tall)
 * and corner brackets for the video frame.
 */
final class FrameOverlayView extends View {
    private final Paint reticlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint photoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint videoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint maskPaint = new Paint();
    private float boxLeft;
    private float boxTop;
    private float boxWidth;
    private float boxHeight;
    private float previewAspect = 4f / 3f;
    /** Width / height as viewed; 0 = the whole frame. */
    private float photoAspect = 0f;
    /** Width / height of the video as viewed (16:9 wide, 9:16 tall). */
    private float videoAspect = 16f / 9f;
    private boolean recording;
    private boolean frameOnly;
    /** Frame-only mode: size of the frame relative to the picture area (calibration / zoom). */
    private float frameScale = 1f;
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String photoLabel;
    private String videoLabel;

    FrameOverlayView(Context context) {
        super(context);
        setClickable(false);
        float density = getResources().getDisplayMetrics().density;
        reticlePaint.setColor(Color.argb(190, 255, 255, 255));
        reticlePaint.setStyle(Paint.Style.STROKE);
        reticlePaint.setStrokeWidth(2f * density);
        photoPaint.setColor(Color.argb(200, 140, 255, 140));
        photoPaint.setStyle(Paint.Style.STROKE);
        photoPaint.setStrokeWidth(2f * density);
        photoPaint.setPathEffect(new DashPathEffect(new float[]{10f * density, 6f * density}, 0f));
        videoPaint.setColor(Color.argb(230, 255, 255, 255));
        videoPaint.setStyle(Paint.Style.STROKE);
        videoPaint.setStrokeWidth(3f * density);
        // Everything outside the frame that goes into the file is dimmed, so the
        // visible picture has the chosen shape (4:3, 16:9, 3:2, 1:1).
        labelPaint.setColor(Color.rgb(90, 190, 90));
        labelPaint.setTextSize(11f * getResources().getDisplayMetrics().scaledDensity);
        outlinePaint.setColor(Color.argb(255, 160, 255, 160));
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(3f * density);
        maskPaint.setColor(Color.argb(205, 0, 0, 0));
        maskPaint.setStyle(Paint.Style.FILL);
    }

    /** Bounds of the full camera image in this view's coordinates. */
    void setPreviewBounds(float left, float top, float width, float height, float aspect) {
        boxLeft = left;
        boxTop = top;
        boxWidth = width;
        boxHeight = height;
        previewAspect = Math.max(1f, aspect);
        invalidate();
    }

    void setFrames(float photo, float video, boolean isRecording) {
        photoAspect = photo;
        videoAspect = video;
        recording = isRecording;
        invalidate();
    }

    /**
     * Frame-only mode: no camera picture, just the outline of what gets captured.
     * @param scale calibration divided by zoom; 1 = the whole picture area
     */
    /** Small captions drawn at the frame, e.g. "ФОТО 3:4 весь кадр", "ВИДЕО 16:9". */
    void setFrameLabels(String photo, String video) {
        photoLabel = photo;
        videoLabel = video;
        invalidate();
    }

    void setFrameOnly(boolean enabled, float scale) {
        frameOnly = enabled;
        frameScale = scale;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float gap = 8f * density;
        float length = 22f * density;
        canvas.drawLine(cx - length, cy, cx - gap, cy, reticlePaint);
        canvas.drawLine(cx + gap, cy, cx + length, cy, reticlePaint);
        canvas.drawLine(cx, cy - length, cx, cy - gap, reticlePaint);
        canvas.drawLine(cx, cy + gap, cx, cy + length, reticlePaint);

        if (boxWidth <= 0f || boxHeight <= 0f) return;
        float inset = 3f * density;
        if (frameOnly) {
            float aspect = recording ? videoAspect : photoAspect;
            float sw = boxWidth * frameScale;
            float sh = boxHeight * frameScale;
            float sl = boxLeft + (boxWidth - sw) / 2f;
            float st = boxTop + (boxHeight - sh) / 2f;
            float[] r = clip(PhotoCrop.fitFrame(sl, st, sw, sh, aspect), inset);
            // Light frame so it does not distract while filming: corner brackets
            // plus three short ticks on each side.
            float shortSide = Math.min(r[2] - r[0], r[3] - r[1]);
            drawCorners(canvas, r, Math.min(26f * density, shortSide * 0.25f), outlinePaint);
            drawTicks(canvas, r, 5f * density, outlinePaint);
            String label = recording ? videoLabel : photoLabel;
            if (label != null) {
                float y = r[1] - 4f * density;
                if (y < labelPaint.getTextSize()) y = r[1] + labelPaint.getTextSize() + 4f * density;
                canvas.drawText(label, r[0] + 4f * density, y, labelPaint);
            }
            return;
        }
        float[] f = recording
                ? PhotoCrop.fitFrame(boxLeft, boxTop, boxWidth, boxHeight, videoAspect)
                : PhotoCrop.fitFrame(boxLeft, boxTop, boxWidth, boxHeight, photoAspect);
        float w = getWidth();
        float h = getHeight();
        canvas.drawRect(0f, 0f, w, f[1], maskPaint);
        canvas.drawRect(0f, f[3], w, h, maskPaint);
        canvas.drawRect(0f, f[1], f[0], f[3], maskPaint);
        canvas.drawRect(f[2], f[1], w, f[3], maskPaint);
        if (!recording && photoAspect > 0f) {
            float[] r = clip(f, inset);
            canvas.drawRect(r[0], r[1], r[2], r[3], photoPaint);
        }
        if (recording) {
            float[] r = clip(f, inset);
            drawCorners(canvas, r, 26f * density, videoPaint);
        }
    }

    private void drawTicks(Canvas canvas, float[] r, float half, Paint paint) {
        float w = r[2] - r[0];
        float h = r[3] - r[1];
        for (int i = 1; i <= 3; i++) {
            float x = r[0] + w * i / 4f;
            float y = r[1] + h * i / 4f;
            canvas.drawLine(x - half, r[1], x + half, r[1], paint);
            canvas.drawLine(x - half, r[3], x + half, r[3], paint);
            canvas.drawLine(r[0], y - half, r[0], y + half, paint);
            canvas.drawLine(r[2], y - half, r[2], y + half, paint);
        }
    }

    private float[] clip(float[] r, float inset) {
        return new float[]{
                Math.max(r[0], inset), Math.max(r[1], inset),
                Math.min(r[2], getWidth() - inset), Math.min(r[3], getHeight() - inset)
        };
    }

    private void drawCorners(Canvas canvas, float[] r, float size, Paint videoPaint) {
        float l = r[0], t = r[1], rr = r[2], b = r[3];
        canvas.drawLine(l, t, l + size, t, videoPaint);
        canvas.drawLine(l, t, l, t + size, videoPaint);
        canvas.drawLine(rr, t, rr - size, t, videoPaint);
        canvas.drawLine(rr, t, rr, t + size, videoPaint);
        canvas.drawLine(l, b, l + size, b, videoPaint);
        canvas.drawLine(l, b, l, b - size, videoPaint);
        canvas.drawLine(rr, b, rr - size, b, videoPaint);
        canvas.drawLine(rr, b, rr, b - size, videoPaint);
    }
}
