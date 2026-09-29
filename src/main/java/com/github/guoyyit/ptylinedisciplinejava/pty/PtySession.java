package com.github.guoyyit.ptylinedisciplinejava.pty;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public final class PtySession {

    private static final long NON_BLOCKING = -1L;

    private final String id;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition inputReadable = lock.newCondition();
    private final Condition inputWritable = lock.newCondition();
    private final Condition outputReadable = lock.newCondition();
    private final Condition outputWritable = lock.newCondition();

    private SessionOptions options;
    private SpecialChars specialChars;
    private LineDiscipline discipline;

    private final ByteArrayBuffer inputQueue = new ByteArrayBuffer();
    private final ByteArrayBuffer outputQueue = new ByteArrayBuffer();

    private ControlParty controlParty;
    private OutputFlowListener flowListener;
    private boolean outputFlowStopped;
    private boolean manuallyStopped;
    private boolean autoStopped;

    private boolean closed;
    private boolean hangupDelivered;
    private Runnable onClosed;

    PtySession(String id, SessionOptions options) {
        this.id = id;
        options.validate();
        this.options = options.copy();
        this.specialChars = this.options.getSpecialChars().copy();
        this.discipline = new LineDiscipline(this.options, new DisciplineSink());
    }

    void setOnClosed(Runnable hook) {
        lock.lock();
        try {
            this.onClosed = hook;
        } finally {
            lock.unlock();
        }
    }

    public String getId() {
        return id;
    }

    public SessionOptions getOptions() {
        lock.lock();
        try {
            return options.copy();
        } finally {
            lock.unlock();
        }
    }

    public LineMode getMode() {
        lock.lock();
        try {
            return options.getMode();
        } finally {
            lock.unlock();
        }
    }

    public void setMode(LineMode mode) {
        lock.lock();
        try {
            ensureOpen();
            if (options.getMode() == mode) {
                return;
            }
            options.setMode(mode);
            discipline.setMode(mode);
            inputWritable.signalAll();
            inputReadable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public SpecialChars getSpecialChars() {
        lock.lock();
        try {
            return specialChars.copy();
        } finally {
            lock.unlock();
        }
    }

    public void setSpecialChars(SpecialChars chars) {
        lock.lock();
        try {
            ensureOpen();
            if (chars == null) {
                throw new IllegalArgumentException("specialChars must not be null");
            }
            specialChars = chars.copy();
            options.setSpecialChars(specialChars);
            discipline.applyOptions(options);
        } finally {
            lock.unlock();
        }
    }

    public void setReadSemantics(int vmin, int vtime) {
        lock.lock();
        try {
            ensureOpen();
            if (vmin < 0 || vmin > 255 || vtime < 0 || vtime > 255) {
                throw new IllegalArgumentException("VMIN/VTIME must be within 0..255");
            }
            options.setVMIN(vmin);
            options.setVTIME(vtime);
            discipline.applyOptions(options);
        } finally {
            lock.unlock();
        }
    }

    public void setControlParty(ControlParty party) {
        lock.lock();
        try {
            this.controlParty = party;
        } finally {
            lock.unlock();
        }
    }

    public void setOutputFlowListener(OutputFlowListener listener) {
        lock.lock();
        try {
            this.flowListener = listener;
        } finally {
            lock.unlock();
        }
    }

    public WriteResult writeInput(byte[] data) {
        return writeInput(data, 0, data.length);
    }

    public WriteResult writeInput(byte[] data, int offset, int length) {
        checkArray(data, offset, length);
        lock.lock();
        try {
            if (closed) {
                return WriteResult.rejected(WriteRejectReason.CLOSED);
            }
            if (length == 0) {
                return WriteResult.accepted(0);
            }
            int accepted = discipline.write(data, offset, length);
            if (accepted > 0) {
                inputReadable.signalAll();
            }
            if (accepted == length || options.getInputOverflowPolicy() == OverflowPolicy.DROP) {
                return WriteResult.accepted(accepted);
            }
            return WriteResult.rejected(WriteRejectReason.BUFFER_FULL);
        } finally {
            lock.unlock();
        }
    }

    public WriteResult writeInput(byte[] data, int offset, int length, long timeout, TimeUnit unit)
            throws InterruptedException {
        checkArray(data, offset, length);
        lock.lockInterruptibly();
        try {
            long remainingNanos = timeoutToNanos(timeout, unit);
            int totalAccepted = 0;
            while (totalAccepted < length && !closed) {
                int accepted = discipline.write(data, offset + totalAccepted, length - totalAccepted);
                if (accepted > 0) {
                    totalAccepted += accepted;
                    inputReadable.signalAll();
                }
                if (totalAccepted >= length
                        || options.getInputOverflowPolicy() == OverflowPolicy.DROP) {
                    break;
                }
                if (remainingNanos == NON_BLOCKING) {
                    return WriteResult.rejected(WriteRejectReason.BUFFER_FULL);
                }
                if (remainingNanos <= 0L) {
                    break;
                }
                remainingNanos = inputWritable.awaitNanos(remainingNanos);
            }
            if (closed && totalAccepted == 0) {
                return WriteResult.rejected(WriteRejectReason.CLOSED);
            }
            return WriteResult.accepted(totalAccepted);
        } finally {
            lock.unlock();
        }
    }

    public int readInput(byte[] buffer, int offset, int length, long timeout, TimeUnit unit)
            throws InterruptedException {
        checkArray(buffer, offset, length);
        lock.lockInterruptibly();
        try {
            long remainingNanos = timeoutToNanos(timeout, unit);
            LineMode mode = options.getMode();
            while (!closed && !inputReadyFor(mode, length)) {
                if (remainingNanos == NON_BLOCKING || remainingNanos <= 0L) {
                    return drainInput(buffer, offset, length);
                }
                long waitNanos = mode == LineMode.RAW
                        ? rawWaitNanos(remainingNanos)
                        : remainingNanos;
                if (waitNanos <= 0L) {
                    return drainInput(buffer, offset, length);
                }
                remainingNanos = inputReadable.awaitNanos(Math.min(waitNanos, remainingNanos));
            }
            return drainInput(buffer, offset, length);
        } finally {
            lock.unlock();
        }
    }

    private boolean inputReadyFor(LineMode mode, int requested) {
        if (inputQueue.size() == 0) {
            return false;
        }
        if (mode == LineMode.CANONICAL) {
            return true;
        }
        int vmin = discipline.getVMIN();
        return vmin == 0 || inputQueue.size() >= Math.min(vmin, requested);
    }

    private long rawWaitNanos(long outerRemainingNanos) {
        int vmin = discipline.getVMIN();
        int vtime = discipline.getVTIME();
        if (vtime == 0) {
            return outerRemainingNanos;
        }
        long interByteNanos = vtime * 100L * 1_000_000L;
        if (vmin == 0) {
            return interByteNanos;
        }
        if (inputQueue.size() >= vmin) {
            return 0L;
        }
        long sinceLastByte = System.nanoTime() - discipline.lastRawByteNanos();
        return Math.max(0L, interByteNanos - sinceLastByte);
    }

    private int drainInput(byte[] buffer, int offset, int length) {
        int taken = Math.min(length, inputQueue.size());
        if (taken == 0) {
            return 0;
        }
        byte[] chunk = inputQueue.copyFirst(taken);
        System.arraycopy(chunk, 0, buffer, offset, taken);
        inputQueue.removeFirst(taken);
        inputWritable.signalAll();
        return taken;
    }

    private static long timeoutToNanos(long timeout, TimeUnit unit) {
        if (timeout < 0L) {
            return NON_BLOCKING;
        }
        return unit.toNanos(timeout);
    }

    private static void checkArray(byte[] data, int offset, int length) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IndexOutOfBoundsException(
                    "offset=" + offset + ", length=" + length + ", dataLength=" + data.length);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new SessionClosedException(id);
        }
    }

    public WriteResult writeOutput(byte[] data, int offset, int length) {
        checkArray(data, offset, length);
        lock.lock();
        try {
            if (closed) {
                return WriteResult.rejected(WriteRejectReason.CLOSED);
            }
            if (outputFlowStopped) {
                return WriteResult.rejected(WriteRejectReason.FLOW_STOPPED);
            }
            int accepted = appendOutput(data, offset, length);
            if (accepted < length && options.getOutputOverflowPolicy() == OverflowPolicy.BLOCK) {
                return WriteResult.rejected(WriteRejectReason.BUFFER_FULL);
            }
            return WriteResult.accepted(accepted);
        } finally {
            lock.unlock();
        }
    }

    public WriteResult writeOutput(byte[] data, int offset, int length, long timeout, TimeUnit unit)
            throws InterruptedException {
        checkArray(data, offset, length);
        lock.lockInterruptibly();
        try {
            long remainingNanos = timeoutToNanos(timeout, unit);
            int totalAccepted = 0;
            while (totalAccepted < length && !closed) {
                if (outputFlowStopped) {
                    if (totalAccepted > 0) {
                        return WriteResult.accepted(totalAccepted);
                    }
                    if (remainingNanos == NON_BLOCKING || remainingNanos <= 0L) {
                        return WriteResult.rejected(WriteRejectReason.FLOW_STOPPED);
                    }
                    remainingNanos = outputWritable.awaitNanos(remainingNanos);
                    continue;
                }
                int accepted = appendOutput(data, offset + totalAccepted, length - totalAccepted);
                totalAccepted += accepted;
                if (totalAccepted < length) {
                    if (options.getOutputOverflowPolicy() == OverflowPolicy.DROP) {
                        break;
                    }
                    if (remainingNanos == NON_BLOCKING) {
                        return WriteResult.rejected(WriteRejectReason.BUFFER_FULL);
                    }
                    if (remainingNanos <= 0L) {
                        break;
                    }
                    remainingNanos = outputWritable.awaitNanos(remainingNanos);
                }
            }
            if (closed && totalAccepted == 0) {
                return WriteResult.rejected(WriteRejectReason.CLOSED);
            }
            return WriteResult.accepted(totalAccepted);
        } finally {
            lock.unlock();
        }
    }

    private int appendOutput(byte[] data, int offset, int length) {
        int room = options.getOutputCapacity() - outputQueue.size();
        int accepted = Math.min(length, Math.max(room, 0));
        if (accepted > 0) {
            outputQueue.append(data, offset, accepted);
            outputReadable.signalAll();
            updateFlowAfterWrite();
        }
        return accepted;
    }

    public int readOutput(byte[] buffer, int offset, int length, long timeout, TimeUnit unit)
            throws InterruptedException {
        checkArray(buffer, offset, length);
        lock.lockInterruptibly();
        try {
            long remainingNanos = timeoutToNanos(timeout, unit);
            while (!closed && outputQueue.isEmpty()) {
                if (remainingNanos == NON_BLOCKING || remainingNanos <= 0L) {
                    return 0;
                }
                remainingNanos = outputReadable.awaitNanos(remainingNanos);
            }
            int taken = Math.min(length, outputQueue.size());
            if (taken == 0) {
                return 0;
            }
            byte[] chunk = outputQueue.copyFirst(taken);
            System.arraycopy(chunk, 0, buffer, offset, taken);
            outputQueue.removeFirst(taken);
            outputWritable.signalAll();
            updateFlowAfterRead();
            return taken;
        } finally {
            lock.unlock();
        }
    }

    private void updateFlowAfterWrite() {
        if (!autoStopped && outputQueue.size() >= options.getHighWaterMark()) {
            autoStopped = true;
            if (!outputFlowStopped) {
                outputFlowStopped = true;
                notifyFlow(FlowState.STOPPED);
            }
        }
    }

    private void updateFlowAfterRead() {
        if (autoStopped
                && outputQueue.size() <= options.getLowWaterMark()) {
            autoStopped = false;
            if (outputFlowStopped && !manuallyStopped) {
                outputFlowStopped = false;
                outputWritable.signalAll();
                notifyFlow(FlowState.RUNNING);
            }
        }
    }

    private void notifyFlow(FlowState state) {
        OutputFlowListener listener = flowListener;
        if (listener != null) {
            listener.onFlowStateChange(state, outputQueue.size());
        }
    }

    private void setFlowStopped(boolean stopped, boolean manual) {
        lock.lock();
        try {
            boolean previous = outputFlowStopped;
            if (manual) {
                manuallyStopped = stopped;
            }
            boolean next = manuallyStopped || autoStopped;
            if (next == previous) {
                return;
            }
            outputFlowStopped = next;
            if (!next) {
                outputWritable.signalAll();
            }
            notifyFlow(next ? FlowState.STOPPED : FlowState.RUNNING);
        } finally {
            lock.unlock();
        }
    }

    public FlowState getFlowState() {
        lock.lock();
        try {
            return outputFlowStopped ? FlowState.STOPPED : FlowState.RUNNING;
        } finally {
            lock.unlock();
        }
    }

    public int inputQueueSize() {
        lock.lock();
        try {
            return inputQueue.size();
        } finally {
            lock.unlock();
        }
    }

    public int outputQueueSize() {
        lock.lock();
        try {
            return outputQueue.size();
        } finally {
            lock.unlock();
        }
    }

    public int canonicalBufferSize() {
        lock.lock();
        try {
            return discipline.canonicalBufferSize();
        } finally {
            lock.unlock();
        }
    }

    public boolean isClosed() {
        lock.lock();
        try {
            return closed;
        } finally {
            lock.unlock();
        }
    }

    public void close() {
        Runnable closedHook = null;
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            ControlParty party = controlParty;
            if (party != null && !hangupDelivered) {
                party.signal(SignalType.HANGUP, id);
                hangupDelivered = true;
            }
            inputQueue.clear();
            outputQueue.clear();
            discipline.reset();
            controlParty = null;
            flowListener = null;
            inputReadable.signalAll();
            inputWritable.signalAll();
            outputReadable.signalAll();
            outputWritable.signalAll();
            closedHook = onClosed;
            onClosed = null;
        } finally {
            lock.unlock();
        }
        if (closedHook != null) {
            closedHook.run();
        }
    }

    private void raiseSignal(SignalType type) {
        ControlParty party;
        lock.lock();
        try {
            party = controlParty;
        } finally {
            lock.unlock();
        }
        if (party != null) {
            party.signal(type, id);
        }
    }

    private final class DisciplineSink implements LineDiscipline.Sink {
        @Override
        public int enqueueInput(byte[] data, int offset, int length) {
            int room = options.getInputCapacity() - inputQueue.size();
            int accepted = Math.min(length, Math.max(room, 0));
            if (accepted > 0) {
                inputQueue.append(data, offset, accepted);
                inputReadable.signalAll();
            }
            return accepted;
        }

        @Override
        public void raiseSignal(SignalType type) {
            PtySession.this.raiseSignal(type);
        }

        @Override
        public void setOutputFlowStopped(boolean stopped) {
            PtySession.this.setFlowStopped(stopped, true);
        }
    }
}
