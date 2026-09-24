package com.autumn.douyin.liquidglass.root;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Process;
import android.os.SystemClock;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Root frame source for the liquid glass overlay. The loopback transport keeps
 * this isolated from SELinux rules for root-owned abstract Unix sockets.
 *
 * <p>The client sends capture-rectangle updates asynchronously while this daemon
 * continuously pushes the newest composited display frame. Keeping the capture
 * loop independent from client bitmap copies removes one request round-trip per
 * frame and lets TCP back-pressure keep the stream near the latest frame.
 */
public final class CompositeFrameDaemon {
    private static final int Magic = 0x4c475043;
    private static final int StatusOk = 1;
    private static final int StatusError = 2;
    private static final int StatusIdle = 3;
    private static final int DefaultTargetFramePeriodMillis = 17;
    private static final int MinTargetFramePeriodMillis = 8;
    private static final int MaxTargetFramePeriodMillis = 33;
    private static final int DefaultMaxTransportWidth = 480;
    private static final int MinTransportWidth = 320;
    private static final int MaxTransportWidthLimit = 640;
    private static final int IdleResponsePeriodMillis = 32;
    private static final String PidFilePath =
        "/data/local/tmp/douyin_liquid_glass_composite.pid";
    private static final String DaemonCommandLine =
        "app_process /system/bin com.autumn.douyin.liquidglass.root.CompositeFrameDaemon";

    private static boolean diagnosticsEnabled = false;
    private static int targetFramePeriodMillis = DefaultTargetFramePeriodMillis;
    private static int maxTransportWidth = DefaultMaxTransportWidth;

    private CompositeFrameDaemon() {
    }

    private static void stopExistingDaemons() throws InterruptedException {
        Long recordedPid = readPidFile();
        if (recordedPid != null) {
            stopDaemonIfMatched(recordedPid);
        }

        File[] processes = new File("/proc").listFiles();
        if (processes != null) {
            for (File processDirectory : processes) {
                long pid = parsePid(processDirectory.getName());
                if (pid <= 0 || pid == Process.myPid()) continue;
                stopDaemonIfMatched(pid);
            }
        }
        Thread.sleep(100);
        deletePidFile();
    }

    private static void stopDaemonIfMatched(long pid) {
        String command = readCommandLine(pid);
        if (!command.startsWith(DaemonCommandLine)) return;

        Process.killProcess((int) pid);
        log("composite daemon stopped previous pid=" + pid);
    }

    private static long parsePid(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static Long readPidFile() {
        File file = new File(PidFilePath);
        if (!file.isFile()) return null;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] data = new byte[32];
            int length = input.read(data);
            if (length <= 0) return null;
            long pid = Long.parseLong(new String(data, 0, length).trim());
            return pid > 0 ? pid : null;
        } catch (Exception exception) {
            return null;
        }
    }

    private static String readCommandLine(long pid) {
        File file = new File("/proc/" + pid + "/cmdline");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] data = new byte[512];
            int length = input.read(data);
            if (length <= 0) return "";
            return new String(data, 0, length).replace('\0', ' ').trim();
        } catch (Exception exception) {
            return "";
        }
    }

    private static void writePidFile() throws Exception {
        try (FileOutputStream output = new FileOutputStream(PidFilePath)) {
            output.write((Process.myPid() + "\n").getBytes("UTF-8"));
            output.flush();
        }
    }

    private static void deletePidFile() {
        new File(PidFilePath).delete();
    }

    private static void deletePidFileIfCurrent() {
        Long pid = readPidFile();
        if (pid != null && pid == Process.myPid()) {
            deletePidFile();
        }
    }

    public static void main(String[] args) throws Exception {
        if (hasProbeCaptureArgument(args)) {
            runCaptureProbe();
            return;
        }

        boolean stopOnly = args.length > 0 && "--stop".equals(args[0]);
        diagnosticsEnabled = parseDiagnosticsEnabled(args);
        targetFramePeriodMillis = clamp(
            parseIntegerArgument(args, "--frame-period-ms=", DefaultTargetFramePeriodMillis),
            MinTargetFramePeriodMillis,
            MaxTargetFramePeriodMillis
        );
        maxTransportWidth = clamp(
            parseIntegerArgument(args, "--transport-width=", DefaultMaxTransportWidth),
            MinTransportWidth,
            MaxTransportWidthLimit
        );
        stopExistingDaemons();
        if (stopOnly) {
            deletePidFile();
            return;
        }

        writePidFile();
        try (ServerSocket server = new ServerSocket(
            CompositeFrameDaemonLauncher.PORT,
            2,
            InetAddress.getLoopbackAddress()
        )) {
            if (diagnosticsEnabled) {
                log(
                    "composite frame daemon ready port=" + CompositeFrameDaemonLauncher.PORT
                        + " framePeriodMs=" + targetFramePeriodMillis
                        + " transportWidth=" + maxTransportWidth
                );
            }
            while (true) {
                try (Socket client = server.accept()) {
                    client.setTcpNoDelay(true);
                    // A sub-frame send buffer makes back-pressure take effect before
                    // multiple stale frames can queue up in the kernel.
                    client.setSendBufferSize(512 * 1024);
                    handleClient(client);
                } catch (Exception exception) {
                    Throwable cause = unwrap(exception);
                    if (diagnosticsEnabled) {
                        logThrowable("composite frame client ended: " + cause, cause);
                    }
                }
            }
        } catch (Throwable throwable) {
            if (diagnosticsEnabled) {
                logThrowable("composite frame daemon stopped", throwable);
            }
        } finally {
            deletePidFileIfCurrent();
        }
    }

    private static void handleClient(Socket client) throws Exception {
        DataOutputStream output = new DataOutputStream(
            new BufferedOutputStream(client.getOutputStream(), 256 * 1024)
        );

        // Do not buffer handshake input: a buffered stream can prefetch the first
        // geometry update before the dedicated reader thread is started.
        String handshake = new DataInputStream(client.getInputStream()).readUTF();
        if (!CompositeFrameDaemonLauncher.HANDSHAKE_TOKEN.equals(handshake)) {
            throw new SecurityException("bad composite handshake");
        }

        Request request = new Request();
        Thread geometryReader = new Thread(
            () -> readGeometry(client, request),
            "liquid-glass-composite-geometry"
        );
        geometryReader.setDaemon(true);
        geometryReader.start();
        try {
            streamFrames(request, output);
        } finally {
            invokeClose(client);
        }
    }

    private static void readGeometry(Socket client, Request request) {
        try {
            DataInputStream input = new DataInputStream(
                new BufferedInputStream(client.getInputStream(), 16 * 1024)
            );
            while (true) {
                int left = input.readInt();
                int top = input.readInt();
                int right = input.readInt();
                int bottom = input.readInt();
                request.rect = new Rect(left, top, right, bottom);
            }
        } catch (Throwable ignored) {
            // The writer observes disconnects while pushing frames.
        }
    }

    private static boolean parseDiagnosticsEnabled(String[] args) {
        for (String argument : args) {
            if ("--diagnostics=true".equals(argument)) return true;
            if ("--diagnostics=false".equals(argument)) return false;
        }
        return false;
    }

    private static int parseIntegerArgument(String[] args, String prefix, int defaultValue) {
        for (String argument : args) {
            if (argument.startsWith(prefix)) {
                try {
                    return Integer.parseInt(argument.substring(prefix.length()));
                } catch (NumberFormatException ignored) {
                    return defaultValue;
                }
            }
        }
        return defaultValue;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static boolean hasProbeCaptureArgument(String[] args) {
        for (String argument : args) {
            if ("--probe-capture".equals(argument)) return true;
        }
        return false;
    }

    private static void runCaptureProbe() {
        String backendName = "unknown";
        Object hardwareBuffer = null;
        try {
            FrameCaptureBackend backend = createCaptureBackend();
            backendName = backend.name();
            Object buffer = backend.capture(new Rect(0, 0, 16, 16), 1.0f);
            if (buffer == null) {
                throw new UnsupportedOperationException("capture returned null buffer");
            }
            hardwareBuffer = buffer.getClass()
                .getMethod("getHardwareBuffer")
                .invoke(buffer);
            Bitmap bitmap = (Bitmap) buffer.getClass()
                .getMethod("asBitmap")
                .invoke(buffer);
            if (bitmap == null) {
                throw new UnsupportedOperationException("capture returned null bitmap");
            }
            System.out.println(
                "CAPTURE_SUPPORTED backend=" + backendName +
                    " bitmap=" + bitmapMetadata(bitmap)
            );
        } catch (Throwable throwable) {
            System.out.println(
                "CAPTURE_UNSUPPORTED backend=" + backendName +
                    " reason=" + throwableMetadata(throwable)
            );
            throwable.printStackTrace(System.out);
        } finally {
            try {
                if (hardwareBuffer != null) invokeClose(hardwareBuffer);
            } catch (Exception ignored) {
                // Probe metadata is already complete by this point.
            }
        }
    }

    private static void log(String message) {
        if (diagnosticsEnabled) System.out.println(message);
    }

    private static void logThrowable(String message, Throwable throwable) {
        if (!diagnosticsEnabled) return;
        log(message);
        throwable.printStackTrace(System.out);
    }

    private static void streamFrames(
        Request request,
        DataOutputStream output
    ) throws Exception {
        FrameCaptureBackend backend;
        try {
            backend = createCaptureBackend();
            log("composite capture backend selected=" + backend.name());
        } catch (UnsupportedOperationException exception) {
            writeError(output, "capture unsupported: " + throwableMetadata(exception));
            return;
        }
        Method getHardwareBuffer = null;
        Method asBitmap = null;
        ByteBuffer pixels = null;
        long frames = 0;
        long conversionFailures = 0;
        long statsFrames = 0;
        double statsElapsedSum = 0.0;
        double statsElapsedMax = 0.0;
        double captureSum = 0.0;
        double conversionSum = 0.0;
        double readSum = 0.0;
        double writeSum = 0.0;
        double transportScale = 1.0;
        String loggedSourceMode = null;
        int sourceModeLogs = 0;
        long statsStartTime = SystemClock.uptimeMillis();
        long lastIdleResponseTime = 0;
        while (true) {
            Rect requestedRect = request.rect;
            if (requestedRect == null || requestedRect.isEmpty()) {
                long now = SystemClock.uptimeMillis();
                if (now - lastIdleResponseTime >= IdleResponsePeriodMillis) {
                    lastIdleResponseTime = now;
                    writeIdle(output);
                }
                Thread.sleep(IdleResponsePeriodMillis);
                continue;
            }

            long frameStart = SystemClock.uptimeMillis();
            int left = requestedRect.left;
            int top = requestedRect.top;
            int right = requestedRect.right;
            int bottom = requestedRect.bottom;

            float nativeScale = Math.min(
                1.0f,
                maxTransportWidth / (float) Math.max(1, right - left)
            );
            long captureStart = SystemClock.uptimeMillis();
            Object buffer = backend.capture(requestedRect, nativeScale);
            double captureElapsed = SystemClock.uptimeMillis() - captureStart;
            if (buffer == null) {
                writeError(output, "null buffer");
                sleepUntilNextFrame(frameStart);
                continue;
            }

            if (getHardwareBuffer == null) {
                getHardwareBuffer = buffer.getClass().getMethod("getHardwareBuffer");
            }
            Object hardwareBuffer = getHardwareBuffer.invoke(buffer);
            if (hardwareBuffer == null) {
                writeError(output, "null hardware buffer");
                sleepUntilNextFrame(frameStart);
                continue;
            }

            Bitmap sourceBitmap = null;
            Bitmap scaledBitmap = null;
            Bitmap bitmap = null;
            long conversionStart = SystemClock.uptimeMillis();
            try {
                try {
                    if (asBitmap == null) {
                        asBitmap = buffer.getClass().getMethod("asBitmap");
                    }
                    sourceBitmap = (Bitmap) asBitmap.invoke(buffer);
                    if (sourceBitmap == null) {
                        writeError(output, "null bitmap");
                        sleepUntilNextFrame(frameStart);
                        continue;
                    }

                    int sourceWidth = sourceBitmap.getWidth();
                    int sourceHeight = sourceBitmap.getHeight();
                    int requestedWidth = right - left;
                    int requestedHeight = bottom - top;
                    float sourceCoordinateScale = backend.sourceCoordinateScale();
                    boolean backendCropsSource = backend.cropsSource(
                        sourceWidth,
                        sourceHeight,
                        requestedRect,
                        nativeScale
                    );
                    boolean requestAlreadyCropped =
                        sourceWidth == requestedWidth && sourceHeight == requestedHeight;
                    String sourceMode = backendCropsSource || requestAlreadyCropped
                        ? "request-frame"
                        : "display-frame";
                    if (sourceModeLogs < 3 || !sourceMode.equals(loggedSourceMode)) {
                        loggedSourceMode = sourceMode;
                        sourceModeLogs++;
                        log(
                            "composite source mode=" + sourceMode +
                                " request=" + requestedWidth + "x" + requestedHeight +
                                " source=" + sourceWidth + "x" + sourceHeight +
                                " coordinateScale=" + sourceCoordinateScale
                        );
                    }
                    if (!backendCropsSource && !requestAlreadyCropped) {
                        int cropLeft = Math.max(
                            0,
                            Math.min(Math.round(left * sourceCoordinateScale), sourceWidth - 1)
                        );
                        int cropTop = Math.max(
                            0,
                            Math.min(Math.round(top * sourceCoordinateScale), sourceHeight - 1)
                        );
                        int cropRight = Math.max(
                            cropLeft + 1,
                            Math.min(Math.round(right * sourceCoordinateScale), sourceWidth)
                        );
                        int cropBottom = Math.max(
                            cropTop + 1,
                            Math.min(Math.round(bottom * sourceCoordinateScale), sourceHeight)
                        );
                        sourceBitmap = Bitmap.createBitmap(
                            sourceBitmap,
                            cropLeft,
                            cropTop,
                            cropRight - cropLeft,
                            cropBottom - cropTop
                        );
                        sourceWidth = sourceBitmap.getWidth();
                        sourceHeight = sourceBitmap.getHeight();
                    }
                    transportScale = Math.min(1.0, maxTransportWidth / (double) sourceWidth);
                    int transportWidth = Math.max(
                        1,
                        (int) Math.round(sourceWidth * transportScale)
                    );
                    int transportHeight = Math.max(
                        1,
                        (int) Math.round(sourceHeight * transportScale)
                    );
                    boolean nativeScaleApplied = Math.abs(
                        sourceWidth - transportWidth
                    ) <= 2 && Math.abs(sourceHeight - transportHeight) <= 2;
                    if (nativeScaleApplied) {
                        scaledBitmap = sourceBitmap.copy(Bitmap.Config.ARGB_8888, false);
                    } else {
                        scaledBitmap = Bitmap.createScaledBitmap(
                            sourceBitmap,
                            transportWidth,
                            transportHeight,
                            // The glass effect blurs this source again, so nearest-neighbor
                            // scaling avoids unusable detail and conversion work.
                            false
                        );
                    }
                    bitmap = scaledBitmap.getConfig() == Bitmap.Config.ARGB_8888 &&
                        scaledBitmap.getRowBytes() == transportWidth * 4
                        ? scaledBitmap
                        : scaledBitmap.copy(Bitmap.Config.ARGB_8888, false);
                    if (bitmap == null) {
                        writeError(
                            output,
                            "software bitmap copy failed source=" + bitmapMetadata(sourceBitmap)
                        );
                        sleepUntilNextFrame(frameStart);
                        continue;
                    }
                } catch (Throwable throwable) {
                    conversionFailures++;
                    if (
                        diagnosticsEnabled &&
                            (conversionFailures == 1L || conversionFailures % 300L == 0L)
                    ) {
                        log(
                            "composite software conversion failures=" + conversionFailures +
                                " " + throwableMetadata(throwable)
                        );
                    }
                    String sourceMetadata = sourceBitmap == null
                        ? "source=null"
                        : "source=" + bitmapMetadata(sourceBitmap);
                    writeError(
                        output,
                        "software frame conversion failed " + sourceMetadata +
                            " cause=" + throwableMetadata(throwable)
                    );
                    sleepUntilNextFrame(frameStart);
                    continue;
                }
                double conversionElapsed = SystemClock.uptimeMillis() - conversionStart;

                int width = bitmap.getWidth();
                int height = bitmap.getHeight();
                int byteCount = bitmap.getByteCount();
                if (
                    bitmap.getConfig() != Bitmap.Config.ARGB_8888 ||
                        bitmap.getRowBytes() != width * 4 ||
                        byteCount != width * height * 4
                ) {
                    writeError(
                        output,
                        "unsupported software bitmap " + bitmapMetadata(bitmap)
                    );
                    sleepUntilNextFrame(frameStart);
                    continue;
                }

                if (pixels == null || pixels.capacity() < byteCount) {
                    pixels = ByteBuffer.allocateDirect(byteCount)
                        .order(ByteOrder.nativeOrder());
                }
                pixels.clear();
                pixels.limit(byteCount);
                long readStart = SystemClock.uptimeMillis();
                bitmap.copyPixelsToBuffer(pixels);
                double readElapsed = SystemClock.uptimeMillis() - readStart;
                long writeStart = SystemClock.uptimeMillis();
                output.writeInt(Magic);
                output.writeInt(StatusOk);
                output.writeInt(width);
                output.writeInt(height);
                output.writeInt(left);
                output.writeInt(top);
                output.writeInt(right);
                output.writeInt(bottom);
                output.writeInt(byteCount);
                output.writeLong(frameStart);
                pixels.position(0);
                writeByteBuffer(output, pixels);
                output.flush();
                double writeElapsed = SystemClock.uptimeMillis() - writeStart;
                frames++;
                double elapsed = (SystemClock.uptimeMillis() - frameStart) / 1.0;
                statsFrames++;
                statsElapsedSum += elapsed;
                statsElapsedMax = Math.max(statsElapsedMax, elapsed);
                captureSum += captureElapsed;
                conversionSum += conversionElapsed;
                readSum += readElapsed;
                writeSum += writeElapsed;
                if (diagnosticsEnabled && (frames == 1 || frames % 300 == 0)) {
                    log(
                        "composite frames=" + frames
                            + " size=" + width + "x" + height
                            + " scale=" + String.format("%.2f", transportScale)
                            + " config=" + bitmap.getConfig()
                            + " avgMs=" + String.format(
                                "%.2f", statsElapsedSum / statsFrames
                            )
                            + " maxMs=" + String.format("%.2f", statsElapsedMax)
                            + " fps=" + String.format(
                                "%.2f",
                                statsFrames * 1000.0 /
                                    Math.max(1, SystemClock.uptimeMillis() - statsStartTime)
                            )
                            + " captureAvgMs=" + String.format("%.2f", captureSum / statsFrames)
                            + " convertAvgMs=" + String.format("%.2f", conversionSum / statsFrames)
                            + " readAvgMs=" + String.format("%.2f", readSum / statsFrames)
                            + " writeAvgMs=" + String.format("%.2f", writeSum / statsFrames)
                    );
                    statsFrames = 0;
                    statsElapsedSum = 0.0;
                    statsElapsedMax = 0.0;
                    captureSum = 0.0;
                    conversionSum = 0.0;
                    readSum = 0.0;
                    writeSum = 0.0;
                    statsStartTime = SystemClock.uptimeMillis();
                }
            } finally {
                if (bitmap != null) {
                    bitmap.recycle();
                }
                if (
                    scaledBitmap != null &&
                        scaledBitmap != bitmap &&
                        scaledBitmap != sourceBitmap &&
                        scaledBitmap.getConfig() != Bitmap.Config.HARDWARE
                ) {
                    scaledBitmap.recycle();
                }
                if (
                    sourceBitmap != null &&
                        sourceBitmap.getConfig() != Bitmap.Config.HARDWARE
                ) {
                    sourceBitmap.recycle();
                }
                invokeClose(hardwareBuffer);
            }
            sleepUntilNextFrame(frameStart);
        }
    }

    private static void sleepUntilNextFrame(long frameStart) throws InterruptedException {
        long remaining = targetFramePeriodMillis -
            (SystemClock.uptimeMillis() - frameStart);
        if (remaining > 0) {
            Thread.sleep(remaining);
        }
    }

    private static String bitmapMetadata(Bitmap bitmap) {
        return "width=" + bitmap.getWidth()
            + " height=" + bitmap.getHeight()
            + " byteCount=" + bitmap.getByteCount()
            + " config=" + bitmap.getConfig()
            + " rowBytes=" + bitmap.getRowBytes();
    }

    private static String throwableMetadata(Throwable throwable) {
        Throwable cause = unwrap(throwable);
        String message = cause.getMessage();
        return cause.getClass().getName() + (message == null || message.isEmpty()
            ? ""
            : ":" + message);
    }

    private static void invokeClose(Object closeable) throws Exception {
        try {
            closeable.getClass().getMethod("close").invoke(closeable);
        } catch (InvocationTargetException exception) {
            Throwable cause = unwrap(exception);
            throw cause instanceof Exception ? (Exception) cause : exception;
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof InvocationTargetException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static void writeByteBuffer(DataOutputStream output, ByteBuffer bytes)
        throws Exception {
        byte[] chunk = new byte[64 * 1024];
        while (bytes.hasRemaining()) {
            int count = Math.min(chunk.length, bytes.remaining());
            bytes.get(chunk, 0, count);
            output.write(chunk, 0, count);
        }
    }

    private static void writeError(DataOutputStream output, String message)
        throws Exception {
        output.writeInt(Magic);
        output.writeInt(StatusError);
        output.writeInt(0);
        output.writeInt(0);
        output.writeInt(0);
        output.writeInt(0);
        output.writeInt(0);
        output.writeInt(0);
        output.writeInt(0);
        output.writeUTF(message);
        output.flush();
    }

    private static void writeIdle(DataOutputStream output) throws Exception {
        output.writeInt(Magic);
        output.writeInt(StatusIdle);
        output.writeInt(0);
        output.writeInt(0);
        output.writeInt(0);
        output.writeInt(0);
        output.writeInt(0);
        output.writeInt(0);
        output.writeInt(0);
        output.writeLong(SystemClock.uptimeMillis());
        output.flush();
    }

    private static FrameCaptureBackend createCaptureBackend()
        throws UnsupportedOperationException {
        Exception modernError;
        try {
            return createModernCaptureBackend();
        } catch (Exception exception) {
            modernError = exception;
        }

        Exception legacyError;
        try {
            return createLegacyCaptureBackend();
        } catch (Exception exception) {
            legacyError = exception;
        }

        throw new UnsupportedOperationException(
            "modern=" + throwableMetadata(modernError) +
                " legacy=" + throwableMetadata(legacyError)
        );
    }

    private static FrameCaptureBackend createModernCaptureBackend() throws Exception {
        Object windowManager = Class.forName("android.view.WindowManagerGlobal")
            .getMethod("getInstance")
            .invoke(null);
        windowManager = windowManager.getClass()
            .getMethod("getWindowManagerService")
            .invoke(windowManager);
        Method createListener = Class.forName("android.window.ScreenCapture")
            .getMethod("createSyncCaptureListener");

        Class<?> builderClass = Class.forName(
            "android.window.ScreenCapture$CaptureArgs$Builder"
        );
        Class<?> argsClass = Class.forName("android.window.ScreenCapture$CaptureArgs");
        Class<?> listenerClass = Class.forName(
            "android.window.ScreenCapture$ScreenCaptureListener"
        );
        Method capture = windowManager.getClass().getMethod(
            "captureDisplay",
            int.class,
            argsClass,
            listenerClass
        );
        Constructor<?> createBuilder = builderClass.getDeclaredConstructor();
        Method setSourceCrop = builderClass.getMethod("setSourceCrop", Rect.class);
        Method setFrameScale = null;
        try {
            setFrameScale = builderClass.getMethod("setFrameScale", float.class);
        } catch (NoSuchMethodException ignored) {
            // Older framework builds only return the cropped native-resolution frame.
        }
        Method buildArgs = builderClass.getMethod("build");
        return new ModernCaptureBackend(
            windowManager,
            createListener,
            capture,
            createBuilder,
            setSourceCrop,
            setFrameScale,
            buildArgs
        );
    }

    private static FrameCaptureBackend createLegacyCaptureBackend() throws Exception {
        Class<?> surfaceControlClass = Class.forName("android.view.SurfaceControl");
        Object displayToken = surfaceControlClass
            .getMethod("getInternalDisplayToken")
            .invoke(null);
        if (displayToken == null) {
            throw new IllegalStateException("internal display token is null");
        }

        Class<?> builderClass = Class.forName(
            "android.view.SurfaceControl$DisplayCaptureArgs$Builder"
        );
        Class<?> argsClass = Class.forName(
            "android.view.SurfaceControl$DisplayCaptureArgs"
        );
        Constructor<?> createBuilder = builderClass.getConstructor(
            Class.forName("android.os.IBinder")
        );
        Method setSourceCrop = builderClass.getMethod(
            "setSourceCrop",
            Rect.class
        );
        Method setFrameScale = null;
        try {
            setFrameScale = builderClass.getMethod("setFrameScale", float.class);
        } catch (NoSuchMethodException ignored) {
            // The software scaler below can handle native-resolution captures.
        }
        Method setSize = builderClass.getMethod("setSize", int.class, int.class);
        Method buildArgs = builderClass.getMethod("build");
        Method captureDisplay = surfaceControlClass.getMethod(
            "captureDisplay",
            argsClass
        );
        return new LegacySurfaceControlBackend(
            createBuilder,
            displayToken,
            setSourceCrop,
            setFrameScale,
            setSize,
            buildArgs,
            captureDisplay
        );
    }

    private interface FrameCaptureBackend {
        String name();

        boolean cropsSource(
            int sourceWidth,
            int sourceHeight,
            Rect rect,
            float frameScale
        );

        float sourceCoordinateScale();

        Object capture(Rect rect, float frameScale) throws Exception;
    }

    private static final class ModernCaptureBackend implements FrameCaptureBackend {
        private final Object windowManager;
        private final Method createListener;
        private final Method captureDisplay;
        private final Constructor<?> createBuilder;
        private final Method setSourceCrop;
        private final Method setFrameScale;
        private final Method buildArgs;

        private ModernCaptureBackend(
            Object windowManager,
            Method createListener,
            Method captureDisplay,
            Constructor<?> createBuilder,
            Method setSourceCrop,
            Method setFrameScale,
            Method buildArgs
        ) {
            this.windowManager = windowManager;
            this.createListener = createListener;
            this.captureDisplay = captureDisplay;
            this.createBuilder = createBuilder;
            this.setSourceCrop = setSourceCrop;
            this.setFrameScale = setFrameScale;
            this.buildArgs = buildArgs;
        }

        @Override
        public String name() {
            return "screen-capture";
        }

        @Override
        public boolean cropsSource(
            int sourceWidth,
            int sourceHeight,
            Rect rect,
            float frameScale
        ) {
            return true;
        }

        @Override
        public float sourceCoordinateScale() {
            return 1.0f;
        }

        @Override
        public Object capture(Rect rect, float frameScale) throws Exception {
            Object builder = createBuilder.newInstance();
            setSourceCrop.invoke(builder, new Rect(rect));
            if (setFrameScale != null) {
                setFrameScale.invoke(builder, frameScale);
            }
            Object captureArgs = buildArgs.invoke(builder);
            Object listener = createListener.invoke(null);
            captureDisplay.invoke(windowManager, 0, captureArgs, listener);
            return listener.getClass().getMethod("getBuffer").invoke(listener);
        }
    }

    private static final class LegacySurfaceControlBackend implements FrameCaptureBackend {
        private final Constructor<?> createBuilder;
        private final Object displayToken;
        private final Method setSourceCrop;
        private final Method setFrameScale;
        private final Method setSize;
        private final Method buildArgs;
        private final Method captureDisplay;
        private int displayWidth;
        private int displayHeight;
        private float coordinateScale = 1.0f;

        private LegacySurfaceControlBackend(
            Constructor<?> createBuilder,
            Object displayToken,
            Method setSourceCrop,
            Method setFrameScale,
            Method setSize,
            Method buildArgs,
            Method captureDisplay
        ) {
            this.createBuilder = createBuilder;
            this.displayToken = displayToken;
            this.setSourceCrop = setSourceCrop;
            this.setFrameScale = setFrameScale;
            this.setSize = setSize;
            this.buildArgs = buildArgs;
            this.captureDisplay = captureDisplay;
        }

        @Override
        public String name() {
            return "surface-control";
        }

        @Override
        public boolean cropsSource(
            int sourceWidth,
            int sourceHeight,
            Rect rect,
            float frameScale
        ) {
            return matchesRequestedFrame(sourceWidth, sourceHeight, rect, 1.0f) ||
                matchesRequestedFrame(sourceWidth, sourceHeight, rect, frameScale);
        }

        @Override
        public float sourceCoordinateScale() {
            return coordinateScale;
        }

        @Override
        public Object capture(Rect rect, float frameScale) throws Exception {
            Object builder = createBuilder.newInstance(displayToken);
            setSourceCrop.invoke(builder, new Rect(rect));
            if (setFrameScale != null) {
                setFrameScale.invoke(builder, frameScale);
            }
            int outputWidth = 0;
            int outputHeight = 0;
            if (displayWidth > 0 && displayHeight > 0) {
                // Android 13 vendors combine crop, scale, and size differently.
                // Classify the returned frame below before applying software crop.
                outputWidth = Math.max(1, Math.round(rect.width() * frameScale));
                outputHeight = Math.max(1, Math.round(rect.height() * frameScale));
                setSize.invoke(builder, outputWidth, outputHeight);
                coordinateScale = outputWidth / (float) displayWidth;
            }
            Object captureArgs = buildArgs.invoke(builder);
            Object buffer = captureDisplay.invoke(null, captureArgs);
            if (displayWidth == 0 || displayHeight == 0) {
                Object hardwareBuffer = buffer.getClass()
                    .getMethod("getHardwareBuffer")
                    .invoke(buffer);
                displayWidth = (Integer) hardwareBuffer.getClass()
                    .getMethod("getWidth")
                    .invoke(hardwareBuffer);
                displayHeight = (Integer) hardwareBuffer.getClass()
                    .getMethod("getHeight")
                    .invoke(hardwareBuffer);
                coordinateScale = 1.0f;
            }
            return buffer;
        }

        private static boolean matchesRequestedFrame(
            int width,
            int height,
            Rect rect,
            float scale
        ) {
            return Math.abs(width - Math.round(rect.width() * scale)) <= 2 &&
                Math.abs(height - Math.round(rect.height() * scale)) <= 2;
        }
    }

    private static final class Request {
        volatile Rect rect;
    }
}
