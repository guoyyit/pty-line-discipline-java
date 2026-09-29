package com.github.guoyyit.ptylinedisciplinejava.pty;

public final class SpecialChars {

    public static final int DISABLED = -1;

    private int erase = 0x7F;
    private int kill = 0x15;
    private int eof = 0x04;
    private int interrupt = 0x03;
    private int quit = 0x1C;
    private int stop = 0x13;
    private int start = 0x11;
    private int lineEnd = '\n';

    public SpecialChars() {
    }

    public int getErase() {
        return erase;
    }

    public void setErase(int erase) {
        this.erase = validate(erase);
    }

    public int getKill() {
        return kill;
    }

    public void setKill(int kill) {
        this.kill = validate(kill);
    }

    public int getEof() {
        return eof;
    }

    public void setEof(int eof) {
        this.eof = validate(eof);
    }

    public int getInterrupt() {
        return interrupt;
    }

    public void setInterrupt(int interrupt) {
        this.interrupt = validate(interrupt);
    }

    public int getQuit() {
        return quit;
    }

    public void setQuit(int quit) {
        this.quit = validate(quit);
    }

    public int getStop() {
        return stop;
    }

    public void setStop(int stop) {
        this.stop = validate(stop);
    }

    public int getStart() {
        return start;
    }

    public void setStart(int start) {
        this.start = validate(start);
    }

    public int getLineEnd() {
        return lineEnd;
    }

    public void setLineEnd(int lineEnd) {
        this.lineEnd = validate(lineEnd);
    }

    private static int validate(int value) {
        if (value != DISABLED && (value < 0 || value > 255)) {
            throw new IllegalArgumentException("control character must be 0..255 or DISABLED(-1): " + value);
        }
        return value;
    }

    public SpecialChars copy() {
        SpecialChars copy = new SpecialChars();
        copy.erase = erase;
        copy.kill = kill;
        copy.eof = eof;
        copy.interrupt = interrupt;
        copy.quit = quit;
        copy.stop = stop;
        copy.start = start;
        copy.lineEnd = lineEnd;
        return copy;
    }
}
