package com.metamanning.questlite;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import java.io.ByteArrayOutputStream;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Real Camera2 preview and JPEG capture. No synthetic frames or network streaming. */
final class QuestCamera implements AutoCloseable {
    interface Listener { void status(String value); void frame(byte[] jpeg); }
    private final CameraManager manager;
    private final Context context;
    private final TextureView preview;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Surface surface;
    private boolean capturePending;
    private int generation;
    private int outputFormat;

    QuestCamera(Context context, TextureView preview, Listener listener) {
        this.context = context;
        manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.preview = preview; this.listener = listener;
    }
    boolean ready() { return session != null; }
    String[] ids() throws CameraAccessException { return manager.getCameraIdList(); }
    void open(String id) {
        close();
        if (!preview.isAvailable()) { listener.status("Camera preview is not ready; try Camera again."); return; }
        if (context.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED
            || context.checkSelfPermission("horizonos.permission.HEADSET_CAMERA") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            listener.status("Camera permissions are required. Select Camera and allow access."); return;
        }
        final int token = generation;
        try {
            StreamConfigurationMap map = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) throw new IllegalStateException("Camera has no stream configuration");
            Size[] yuv = map.getOutputSizes(ImageFormat.YUV_420_888);
            outputFormat = yuv != null && yuv.length > 0 ? ImageFormat.YUV_420_888 : ImageFormat.JPEG;
            Size jpeg = choose(map.getOutputSizes(outputFormat), 1280);
            Size display = choose(map.getOutputSizes(android.graphics.SurfaceTexture.class), 1280);
            preview.getSurfaceTexture().setDefaultBufferSize(display.getWidth(), display.getHeight());
            surface = new Surface(preview.getSurfaceTexture());
            reader = ImageReader.newInstance(jpeg.getWidth(), jpeg.getHeight(), outputFormat, 2);
            reader.setOnImageAvailableListener(source -> {
                if (token != generation) return;
                try (Image image = source.acquireLatestImage()) {
                    if (image == null) return;
                    byte[] bytes;
                    if (image.getFormat() == ImageFormat.YUV_420_888) {
                        Rect crop = image.getCropRect();
                        int left = crop.left & ~1, top = crop.top & ~1;
                        int width = crop.width() & ~1, height = crop.height() & ~1;
                        Image.Plane[] planes = image.getPlanes();
                        byte[] nv21 = Yuv420.nv21(width, height, left, top,
                            new ByteBuffer[]{planes[0].getBuffer(), planes[1].getBuffer(), planes[2].getBuffer()},
                            new int[]{planes[0].getRowStride(), planes[1].getRowStride(), planes[2].getRowStride()},
                            new int[]{planes[0].getPixelStride(), planes[1].getPixelStride(), planes[2].getPixelStride()});
                        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                        if (!new YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(new Rect(0, 0, width, height), 70, encoded))
                            throw new IllegalStateException("Could not encode camera image");
                        bytes = encoded.toByteArray();
                    } else {
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        bytes = new byte[buffer.remaining()]; buffer.get(bytes);
                    }
                    capturePending = false;
                    listener.frame(bytes);
                } catch (RuntimeException error) {
                    capturePending = false; listener.status("Image conversion failed: " + error.getMessage());
                }
            }, handler);
            manager.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    if (token != generation) { device.close(); return; }
                    camera = device;
                    try {
                        device.createCaptureSession(Arrays.asList(surface, reader.getSurface()), new CameraCaptureSession.StateCallback() {
                            @Override public void onConfigured(CameraCaptureSession value) {
                                if (token != generation) { value.close(); return; }
                                session = value;
                                try {
                                    CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                    request.addTarget(surface);
                                    session.setRepeatingRequest(request.build(), null, handler);
                                    listener.status("Camera ready. Snapshot attaches a photo; Live sends sampled views.");
                                } catch (Exception e) { fail("Preview failed: " + e.getMessage()); }
                            }
                            @Override public void onConfigureFailed(CameraCaptureSession value) { if (token == generation) fail("Camera configuration failed"); }
                        }, handler);
                    } catch (Exception e) { fail("Camera session failed: " + e.getMessage()); }
                }
                @Override public void onDisconnected(CameraDevice device) { device.close(); if (token == generation) fail("Camera disconnected"); }
                @Override public void onError(CameraDevice device, int error) { device.close(); if (token == generation) fail("Camera error " + error); }
            }, handler);
        } catch (Exception e) { fail("Camera unavailable: " + e.getMessage()); }
    }
    void capture() {
        if (!ready() || capturePending) { listener.status("Wait for the camera, then try Snapshot again."); return; }
        try {
            CaptureRequest.Builder request = camera.createCaptureRequest(outputFormat == ImageFormat.YUV_420_888 ? CameraDevice.TEMPLATE_PREVIEW : CameraDevice.TEMPLATE_STILL_CAPTURE);
            request.addTarget(reader.getSurface());
            if (outputFormat == ImageFormat.JPEG) request.set(CaptureRequest.JPEG_QUALITY, (byte)70);
            capturePending = true;
            session.capture(request.build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureFailed(CameraCaptureSession value, CaptureRequest request, CaptureFailure failure) {
                    capturePending = false; listener.status("Photo capture failed; try again.");
                }
            }, handler);
        } catch (Exception e) { capturePending = false; listener.status("Capture failed: " + e.getMessage()); }
    }
    private static Size choose(Size[] sizes, int edge) {
        if (sizes == null || sizes.length == 0) throw new IllegalStateException("Required image format unsupported");
        Size best = sizes[0];
        long score = Long.MAX_VALUE;
        for (Size size : sizes) {
            long candidate = Math.abs(Math.max(size.getWidth(), size.getHeight()) - edge);
            if (candidate < score) { best = size; score = candidate; }
        }
        return best;
    }
    private void fail(String message) { close(); listener.status(message); }
    @Override public void close() {
        generation++; capturePending = false;
        if (session != null) { session.close(); session = null; }
        if (camera != null) { camera.close(); camera = null; }
        if (reader != null) { reader.close(); reader = null; }
        if (surface != null) { surface.release(); surface = null; }
    }
}
