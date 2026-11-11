package com.metamanning.questlite;

import java.nio.ByteBuffer;

/** Packs arbitrary Android YUV_420_888 row/pixel strides into the NV21 format for YuvImage. */
final class Yuv420 {
    private Yuv420() {}
    static byte[] nv21(int width, int height, int left, int top, ByteBuffer[] planes, int[] rows, int[] pixels) {
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096 || ((width | height | left | top) & 1) != 0 || left < 0 || top < 0)
            throw new IllegalArgumentException("Unsupported YUV crop");
        if (planes.length != 3 || rows.length != 3 || pixels.length != 3) throw new IllegalArgumentException("Three YUV planes required");
        for (int p = 0; p < 3; p++) if (rows[p] <= 0 || pixels[p] <= 0) throw new IllegalArgumentException("Invalid plane stride");
        byte[] output = new byte[width * height * 3 / 2];
        int index = 0;
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                output[index++] = planes[0].get(planes[0].position() + (top + y) * rows[0] + (left + x) * pixels[0]);
        for (int y = 0; y < height / 2; y++) {
            for (int x = 0; x < width / 2; x++) {
                output[index++] = planes[2].get(planes[2].position() + (top / 2 + y) * rows[2] + (left / 2 + x) * pixels[2]);
                output[index++] = planes[1].get(planes[1].position() + (top / 2 + y) * rows[1] + (left / 2 + x) * pixels[1]);
            }
        }
        return output;
    }
}
