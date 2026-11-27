package io.github.ksuzukigh.rokidzoomincamera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Applies the digital zoom and the chosen aspect ratio to a full-sensor JPEG.
 * The camera is asked for the full frame and the crop is done here, so the
 * saved photo always matches the zoom shown in the preview, whatever the
 * camera HAL does with SCALER_CROP_REGION for JPEG captures.
 */
final class PhotoProcessor {
    private static final String TAG = "RokidZoomRu";
    private static final String[] EXIF_TAGS = {
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
    };

    static final class Result {
        final byte[] jpeg;
        final int width;
        final int height;
        final boolean cropped;

        Result(byte[] jpeg, int width, int height, boolean cropped) {
            this.jpeg = jpeg;
            this.width = width;
            this.height = height;
            this.cropped = cropped;
        }
    }

    private PhotoProcessor() {}

    /**
     * @param maxLongSide limit for the long side of the saved photo, 0 = no limit
     * @param quality     JPEG quality 1..100 used when the photo has to be re-encoded
     */
    static Result process(byte[] jpeg, float zoom, float aspect, int maxLongSide, int quality,
                          File cacheDir) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
        int rawWidth = bounds.outWidth;
        int rawHeight = bounds.outHeight;
        int[] crop = PhotoCrop.crop(rawWidth, rawHeight, zoom, aspect, isRotated(jpeg));
        int[] region = crop == null ? new int[]{0, 0, rawWidth, rawHeight} : crop;
        int regionWidth = region[2] - region[0];
        int regionHeight = region[3] - region[1];
        int[] target = PhotoCrop.limitSize(regionWidth, regionHeight, maxLongSide);
        boolean resize = target[0] != regionWidth || target[1] != regionHeight;
        // Nothing to do: the camera already encoded the full frame at the chosen quality.
        if (crop == null && !resize) return new Result(jpeg, rawWidth, rawHeight, false);
        int q = Math.max(1, Math.min(100, quality));

        Bitmap.Config[] configs = {Bitmap.Config.ARGB_8888, Bitmap.Config.RGB_565};
        for (Bitmap.Config config : configs) {
            Bitmap bitmap = null;
            File temp = null;
            try {
                @SuppressWarnings("deprecation")
                BitmapRegionDecoder decoder =
                        BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.length, false);
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = config;
                options.inSampleSize = PhotoCrop.sampleSize(
                        Math.max(regionWidth, regionHeight), Math.max(target[0], target[1]));
                bitmap = decoder.decodeRegion(new Rect(region[0], region[1], region[2], region[3]), options);
                decoder.recycle();
                if (bitmap == null) throw new IOException("decodeRegion returned null");
                if (bitmap.getWidth() != target[0] || bitmap.getHeight() != target[1]) {
                    Bitmap scaled = Bitmap.createScaledBitmap(bitmap, target[0], target[1], true);
                    if (scaled != bitmap) bitmap.recycle();
                    bitmap = scaled;
                }
                temp = File.createTempFile("crop", ".jpg", cacheDir);
                try (FileOutputStream output = new FileOutputStream(temp)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, q, output)) {
                        throw new IOException("JPEG compression failed");
                    }
                }
                int width = bitmap.getWidth();
                int height = bitmap.getHeight();
                bitmap.recycle();
                bitmap = null;
                copyExif(jpeg, temp);
                byte[] result = Files.readAllBytes(temp.toPath());
                return new Result(result, width, height, true);
            } catch (OutOfMemoryError error) {
                Log.w(TAG, "Out of memory while processing with " + config + ", retrying", error);
            } catch (Exception error) {
                Log.e(TAG, "Photo processing failed; saving the full frame", error);
                break;
            } finally {
                if (bitmap != null) bitmap.recycle();
                if (temp != null && !temp.delete()) temp.deleteOnExit();
            }
        }
        return new Result(jpeg, rawWidth, rawHeight, false);
    }

    /** True when EXIF says the picture is viewed turned by 90/270 degrees. */
    static boolean isRotated(byte[] jpeg) {
        try {
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(jpeg));
            int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            return o == ExifInterface.ORIENTATION_ROTATE_90 || o == ExifInterface.ORIENTATION_ROTATE_270
                    || o == ExifInterface.ORIENTATION_TRANSPOSE || o == ExifInterface.ORIENTATION_TRANSVERSE;
        } catch (Exception error) {
            return false;
        }
    }

    private static void copyExif(byte[] source, File target) {
        try {
            ExifInterface in = new ExifInterface(new ByteArrayInputStream(source));
            ExifInterface out = new ExifInterface(target.getAbsolutePath());
            for (String tag : EXIF_TAGS) {
                String value = in.getAttribute(tag);
                if (value != null) out.setAttribute(tag, value);
            }
            out.saveAttributes();
        } catch (Exception error) {
            Log.w(TAG, "Could not copy EXIF data", error);
        }
    }
}
