package io.github.ksuzukigh.rokidzoomincamera;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Draws camera frames into the video encoder surface, turned upright and
 * cropped to the chosen video shape (see {@link VideoGeometry}). The camera
 * writes into {@link #start()}'s surface instead of the recorder's.
 */
final class VideoGlRenderer {
    private static final String TAG = "RokidZoomRu";
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    private static final String VERTEX =
            "attribute vec4 aPos;\n" +
            "attribute vec4 aTex;\n" +
            "uniform mat4 uSt;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  gl_Position = aPos;\n" +
            "  vTex = (uSt * aTex).xy;\n" +
            "}\n";
    private static final String FRAGMENT =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTex;\n" +
            "uniform samplerExternalOES sTex;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(sTex, vTex);\n" +
            "}\n";
    private static final float[] POSITIONS = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};

    private final Surface encoderSurface;
    private final int outWidth;
    private final int outHeight;
    private final int sourceWidth;
    private final int sourceHeight;
    private final FloatBuffer positions;
    private FloatBuffer texCoords;
    private final int rotation;
    private final float aspect;
    private final int uprightWidth;
    private final int uprightHeight;
    private final float[] stMatrix = new float[16];

    private HandlerThread thread;
    private Handler handler;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture texture;
    private Surface inputSurface;
    private int program;
    private int textureId;
    private int aPos;
    private int aTex;
    private int uSt;
    private volatile boolean drawing = true;
    private volatile boolean released;
    private Exception startError;
    private long frames;

    /**
     * @param rotation clockwise turn that makes the camera picture upright
     * @param aspect   width / height of the output as viewed
     */
    VideoGlRenderer(Surface encoderSurface, int outWidth, int outHeight,
                    int sourceWidth, int sourceHeight, int rotation, float aspect,
                    int uprightWidth, int uprightHeight) {
        this.encoderSurface = encoderSurface;
        this.outWidth = outWidth;
        this.outHeight = outHeight;
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.rotation = rotation;
        this.aspect = aspect;
        this.uprightWidth = uprightWidth;
        this.uprightHeight = uprightHeight;
        positions = floats(POSITIONS);
    }

    /** Sets up OpenGL on its own thread; returns the surface the camera should draw into. */
    Surface start() throws Exception {
        thread = new HandlerThread("RokidZoomRuVideoGl");
        thread.start();
        handler = new Handler(thread.getLooper());
        final CountDownLatch ready = new CountDownLatch(1);
        handler.post(new Runnable() {
            @Override public void run() {
                try {
                    setUp();
                } catch (Exception error) {
                    startError = error;
                } finally {
                    ready.countDown();
                }
            }
        });
        if (!ready.await(3, TimeUnit.SECONDS)) {
            release();
            throw new IllegalStateException("OpenGL setup timed out");
        }
        if (startError != null) {
            Exception error = startError;
            release();
            throw error;
        }
        return inputSurface;
    }

    /** No more frames go to the encoder (call before MediaRecorder.stop). */
    void stopDrawing() {
        drawing = false;
    }

    void release() {
        drawing = false;
        if (released) return;
        released = true;
        final Handler h = handler;
        final HandlerThread t = thread;
        if (h == null) return;
        h.post(new Runnable() {
            @Override public void run() {
                tearDown();
                if (t != null) t.quitSafely();
            }
        });
    }

    private void setUp() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) throw new IllegalStateException("no EGL display");
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw new IllegalStateException("eglInitialize");
        int[] attribs = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            throw new IllegalStateException("no recordable EGL config");
        }
        int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        if (context == EGL14.EGL_NO_CONTEXT) throw new IllegalStateException("eglCreateContext");
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface, new int[]{EGL14.EGL_NONE}, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw new IllegalStateException("eglCreateWindowSurface");
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) throw new IllegalStateException("eglMakeCurrent");

        program = buildProgram();
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aTex = GLES20.glGetAttribLocation(program, "aTex");
        uSt = GLES20.glGetUniformLocation(program, "uSt");

        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        textureId = ids[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        texture = new SurfaceTexture(textureId);
        texture.setDefaultBufferSize(sourceWidth, sourceHeight);
        texture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override public void onFrameAvailable(SurfaceTexture st) { drawFrame(); }
        }, handler);
        inputSurface = new Surface(texture);
        Log.i(TAG, "VideoGl ready: source " + sourceWidth + "x" + sourceHeight + " -> " + outWidth + "x" + outHeight);
    }

    private void drawFrame() {
        if (released || texture == null) return;
        try {
            texture.updateTexImage();
            if (!drawing) return;
            texture.getTransformMatrix(stMatrix);
            if (texCoords == null) {
                // On RV101 the camera already turns the SurfaceTexture picture upright
                // (its transform matrix holds the 90-degree turn; 2.7.0 turned it a
                // second time, so the file came out sideways). Turn here only what the
                // matrix does not.
                boolean matrixTurns = Math.abs(stMatrix[0]) < 0.5f && Math.abs(stMatrix[5]) < 0.5f;
                int residual = matrixTurns ? 0 : rotation;
                texCoords = floats(VideoGeometry.texCoords(residual, aspect, uprightWidth, uprightHeight));
                Log.i(TAG, "VideoGl matrix " + java.util.Arrays.toString(stMatrix) +
                        " turns=" + matrixTurns + " residual=" + residual);
            }
            GLES20.glViewport(0, 0, outWidth, outHeight);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glUniformMatrix4fv(uSt, 1, false, stMatrix, 0);
            positions.position(0);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, positions);
            GLES20.glEnableVertexAttribArray(aPos);
            texCoords.position(0);
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texCoords);
            GLES20.glEnableVertexAttribArray(aTex);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, texture.getTimestamp());
            EGL14.eglSwapBuffers(display, eglSurface);
            if (++frames == 1) Log.i(TAG, "VideoGl first frame");
        } catch (RuntimeException error) {
            Log.w(TAG, "VideoGl frame failed", error);
        }
    }

    private void tearDown() {
        if (inputSurface != null) inputSurface.release();
        inputSurface = null;
        if (texture != null) {
            texture.setOnFrameAvailableListener(null);
            texture.release();
        }
        texture = null;
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(display);
        }
        display = EGL14.EGL_NO_DISPLAY;
        context = EGL14.EGL_NO_CONTEXT;
        eglSurface = EGL14.EGL_NO_SURFACE;
    }

    private static int buildProgram() {
        int vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("link: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("shader: " + GLES20.glGetShaderInfoLog(shader));
        return shader;
    }

    private static FloatBuffer floats(float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(values).position(0);
        return buffer;
    }
}
