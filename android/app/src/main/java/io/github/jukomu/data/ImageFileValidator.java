package io.github.jukomu.data;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;

/**
 * 图片文件校验工具。
 *
 */
public final class ImageFileValidator {

    private static final String TAG = "ImageFileValidator";

    private ImageFileValidator() {
    }

    /**
     * 快速校验只读取图片边界
     *
     */
    public static boolean validateQuick(File imageFile) {
        if (!isNonEmptyFile(imageFile)) {
            return false;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try {
            BitmapFactory.decodeFile(imageFile.getAbsolutePath(), options);
            return options.outWidth > 0 && options.outHeight > 0;
        } catch (RuntimeException | OutOfMemoryError error) {
            Log.w(TAG, "快速图片校验失败: " + imageFile.getPath(), error);
            return false;
        }
    }

    /**
     * 完整校验实际解码图片
     *
     */
    public static synchronized boolean validateFull(File imageFile) {
        if (!isNonEmptyFile(imageFile)) {
            return false;
        }

        Bitmap bitmap = null;
        try {
            bitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath());
            return bitmap != null && bitmap.getWidth() > 0 && bitmap.getHeight() > 0;
        } catch (RuntimeException error) {
            Log.w(TAG, "完整图片校验失败: " + imageFile.getPath(), error);
            return false;
        } catch (OutOfMemoryError error) {
            Log.e(TAG, "完整图片校验资源不足: " + imageFile.getPath(), error);
            throw error;
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    private static boolean isNonEmptyFile(File imageFile) {
        return imageFile != null && imageFile.isFile() && imageFile.length() > 0L;
    }
}
