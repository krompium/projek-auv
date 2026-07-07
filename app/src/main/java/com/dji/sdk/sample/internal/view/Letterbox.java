package com.dji.sdk.sample.internal.view;

/**
 * Pure-math letterbox transform between a source frame of arbitrary aspect
 * ratio and the model's square input (aspect-preserving resize + padding).
 *
 * <p>Why: {@code getBitmap(640, 640)} squashed the 16:9 feed into a square,
 * but YOLOv8 is trained on aspect-preserved letterboxed images — squashed
 * input is a systematic accuracy loss and distorts every box (R2). This
 * class computes the forward mapping (how the source is placed inside the
 * square) and the inverse mapping (model-output box → normalized source
 * coordinates).</p>
 *
 * <p>No Android dependencies: unit-testable on the plain JVM. The bitmap
 * drawing itself happens in {@link YoloDetector} using these parameters.</p>
 */
public final class Letterbox {

    /** Model input edge (square), in pixels. */
    public final int dstSize;
    /** Source frame size in pixels. */
    public final int srcWidth, srcHeight;
    /** Uniform scale applied to the source. */
    public final float scale;
    /** Scaled content size inside the square, in pixels. */
    public final int contentWidth, contentHeight;
    /** Padding offsets (left/top), in pixels, centering the content. */
    public final int padX, padY;

    private Letterbox(int srcWidth, int srcHeight, int dstSize) {
        this.srcWidth = srcWidth;
        this.srcHeight = srcHeight;
        this.dstSize = dstSize;
        this.scale = Math.min((float) dstSize / srcWidth, (float) dstSize / srcHeight);
        this.contentWidth = Math.round(srcWidth * scale);
        this.contentHeight = Math.round(srcHeight * scale);
        this.padX = (dstSize - contentWidth) / 2;
        this.padY = (dstSize - contentHeight) / 2;
    }

    public static Letterbox compute(int srcWidth, int srcHeight, int dstSize) {
        if (srcWidth <= 0 || srcHeight <= 0 || dstSize <= 0) {
            throw new IllegalArgumentException(
                    "invalid sizes: " + srcWidth + "x" + srcHeight + " -> " + dstSize);
        }
        return new Letterbox(srcWidth, srcHeight, dstSize);
    }

    /** True when the transform is the identity (square source, no padding). */
    public boolean isIdentity() {
        return padX == 0 && padY == 0 && contentWidth == dstSize && contentHeight == dstSize;
    }

    /**
     * Maps a box normalized to the letterboxed model input ([0,1] over the
     * square) back to coordinates normalized to the source frame ([0,1] over
     * the original image), clamped to [0,1].
     *
     * @param box {left, top, right, bottom} in model-input normalized coords;
     *            modified in place and also returned.
     */
    public float[] inverseMapNormalized(float[] box) {
        box[0] = clamp01(((box[0] * dstSize) - padX) / (srcWidth * scale));
        box[1] = clamp01(((box[1] * dstSize) - padY) / (srcHeight * scale));
        box[2] = clamp01(((box[2] * dstSize) - padX) / (srcWidth * scale));
        box[3] = clamp01(((box[3] * dstSize) - padY) / (srcHeight * scale));
        return box;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    @Override
    public String toString() {
        return "Letterbox{" + srcWidth + "x" + srcHeight + "->" + dstSize
                + " scale=" + scale + " pad=(" + padX + "," + padY + ")}";
    }
}
