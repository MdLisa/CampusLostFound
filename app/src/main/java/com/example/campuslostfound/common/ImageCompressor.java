package com.example.campuslostfound.common;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import androidx.exifinterface.media.ExifInterface;
import android.net.Uri;
import android.os.Looper;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** 图片压缩与预览解码都由调用方放在后台线程，不一次性解码相机原始大图。 */
public final class ImageCompressor {
    private ImageCompressor() { }

    public static final class CompressedImage {
        public final byte[] jpeg;
        public final String base64;
        public final int width;
        public final int height;

        private CompressedImage(byte[] jpeg, int width, int height) {
            this.jpeg = jpeg;
            this.base64 = Base64.encodeToString(jpeg, Base64.NO_WRAP);
            this.width = width;
            this.height = height;
        }
    }

    public static CompressedImage compress(Context context, Uri uri) throws IOException {
        requireWorker();
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream input = open(context, uri)) {
                BitmapFactory.decodeStream(input, null, bounds);
            }
            BitmapFactory.Options options = sampledOptions(bounds);
            try (InputStream input = open(context, uri)) {
                bitmap = BitmapFactory.decodeStream(input, null, options);
            }
            if (bitmap == null) throw new IOException("无法读取所选图片");
            int orientation = ExifInterface.ORIENTATION_NORMAL;
            try (InputStream input = open(context, uri)) {
                orientation = readOrientation(input);
            }
            bitmap = orientAndScale(bitmap, orientation);
            // 透明 PNG 等转成白底 JPEG，避免透明区域变黑。
            if (bitmap.hasAlpha()) {
                Bitmap opaque = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.RGB_565);
                Canvas canvas = new Canvas(opaque);
                canvas.drawColor(Color.WHITE);
                canvas.drawBitmap(bitmap, 0, 0, null);
                bitmap.recycle();
                bitmap = opaque;
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (int scalePass = 0; scalePass < 6; scalePass++) {
                for (int quality = 85; quality >= 45; quality -= 10) {
                    output.reset();
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                        throw new IOException("无法将图片转换为 JPEG");
                    }
                    if (output.size() <= ImageRules.TARGET_BYTES) {
                        return new CompressedImage(output.toByteArray(), bitmap.getWidth(), bitmap.getHeight());
                    }
                }
                Bitmap smaller = Bitmap.createScaledBitmap(bitmap,
                        Math.max(1, bitmap.getWidth() * 3 / 4),
                        Math.max(1, bitmap.getHeight() * 3 / 4), true);
                if (smaller != bitmap) bitmap.recycle();
                bitmap = smaller;
            }
            throw new IOException("图片压缩后仍然过大，请换一张图片");
        } catch (OutOfMemoryError e) {
            throw new IOException("图片处理内存不足，请换一张较小的图片");
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    /** 验证 JPEG 并生成最长边不超过 800 的预览；返回 Bitmap 由使用者回收。 */
    public static Bitmap decodeJpeg(byte[] jpeg) throws IOException {
        requireWorker();
        if (jpeg == null || jpeg.length < 4 || jpeg.length > ImageRules.MAX_JPEG_BYTES
                || (jpeg[0] & 0xff) != 0xff || (jpeg[1] & 0xff) != 0xd8
                || (jpeg[2] & 0xff) != 0xff
                || (jpeg[jpeg.length - 2] & 0xff) != 0xff || (jpeg[jpeg.length - 1] & 0xff) != 0xd9) {
            throw new IOException("图片不是完整的 JPEG");
        }
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
            if (!"image/jpeg".equals(bounds.outMimeType)) throw new IOException("图片格式必须为 JPEG");
            BitmapFactory.Options options = sampledOptions(bounds);
            bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
            if (bitmap == null) throw new IOException("JPEG 图片无法解码");
            int orientation = readOrientation(new ByteArrayInputStream(jpeg));
            Bitmap result = orientAndScale(bitmap, orientation);
            bitmap = null; // 成功后交给调用者管理。
            return result;
        } catch (OutOfMemoryError e) {
            throw new IOException("图片解码内存不足");
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private static InputStream open(Context context, Uri uri) throws IOException {
        InputStream input = context.getContentResolver().openInputStream(uri);
        if (input == null) throw new IOException("无法打开所选图片");
        return input;
    }

    private static BitmapFactory.Options sampledOptions(BitmapFactory.Options bounds) throws IOException {
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("图片无法解码");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > ImageRules.MAX_SIDE * 2) {
            options.inSampleSize *= 2;
        }
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return options;
    }

    private static int readOrientation(InputStream input) {
        try {
            return new ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException e) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private static Bitmap orientAndScale(Bitmap bitmap, int orientation) {
        int max = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (max > ImageRules.MAX_SIDE) {
            float ratio = ImageRules.MAX_SIDE / (float) max;
            Bitmap resized = Bitmap.createScaledBitmap(bitmap,
                    Math.max(1, Math.round(bitmap.getWidth() * ratio)),
                    Math.max(1, Math.round(bitmap.getHeight() * ratio)), true);
            if (resized != bitmap) bitmap.recycle();
            bitmap = resized;
        }
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: matrix.setRotate(90); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: matrix.setRotate(-90); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(-90); break;
            default: return bitmap;
        }
        Bitmap result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (result != bitmap) bitmap.recycle();
        return result;
    }

    private static void requireWorker() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("图片处理必须在后台线程执行");
        }
    }
}
