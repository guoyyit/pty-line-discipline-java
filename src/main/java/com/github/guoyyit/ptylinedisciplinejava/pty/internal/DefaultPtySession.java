package com.github.guoyyit.ptylinedisciplinejava.pty.internal;

import com.github.guoyyit.ptylinedisciplinejava.pty.ControlCharacters;
import com.github.guoyyit.ptylinedisciplinejava.pty.ControlParty;
import com.github.guoyyit.ptylinedisciplinejava.pty.FlowControlHandle;
import com.github.guoyyit.ptylinedisciplinejava.pty.FlowControlEvent;
import com.github.guoyyit.ptylinedisciplinejava.pty.LineDisciplineMode;
import com.github.guoyyit.ptylinedisciplinejava.pty.PtySignal;
import com.github.guoyyit.ptylinedisciplinejava.pty.PtySession;
import com.github.guoyyit.ptylinedisciplinejava.pty.ReadResult;
import com.github.guoyyit.ptylinedisciplinejava.pty.SessionConfig;
import com.github.guoyyit.ptylinedisciplinejava.pty.SignalDeliveryPolicy;
import com.github.guoyyit.ptylinedisciplinejava.pty.WriteResult;
import com.github.guoyyit.ptylinedisciplinejava.pty.internal.boundedbuffer.BoundedByteBuffer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Default {@link PtySession} implementation.
 */
public final class DefaultPtySession implements PtySession {

    private final Runnable onClose;
    private final String id;
    private final Object lock = new Object();
    private final BoundedByteBuffer inputBuffer;
    private final BoundedByteBuffer outputBuffer;
    private final CanonicalEditBuffer editBuffer = new CanonicalEditBuffer();
    private final Map<String, DefaultControlParty> controlParties = new LinkedHashMap<>();
    private final Map<String, DefaultFlowControlHandle> flowHandles = new LinkedHashMap<>();

    private SessionConfig config;
    private boolean inputEof;
    private boolean pendingLineFlushed;
    private boolean outputSuspended;
    private boolean writerFlowStopped;
    private boolean closed;

    public DefaultPtySession(String id, SessionConfig config) {
        this(id, config, null);
    }

    public DefaultPtySession(String id, SessionConfig config, Runnable onClose) {
        this.id = Objects.requireNonNull(id, "id");
        this.config = Objects.requireNonNull(config, "config");
        this.onClose = onClose;
        this.inputBuffer = new BoundedByteBuffer(
                config.input().capacity(), config.input().overflowPolicy(), lock);
        this.outputBuffer = new BoundedByteBuffer(
                config.output().capacity(), config.output().overflowPolicy(), lock);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public WriteResult writeInput(byte[] data, long timeoutMillis) throws InterruptedException {
        Objects.requireNonNull(data, "data");
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must not be negative");
        }
        synchronized (lock) {
            if (closed) {
                return new WriteResult(0, data.length, false, false, true);
            }
            int consumed;
            int discarded;
            boolean timedOut;
            if (config.mode() == LineDisciplineMode.CANONICAL) {
                CanonicalWriteOutcome outcome =
                        processCanonicalInput(data, timeoutMillis);
                consumed = outcome.consumed();
                int accepted = outcome.accepted();
                discarded = outcome.discarded();
                timedOut = outcome.timedOut();
                return new WriteResult(accepted, discarded, timedOut, false, closed);
            } else {
                boolean blockPolicy = config.input().overflowPolicy()
                        == com.github.guoyyit.ptylinedisciplinejava.pty.OverflowPolicy.BLOCK;
                consumed = inputBuffer.offerHeld(data, timeoutMillis);
                discarded = blockPolicy ? 0 : data.length - consumed;
                timedOut = consumed < data.length
                        && blockPolicy
                        && timeoutMillis > 0
                        && !closed;
            }
            return new WriteResult(consumed, discarded, timedOut, false, closed);
        }
    }

    /**
     * Result of processing one canonical write: how many offered bytes were
     * stored ({@code accepted}), how many were processed in total
     * ({@code consumed}, including edited-away control bytes), how many were
     * refused by the DISCARD policy ({@code discarded}), and whether the BLOCK
     * policy wait elapsed.
     */
    private record CanonicalWriteOutcome(int consumed, int accepted, int discarded,
                                         boolean timedOut) {
    }

    /**
     * Handle one input chunk in canonical mode.
     *
     * @return number of bytes from {@code data} consumed (edited-away bytes count
     *         as consumed since they are processed immediately)
     */
    private CanonicalWriteOutcome processCanonicalInput(byte[] data, long timeoutMillis)
            throws InterruptedException {
        ControlCharacters chars = config.controlCharacters();
        boolean discardPolicy = config.input().overflowPolicy()
                == com.github.guoyyit.ptylinedisciplinejava.pty.OverflowPolicy.DISCARD;
        long deadlineNanos = timeoutMillis == 0
                ? 0L
                : System.nanoTime() + timeoutMillis * 1_000_000L;
        int index = 0;
        int accepted = 0;
        int discarded = 0;
        int admittedInLine = 0;
        boolean lineOverflow = false;
        boolean timedOut = false;
        while (index < data.length && !closed) {
            int b = data[index] & 0xff;
            if (chars.matches(b, chars.intr())) {
                raiseSignal(PtySignal.INTERRUPT);
                index++;
                continue;
            }
            if (chars.matches(b, chars.quit())) {
                raiseSignal(PtySignal.QUIT);
                index++;
                continue;
            }
            if (chars.matches(b, chars.stop())) {
                outputSuspended = true;
                index++;
                continue;
            }
            if (chars.matches(b, chars.start())) {
                outputSuspended = false;
                resumeOutputDelivery();
                index++;
                continue;
            }
            if (chars.matches(b, chars.erase())) {
                editBuffer.eraseOne();
                index++;
                continue;
            }
            if (chars.matches(b, chars.kill())) {
                int reclassified = admitPendingOnKill(admittedInLine, discardPolicy);
                discarded += reclassified;
                accepted -= reclassified;
                admittedInLine = 0;
                lineOverflow = false;
                index++;
                continue;
            }
            if (chars.matches(b, chars.eof())) {
                if (editBuffer.pendingSize() > 0) {
                    flushPartialLine();
                    admittedInLine = 0;
                    lineOverflow = false;
                } else {
                    inputEof = true;
                    lock.notifyAll();
                }
                index++;
                continue;
            }
            if (b == '\n') {
                if (lineOverflow) {
                    // DISCARD: the newline closes a line that was refused whole.
                    lineOverflow = false;
                    admittedInLine = 0;
                    discarded++;
                    index++;
                    continue;
                }
                if (awaitCanonicalSpace(deadlineNanos, timeoutMillis)) {
                    editBuffer.admit((byte) '\n');
                    commitEditLine();
                    accepted++;
                    admittedInLine = 0;
                    index++;
                    continue;
                }
                timedOut = true;
                break;
            }
            if (discardPolicy) {
                if (lineOverflow || editBuffer.isFull(inputBuffer)) {
                    // A canonical line is indivisible: refuse the whole line.
                    discarded += admittedInLine + 1;
                    accepted -= admittedInLine;
                    admittedInLine = 0;
                    lineOverflow = true;
                    editBuffer.discard();
                    index++;
                    continue;
                }
                editBuffer.admit((byte) b);
                accepted++;
                admittedInLine++;
                index++;
                continue;
            }
            if (!awaitCanonicalSpace(deadlineNanos, timeoutMillis)) {
                timedOut = true;
                break;
            }
            editBuffer.admit((byte) b);
            accepted++;
            admittedInLine++;
            index++;
        }
        return new CanonicalWriteOutcome(index, accepted, discarded, timedOut);
    }

    /**
     * VKILL clears the pending line. Under DISCARD the pending bytes admitted
     * by the current write had been accepted provisionally; they are now
     * reclassified as discarded. Under BLOCK the slots are simply released.
     */
    private int admitPendingOnKill(int admittedInLine, boolean discardPolicy) {
        int reclassified = discardPolicy ? admittedInLine : 0;
        editBuffer.discard();
        return reclassified;
    }

    /**
     * Wait until one more pending byte fits (deliverable buffer + pending line
     * must stay within the configured capacity).
     *
     * @return {@code true} when space is available, {@code false} on timeout
     */
    private boolean awaitCanonicalSpace(long deadlineNanos, long timeoutMillis)
            throws InterruptedException {
        while (editBuffer.isFull(inputBuffer) && !closed) {
            if (timeoutMillis == 0) {
                return false;
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return false;
            }
            lock.wait(remainingNanos / 1_000_000L,
                    (int) (remainingNanos % 1_000_000L));
        }
        return !editBuffer.isFull(inputBuffer);
    }

    private void commitEditLine() {
        editBuffer.drainReserved(inputBuffer);
        pendingLineFlushed = false;
        lock.notifyAll();
    }

    private void flushPartialLine() {
        if (editBuffer.pendingSize() > 0) {
            editBuffer.flushInto(inputBuffer);
            pendingLineFlushed = true;
        }
        lock.notifyAll();
    }

    private void raiseSignal(PtySignal signal) {
        for (DefaultControlParty party : new ArrayList<>(controlParties.values())) {
            party.deliver(signal);
        }
    }

    private void resumeOutputDelivery() {
        lock.notifyAll();
    }

    @Override
    public ReadResult readInput(int maxBytes, long timeoutMillis) throws InterruptedException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive: " + maxBytes);
        }
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must not be negative");
        }
        synchronized (lock) {
            if (config.mode() == LineDisciplineMode.CANONICAL) {
                return readCanonical(maxBytes, timeoutMillis);
            }
            return readRaw(maxBytes, timeoutMillis);
        }
    }

    private ReadResult readCanonical(int maxBytes, long timeoutMillis)
            throws InterruptedException {
        long deadline = timeoutMillis > 0
                ? System.nanoTime() + timeoutMillis * 1_000_000L
                : 0L;
        while (!inputBuffer.containsHeld((byte) '\n')
                && !pendingLineFlushed && !inputEof && !closed) {
            if (!awaitInputDeadline(deadline, timeoutMillis)) {
                return new ReadResult(new byte[0], false, false, true);
            }
        }
        if (inputBuffer.containsHeld((byte) '\n')) {
            return new ReadResult(
                    inputBuffer.pollUntilDelimiter((byte) '\n', maxBytes),
                    false, false, false);
        }
        if (!inputBuffer.isEmptyHeld()) {
            pendingLineFlushed = false;
            return new ReadResult(inputBuffer.pollHeld(maxBytes), false, false, false);
        }
        if (closed) {
            return new ReadResult(new byte[0], false, true, false);
        }
        inputEof = false;
        return new ReadResult(new byte[0], true, false, false);
    }

    private ReadResult readRaw(int maxBytes, long timeoutMillis)
            throws InterruptedException {
        int vmin = config.vmin();
        long vtimeNanos = config.vtimeMillis() * 1_000_000L;

        long callerDeadline = timeoutMillis == 0
                ? Long.MAX_VALUE
                : System.nanoTime() + timeoutMillis * 1_000_000L;

        // VMIN=0, VTIME=0: pure poll, never blocks.
        if (vmin == 0 && vtimeNanos == 0) {
            return takeRawAvailable(maxBytes);
        }

        // VMIN=0, VTIME>0: one-shot timer; return whatever arrived.
        if (vmin == 0) {
            long deadline = boundedDeadline(vtimeNanos, callerDeadline);
            if (inputBuffer.isEmptyHeld() && !inputEof && !closed) {
                long waitNanos = deadline - System.nanoTime();
                if (waitNanos > 0) {
                    inputBuffer.awaitCountHeld(1, 0, waitNanos);
                }
            }
            if (inputBuffer.isEmptyHeld()) {
                if (closed) {
                    return new ReadResult(new byte[0], false, true, false);
                }
                if (inputEof) {
                    inputEof = false;
                    return new ReadResult(new byte[0], true, false, false);
                }
                return timeoutResult();
            }
            return takeRawAvailable(maxBytes);
        }

        // VMIN>0: block until VMIN bytes, optionally with inter-byte VTIME.
        long idleDeadline = 0L;
        while (inputBuffer.sizeHeld() < vmin && !inputEof && !closed) {
            int before = inputBuffer.sizeHeld();
            long waitNanos;
            if (vtimeNanos == 0 || before == 0) {
                // No inter-byte timer yet (VTIME=0 or no byte received so far).
                idleDeadline = 0L;
                waitNanos = callerDeadline == Long.MAX_VALUE
                        ? Long.MAX_VALUE / 2
                        : callerDeadline - System.nanoTime();
            } else {
                if (idleDeadline == 0L) {
                    idleDeadline = System.nanoTime() + vtimeNanos;
                }
                waitNanos = idleDeadline - System.nanoTime();
            }
            if (callerDeadline != Long.MAX_VALUE) {
                waitNanos = Math.min(waitNanos, callerDeadline - System.nanoTime());
            }
            if (waitNanos <= 0) {
                boolean callerTimedOut = callerDeadline != Long.MAX_VALUE
                        && (idleDeadline == 0L
                                || callerDeadline <= idleDeadline);
                return timeoutWithRawData(maxBytes, callerTimedOut);
            }

            int after = inputBuffer.awaitCountHeld(
                    vmin, before, Math.max(1L, waitNanos));
            if (after > before && vtimeNanos > 0 && after < vmin) {
                idleDeadline = System.nanoTime() + vtimeNanos;
            }
        }
        return takeRawAvailable(maxBytes);
    }

    private static long boundedDeadline(long waitNanos, long callerDeadline) {
        long absolute = System.nanoTime() + waitNanos;
        return Math.min(absolute, callerDeadline);
    }

    private ReadResult timeoutResult() {
        return new ReadResult(new byte[0], false, false, true);
    }

    /**
     * Return buffered bytes when the caller timeout elapsed before VMIN was met.
     * The result reports both the partial data and the timeout condition.
     */
    private ReadResult timeoutWithRawData(int maxBytes, boolean callerTimedOut) {
        if (!inputBuffer.isEmptyHeld()) {
            return new ReadResult(inputBuffer.pollHeld(maxBytes),
                    false, false, callerTimedOut);
        }
        if (closed) {
            return new ReadResult(new byte[0], false, true, false);
        }
        if (inputEof) {
            inputEof = false;
            return new ReadResult(new byte[0], true, false, false);
        }
        return new ReadResult(new byte[0], false, false, callerTimedOut);
    }

    private ReadResult takeRawAvailable(int maxBytes) {
        if (!inputBuffer.isEmptyHeld()) {
            return new ReadResult(inputBuffer.pollHeld(maxBytes), false, false, false);
        }
        if (closed) {
            return new ReadResult(new byte[0], false, true, false);
        }
        if (inputEof) {
            inputEof = false;
            return new ReadResult(new byte[0], true, false, false);
        }
        return new ReadResult(new byte[0], false, false, false);
    }

    private boolean awaitInputDeadline(long deadlineNanos, long timeoutMillis)
            throws InterruptedException {
        if (timeoutMillis == 0) {
            return false;
        }
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            return false;
        }
        return inputBuffer.awaitDataNanosHeld(remainingNanos) > 0;
    }

    @Override
    public WriteResult writeOutput(byte[] data, long timeoutMillis) throws InterruptedException {
        Objects.requireNonNull(data, "data");
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must not be negative");
        }
        synchronized (lock) {
            if (closed) {
                return new WriteResult(0, data.length, false, false, true);
            }
            boolean blockPolicy = config.output().overflowPolicy()
                    == com.github.guoyyit.ptylinedisciplinejava.pty.OverflowPolicy.BLOCK;
            long deadline = timeoutMillis == 0
                    ? Long.MAX_VALUE
                    : System.nanoTime() + timeoutMillis * 1_000_000L;

            if (writerFlowStopped) {
                if (!blockPolicy) {
                    return new WriteResult(0, data.length, false, true, false);
                }
                while (writerFlowStopped && !closed) {
                    if (deadline != Long.MAX_VALUE) {
                        long waitNanos = deadline - System.nanoTime();
                        if (waitNanos <= 0) {
                            return new WriteResult(0, data.length, true, true, false);
                        }
                        lock.wait(Math.max(1L, waitNanos / 1_000_000L));
                    } else {
                        lock.wait(1000);
                    }
                }
                if (closed) {
                    return new WriteResult(0, data.length, false, false, true);
                }
            }

            int accepted;
            if (blockPolicy) {
                int headroom = Math.max(0,
                        config.highWatermark() - outputBuffer.sizeHeld());
                int allowed = Math.min(data.length, headroom);
                byte[] chunk = new byte[allowed];
                System.arraycopy(data, 0, chunk, 0, allowed);
                long remaining = deadline == Long.MAX_VALUE
                        ? 0
                        : Math.max(0L, (deadline - System.nanoTime()) / 1_000_000L);
                accepted = outputBuffer.offerHeld(
                        chunk, deadline == Long.MAX_VALUE ? 0 : remaining);
            } else {
                accepted = outputBuffer.offerHeld(data, timeoutMillis);
            }
            boolean flowNowStopped = updateWatermarkAfterOffer();
            boolean timedOut = accepted < data.length
                    && blockPolicy
                    && timeoutMillis > 0
                    && !closed
                    && !writerFlowStopped;
            return new WriteResult(accepted, data.length - accepted,
                    timedOut, writerFlowStopped, false);
        }
    }

    /**
     * Raise STOP when the fill level crosses the high watermark.
     *
     * @return {@code true} if the flow is stopped after this update
     */
    private boolean updateWatermarkAfterOffer() {
        if (!writerFlowStopped && outputBuffer.sizeHeld() >= config.highWatermark()) {
            writerFlowStopped = true;
            FlowControlEvent event = FlowControlEvent.stop();
            for (DefaultFlowControlHandle handle : flowHandles.values()) {
                handle.notifyEvent(event);
            }
        }
        return writerFlowStopped;
    }

    /**
     * Raise START once the fill level drains below the low watermark.
     */
    private void updateWatermarkAfterDrain() {
        if (writerFlowStopped && outputBuffer.sizeHeld() < config.lowWatermark()) {
            writerFlowStopped = false;
            FlowControlEvent event = FlowControlEvent.start();
            for (DefaultFlowControlHandle handle : flowHandles.values()) {
                handle.notifyEvent(event);
            }
            lock.notifyAll();
        }
    }

    @Override
    public ReadResult readOutput(int maxBytes, long timeoutMillis) throws InterruptedException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive: " + maxBytes);
        }
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must not be negative");
        }
        synchronized (lock) {
            long deadline = timeoutMillis == 0
                    ? Long.MAX_VALUE
                    : System.nanoTime() + timeoutMillis * 1_000_000L;
            while ((outputBuffer.isEmptyHeld() || outputSuspended) && !closed) {
                if (deadline == Long.MAX_VALUE) {
                    lock.wait(1000);
                    continue;
                }
                long waitMillis = Math.max(0L,
                        (deadline - System.nanoTime()) / 1_000_000L);
                if (waitMillis == 0) {
                    return new ReadResult(new byte[0], false, false, true);
                }
                lock.wait(waitMillis);
                if ((outputBuffer.isEmptyHeld() || outputSuspended)
                        && System.nanoTime() >= deadline) {
                    return new ReadResult(new byte[0], false, false, true);
                }
            }
            if (!outputBuffer.isEmptyHeld()) {
                byte[] bytes = outputBuffer.pollHeld(maxBytes);
                updateWatermarkAfterDrain();
                return new ReadResult(bytes, false, false, false);
            }
            return new ReadResult(new byte[0], false, true, false);
        }
    }

    @Override
    public ControlParty registerControlParty(String partyId, SignalDeliveryPolicy policy,
                                             int queueCapacity) {
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(policy, "policy");
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("session is closed: " + id);
            }
            if (controlParties.containsKey(partyId)) {
                throw new IllegalArgumentException(
                        "control party already registered: " + partyId);
            }
            DefaultControlParty party =
                    new DefaultControlParty(partyId, policy, queueCapacity);
            controlParties.put(partyId, party);
            return party;
        }
    }

    @Override
    public FlowControlHandle registerFlowControl(String writerId, int eventQueueCapacity) {
        Objects.requireNonNull(writerId, "writerId");
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("session is closed: " + id);
            }
            if (flowHandles.containsKey(writerId)) {
                throw new IllegalArgumentException(
                        "flow control handle already registered: " + writerId);
            }
            DefaultFlowControlHandle handle =
                    new DefaultFlowControlHandle(writerId, eventQueueCapacity);
            flowHandles.put(writerId, handle);
            if (writerFlowStopped) {
                handle.notifyEvent(FlowControlEvent.stop());
            }
            return handle;
        }
    }

    @Override
    public LineDisciplineMode mode() {
        synchronized (lock) {
            return config.mode();
        }
    }

    @Override
    public void setMode(LineDisciplineMode newMode) {
        Objects.requireNonNull(newMode, "newMode");
        synchronized (lock) {
            if (closed) {
                return;
            }
            if (newMode == config.mode()) {
                return;
            }
            editBuffer.reset();
            config = config.withMode(newMode);
            lock.notifyAll();
        }
    }

    @Override
    public ControlCharacters controlCharacters() {
        synchronized (lock) {
            return config.controlCharacters();
        }
    }

    @Override
    public void setControlCharacters(ControlCharacters characters) {
        Objects.requireNonNull(characters, "characters");
        synchronized (lock) {
            if (closed) {
                return;
            }
            config = config.withControlCharacters(characters);
        }
    }

    @Override
    public void setVminVtime(int vmin, int vtimeMillis) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            config = config.withVminVtime(vmin, vtimeMillis);
            lock.notifyAll();
        }
    }

    @Override
    public int inputBufferSize() {
        synchronized (lock) {
            return inputBuffer.sizeHeld()
                    + (config.mode() == LineDisciplineMode.CANONICAL
                            ? editBuffer.pendingSize()
                            : 0);
        }
    }

    @Override
    public int outputBufferSize() {
        synchronized (lock) {
            return outputBuffer.sizeHeld();
        }
    }

    @Override
    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    @Override
    public void close() {
        List<DefaultControlParty> parties;
        List<DefaultFlowControlHandle> handles;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            editBuffer.reset();
            inputBuffer.close();
            outputBuffer.close();
            parties = new ArrayList<>(controlParties.values());
            handles = new ArrayList<>(flowHandles.values());
            controlParties.clear();
            flowHandles.clear();
            lock.notifyAll();
        }
        for (DefaultControlParty party : parties) {
            party.deliver(PtySignal.HANGUP);
            party.detach();
        }
        for (DefaultFlowControlHandle handle : handles) {
            handle.close();
        }
        if (onClose != null) {
            onClose.run();
        }
    }
}
