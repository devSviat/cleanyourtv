package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

/**
 * The wallpaper behind the dock band, blurred (config.xml dock_blur). The picture is shrunk to a tiny copy and
 * box-blurred once per wallpaper change; the band draws that copy scaled back up, so nothing is blurred per frame.
 */
final class DockBackdrop {
    /** The tiny copy is the picture divided by this; scaling it back up softens it further. */
    static final int SCALE = 12;
    private static final int RADIUS = 2;
    private static final int PASSES = 3;

    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final int[] sourceAt = new int[2];
    private final int[] targetAt = new int[2];

    private View source;
    private Bitmap blurred;
    private int pictureWidth, pictureHeight;
    private Runnable onChange;

    void setSource(View wallpaperView) {
        source = wallpaperView;
    }

    void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    /** Main thread: the blurred copy of the picture now on screen, and that picture's size. */
    void set(Bitmap blurredCopy, int width, int height) {
        blurred = blurredCopy;
        pictureWidth = width;
        pictureHeight = height;
        paint.setShader(blurredCopy == null ? null
                : new BitmapShader(blurredCopy, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        if (onChange != null) onChange.run();
    }

    /**
     * Lines the blurred copy up with the wallpaper as the wallpaper's centre-crop shows it, in target's coordinates.
     * @return false while there is nothing to draw.
     */
    boolean place(View target) {
        if (blurred == null || source == null || source.getWidth() == 0) return false;
        float viewWidth = source.getWidth(), viewHeight = source.getHeight();
        float cover = Math.max(viewWidth / pictureWidth, viewHeight / pictureHeight);
        source.getLocationInWindow(sourceAt);
        target.getLocationInWindow(targetAt);
        matrix.setScale(cover * pictureWidth / blurred.getWidth(), cover * pictureHeight / blurred.getHeight());
        matrix.postTranslate((viewWidth - pictureWidth * cover) / 2f + sourceAt[0] - targetAt[0],
                (viewHeight - pictureHeight * cover) / 2f + sourceAt[1] - targetAt[1]);
        paint.getShader().setLocalMatrix(matrix);
        return true;
    }

    /** Any thread: a small blurred ARGB copy of a software bitmap, or null if it cannot be made. */
    static Bitmap blur(Bitmap picture) {
        try {
            int width = Math.max(1, picture.getWidth() / SCALE), height = Math.max(1, picture.getHeight() / SCALE);
            Bitmap small = Bitmap.createScaledBitmap(picture, width, height, true);
            int[] pixels = new int[width * height];
            small.getPixels(pixels, 0, width, 0, 0, width, height);
            if (small != picture) small.recycle();
            int[] scratch = new int[pixels.length];
            for (int pass = 0; pass < PASSES; pass++) {
                boxBlur(pixels, scratch, width, height, true);
                boxBlur(scratch, pixels, width, height, false);
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }

    /** One box-blur pass along rows (horizontal) or columns, clamping at the edges, from in to out. */
    static void boxBlur(int[] in, int[] out, int width, int height, boolean horizontal) {
        int lines = horizontal ? height : width;
        int length = horizontal ? width : height;
        int window = RADIUS * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int r = 0, g = 0, b = 0;
            for (int k = -RADIUS; k <= RADIUS; k++) {
                int c = in[index(line, clamp(k, length), width, horizontal)];
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
            }
            for (int i = 0; i < length; i++) {
                out[index(line, i, width, horizontal)] = 0xFF000000 | (r / window) << 16 | (g / window) << 8 | (b / window);
                int leaving = in[index(line, clamp(i - RADIUS, length), width, horizontal)];
                int entering = in[index(line, clamp(i + RADIUS + 1, length), width, horizontal)];
                r += ((entering >> 16) & 0xFF) - ((leaving >> 16) & 0xFF);
                g += ((entering >> 8) & 0xFF) - ((leaving >> 8) & 0xFF);
                b += (entering & 0xFF) - (leaving & 0xFF);
            }
        }
    }

    private static int index(int line, int i, int width, boolean horizontal) {
        return horizontal ? line * width + i : i * width + line;
    }

    private static int clamp(int i, int length) {
        return i < 0 ? 0 : Math.min(i, length - 1);
    }
}
