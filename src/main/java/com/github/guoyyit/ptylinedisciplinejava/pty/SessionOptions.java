package com.github.guoyyit.ptylinedisciplinejava.pty;

public final class SessionOptions {

    public static final int DEFAULT_CAPACITY = 4096;
    public static final int DEFAULT_HIGH_WATER_MARK = 3072;
    public static final int DEFAULT_LOW_WATER_MARK = 1024;

    private LineMode mode = LineMode.CANONICAL;
    private int inputCapacity = DEFAULT_CAPACITY;
    private int outputCapacity = DEFAULT_CAPACITY;
    private int highWaterMark = DEFAULT_HIGH_WATER_MARK;
    private int lowWaterMark = DEFAULT_LOW_WATER_MARK;
    private OverflowPolicy inputOverflowPolicy = OverflowPolicy.BLOCK;
    private OverflowPolicy outputOverflowPolicy = OverflowPolicy.BLOCK;
    private int vmin = 1;
    private int vtime = 0;
    private SpecialChars specialChars = new SpecialChars();

    public SessionOptions() {
    }

    public LineMode getMode() {
        return mode;
    }

    public void setMode(LineMode mode) {
        this.mode = requireNonNull(mode, "mode");
    }

    public int getInputCapacity() {
        return inputCapacity;
    }

    public void setInputCapacity(int inputCapacity) {
        this.inputCapacity = inputCapacity;
    }

    public int getOutputCapacity() {
        return outputCapacity;
    }

    public void setOutputCapacity(int outputCapacity) {
        this.outputCapacity = outputCapacity;
    }

    public int getHighWaterMark() {
        return highWaterMark;
    }

    public void setHighWaterMark(int highWaterMark) {
        this.highWaterMark = highWaterMark;
    }

    public int getLowWaterMark() {
        return lowWaterMark;
    }

    public void setLowWaterMark(int lowWaterMark) {
        this.lowWaterMark = lowWaterMark;
    }

    public OverflowPolicy getInputOverflowPolicy() {
        return inputOverflowPolicy;
    }

    public void setInputOverflowPolicy(OverflowPolicy inputOverflowPolicy) {
        this.inputOverflowPolicy = requireNonNull(inputOverflowPolicy, "inputOverflowPolicy");
    }

    public OverflowPolicy getOutputOverflowPolicy() {
        return outputOverflowPolicy;
    }

    public void setOutputOverflowPolicy(OverflowPolicy policy) {
        this.outputOverflowPolicy = requireNonNull(policy, "outputOverflowPolicy");
    }

    public int getVMIN() {
        return vmin;
    }

    public void setVMIN(int vmin) {
        this.vmin = vmin;
    }

    public int getVTIME() {
        return vtime;
    }

    public void setVTIME(int vtime) {
        this.vtime = vtime;
    }

    public SpecialChars getSpecialChars() {
        return specialChars;
    }

    public void setSpecialChars(SpecialChars specialChars) {
        this.specialChars = requireNonNull(specialChars, "specialChars");
    }

    public void validate() {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        if (inputCapacity <= 0 || outputCapacity <= 0) {
            throw new IllegalArgumentException("capacities must be positive");
        }
        if (lowWaterMark < 0 || lowWaterMark >= highWaterMark) {
            throw new IllegalArgumentException("require 0 <= lowWaterMark < highWaterMark");
        }
        if (highWaterMark > outputCapacity) {
            throw new IllegalArgumentException("highWaterMark must not exceed outputCapacity");
        }
        if (inputOverflowPolicy == null || outputOverflowPolicy == null) {
            throw new IllegalArgumentException("overflow policies must not be null");
        }
        if (vmin < 0 || vmin > 255 || vtime < 0 || vtime > 255) {
            throw new IllegalArgumentException("VMIN/VTIME must be within 0..255");
        }
        if (specialChars == null) {
            throw new IllegalArgumentException("specialChars must not be null");
        }
    }

    public SessionOptions copy() {
        SessionOptions copy = new SessionOptions();
        copy.mode = mode;
        copy.inputCapacity = inputCapacity;
        copy.outputCapacity = outputCapacity;
        copy.highWaterMark = highWaterMark;
        copy.lowWaterMark = lowWaterMark;
        copy.inputOverflowPolicy = inputOverflowPolicy;
        copy.outputOverflowPolicy = outputOverflowPolicy;
        copy.vmin = vmin;
        copy.vtime = vtime;
        copy.specialChars = specialChars.copy();
        return copy;
    }

    private static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
