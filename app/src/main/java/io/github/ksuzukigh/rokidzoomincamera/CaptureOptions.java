package io.github.ksuzukigh.rokidzoomincamera;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * User-selectable capture settings and the on-glasses settings menu model.
 * Pure Java so it can be unit tested without a device.
 */
final class CaptureOptions {
    static final int ITEM_PHOTO_ASPECT = 0;
    static final int ITEM_PHOTO_SIZE = 1;
    static final int ITEM_JPEG_QUALITY = 2;
    static final int ITEM_VIDEO_ASPECT = 3;
    static final int ITEM_VIDEO_SIZE = 4;
    static final int ITEM_FPS = 5;
    static final int ITEM_TAP_SHUTTER = 6;
    static final int ITEM_PREVIEW_SIZE = 7;
    static final int ITEM_PREVIEW_SHIFT = 8;
    static final int ITEM_VIEW_MODE = 9;
    static final int ITEM_FRAME_SCALE = 10;
    static final int ITEM_SWIPE_DIR = 11;
    static final int ITEM_ZOOM_STEP = 12;
    static final int ITEM_HELP = 13;
    static final int ITEM_CLOSE = 14;
    static final int ITEM_COUNT = 15;

    /** Limit for the long side of saved photos, pixels; 0 = full size. */
    static final int[] PHOTO_SIZES = {0, 3000, 2000, 1280};
    static final int[] JPEG_QUALITIES = {100, 95, 90, 85, 80, 70};
    static final int JPEG_QUALITY_DEFAULT_INDEX = 1;

    /** Preview window size, percent of the HUD. */
    static final int[] PREVIEW_SIZES = {100, 90, 80, 70, 60, 50};
    /** Vertical shift of the whole picture, percent of HUD height (negative = up). */
    static final int[] PREVIEW_SHIFTS = {-30, -25, -20, -15, -10, -5, 0, 5, 10};
    /** Default 90 % lifted by 5 %: top edge stays, bottom edge moves up into the readable area. */
    static final int PREVIEW_SIZE_DEFAULT_INDEX = 1;
    static final int PREVIEW_SHIFT_DEFAULT_INDEX = 5;

    /** Width / height of the photo as viewed; 0 = the whole camera frame. */
    static final float[] PHOTO_ASPECTS = {0f, 16f / 9f, 9f / 16f, 4f / 3f, 3f / 4f, 3f / 2f, 2f / 3f, 1f};
    private static final String[] PHOTO_ASPECT_LABELS = {
            "весь кадр", "16:9 гориз.", "9:16 верт.", "4:3 гориз.", "3:4 верт.",
            "3:2 гориз.", "2:3 верт.", "1:1"};
    /** Width / height of the video as viewed; 0 = as the camera gives it (no re-rendering). */
    static final float[] VIDEO_ASPECTS = {0f, 16f / 9f, 9f / 16f, 4f / 3f, 3f / 4f, 1f};
    private static final String[] VIDEO_ASPECT_LABELS = {
            "родной", "16:9 гориз.", "9:16 верт.", "4:3 гориз.", "3:4 верт.", "1:1"};
    /**
     * Size of the 1x frame in "frame only" mode, percent of the picture area.
     * Lets the frame be matched to what the eye sees through the glasses.
     */
    static final int[] FRAME_SCALES = {50, 70, 85, 100, 120, 150, 200, 300};
    static final int FRAME_SCALE_DEFAULT_INDEX = 3;
    /** Zoom change per wheel click / swipe. */
    static final float[] ZOOM_STEPS = {0.1f, 0.2f, 0.5f};

    private final List<int[]> videoSizes = new ArrayList<>();
    private final List<Integer> frameRates = new ArrayList<>();
    int photoAspectIndex = 0;
    int photoSizeIndex = 0;
    int jpegQualityIndex = JPEG_QUALITY_DEFAULT_INDEX;
    int videoSizeIndex = 0;
    int fpsIndex = 0;
    boolean tapShutter = true;
    boolean swipeReversed = false;
    int zoomStepIndex = 0;
    int videoAspectIndex = 0;
    /** true = no camera picture on the HUD, only the outline of what gets captured. */
    boolean frameOnly = true;
    int frameScaleIndex = FRAME_SCALE_DEFAULT_INDEX;
    /** True when the camera picture is turned 90/270 degrees to be upright (set by the activity). */
    boolean cameraTurned = false;
    int previewSizeIndex = PREVIEW_SIZE_DEFAULT_INDEX;
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
        Collections.sort(videoSizes, new Comparator<int[]>() {
            @Override public int compare(int[] a, int[] b) {
                return Long.compare((long) b[0] * b[1], (long) a[0] * a[1]);
            }
        });
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

    void selectPhotoSize(int index) {
        photoSizeIndex = clampIndex(index, PHOTO_SIZES.length);
    }

    void selectJpegQuality(int index) {
        jpegQualityIndex = clampIndex(index, JPEG_QUALITIES.length);
    }

    /** 0 = no limit. */
    int photoMaxLongSide() {
        return PHOTO_SIZES[photoSizeIndex];
    }

    int jpegQuality() {
        return JPEG_QUALITIES[jpegQualityIndex];
    }

    /** Items that do something on tap instead of holding a value. */
    static boolean isAction(int item) {
        return item == ITEM_HELP || item == ITEM_CLOSE;
    }

    void selectFrameScale(int index) {
        frameScaleIndex = clampIndex(index, FRAME_SCALES.length);
    }

    /** 1.0 = the 1x frame fills the picture area. */
    float frameScale() {
        return FRAME_SCALES[frameScaleIndex] / 100f;
    }

    void selectVideoAspect(int index) {
        videoAspectIndex = clampIndex(index, VIDEO_ASPECTS.length);
    }

    /** Chosen video shape (width / height as viewed); 0 = native. */
    float videoFormat() {
        return VIDEO_ASPECTS[videoAspectIndex];
    }

    /** Width / height of the native video as viewed (the camera size turned upright). */
    float nativeVideoAspect() {
        int[] size = videoSize();
        if (size == null) return cameraTurned ? 9f / 16f : 16f / 9f;
        int w = Math.max(size[0], size[1]);
        int h = Math.min(size[0], size[1]);
        return cameraTurned ? (float) h / w : (float) w / h;
    }

    /** Width / height of the video file as viewed, whatever is chosen. */
    float videoViewAspect() {
        float f = videoFormat();
        return f > 0f ? f : nativeVideoAspect();
    }

    float zoomStep() {
        return ZOOM_STEPS[zoomStepIndex];
    }

    void selectZoomStep(int index) {
        zoomStepIndex = clampIndex(index, ZOOM_STEPS.length);
    }

    float photoAspect() {
        return PHOTO_ASPECTS[photoAspectIndex];
    }

    /** All recorder sizes {width, height}, largest first. */
    List<int[]> allVideoSizes() {
        return Collections.unmodifiableList(videoSizes);
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
        } else if (item == ITEM_PHOTO_SIZE) {
            photoSizeIndex = wrap(photoSizeIndex + step, PHOTO_SIZES.length);
        } else if (item == ITEM_JPEG_QUALITY) {
            // forward = better quality
            jpegQualityIndex = clampIndex(jpegQualityIndex - step, JPEG_QUALITIES.length);
        } else if (item == ITEM_VIDEO_ASPECT) {
            videoAspectIndex = wrap(videoAspectIndex + step, VIDEO_ASPECTS.length);
        } else if (item == ITEM_VIDEO_SIZE && !videoSizes.isEmpty()) {
            videoSizeIndex = wrap(videoSizeIndex + step, videoSizes.size());
        } else if (item == ITEM_FPS) {
            fpsIndex = wrap(fpsIndex + step, frameRates.size());
        } else if (item == ITEM_TAP_SHUTTER) {
            tapShutter = !tapShutter;
        } else if (item == ITEM_VIEW_MODE) {
            frameOnly = !frameOnly;
        } else if (item == ITEM_FRAME_SCALE) {
            frameScaleIndex = clampIndex(frameScaleIndex + step, FRAME_SCALES.length);
        } else if (item == ITEM_ZOOM_STEP) {
            zoomStepIndex = wrap(zoomStepIndex + step, ZOOM_STEPS.length);
        } else if (item == ITEM_SWIPE_DIR) {
            swipeReversed = !swipeReversed;
        } else if (item == ITEM_PREVIEW_SIZE) {
            previewSizeIndex = clampIndex(previewSizeIndex - step, PREVIEW_SIZES.length);
        } else if (item == ITEM_PREVIEW_SHIFT) {
            previewShiftIndex = clampIndex(previewShiftIndex - step, PREVIEW_SHIFTS.length);
        }
    }

    String itemName(int item) {
        switch (item) {
            case ITEM_PHOTO_ASPECT: return "Формат ФОТО";
            case ITEM_PHOTO_SIZE: return "Размер ФОТО";
            case ITEM_JPEG_QUALITY: return "Качество JPEG";
            case ITEM_VIDEO_ASPECT: return "Формат ВИДЕО";
            case ITEM_VIDEO_SIZE: return "Разрешение ВИДЕО";
            case ITEM_FPS: return "Кадров/с ВИДЕО";
            case ITEM_TAP_SHUTTER: return "Съёмка тапом";
            case ITEM_PREVIEW_SIZE: return "Размер картинки";
            case ITEM_PREVIEW_SHIFT: return "Высота картинки";
            case ITEM_SWIPE_DIR: return "Свайп зума";
            case ITEM_ZOOM_STEP: return "Шаг зума";
            case ITEM_VIEW_MODE: return "Вид на экране";
            case ITEM_FRAME_SCALE: return "Масштаб рамки";
            case ITEM_HELP: return "Справка";
            case ITEM_CLOSE: return "Закрыть меню";
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
            case ITEM_PHOTO_SIZE: {
                int v = PHOTO_SIZES[photoSizeIndex];
                return v == 0 ? "максимум" : "до " + v + " px";
            }
            case ITEM_JPEG_QUALITY: return JPEG_QUALITIES[jpegQualityIndex] + "%";
            case ITEM_SWIPE_DIR: return swipeReversed ? "обратный" : "обычный";
            case ITEM_ZOOM_STEP: return String.format(Locale.US, "%.1f× за щелчок", zoomStep());
            case ITEM_VIEW_MODE: return frameOnly ? "только рамка" : "картинка камеры";
            case ITEM_FRAME_SCALE: return FRAME_SCALES[frameScaleIndex] + "%";
            case ITEM_HELP: return "нажать колесо";
            case ITEM_CLOSE: return "колесо / правая кн.";
            case ITEM_VIDEO_ASPECT: {
                if (videoAspectIndex != 0) return VIDEO_ASPECT_LABELS[videoAspectIndex];
                return "родной " + aspectName(nativeVideoAspect());
            }
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

    /** "16:9", "9:16", "4:3", ... for known shapes. */
    static String aspectName(float aspect) {
        float[] values = {16f / 9f, 9f / 16f, 4f / 3f, 3f / 4f, 3f / 2f, 2f / 3f, 1f};
        String[] names = {"16:9", "9:16", "4:3", "3:4", "3:2", "2:3", "1:1"};
        for (int i = 0; i < values.length; i++) {
            if (Math.abs(aspect - values[i]) < 0.02f) return names[i];
        }
        return String.format(Locale.US, "%.2f", aspect);
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
