package com.github.guoyyit.ptylinedisciplinejava.pty;

/**
 * Configurable special characters. Use {@link Defaults#DISABLED} (-1) to disable one.
 *
 * @param erase  VERASE - erase the previous character of the pending line
 * @param kill   VKILL  - erase the entire pending line
 * @param eof    VEOF   - end of file / flush the pending line
 * @param intr   VINTR  - raise {@link PtySignal#INTERRUPT}
 * @param quit   VQUIT  - raise {@link PtySignal#QUIT}
 * @param stop   VSTOP  - suspend output delivery
 * @param start  VSTART - resume output delivery
 */
public record ControlCharacters(int erase, int kill, int eof, int intr, int quit,
                                int stop, int start) {

    public static ControlCharacters defaults() {
        return new ControlCharacters(
                Defaults.VERASE, Defaults.VKILL, Defaults.VEOF,
                Defaults.VINTR, Defaults.VQUIT, Defaults.VSTOP, Defaults.VSTART);
    }

    public ControlCharacters withErase(int value) {
        checkSpecial(value);
        return new ControlCharacters(value, kill, eof, intr, quit, stop, start);
    }

    public ControlCharacters withKill(int value) {
        checkSpecial(value);
        return new ControlCharacters(erase, value, eof, intr, quit, stop, start);
    }

    public ControlCharacters withEof(int value) {
        checkSpecial(value);
        return new ControlCharacters(erase, kill, value, intr, quit, stop, start);
    }

    public ControlCharacters withIntr(int value) {
        checkSpecial(value);
        return new ControlCharacters(erase, kill, eof, value, quit, stop, start);
    }

    public ControlCharacters withQuit(int value) {
        checkSpecial(value);
        return new ControlCharacters(erase, kill, eof, intr, value, stop, start);
    }

    public ControlCharacters withStop(int value) {
        checkSpecial(value);
        return new ControlCharacters(erase, kill, eof, intr, quit, value, start);
    }

    public ControlCharacters withStart(int value) {
        checkSpecial(value);
        return new ControlCharacters(erase, kill, eof, intr, quit, stop, value);
    }

    /**
     * @return {@code true} if {@code b} matches one of the enabled special characters
     */
    public boolean matches(int b, int special) {
        return special != Defaults.DISABLED && b == (special & 0xff);
    }

    private static void checkSpecial(int value) {
        if (value != Defaults.DISABLED && (value < 0 || value > 255)) {
            throw new IllegalArgumentException(
                    "special character must be in 0..255 or -1 (disabled): " + value);
        }
    }
}
