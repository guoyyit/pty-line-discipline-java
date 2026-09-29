package com.github.guoyyit.ptylinedisciplinejava.pty;

/**
 * Default configuration constants (termios-like values).
 */
public final class Defaults {

    private Defaults() {
    }

    /** Value indicating that a special character is disabled. */
    public static final int DISABLED = -1;

    public static final int VINTR = 0x03;   // ^C
    public static final int VQUIT = 0x1c;   // ^\
    public static final int VERASE = 0x7f;  // DEL
    public static final int VKILL = 0x15;   // ^U
    public static final int VEOF = 0x04;    // ^D
    public static final int VSTART = 0x11;  // ^Q
    public static final int VSTOP = 0x13;   // ^S

    public static final int INPUT_CAPACITY = 4096;
    public static final int OUTPUT_CAPACITY = 4096;
    public static final int HIGH_WATERMARK = 3072;
    public static final int LOW_WATERMARK = 1024;
}
