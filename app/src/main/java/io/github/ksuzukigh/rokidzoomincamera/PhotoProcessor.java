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
    private static final int JPEG_QUALITY = 95;
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

    static Result process(byte[] jpeg, float zoom, float aspect, File cacheDir) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
        int rawWidth = bounds.outWidth;
        int rawHeight = bounds.outHeight;
        int[] crop = PhotoCrop.crop(rawWidth, rawHeight, zoom, aspect);
        if (crop == null) return new Result(jpeg, rawWidth, rawHeight, false);

        Bitmap.Config[] configs = {Bitmap.Config.ARGB_8888, Bitmap.Config.RGB_565};
        for (Bitmap.Config config : configs) {
            Bitmap region = null;
            File temp = null;
            try {
                @SuppressWarnings("deprecation")
                BitmapRegionDecoder decoder =
                        BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.length, false);
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = config;
                region = decoder.decodeRegion(new Rect(crop[0], crop[1], crop[2], crop[3]), options);
                decoder.recycle();
                if (region == null) throw new IOException("decodeRegion returned null");
                temp = File.createTempFile("crop", ".jpg", cacheDir);
                try (FileOutputStream output = new FileOutputStream(temp)) {
                    if (!region.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                        throw new IOException("JPEG compression failed");
                    }
                }
                int width = region.getWidth();
                int height = region.getHeight();
                region.recycle();
                region = null;
                copyExif(jpeg, temp);
                byte[] result = Files.readAllBytes(temp.toPath());
                return new Result(result, width, height, true);
            } catch (OutOfMemoryError error) {
                Log.w(TAG, "Out of memory while cropping with " + config + ", retrying", error);
            } catch (Exception error) {
                Log.e(TAG, "Photo crop failed; saving the full frame", error);
                break;
            } finally {
                if (region != null) region.recycle();
                if (temp != null && !temp.delete()) temp.deleteOnExit();
            }
        }
        return new Result(jpeg, rawWidth, rawHeight, false);
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
