package com.dji.sdk.sample.internal.util;

/**
 * Pure-math int8 quantization helpers for TFLite I/O. JVM-testable.
 *
 * <p>Latent bug fixed here (R4): the int8 input path wrote
 * {@code (byte)(r + inputZeroPoint)} — adding the zero-point to a raw 0..255
 * channel value. The correct affine quantization is
 * {@code round((r/255) / scale) + zeroPoint}, clamped to the int8 range.
 * The bug was dormant because the active model is float16, but it breaks any
 * switch to the int8 asset.</p>
 */
public final class QuantUtil {

    private QuantUtil() {
    }

    /**
     * Quantizes one 8-bit color channel (0..255) into an int8 model input
     * value using the model's input quantization parameters.
     */
    public static byte quantizeChannel(int channel, float inputScale, int inputZeroPoint) {
        float normalized = channel / 255.0f;             // training-time [0,1]
        int q = Math.round(normalized / inputScale) + inputZeroPoint;
        return (byte) Math.max(-128, Math.min(127, q));
    }

    /** Dequantizes a raw int8 model output value back to float. */
    public static float dequantize(byte raw, int zeroPoint, float scale) {
        return (raw - zeroPoint) * scale;
    }
}
