package com.github.guoyyit.ptylinedisciplinejava.pty;

/**
 * Full configuration of a PTY session.
 *
 * @param mode                 initial line discipline mode
 * @param controlCharacters    special character mapping
 * @param input                input (master -> slave) buffer limits
 * @param output               output (slave -> master) buffer limits
 * @param highWatermark        output fill level that triggers STOP to writers
 * @param lowWatermark         output fill level that triggers START to writers
 * @param vmin                 raw mode: minimum bytes for an un-timed read (0..255)
 * @param vtimeMillis          raw mode: timeout in milliseconds (0 = no timeout)
 * @param signalQueueCapacity  per-control-party signal queue capacity
 * @param signalDeliveryPolicy signal delivery policy for new control parties
 */
public record SessionConfig(LineDisciplineMode mode,
                            ControlCharacters controlCharacters,
                            BufferLimits input,
                            BufferLimits output,
                            int highWatermark,
                            int lowWatermark,
                            int vmin,
                            int vtimeMillis,
                            int signalQueueCapacity,
                            SignalDeliveryPolicy signalDeliveryPolicy) {

    public static SessionConfig defaults() {
        return new SessionConfig(
                LineDisciplineMode.CANONICAL,
                ControlCharacters.defaults(),
                BufferLimits.inputDefaults(),
                BufferLimits.outputDefaults(),
                Defaults.HIGH_WATERMARK,
                Defaults.LOW_WATERMARK,
                1,
                0,
                64,
                SignalDeliveryPolicy.QUEUE);
    }

    public SessionConfig withMode(LineDisciplineMode newMode) {
        if (newMode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        return new SessionConfig(newMode, controlCharacters, input, output,
                highWatermark, lowWatermark, vmin, vtimeMillis,
                signalQueueCapacity, signalDeliveryPolicy);
    }

    public SessionConfig withControlCharacters(ControlCharacters newCharacters) {
        if (newCharacters == null) {
            throw new IllegalArgumentException("controlCharacters must not be null");
        }
        return new SessionConfig(mode, newCharacters, input, output,
                highWatermark, lowWatermark, vmin, vtimeMillis,
                signalQueueCapacity, signalDeliveryPolicy);
    }

    public SessionConfig withVminVtime(int newVmin, int newVtimeMillis) {
        checkVminVtime(newVmin, newVtimeMillis);
        return new SessionConfig(mode, controlCharacters, input, output,
                highWatermark, lowWatermark, newVmin, newVtimeMillis,
                signalQueueCapacity, signalDeliveryPolicy);
    }

    public SessionConfig {
        if (mode == null || controlCharacters == null
                || input == null || output == null
                || signalDeliveryPolicy == null) {
            throw new IllegalArgumentException("required config components must not be null");
        }
        checkWatermarks(lowWatermark, highWatermark, output.capacity());
        checkVminVtime(vmin, vtimeMillis);
        if (signalQueueCapacity <= 0) {
            throw new IllegalArgumentException(
                    "signalQueueCapacity must be positive: " + signalQueueCapacity);
        }
    }

    private static void checkWatermarks(int low, int high, int capacity) {
        if (low < 0 || high < 0) {
            throw new IllegalArgumentException("watermarks must not be negative");
        }
        if (high > capacity) {
            throw new IllegalArgumentException(
                    "highWatermark (" + high + ") must not exceed output capacity ("
                            + capacity + ")");
        }
        if (high < low) {
            throw new IllegalArgumentException(
                    "highWatermark (" + high + ") must be >= lowWatermark (" + low + ")");
        }
    }

    private static void checkVminVtime(int vmin, int vtimeMillis) {
        if (vmin < 0 || vmin > 255) {
            throw new IllegalArgumentException("vmin must be in 0..255: " + vmin);
        }
        if (vtimeMillis < 0) {
            throw new IllegalArgumentException(
                    "vtimeMillis must not be negative: " + vtimeMillis);
        }
    }
}
