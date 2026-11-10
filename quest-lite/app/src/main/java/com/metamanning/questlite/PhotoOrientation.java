/*
 * Copyright 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * Adapted to Java from android/camera-samples ImageUtils.kt (decodeOriented).
 * Metamanning modifications: platform EXIF orientation mapping and bounded-photo import.
 */
package com.metamanning.questlite;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import androidx.exifinterface.media.ExifInterface;
import java.io.ByteArrayInputStream;

final class PhotoOrientation {
    static Bitmap correct(Bitmap original, byte[] jpeg) {
        int orientation;
        try {
            orientation = new ExifInterface(new ByteArrayInputStream(jpeg))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception e) { return original; }
        int rotation = 0;
        boolean mirror = false;
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90: rotation = 90; break;
            case ExifInterface.ORIENTATION_ROTATE_180: rotation = 180; break;
            case ExifInterface.ORIENTATION_ROTATE_270: rotation = 270; break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: mirror = true; break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: rotation = 180; mirror = true; break;
            case ExifInterface.ORIENTATION_TRANSPOSE: rotation = 90; mirror = true; break;
            case ExifInterface.ORIENTATION_TRANSVERSE: rotation = 270; mirror = true; break;
            default: break;
        }
        if (rotation == 0 && !mirror) return original;
        Matrix matrix = new Matrix(); matrix.postRotate(rotation);
        if (mirror) matrix.postScale(-1f, 1f);
        Bitmap result = Bitmap.createBitmap(original, 0, 0, original.getWidth(), original.getHeight(), matrix, true);
        if (result != original) original.recycle();
        return result;
    }
}
