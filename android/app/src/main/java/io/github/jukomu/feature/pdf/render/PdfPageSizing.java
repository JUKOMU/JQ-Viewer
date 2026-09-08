package io.github.jukomu.feature.pdf.render;

/**
 * 计算 PdfRenderer 的目标尺寸，并将单页 Bitmap 限制在固定像素预算内。
 * 所有中间像素计算使用 long，避免异常页面尺寸导致整数溢出。
 */
public final class PdfPageSizing {
    public static final long MAX_RENDER_PIXELS = 8_000_000L;

    private PdfPageSizing() {
    }

    public static Size calculate(int targetWidth, long sourceWidth, long sourceHeight) {
        if (sourceWidth <= 0L || sourceHeight <= 0L) {
            throw new IllegalArgumentException("PDF 页面尺寸无效");
        }

        long width = PdfPageResourceId.normalizeTargetWidth(targetWidth);
        long height = roundedHeight(width, sourceWidth, sourceHeight);
        long pixels = multiplySaturated(width, height);
        if (pixels <= MAX_RENDER_PIXELS) {
            return new Size((int) width, (int) height, pixels);
        }

        double scale = Math.sqrt((double) MAX_RENDER_PIXELS / (double) pixels);
        width = Math.max(1L, (long) Math.floor(width * scale));
        height = Math.max(1L, (long) Math.floor(height * scale));
        // 浮点缩放和取整后最多需要两次按除法收紧，不能逐像素递减超大尺寸。
        if (height > MAX_RENDER_PIXELS / width) {
            height = Math.max(1L, MAX_RENDER_PIXELS / width);
        }
        if (width > MAX_RENDER_PIXELS / height) {
            width = Math.max(1L, MAX_RENDER_PIXELS / height);
        }
        return new Size((int) width, (int) height, multiplySaturated(width, height));
    }

    private static long roundedHeight(long width, long sourceWidth, long sourceHeight) {
        double value = ((double) width * (double) sourceHeight) / (double) sourceWidth;
        if (value >= Long.MAX_VALUE) return Long.MAX_VALUE;
        return Math.max(1L, Math.round(value));
    }

    private static long multiplySaturated(long left, long right) {
        if (left <= 0L || right <= 0L) return 0L;
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE;
        return left * right;
    }

    public static final class Size {
        public final int width;
        public final int height;
        public final long pixels;

        private Size(int width, int height, long pixels) {
            this.width = width;
            this.height = height;
            this.pixels = pixels;
        }
    }
}
