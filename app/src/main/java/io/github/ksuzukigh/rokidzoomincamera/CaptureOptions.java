package io.github.ksuzukigh.rokidzoomincamera;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * User-selectable capture settings and the on-glasses settings menu model.
 * Pure Java so it can be unit tested without a device.
 */
final class CaptureOptions {
    static final int ITEM_PHOTO_ASPECT = 0;
    static final int ITEM_VIDEO_SIZE = 1;
    static final int ITEM_FPS = 2;
    static final int ITEM_TAP_SHUTTER = 3;
    static final int ITEM_PREVIEW_SIZE = 4;
    static final int ITEM_PREVIEW_SHIFT = 5;
    static final int ITEM_COUNT = 6;

    /** Preview window size, percent of the HUD. */
    static final int[] PREVIEW_SIZES = {100, 90, 80, 70, 60, 50};
    /** Vertical shift of the whole picture, percent of HUD height (negative = up). */
    static final int[] PREVIEW_SHIFTS = {-30, -25, -20, -15, -10, -5, 0, 5, 10};
    static final int PREVIEW_SHIFT_DEFAULT_INDEX = 6;

    static final float[] PHOTO_ASPECTS = {4f / 3f, 16f / 9f, 3f / 2f, 1f};
    private static final String[] PHOTO_ASPECT_LABELS = {"4:3 (весь кадр)", "16:9", "3:2", "1:1"};

    private final List<int[]> videoSizes = new ArrayList<>();
    private final List<Integer> frameRates = new ArrayList<>();
    int photoAspectIndex = 0;
    int videoSizeIndex = 0;
    int fpsIndex = 0;
    boolean tapShutter = true;
    int previewSizeIndex = 0;
    int previewShiftIndex = PREVIEW_SHIFT_DEFAULT_INDEX;

    /** Sizes are {width, height}; frame rates in frames per second. */
    void setAvailable(List<int[]> sizes, List<Integer> fps) {
        videoSizes.clear();
        for (int[] size : sizes) {
            boolean duplicate = false;
            for (int[] existing : videoSizes) {
                if (existing[0] == size[0] && existing[1] == size[1]) duplicate = true;
            }
            if (!duplicate) videoSizes.add(new int[]{size[0], size[1]});
        }
        Collections.sort(videoSizes, (a, b) -> Long.compare((long) b[0] * b[1], (long) a[0] * a[1]));
        frameRates.clear();
        for (Integer value : fps) {
            if (value != null && value > 0 && !frameRates.contains(value)) frameRates.add(value);
        }
        Collections.sort(frameRates);
        if (frameRates.isEmpty()) frameRates.add(30);
        videoSizeIndex = clampIndex(videoSizeIndex, videoSizes.size());
        fpsIndex = clampIndex(fpsIndex, frameRates.size());
    }

    void selectVideoSize(int width, int height) {
        for (int i = 0; i < videoSizes.size(); i++) {
            if (videoSizes.get(i)[0] == width && videoSizes.get(i)[1] == height) {
                videoSizeIndex = i;
                return;
            }
        }
    }

    /** Picks the largest 16:9 size up to 1080p, the original app's default. */
    void selectDefaultVideoSize() {
        for (int i = 0; i < videoSizes.size(); i++) {
            int[] s = videoSizes.get(i);
            long pixels = (long) s[0] * s[1];
            float ratio = (float) Math.max(s[0], s[1]) / Math.min(s[0], s[1]);
            if (pixels <= 1920L * 1080L && Math.abs(ratio - 16f / 9f) < 0.08f) {
                videoSizeIndex = i;
                return;
            }
        }
        videoSizeIndex = 0;
    }

    void selectFps(int fps) {
        int best = 0;
        for (int i = 0; i < frameRates.size(); i++) {
            if (Math.abs(frameRates.get(i) - fps) < Math.abs(frameRates.get(best) - fps)) best = i;
        }
        fpsIndex = best;
    }

    void selectPhotoAspect(int index) {
        photoAspectIndex = clampIndex(index, PHOTO_ASPECTS.length);
    }

    float photoAspect() {
        return PHOTO_ASPECTS[photoAspectIndex];
    }

    int[] videoSize() {
        return videoSizes.isEmpty() ? null : videoSizes.get(videoSizeIndex);
    }

    float videoAspect() {
        int[] size = videoSize();
        if (size == null) return 16f / 9f;
        return (float) Math.max(size[0], size[1]) / Math.min(size[0], size[1]);
    }

    int fps() {
        return frameRates.get(fpsIndex);
    }

    /** Moves the value of a menu item one step; wraps around. */
    void change(int item, int direction) {
        int step = direction >= 0 ? 1 : -1;
        if (item == ITEM_PHOTO_ASPECT) {
            photoAspectIndex = wrap(photoAspectIndex + step, PHOTO_ASPECTS.length);
        } else if (item == ITEM_VIDEO_SIZE && !videoSizes.isEmpty()) {
            videoSizeIndex = wrap(videoSizeIndex + step, videoSizes.size());
        } else if (item == ITEM_FPS) {
            fpsIndex = wrap(fpsIndex + step, frameRates.size());
        } else if (item == ITEM_TAP_SHUTTER) {
            tapShutter = !tapShutter;
        } else if (item == ITEM_PREVIEW_SIZE) {
            previewSizeIndex = clampIndex(previewSizeIndex - step, PREVIEW_SIZES.length);
        } else if (item == ITEM_PREVIEW_SHIFT) {
            previewShiftIndex = clampIndex(previewShiftIndex - step, PREVIEW_SHIFTS.length);
        }
    }

    String itemName(int item) {
        switch (item) {
            case ITEM_PHOTO_ASPECT: return "Формат фото";
            case ITEM_VIDEO_SIZE: return "Разрешение видео";
            case ITEM_FPS: return "Кадров в секунду";
            case ITEM_TAP_SHUTTER: return "Съёмка тапом по дужке";
            case ITEM_PREVIEW_SIZE: return "Размер картинки";
            case ITEM_PREVIEW_SHIFT: return "Высота картинки";
            default: return "";
        }
    }

    void selectPreview(int sizeIndex, int shiftIndex) {
        previewSizeIndex = clampIndex(sizeIndex, PREVIEW_SIZES.length);
        previewShiftIndex = clampIndex(shiftIndex, PREVIEW_SHIFTS.length);
    }

    /** 1.0 = full HUD. */
    float previewScale() {
        return PREVIEW_SIZES[previewSizeIndex] / 100f;
    }

    /** Fraction of HUD height, negative moves the picture up. */
    float previewShift() {
        return PREVIEW_SHIFTS[previewShiftIndex] / 100f;
    }

    String valueLabel(int item) {
        switch (item) {
            case ITEM_PHOTO_ASPECT: return PHOTO_ASPECT_LABELS[photoAspectIndex];
            case ITEM_VIDEO_SIZE: {
                int[] s = videoSize();
                return s == null ? "—" : String.format(Locale.US, "%d×%d", s[0], s[1]);
            }
            case ITEM_FPS: return fps() + " к/с";
            case ITEM_TAP_SHUTTER: return tapShutter ? "вкл" : "выкл";
            case ITEM_PREVIEW_SIZE: return PREVIEW_SIZES[previewSizeIndex] + "%";
            case ITEM_PREVIEW_SHIFT: {
                int v = PREVIEW_SHIFTS[previewShiftIndex];
                return v == 0 ? "по центру" : (v < 0 ? "выше на " + (-v) + "%" : "ниже на " + v + "%");
            }
            default: return "";
        }
    }

    private static int wrap(int value, int size) {
        if (size <= 0) return 0;
        return ((value % size) + size) % size;
    }

    private static int clampIndex(int value, int size) {
        if (size <= 0) return 0;
        return Math.max(0, Math.min(size - 1, value));
    }
}
