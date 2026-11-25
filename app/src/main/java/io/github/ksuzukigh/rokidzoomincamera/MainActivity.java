package io.github.ksuzukigh.rokidzoomincamera;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String TAG = "RokidZoomRu";
    private static final int CAMERA_PERMISSION = 7;
    private static final int AUDIO_PERMISSION = 8;
    private static final long TOUCH_BUTTON_SUPPRESSION_MS = 1200L;
    private static final long RECONNECT_DELAY_MS = 2000L;
    private static final long BUTTON_CONFLICT_WINDOW_MS = 4000L;
    private static final float[] ZOOM_STEPS = {1f, 1.5f, 2f, 2.5f, 3f, 3.5f, 4f};
    private static final String BUTTON_DOWN = "com.android.action.ACTION_SPRITE_BUTTON_DOWN";
    private static final String BUTTON_UP = "com.android.action.ACTION_SPRITE_BUTTON_UP";
    private static final String BUTTON_LONG = "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS";
    private static final String BUTTON_VERY_LONG = "com.android.action.ACTION_SPRITE_BUTTON_VERY_VERY_LONG_PRESS";
    private static final String PREFS = "settings";
    private static final String PHOTO_FILE_PREFIX = "RokidZoomRu_";

    private final Handler mainHandler = new Handler();
    private final CaptureOptions options = new CaptureOptions();
    private FrameLayout root;
    private FrameLayout stage;
    private TextureView preview;
    private FrameOverlayView overlay;
    private TextView zoomLabel;
    private TextView statusLabel;
    private TextView recordingIndicator;
    private TextView batteryLabel;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraManager cameraManager;
    private String cameraId;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private CaptureRequest.Builder repeatingBuilder;
    private ImageReader imageReader;
    private Size previewSize;
    private Rect sensorRect;
    private float maximumZoom = 1f;
    private int sensorOrientation;
    private boolean videoStabilizationSupported;
    private final List<Integer> fixedFpsRanges = new ArrayList<>();
    private int zoomIndex = 0;
    private boolean opening;
    private boolean resumed;
    private volatile boolean takingPhoto;
    private float photoZoom = 1f;
    private float photoAspect = 4f / 3f;
    private boolean recording;
    private boolean recorderStarted;
    private boolean stopVideoRequested;
    private boolean stopVideoScheduled;
    private boolean videoStartPending;
    private boolean buttonReceiverRegistered;
    private boolean batteryReceiverRegistered;
    private boolean availabilityRegistered;
    private boolean userLeaving;
    private boolean settingsOpen;
    private int settingsItem;
    private long videoStartedAt;
    private boolean longPressHandled;
    private long buttonDownAt;
    private long lastPhysicalButtonAt = -1L;
    private float touchDownX;
    private float touchDownY;
    private long touchDownAt;
    private boolean touchMoved;
    private boolean touchLongFired;
    private long lastTouchEventAt = -1L;
    private long lastTouchUpAt = -1L;
    private boolean singleTapPending;
    private MediaRecorder recorder;
    private Uri pendingVideo;
    private ParcelFileDescriptor videoFile;
    private PowerManager.WakeLock recordingWakeLock;
    private PowerManager.WakeLock displayWakeLock;

    private final Runnable hideStatus = () -> {
        if (!settingsOpen && !recording && statusLabel != null) statusLabel.setVisibility(View.GONE);
    };
    private final Runnable finishVideo = this::finishVideoNow;
    private final Runnable wakeDisplay = this::wakeDisplayForRecording;
    private final Runnable singleTap = () -> {
        singleTapPending = false;
        onSingleTap();
    };
    private final Runnable touchLongPress = () -> {
        touchLongFired = true;
        onTouchLongPress();
    };
    private final Runnable reconnect = new Runnable() {
        @Override public void run() {
            if (!resumed || camera != null) return;
            if (!opening) openCamera();
            mainHandler.postDelayed(this, RECONNECT_DELAY_MS);
        }
    };
    private final Runnable updateRecordingClock = new Runnable() {
        @Override public void run() {
            if (!recording || !recorderStarted || recordingIndicator == null) return;
            long seconds = Math.max(0L, (SystemClock.elapsedRealtime() - videoStartedAt) / 1000L);
            recordingIndicator.setText(RecordingDisplay.label(seconds));
            mainHandler.postDelayed(this, 1000L);
        }
    };
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!resumed && !recording) return;
            sendSafetyAction(ButtonSafetyService.ACTION_HEARTBEAT);
            mainHandler.postDelayed(this, 2000L);
        }
    };

    private final CameraManager.AvailabilityCallback availabilityCallback =
            new CameraManager.AvailabilityCallback() {
                @Override public void onCameraAvailable(String id) {
                    if (id.equals(cameraId) && camera == null && resumed && !opening) {
                        Log.i(TAG, "Camera became available again; reopening");
                        openCamera();
                    }
                }
            };

    private final BroadcastReceiver buttonReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            long eventAt = SystemClock.elapsedRealtime();
            if (TouchButtonGuard.shouldIgnore(lastTouchEventAt, eventAt, TOUCH_BUTTON_SUPPRESSION_MS)) {
                Log.d(TAG, "Ignoring touch-derived button action=" + action);
                return;
            }
            lastPhysicalButtonAt = eventAt;
            if (BUTTON_DOWN.equals(action)) {
                buttonDownAt = eventAt;
                longPressHandled = false;
            } else if (BUTTON_LONG.equals(action) || BUTTON_VERY_LONG.equals(action)) {
                if (!longPressHandled) {
                    longPressHandled = true;
                    if (settingsOpen) closeSettings();
                    else if (!recording) startVideo();
                }
            } else if (BUTTON_UP.equals(action)) {
                if (settingsOpen && !longPressHandled) {
                    closeSettings();
                } else if (recording && !longPressHandled) {
                    stopVideo();
                } else if (!longPressHandled && eventAt - buttonDownAt < 900L) {
                    takePhoto();
                }
            }
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL;
            if (batteryLabel != null) {
                batteryLabel.setText(BatteryDisplay.label(BatteryDisplay.percentage(level, scale), charging));
            }
        }
    };

    private final TextureView.SurfaceTextureListener surfaceListener = new TextureView.SurfaceTextureListener() {
        @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            configurePreviewLayout();
            if (camera == null) openCamera();
            else if (!recording && cameraHandler != null) cameraHandler.post(MainActivity.this::createPreviewSession);
        }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            configurePreviewLayout();
        }
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { return true; }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
    };

    // ---------------------------------------------------------------- lifecycle

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        PowerManager power = getSystemService(PowerManager.class);
        recordingWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RokidZoomRu:recording");
        recordingWakeLock.setReferenceCounted(false);
        displayWakeLock = power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP |
                        PowerManager.ON_AFTER_RELEASE, "RokidZoomRu:keep-recording-visible");
        displayWakeLock.setReferenceCounted(false);
        cameraManager = getSystemService(CameraManager.class);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        options.selectPhotoAspect(prefs.getInt("photo_aspect", 0));
        options.tapShutter = prefs.getBoolean("tap_shutter", true);
        options.selectPreview(prefs.getInt("preview_size", 0),
                prefs.getInt("preview_shift", CaptureOptions.PREVIEW_SHIFT_DEFAULT_INDEX));
        buildScreen();
    }

    private void buildScreen() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setOnTouchListener((view, event) -> handleTouch(event));
        // Everything visible lives in "stage", which can be shrunk and moved up/down
        // so the picture sits where it is comfortable to look through the glasses.
        stage = new FrameLayout(this);
        stage.setClipChildren(true);
        root.addView(stage, new FrameLayout.LayoutParams(-1, -1));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyStagePlacement());
        preview = new TextureView(this);
        preview.setSurfaceTextureListener(surfaceListener);
        stage.addView(preview, new FrameLayout.LayoutParams(-1, -1));

        overlay = new FrameOverlayView(this);
        stage.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        zoomLabel = new TextView(this);
        zoomLabel.setTextColor(Color.rgb(140, 255, 140));
        zoomLabel.setTextSize(36);
        zoomLabel.setGravity(Gravity.CENTER);
        zoomLabel.setShadowLayer(7f, 0f, 2f, Color.BLACK);
        FrameLayout.LayoutParams zoomParams = new FrameLayout.LayoutParams(dp(150), dp(70), Gravity.TOP | Gravity.START);
        zoomParams.leftMargin = dp(10);
        zoomParams.topMargin = dp(8);
        stage.addView(zoomLabel, zoomParams);

        batteryLabel = new TextView(this);
        batteryLabel.setText(BatteryDisplay.label(-1, false));
        batteryLabel.setTextColor(Color.WHITE);
        batteryLabel.setTextSize(15);
        batteryLabel.setGravity(Gravity.CENTER);
        batteryLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        batteryLabel.setShadowLayer(6f, 0f, 2f, Color.BLACK);
        GradientDrawable batteryBackground = new GradientDrawable();
        batteryBackground.setColor(Color.argb(165, 0, 0, 0));
        batteryBackground.setCornerRadius(dp(12));
        batteryLabel.setBackground(batteryBackground);
        FrameLayout.LayoutParams batteryParams = new FrameLayout.LayoutParams(dp(150), dp(42), Gravity.TOP | Gravity.END);
        batteryParams.topMargin = dp(10);
        batteryParams.rightMargin = dp(14);
        stage.addView(batteryLabel, batteryParams);

        recordingIndicator = new TextView(this);
        recordingIndicator.setText(RecordingDisplay.label(0L));
        recordingIndicator.setTextColor(Color.WHITE);
        recordingIndicator.setTextSize(16);
        recordingIndicator.setGravity(Gravity.CENTER);
        recordingIndicator.setTypeface(null, android.graphics.Typeface.BOLD);
        GradientDrawable recordingBackground = new GradientDrawable();
        recordingBackground.setColor(Color.argb(225, 180, 0, 0));
        recordingBackground.setCornerRadius(dp(14));
        recordingIndicator.setBackground(recordingBackground);
        recordingIndicator.setVisibility(View.GONE);
        FrameLayout.LayoutParams recordingParams = new FrameLayout.LayoutParams(dp(170), dp(48), Gravity.TOP | Gravity.END);
        recordingParams.topMargin = dp(60);
        recordingParams.rightMargin = dp(14);
        stage.addView(recordingIndicator, recordingParams);

        statusLabel = new TextView(this);
        statusLabel.setTextColor(Color.WHITE);
        statusLabel.setTextSize(15);
        statusLabel.setGravity(Gravity.CENTER);
        statusLabel.setMinHeight(dp(60));
        statusLabel.setPadding(dp(10), dp(6), dp(10), dp(6));
        statusLabel.setBackgroundColor(Color.argb(150, 0, 0, 0));
        stage.addView(statusLabel, new FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        updateZoomUi();
        updateOverlay();
        setStatus("Запуск…");
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        userLeaving = false;
        startCameraThread();
        registerButtonReceiver();
        registerBatteryReceiver();
        registerAvailability();
        sendSafetyAction(ButtonSafetyService.ACTION_CLAIM);
        mainHandler.removeCallbacks(heartbeat);
        mainHandler.post(heartbeat);
        if (preview.isAvailable()) {
            if (camera == null) openCamera();
            else if (!recording && cameraHandler != null) cameraHandler.post(this::createPreviewSession);
        }
    }

    @Override protected void onPause() {
        resumed = false;
        if (recording && !userLeaving) {
            Log.d(TAG, "Screen slept while recording; keeping the camera active");
            mainHandler.removeCallbacks(wakeDisplay);
            mainHandler.postDelayed(wakeDisplay, 250L);
            super.onPause();
            return;
        }
        mainHandler.removeCallbacks(heartbeat);
        mainHandler.removeCallbacks(reconnect);
        cancelTouchCallbacks();
        if (settingsOpen) closeSettings();
        unregisterButtonReceiver();
        unregisterBatteryReceiver();
        unregisterAvailability();
        if (recording) {
            if (recorderStarted) finishVideoNow();
            else abortVideo();
        }
        closeCamera();
        sendSafetyAction(ButtonSafetyService.ACTION_RELEASE);
        stopCameraThread();
        super.onPause();
    }

    @Override protected void onUserLeaveHint() {
        userLeaving = true;
        videoStartPending = false;
        super.onUserLeaveHint();
    }

    @Override public void onBackPressed() {
        if (settingsOpen) {
            closeSettings();
            return;
        }
        userLeaving = true;
        videoStartPending = false;
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        videoStartPending = false;
        mainHandler.removeCallbacks(heartbeat);
        mainHandler.removeCallbacks(finishVideo);
        mainHandler.removeCallbacks(wakeDisplay);
        mainHandler.removeCallbacks(updateRecordingClock);
        mainHandler.removeCallbacks(reconnect);
        mainHandler.removeCallbacks(hideStatus);
        cancelTouchCallbacks();
        if (recording) {
            if (recorderStarted) finishVideoNow();
            else abortVideo();
        }
        closeCamera();
        unregisterButtonReceiver();
        unregisterBatteryReceiver();
        unregisterAvailability();
        sendSafetyAction(ButtonSafetyService.ACTION_RELEASE);
        stopCameraThread();
        releaseRecordingWakeLock();
        releaseDisplayWakeLock();
        super.onDestroy();
    }

    private void sendSafetyAction(String action) {
        Intent intent = new Intent(this, ButtonSafetyService.class).setAction(action);
        try {
            if (ButtonSafetyService.ACTION_CLAIM.equals(action)) startForegroundService(intent);
            else startService(intent);
        } catch (RuntimeException error) {
            Log.w(TAG, "Button safety service action failed", error);
            if (ButtonSafetyService.ACTION_RELEASE.equals(action)) RokidButtonControl.restore(this);
        }
    }

    private void registerButtonReceiver() {
        if (buttonReceiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(BUTTON_DOWN);
        filter.addAction(BUTTON_UP);
        filter.addAction(BUTTON_LONG);
        filter.addAction(BUTTON_VERY_LONG);
        try {
            registerReceiver(buttonReceiver, filter);
            buttonReceiverRegistered = true;
        } catch (RuntimeException ignored) {}
    }

    private void unregisterButtonReceiver() {
        if (!buttonReceiverRegistered) return;
        try { unregisterReceiver(buttonReceiver); } catch (RuntimeException ignored) {}
        buttonReceiverRegistered = false;
    }

    private void registerBatteryReceiver() {
        if (batteryReceiverRegistered) return;
        try {
            Intent sticky = registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            batteryReceiverRegistered = true;
            if (sticky != null) batteryReceiver.onReceive(this, sticky);
        } catch (RuntimeException error) {
            Log.w(TAG, "Battery status receiver registration failed", error);
        }
    }

    private void unregisterBatteryReceiver() {
        if (!batteryReceiverRegistered) return;
        try { unregisterReceiver(batteryReceiver); } catch (IllegalArgumentException ignored) {}
        batteryReceiverRegistered = false;
    }

    private void registerAvailability() {
        if (availabilityRegistered || cameraManager == null) return;
        cameraManager.registerAvailabilityCallback(availabilityCallback, mainHandler);
        availabilityRegistered = true;
    }

    private void unregisterAvailability() {
        if (!availabilityRegistered || cameraManager == null) return;
        cameraManager.unregisterAvailabilityCallback(availabilityCallback);
        availabilityRegistered = false;
    }

    private void startCameraThread() {
        if (cameraThread != null) return;
        cameraThread = new HandlerThread("RokidZoomRuCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        HandlerThread thread = cameraThread;
        cameraThread = null;
        cameraHandler = null;
        if (thread != null) {
            thread.quitSafely();
            try { thread.join(1200L); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }
    }

    // ---------------------------------------------------------------- camera

    private void openCamera() {
        if (!resumed || opening || camera != null || !preview.isAvailable() || cameraHandler == null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
            return;
        }
        opening = true;
        if (!settingsOpen) setStatus("Подключаю камеру…");
        try {
            cameraId = findBackCamera(cameraManager);
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            videoStabilizationSupported = VideoStabilization.isSupported(
                    characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES));
            sensorRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Float max = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            maximumZoom = max == null ? 1f : max;
            Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = orientation == null ? 0 : orientation;
            android.hardware.camera2.params.StreamConfigurationMap map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) throw new CameraAccessException(CameraAccessException.CAMERA_ERROR);
            previewSize = choosePreviewSize(map.getOutputSizes(SurfaceTexture.class));
            loadVideoOptions(map.getOutputSizes(MediaRecorder.class),
                    characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES));
            Size photoSize = Collections.max(Arrays.asList(map.getOutputSizes(android.graphics.ImageFormat.JPEG)),
                    Comparator.comparingLong(size -> (long) size.getWidth() * size.getHeight()));
            if (imageReader != null) imageReader.close();
            imageReader = ImageReader.newInstance(photoSize.getWidth(), photoSize.getHeight(),
                    android.graphics.ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(this::savePhoto, cameraHandler);
            Log.i(TAG, "Camera " + cameraId + " preview=" + previewSize + " photo=" + photoSize +
                    " maxZoom=" + maximumZoom + " sensorOrientation=" + sensorOrientation +
                    " eis=" + videoStabilizationSupported + " fps=" + fixedFpsRanges);
            configurePreviewLayout();
            cameraManager.openCamera(cameraId, cameraCallback, cameraHandler);
        } catch (Exception error) {
            opening = false;
            Log.e(TAG, "Could not open camera", error);
            scheduleReconnect("Камера недоступна.\nПовторяю попытку…");
        }
    }

    private void loadVideoOptions(Size[] recorderSizes, Range<Integer>[] fpsRanges) {
        List<int[]> sizes = new ArrayList<>();
        if (recorderSizes != null) {
            for (Size size : recorderSizes) {
                int w = Math.max(size.getWidth(), size.getHeight());
                int h = Math.min(size.getWidth(), size.getHeight());
                long pixels = (long) w * h;
                if (pixels >= 640L * 480L && pixels <= 3840L * 2160L) sizes.add(new int[]{w, h});
            }
        }
        fixedFpsRanges.clear();
        List<Integer> fps = new ArrayList<>();
        if (fpsRanges != null) {
            for (Range<Integer> range : fpsRanges) {
                if (range.getLower().equals(range.getUpper())) {
                    fixedFpsRanges.add(range.getUpper());
                    fps.add(range.getUpper());
                }
            }
        }
        if (!fps.contains(30)) fps.add(30);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        options.setAvailable(sizes, fps);
        int savedWidth = prefs.getInt("video_w", 0);
        int savedHeight = prefs.getInt("video_h", 0);
        options.selectDefaultVideoSize();
        if (savedWidth > 0) options.selectVideoSize(savedWidth, savedHeight);
        options.selectFps(prefs.getInt("fps", 30));
        runOnUiThread(this::updateOverlay);
    }

    private void savePrefs() {
        int[] size = options.videoSize();
        SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt("photo_aspect", options.photoAspectIndex)
                .putInt("fps", options.fps())
                .putBoolean("tap_shutter", options.tapShutter)
                .putInt("preview_size", options.previewSizeIndex)
                .putInt("preview_shift", options.previewShiftIndex);
        if (size != null) editor.putInt("video_w", size[0]).putInt("video_h", size[1]);
        editor.apply();
    }

    private String findBackCamera(CameraManager manager) throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing == null || facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        throw new CameraAccessException(CameraAccessException.CAMERA_ERROR);
    }

    private final CameraDevice.StateCallback cameraCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice device) {
            opening = false;
            if (!resumed) { device.close(); return; }
            camera = device;
            mainHandler.removeCallbacks(reconnect);
            createPreviewSession();
        }
        @Override public void onDisconnected(CameraDevice device) {
            Log.w(TAG, "Camera disconnected (taken by another client)");
            handleCameraLost(device);
        }
        @Override public void onError(CameraDevice device, int error) {
            Log.w(TAG, "Camera error " + error);
            handleCameraLost(device);
        }
    };

    /** The system camera (or any other client) took the camera: clean up and keep retrying. */
    private void handleCameraLost(CameraDevice device) {
        opening = false;
        try { device.close(); } catch (RuntimeException ignored) {}
        if (camera == device) camera = null;
        session = null;
        takingPhoto = false;
        runOnUiThread(() -> {
            if (recording) {
                if (recorderStarted) finishVideoNow();
                else abortVideo();
            }
            boolean afterButton = lastPhysicalButtonAt > 0L &&
                    SystemClock.elapsedRealtime() - lastPhysicalButtonAt < BUTTON_CONFLICT_WINDOW_MS;
            scheduleReconnect(afterButton
                    ? "Кнопку перехватила системная камера.\nСнимайте тапом по дужке (см. инструкцию)"
                    : "Камеру занял другой процесс.\nПереподключаюсь…");
        });
    }

    private void scheduleReconnect(String message) {
        if (!resumed) return;
        setStatus(message);
        mainHandler.removeCallbacks(reconnect);
        mainHandler.postDelayed(reconnect, RECONNECT_DELAY_MS);
    }

    private void createPreviewSession() {
        CameraDevice device = camera;
        SurfaceTexture texture = preview.getSurfaceTexture();
        if (device == null || texture == null || previewSize == null || cameraHandler == null || imageReader == null) return;
        try {
            if (session != null) session.close();
            session = null;
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            runOnUiThread(this::configurePreviewLayout);
            Surface surface = new Surface(texture);
            repeatingBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            repeatingBuilder.addTarget(surface);
            setCommonControls(repeatingBuilder, true);
            device.createCaptureSession(Arrays.asList(surface, imageReader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession value) {
                    if (camera == null || recording) { value.close(); return; }
                    session = value;
                    try {
                        value.setRepeatingRequest(repeatingBuilder.build(), null, cameraHandler);
                        if (!settingsOpen) setStatus(idleHint());
                        if (videoStartPending) runOnUiThread(MainActivity.this::startVideo);
                    } catch (CameraAccessException | IllegalStateException error) {
                        setStatus("Не удалось запустить превью");
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession value) {
                    setStatus("Не удалось настроить превью");
                }
            }, cameraHandler);
        } catch (CameraAccessException | IllegalStateException error) {
            Log.e(TAG, "Preview session failed", error);
            setStatus("Не удалось запустить превью");
        }
    }

    private float effectiveZoom() {
        return ZoomMath.clamp(ZOOM_STEPS[zoomIndex], maximumZoom);
    }

    private Rect fullCrop() {
        return sensorRect == null ? null : new Rect(0, 0, sensorRect.width(), sensorRect.height());
    }

    private void setCommonControls(CaptureRequest.Builder builder, boolean applyZoom) {
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        Rect full = fullCrop();
        if (full != null) {
            builder.set(CaptureRequest.SCALER_CROP_REGION,
                    applyZoom ? ZoomMath.crop(full, ZOOM_STEPS[zoomIndex], maximumZoom) : full);
        }
    }

    private void setVideoControls(CaptureRequest.Builder builder, int fps) {
        setCommonControls(builder, true);
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        if (fixedFpsRanges.contains(fps)) {
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(fps, fps));
        }
        if (!videoStabilizationSupported) return;
        builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
        builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
    }

    private void changeZoom(int direction) {
        if (recording) return;
        int next = Math.max(0, Math.min(ZOOM_STEPS.length - 1, zoomIndex + direction));
        if (next == zoomIndex) return;
        zoomIndex = next;
        updateZoomUi();
        CaptureRequest.Builder builder = repeatingBuilder;
        CameraCaptureSession current = session;
        if (builder != null && current != null && cameraHandler != null) {
            try {
                setCommonControls(builder, true);
                current.setRepeatingRequest(builder.build(), null, cameraHandler);
            } catch (CameraAccessException | IllegalStateException ignored) {}
        }
    }

    private void updateZoomUi() {
        if (zoomLabel != null) zoomLabel.setText(String.format(Locale.US, "%.1f×", ZOOM_STEPS[zoomIndex]));
    }

    private void updateOverlay() {
        if (overlay != null) overlay.setFrames(options.photoAspect(), options.videoAspect(), recording);
    }

    private String idleHint() {
        if (options.tapShutter) {
            return "Фото: тап · Видео: удержание (или кнопка)\nЗум: свайп · Настройки: двойной тап";
        }
        return "Фото: кнопка · Видео: долгое нажатие\nЗум: свайп · Настройки: двойной тап";
    }

    // ---------------------------------------------------------------- photo

    private void takePhoto() {
        CameraDevice device = camera;
        CameraCaptureSession current = session;
        Log.d(TAG, "takePhoto camera=" + (device != null) + " session=" + (current != null) +
                " busy=" + takingPhoto + " recording=" + recording);
        if (device == null || current == null || takingPhoto || recording || imageReader == null) return;
        takingPhoto = true;
        photoZoom = effectiveZoom();
        photoAspect = options.photoAspect();
        setStatus("Снимаю…");
        try {
            CaptureRequest.Builder still = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            still.addTarget(imageReader.getSurface());
            // Full sensor frame; zoom and aspect are applied in software so the
            // file always matches the preview, whatever the HAL does with JPEG crops.
            setCommonControls(still, false);
            still.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation());
            still.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            current.capture(still.build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
                    Handler handler = cameraHandler;
                    ImageReader reader = imageReader;
                    if (handler != null) handler.postDelayed(() -> savePhoto(reader), 250L);
                }
                @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure failure) {
                    takingPhoto = false;
                    Log.e(TAG, "Still capture failed, reason=" + failure.getReason());
                    setStatus("Не удалось сделать снимок");
                }
            }, cameraHandler);
        } catch (CameraAccessException | IllegalStateException error) {
            takingPhoto = false;
            setStatus("Не удалось сделать снимок");
        }
    }

    private void savePhoto(ImageReader reader) {
        if (reader == null) return;
        byte[] jpeg;
        try (Image image = reader.acquireNextImage()) {
            if (image == null) return;
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            jpeg = new byte[buffer.remaining()];
            buffer.get(jpeg);
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not read JPEG", error);
            takingPhoto = false;
            return;
        }
        final float zoom = photoZoom;
        final float aspect = photoAspect;
        setStatus("Сохраняю…");
        new Thread(() -> writePhoto(jpeg, zoom, aspect), "RokidZoomRuPhoto").start();
    }

    private void writePhoto(byte[] jpeg, float zoom, float aspect) {
        String message;
        try {
            PhotoProcessor.Result result = PhotoProcessor.process(jpeg, zoom, aspect, getCacheDir());
            String name = PHOTO_FILE_PREFIX + timestamp() + "_" +
                    String.format(Locale.US, "%.1fx_%s.jpg", zoom, aspectTag(aspect));
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, MediaPaths.PHOTO_PATH);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("MediaStore insert failed");
            try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
                if (output == null) throw new IllegalStateException("MediaStore stream failed");
                output.write(result.jpeg);
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, ready, null, null);
            boolean wanted = zoom > 1.01f || Math.abs(aspect - 4f / 3f) > 0.02f;
            if (wanted && !result.cropped) {
                message = "Сохранено БЕЗ зума — не хватило памяти";
            } else {
                message = String.format(Locale.US, "Сохранено %.1f× · %s · %d×%d",
                        zoom, aspectLabel(aspect), result.width, result.height);
            }
        } catch (Exception error) {
            Log.e(TAG, "Could not save photo", error);
            message = "Не удалось сохранить фото";
        } finally {
            takingPhoto = false;
        }
        setStatus(message);
        mainHandler.postDelayed(() -> {
            if (!recording && !settingsOpen && resumed && !takingPhoto) setStatus(idleHint());
        }, 2200L);
    }

    private static String aspectTag(float aspect) {
        return aspectLabel(aspect).replace(':', 'x');
    }

    private static String aspectLabel(float aspect) {
        if (Math.abs(aspect - 16f / 9f) < 0.02f) return "16:9";
        if (Math.abs(aspect - 3f / 2f) < 0.02f) return "3:2";
        if (Math.abs(aspect - 1f) < 0.02f) return "1:1";
        return "4:3";
    }

    // ---------------------------------------------------------------- video

    private void startVideo() {
        if (settingsOpen) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            videoStartPending = true;
            setStatus("Для видео нужен доступ к микрофону");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
            return;
        }
        CameraDevice device = camera;
        SurfaceTexture texture = preview.getSurfaceTexture();
        int[] size = options.videoSize();
        if (recording) return;
        if (device == null || session == null || texture == null || size == null) {
            videoStartPending = true;
            setStatus("Готовлю видео…");
            return;
        }
        final int fps = options.fps();
        videoStartPending = false;
        recording = true;
        recorderStarted = false;
        stopVideoRequested = false;
        stopVideoScheduled = false;
        mainHandler.removeCallbacks(finishVideo);
        acquireRecordingWakeLock();
        updateOverlay();
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, PHOTO_FILE_PREFIX + timestamp() + "_" +
                    String.format(Locale.US, "%.1fx_%dp%d.mp4", effectiveZoom(), size[1], fps));
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, MediaPaths.VIDEO_PATH);
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            pendingVideo = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (pendingVideo == null) throw new IllegalStateException("MediaStore insert failed");
            videoFile = getContentResolver().openFileDescriptor(pendingVideo, "w");
            if (videoFile == null) throw new IllegalStateException("MediaStore file failed");
            long bitrate = Math.max(4_000_000L, Math.min(40_000_000L, (long) (size[0] * (long) size[1] * fps * 0.17)));
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setOutputFile(videoFile.getFileDescriptor());
            recorder.setAudioEncodingBitRate(128_000);
            recorder.setAudioSamplingRate(48_000);
            recorder.setAudioChannels(1);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setVideoEncodingBitRate((int) bitrate);
            recorder.setVideoFrameRate(fps);
            recorder.setVideoSize(size[0], size[1]);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setOrientationHint(jpegOrientation());
            recorder.prepare();

            session.close();
            session = null;
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            runOnUiThread(this::configurePreviewLayout);
            Surface previewSurface = new Surface(texture);
            Surface recordSurface = recorder.getSurface();
            repeatingBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            repeatingBuilder.addTarget(previewSurface);
            repeatingBuilder.addTarget(recordSurface);
            setVideoControls(repeatingBuilder, fps);
            device.createCaptureSession(Arrays.asList(previewSurface, recordSurface), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession value) {
                    if (!recording || camera == null) { value.close(); return; }
                    session = value;
                    try {
                        value.setRepeatingRequest(repeatingBuilder.build(), null, cameraHandler);
                        recorder.start();
                        runOnUiThread(() -> {
                            if (!recording) return;
                            recorderStarted = true;
                            videoStartedAt = SystemClock.elapsedRealtime();
                            recordingIndicator.setText(RecordingDisplay.label(0L));
                            recordingIndicator.setVisibility(View.VISIBLE);
                            mainHandler.removeCallbacks(updateRecordingClock);
                            mainHandler.post(updateRecordingClock);
                            setStatus(String.format(Locale.US, "%d×%d · %d к/с\nСтоп: тап или кнопка",
                                    size[0], size[1], fps));
                            if (stopVideoRequested) stopVideo();
                        });
                    } catch (Exception error) {
                        Log.e(TAG, "Could not start recording session", error);
                        failVideo();
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession value) {
                    Log.e(TAG, "Recording session configuration failed for " + size[0] + "x" + size[1] + "@" + fps);
                    failVideo();
                }
            }, cameraHandler);
        } catch (Exception error) {
            Log.e(TAG, "Could not start video", error);
            failVideo();
        }
    }

    private void stopVideo() {
        if (!recording) return;
        if (!recorderStarted) {
            stopVideoRequested = true;
            setStatus("Запись запускается…");
            return;
        }
        if (stopVideoScheduled) return;
        long remaining = 800L - (SystemClock.elapsedRealtime() - videoStartedAt);
        if (remaining > 0L) {
            stopVideoScheduled = true;
            mainHandler.postDelayed(finishVideo, remaining);
            return;
        }
        finishVideoNow();
    }

    private void finishVideoNow() {
        mainHandler.removeCallbacks(finishVideo);
        stopVideoScheduled = false;
        videoStartPending = false;
        if (!recording || !recorderStarted) return;
        recording = false;
        recorderStarted = false;
        stopVideoRequested = false;
        boolean saved = false;
        try {
            if (recorder != null) {
                recorder.stop();
                saved = true;
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Video was too short or could not stop", error);
        }
        releaseRecorder(saved);
        releaseRecordingWakeLock();
        releaseDisplayWakeLock();
        mainHandler.removeCallbacks(updateRecordingClock);
        recordingIndicator.setVisibility(View.GONE);
        updateOverlay();
        setStatus(saved ? "Видео сохранено" : "Не удалось сохранить видео");
        returnToPreviewLater();
    }

    private void failVideo() {
        runOnUiThread(() -> {
            mainHandler.removeCallbacks(finishVideo);
            recording = false;
            recorderStarted = false;
            stopVideoRequested = false;
            stopVideoScheduled = false;
            videoStartPending = false;
            releaseRecorder(false);
            releaseRecordingWakeLock();
            releaseDisplayWakeLock();
            mainHandler.removeCallbacks(updateRecordingClock);
            recordingIndicator.setVisibility(View.GONE);
            updateOverlay();
            setStatus("Видео не запустилось.\nПопробуйте другое разрешение или к/с");
            returnToPreviewLater();
        });
    }

    private void returnToPreviewLater() {
        Handler handler = cameraHandler;
        if (resumed && camera != null && handler != null) handler.postDelayed(this::createPreviewSession, 250L);
        else if (resumed && camera == null) scheduleReconnect("Переподключаю камеру…");
    }

    private void abortVideo() {
        mainHandler.removeCallbacks(finishVideo);
        recording = false;
        recorderStarted = false;
        stopVideoRequested = false;
        stopVideoScheduled = false;
        videoStartPending = false;
        releaseRecorder(false);
        releaseRecordingWakeLock();
        releaseDisplayWakeLock();
        mainHandler.removeCallbacks(updateRecordingClock);
        recordingIndicator.setVisibility(View.GONE);
        updateOverlay();
    }

    private void acquireRecordingWakeLock() {
        if (recordingWakeLock != null && !recordingWakeLock.isHeld()) {
            recordingWakeLock.acquire(2L * 60L * 60L * 1000L);
        }
    }

    private void releaseRecordingWakeLock() {
        if (recordingWakeLock != null && recordingWakeLock.isHeld()) recordingWakeLock.release();
    }

    private void wakeDisplayForRecording() {
        if (!recording || userLeaving || displayWakeLock == null) return;
        if (displayWakeLock.isHeld()) displayWakeLock.release();
        displayWakeLock.acquire(3000L);
    }

    private void releaseDisplayWakeLock() {
        mainHandler.removeCallbacks(wakeDisplay);
        if (displayWakeLock != null && displayWakeLock.isHeld()) displayWakeLock.release();
    }

    private void releaseRecorder(boolean keepFile) {
        if (recorder != null) {
            try { recorder.reset(); } catch (RuntimeException ignored) {}
            recorder.release();
            recorder = null;
        }
        if (videoFile != null) {
            try { videoFile.close(); } catch (Exception ignored) {}
            videoFile = null;
        }
        if (pendingVideo != null) {
            try {
                if (keepFile) {
                    ContentValues ready = new ContentValues();
                    ready.put(MediaStore.Video.Media.IS_PENDING, 0);
                    getContentResolver().update(pendingVideo, ready, null, null);
                } else {
                    getContentResolver().delete(pendingVideo, null, null);
                }
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not finalize video entry", error);
            }
            pendingVideo = null;
        }
    }

    private void closeCamera() {
        opening = false;
        if (session != null) { try { session.close(); } catch (RuntimeException ignored) {} session = null; }
        if (camera != null) { camera.close(); camera = null; }
        if (imageReader != null) { imageReader.close(); imageReader = null; }
        repeatingBuilder = null;
    }

    // ---------------------------------------------------------------- settings menu

    private void openSettings() {
        if (recording || settingsOpen) return;
        settingsOpen = true;
        settingsItem = 0;
        showSettings();
    }

    private void closeSettings() {
        if (!settingsOpen) return;
        settingsOpen = false;
        savePrefs();
        updateOverlay();
        setStatus("Настройки сохранены");
        mainHandler.postDelayed(() -> {
            if (!settingsOpen && !recording && resumed && !takingPhoto) setStatus(idleHint());
        }, 1500L);
    }

    private void showSettings() {
        updateOverlay();
        setStatus(String.format(Locale.US, "⚙ %s (%d/%d)\n◀  %s  ▶\nсвайп: менять · тап: далее · 2× тап: выход",
                options.itemName(settingsItem), settingsItem + 1, CaptureOptions.ITEM_COUNT,
                options.valueLabel(settingsItem)));
    }

    private void changeSetting(int direction) {
        options.change(settingsItem, direction);
        if (settingsItem == CaptureOptions.ITEM_PREVIEW_SIZE || settingsItem == CaptureOptions.ITEM_PREVIEW_SHIFT) {
            applyStagePlacement();
        }
        showSettings();
    }

    private void applyStagePlacement() {
        if (stage == null || root == null) return;
        int w = root.getWidth();
        int h = root.getHeight();
        if (w == 0 || h == 0) return;
        float scale = options.previewScale();
        stage.setPivotX(w / 2f);
        stage.setPivotY(h / 2f);
        stage.setScaleX(scale);
        stage.setScaleY(scale);
        stage.setTranslationY(options.previewShift() * h);
    }

    private void nextSettingItem() {
        settingsItem = (settingsItem + 1) % CaptureOptions.ITEM_COUNT;
        showSettings();
    }

    // ---------------------------------------------------------------- input

    private boolean handleTouch(MotionEvent event) {
        lastTouchEventAt = SystemClock.elapsedRealtime();
        int action = event.getActionMasked();
        float slop = dp(24);
        if (action == MotionEvent.ACTION_DOWN) {
            touchDownX = event.getX();
            touchDownY = event.getY();
            touchDownAt = SystemClock.elapsedRealtime();
            touchMoved = false;
            touchLongFired = false;
            mainHandler.removeCallbacks(touchLongPress);
            mainHandler.postDelayed(touchLongPress, TouchGesture.LONG_PRESS_MS);
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (!touchMoved && TouchGesture.movedBeyond(touchDownX, touchDownY, event.getX(), event.getY(), slop)) {
                touchMoved = true;
                mainHandler.removeCallbacks(touchLongPress);
            }
        } else if (action == MotionEvent.ACTION_UP) {
            mainHandler.removeCallbacks(touchLongPress);
            lastTouchUpAt = SystemClock.elapsedRealtime();
            if (touchLongFired) return true;
            int swipe = TouchGesture.swipeDirection(touchDownX, touchDownY, event.getX(), event.getY(), dp(55));
            if (swipe != 0) {
                cancelPendingTap();
                onSwipe(swipe);
            } else if (TouchGesture.isTap(touchDownX, touchDownY, event.getX(), event.getY(),
                    lastTouchUpAt - touchDownAt, slop)) {
                onTapDetected();
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            mainHandler.removeCallbacks(touchLongPress);
        }
        return true;
    }

    private void onSwipe(int direction) {
        if (settingsOpen) changeSetting(direction);
        else changeZoom(direction);
    }

    private void onTapDetected() {
        if (singleTapPending) {
            cancelPendingTap();
            onDoubleTap();
        } else {
            singleTapPending = true;
            mainHandler.postDelayed(singleTap, TouchGesture.DOUBLE_TAP_MS);
        }
    }

    private void cancelPendingTap() {
        singleTapPending = false;
        mainHandler.removeCallbacks(singleTap);
    }

    private void cancelTouchCallbacks() {
        cancelPendingTap();
        mainHandler.removeCallbacks(touchLongPress);
    }

    private void onSingleTap() {
        if (settingsOpen) {
            nextSettingItem();
        } else if (recording) {
            stopVideo();
        } else if (options.tapShutter) {
            takePhoto();
        }
    }

    private void onDoubleTap() {
        if (recording) return;
        if (settingsOpen) closeSettings();
        else openSettings();
    }

    private void onTouchLongPress() {
        if (settingsOpen || !options.tapShutter) return;
        if (recording) stopVideo();
        else startVideo();
    }

    /** Ring scroll wheel or any pointer scroll: zoom, or change the selected setting. */
    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_SCROLL &&
                (event.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0) {
            float v = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (v == 0f) v = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
            if (v != 0f) {
                onSwipe(v > 0f ? 1 : -1);
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        Log.d(TAG, "onKeyDown key=" + KeyEvent.keyCodeToString(keyCode) + " repeat=" + event.getRepeatCount());
        if (keyCode == KeyEvent.KEYCODE_NOTIFICATION) {
            lastTouchEventAt = SystemClock.elapsedRealtime();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.getRepeatCount() == 0) onSwipe(1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getRepeatCount() == 0) onSwipe(-1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_CAMERA || keyCode == KeyEvent.KEYCODE_STEM_PRIMARY) {
            lastPhysicalButtonAt = SystemClock.elapsedRealtime();
            if (event.getRepeatCount() == 0) buttonDownAt = SystemClock.elapsedRealtime();
            else if (!longPressHandled && event.isLongPress()) {
                longPressHandled = true;
                if (settingsOpen) closeSettings();
                else startVideo();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        Log.d(TAG, "onKeyUp key=" + KeyEvent.keyCodeToString(keyCode) + " longHandled=" + longPressHandled);
        if (keyCode == KeyEvent.KEYCODE_NOTIFICATION) return true;
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            // Some touch surfaces / the ring report a tap only as a key. If no touch
            // event was seen just now, treat the key as a tap.
            if (lastTouchUpAt < 0L || SystemClock.elapsedRealtime() - lastTouchUpAt > 400L) {
                lastTouchEventAt = SystemClock.elapsedRealtime();
                onTapDetected();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_CAMERA || keyCode == KeyEvent.KEYCODE_STEM_PRIMARY) {
            if (settingsOpen && !longPressHandled) closeSettings();
            else if (recording && !longPressHandled) stopVideo();
            else if (!recording && !longPressHandled) takePhoto();
            longPressHandled = false;
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == CAMERA_PERMISSION) {
            if (granted) openCamera();
            else setStatus("Нужно разрешение на камеру");
        } else if (requestCode == AUDIO_PERMISSION) {
            if (granted) {
                startVideo();
            } else {
                videoStartPending = false;
                setStatus("Для видео нужен доступ к микрофону");
            }
        }
    }

    // ---------------------------------------------------------------- layout helpers

    private void configurePreviewLayout() {
        if (previewSize == null || preview == null || root == null) return;
        int viewWidth = root.getWidth();
        int viewHeight = root.getHeight();
        if (viewWidth == 0 || viewHeight == 0) {
            root.post(this::configurePreviewLayout);
            return;
        }
        int displayDegrees = rotationDegrees(getDisplay().getRotation());
        int rotation = (sensorOrientation - displayDegrees + 360) % 360;
        boolean swapped = rotation == 90 || rotation == 270;
        PreviewGeometry.Layout layout = PreviewGeometry.centerCrop(
                viewWidth, viewHeight, previewSize.getWidth(), previewSize.getHeight(), swapped);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) preview.getLayoutParams();
        if (params.width != layout.width || params.height != layout.height || params.gravity != Gravity.CENTER) {
            params.width = layout.width;
            params.height = layout.height;
            params.gravity = Gravity.CENTER;
            preview.setLayoutParams(params);
        }
        // RV101/YodaOS presents the camera buffer in the display's natural
        // orientation. Match the view to that aspect ratio and let the root crop
        // only the excess edges; never stretch width and height independently.
        preview.setTransform(new Matrix());
        float previewAspect = (float) Math.max(previewSize.getWidth(), previewSize.getHeight()) /
                Math.min(previewSize.getWidth(), previewSize.getHeight());
        overlay.setPreviewBounds((viewWidth - layout.width) / 2f, (viewHeight - layout.height) / 2f,
                layout.width, layout.height, previewAspect);
    }

    private int jpegOrientation() {
        int display = rotationDegrees(getDisplay().getRotation());
        return (sensorOrientation + display) % 360;
    }

    private static int rotationDegrees(int rotation) {
        if (rotation == Surface.ROTATION_90) return 90;
        if (rotation == Surface.ROTATION_180) return 180;
        if (rotation == Surface.ROTATION_270) return 270;
        return 0;
    }

    private static Size choosePreviewSize(Size[] sizes) {
        List<Size> candidates = new ArrayList<>();
        List<Size> hudRatioCandidates = new ArrayList<>();
        for (Size size : sizes) {
            long pixels = (long) size.getWidth() * size.getHeight();
            if (pixels <= 1920L * 1080L && pixels >= 640L * 480L) {
                candidates.add(size);
                float ratio = (float) Math.max(size.getWidth(), size.getHeight()) /
                        Math.min(size.getWidth(), size.getHeight());
                if (Math.abs(ratio - 4f / 3f) < 0.03f) hudRatioCandidates.add(size);
            }
        }
        if (candidates.isEmpty()) candidates.addAll(Arrays.asList(sizes));
        // RV101's HUD is 480x640 (3:4 portrait); a 4:3 stream also matches the
        // full 4:3 sensor, so the frame overlay shows exactly what gets saved.
        if (!hudRatioCandidates.isEmpty()) candidates = hudRatioCandidates;
        return Collections.max(candidates, Comparator.comparingLong(size -> (long) size.getWidth() * size.getHeight()));
    }

    private String timestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    private void setStatus(String message) {
        runOnUiThread(() -> {
            statusLabel.setText(message);
            statusLabel.setVisibility(View.VISIBLE);
            mainHandler.removeCallbacks(hideStatus);
            // Hints disappear after a few seconds so they do not cover the picture.
            if (!settingsOpen && !recording) mainHandler.postDelayed(hideStatus, 6000L);
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
