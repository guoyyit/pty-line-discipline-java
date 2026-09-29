package com.github.guoyyit.ptylinedisciplinejava.pty;

final class LineDiscipline {

    interface Sink {
        int enqueueInput(byte[] data, int offset, int length);

        void raiseSignal(SignalType type);

        void setOutputFlowStopped(boolean stopped);
    }

    private final Sink sink;

    private LineMode mode;
    private SpecialChars chars;
    private int inputCapacity;
    private int vmin;
    private int vtime;

    private final ByteArrayBuffer canonicalBuffer = new ByteArrayBuffer();
    private volatile long lastRawByteNanos = System.nanoTime();

    LineDiscipline(SessionOptions options, Sink sink) {
        this.sink = sink;
        applyOptions(options);
    }

    synchronized int write(byte[] data, int offset, int length) {
        int accepted = 0;
        int runStart = -1;
        for (int index = 0; index < length; index++) {
            int value = data[offset + index] & 0xFF;
            boolean control = mode == LineMode.CANONICAL
                    ? isCanonicalControlByte(value)
                    : isRawControlByte(value);
            if (runStart >= 0 && control) {
                accepted += emitRun(data, offset + runStart, index - runStart);
                runStart = -1;
            }
            boolean consumed = mode == LineMode.CANONICAL
                    ? handleCanonicalByte(value)
                    : handleRawControlByte(value);
            if (consumed) {
                accepted++;
            } else if (runStart < 0) {
                runStart = index;
            }
        }
        if (runStart >= 0) {
            accepted += emitRun(data, offset + runStart, length - runStart);
        }
        return accepted;
    }

    private boolean isCanonicalControlByte(int value) {
        return value == chars.getInterrupt()
                || value == chars.getQuit()
                || value == chars.getStop()
                || value == chars.getStart()
                || value == chars.getErase()
                || value == chars.getKill()
                || value == chars.getEof()
                || value == chars.getLineEnd();
    }

    private boolean isRawControlByte(int value) {
        return value == chars.getInterrupt()
                || value == chars.getQuit()
                || value == chars.getStop()
                || value == chars.getStart();
    }

    private boolean handleCanonicalByte(int value) {
        if (value == chars.getInterrupt()) {
            flushCanonicalLine();
            sink.raiseSignal(SignalType.INTERRUPT);
            return true;
        }
        if (value == chars.getQuit()) {
            flushCanonicalLine();
            sink.raiseSignal(SignalType.QUIT);
            return true;
        }
        if (value == chars.getStop()) {
            sink.setOutputFlowStopped(true);
            return true;
        }
        if (value == chars.getStart()) {
            sink.setOutputFlowStopped(false);
            return true;
        }
        if (value == chars.getErase()) {
            if (!canonicalBuffer.isEmpty()) {
                canonicalBuffer.removeLast();
            }
            return true;
        }
        if (value == chars.getKill()) {
            canonicalBuffer.clear();
            return true;
        }
        if (value == chars.getEof()) {
            flushCanonicalLine();
            return true;
        }
        if (value == chars.getLineEnd()) {
            if (canonicalBuffer.size() < inputCapacity) {
                canonicalBuffer.append((byte) value);
            }
            flushCanonicalLine();
            return true;
        }
        return false;
    }

    private boolean handleRawControlByte(int value) {
        if (value == chars.getInterrupt()) {
            sink.raiseSignal(SignalType.INTERRUPT);
            return true;
        }
        if (value == chars.getQuit()) {
            sink.raiseSignal(SignalType.QUIT);
            return true;
        }
        if (value == chars.getStop()) {
            sink.setOutputFlowStopped(true);
            return true;
        }
        if (value == chars.getStart()) {
            sink.setOutputFlowStopped(false);
            return true;
        }
        lastRawByteNanos = System.nanoTime();
        return false;
    }

    private int emitRun(byte[] data, int offset, int length) {
        if (mode == LineMode.CANONICAL) {
            int room = inputCapacity - canonicalBuffer.size();
            int accepted = Math.min(length, Math.max(room, 0));
            if (accepted > 0) {
                canonicalBuffer.append(data, offset, accepted);
            }
            return accepted;
        } else {
            return sink.enqueueInput(data, offset, length);
        }
    }

    private void flushCanonicalLine() {
        if (!canonicalBuffer.isEmpty()) {
            sink.enqueueInput(canonicalBuffer.toArray(), 0, canonicalBuffer.size());
            canonicalBuffer.clear();
        }
    }

    synchronized void applyOptions(SessionOptions options) {
        this.mode = options.getMode();
        this.chars = options.getSpecialChars().copy();
        this.inputCapacity = options.getInputCapacity();
        this.vmin = options.getVMIN();
        this.vtime = options.getVTIME();
        if (canonicalBuffer.size() > inputCapacity) {
            canonicalBuffer.setSize(inputCapacity);
        }
    }

    synchronized void setMode(LineMode mode) {
        if (this.mode == mode) {
            return;
        }
        this.mode = mode;
        canonicalBuffer.clear();
        lastRawByteNanos = System.nanoTime();
    }

    synchronized void reset() {
        canonicalBuffer.clear();
    }

    synchronized int canonicalBufferSize() {
        return canonicalBuffer.size();
    }

    synchronized int getVMIN() {
        return vmin;
    }

    synchronized int getVTIME() {
        return vtime;
    }

    synchronized long lastRawByteNanos() {
        return lastRawByteNanos;
    }
}
