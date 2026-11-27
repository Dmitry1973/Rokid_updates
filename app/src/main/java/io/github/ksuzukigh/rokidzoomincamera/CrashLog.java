package io.github.ksuzukigh.rokidzoomincamera;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Keeps the stack trace of a crash. The crash handler only writes a private
 * file (safe while the process dies); on the next start the trace is copied to
 * Download/ZoomCamera_crash_*.txt so it can be sent from the phone gallery/files.
 */
final class CrashLog {
    private static final String TAG = "RokidZoomRu";
    private static final String FILE = "last_crash.txt";
    private static boolean installed;

    private CrashLog() {}

    static void install(Context context) {
        if (installed) return;
        installed = true;
        final File file = new File(context.getFilesDir(), FILE);
        String v = "?";
        try {
            v = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        final String version = v;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, Throwable error) {
                try (FileOutputStream out = new FileOutputStream(file)) {
                    StringWriter text = new StringWriter();
                    PrintWriter writer = new PrintWriter(text);
                    writer.println("ZOOM Камера RU " + version + " · " +
                            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
                    writer.println("thread: " + thread.getName());
                    error.printStackTrace(writer);
                    writer.flush();
                    out.write(text.toString().getBytes(StandardCharsets.UTF_8));
                } catch (Throwable ignored) {
                }
                if (previous != null) previous.uncaughtException(thread, error);
            }
        });
    }

    /**
     * Moves a crash saved by the previous run to Download. Returns a short
     * description for the HUD, or null when there was no crash.
     */
    static String collect(Context context) {
        File file = new File(context.getFilesDir(), FILE);
        if (!file.exists()) return null;
        String summary = "Прошлый запуск: сбой";
        try {
            byte[] data = Files.readAllBytes(file.toPath());
            String text = new String(data, StandardCharsets.UTF_8);
            String[] lines = text.split("\n");
            if (lines.length > 2) {
                String cause = lines[2].trim();
                int colon = cause.indexOf(':');
                String name = colon > 0 ? cause.substring(0, colon) : cause;
                summary += " (" + name.substring(name.lastIndexOf('.') + 1) + ")";
            }
            String name = "ZoomCamera_crash_" +
                    new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/");
            Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (OutputStream out = context.getContentResolver().openOutputStream(uri, "w")) {
                    if (out != null) out.write(data);
                }
                summary += "\nотчёт: Download/" + name;
            }
        } catch (Exception error) {
            Log.w(TAG, "Could not copy the crash report", error);
        }
        if (!file.delete()) file.deleteOnExit();
        return summary;
    }
}
