package com.github.guoyyit.ptylinedisciplinejava.pty.internal.boundedbuffer;

import com.github.guoyyit.ptylinedisciplinejava.pty.OverflowPolicy;

/**
 * Fixed-capacity byte FIFO with block/discard offer semantics and
 * high/low watermark notification hooks. All operations are thread safe.
 */
public final class BoundedByteBuffer {

    private final int capacity;
    private final OverflowPolicy overflowPolicy;
    private final byte[] ring;

    private final Object lock;
    private int head;
    private int count;
    private boolean closed;

    public BoundedByteBuffer(int capacity, OverflowPolicy overflowPolicy) {
        this(capacity, overflowPolicy, new Object());
    }

    /**
     * @param monitor shared monitor used for all synchronization; callers that need
     *                to coordinate several buffers may pass their own lock object
     */
    public BoundedByteBuffer(int capacity, OverflowPolicy overflowPolicy, Object monitor) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        if (overflowPolicy == null) {
            throw new IllegalArgumentException("overflowPolicy must not be null");
        }
        this.capacity = capacity;
        this.overflowPolicy = overflowPolicy;
        this.ring = new byte[capacity];
        this.lock = monitor;
    }

    /**
     * @return the monitor this buffer synchronizes on
     */
    public Object monitor() {
        return lock;
    }

    public int capacity() {
        return capacity;
    }

    public int size() {
        synchronized (lock) {
            return sizeHeld();
        }
    }

    public int sizeHeld() {
        return count;
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return isEmptyHeld();
        }
    }

    public boolean isEmptyHeld() {
        return count == 0;
    }

    public int availableCapacity() {
        synchronized (lock) {
            return capacity - count;
        }
    }

    public void close() {
        synchronized (lock) {
            closed = true;
            head = 0;
            count = 0;
            lock.notifyAll();
        }
    }

    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    /**
     * @param data bytes to offer
     * @param timeoutMillis for BLOCK policy, maximum wait time (0 = non-blocking)
     * @return number of bytes accepted; remaining bytes were discarded/timed out
     */
    public int offer(byte[] data, long timeoutMillis) throws InterruptedException {
        synchronized (lock) {
            return offerHeld(data, timeoutMillis);
        }
    }

    /**
     * {@link #offer} variant for callers that already hold {@link #monitor()}.
     */
    public int offerHeld(byte[] data, long timeoutMillis) throws InterruptedException {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        if (data.length == 0) {
            return 0;
        }
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must not be negative");
        }
        int offset = 0;
        long deadline = overflowPolicy == OverflowPolicy.BLOCK
                ? System.nanoTime() + timeoutMillis * 1_000_000L
                : 0L;
        while (offset < data.length) {
            if (closed) {
                break;
            }
            int free = capacity - count;
            if (free > 0) {
                int n = Math.min(free, data.length - offset);
                copyIn(data, offset, n);
                offset += n;
                lock.notifyAll();
                if (overflowPolicy == OverflowPolicy.DISCARD) {
                    break;
                }
                continue;
            }
            if (overflowPolicy == OverflowPolicy.DISCARD) {
                break;
            }
            if (timeoutMillis == 0) {
                break;
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            lock.wait(remainingNanos / 1_000_000L,
                    (int) (remainingNanos % 1_000_000L));
        }
        return offset;
    }

    /**
     * @param maxBytes maximum number of bytes to take
     * @return bytes taken (possibly empty); empty after close means end of stream
     */
    public byte[] poll(int maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive: " + maxBytes);
        }
        synchronized (lock) {
            return pollHeld(maxBytes);
        }
    }

    /**
     * {@link #poll} variant for callers that already hold {@link #monitor()}.
     */
    public byte[] pollHeld(int maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive: " + maxBytes);
        }
        int n = Math.min(maxBytes, count);
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = ring[head];
            ring[head] = 0;
            head = (head + 1) % capacity;
        }
        count -= n;
        if (n > 0) {
            lock.notifyAll();
        }
        return out;
    }

    /**
     * Take bytes up to and including the first occurrence of {@code delimiter},
     * or at most {@code maxBytes}. Caller must hold {@link #monitor()}.
     *
     * @return bytes taken (possibly empty)
     */
    public byte[] pollUntilDelimiter(byte delimiter, int maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive: " + maxBytes);
        }
        synchronized (lock) {
            return pollUntilDelimiterHeld(delimiter, maxBytes);
        }
    }

    /**
     * {@link #pollUntilDelimiter} variant for callers that already hold
     * {@link #monitor()}.
     */
    public byte[] pollUntilDelimiterHeld(byte delimiter, int maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive: " + maxBytes);
        }
        int limit = Math.min(maxBytes, count);
        int n = 0;
        while (n < limit) {
            if (ring[(head + n) % capacity] == delimiter) {
                n++;
                break;
            }
            n++;
        }
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = ring[head];
            ring[head] = 0;
            head = (head + 1) % capacity;
        }
        count -= n;
        if (n > 0) {
            lock.notifyAll();
        }
        return out;
    }

    /**
     * @return {@code true} if at least one {@code delimiter} byte is currently buffered
     */
    public boolean contains(byte delimiter) {
        synchronized (lock) {
            return containsHeld(delimiter);
        }
    }

    public boolean containsHeld(byte delimiter) {
        for (int i = 0; i < count; i++) {
            if (ring[(head + i) % capacity] == delimiter) {
                return true;
            }
        }
        return false;
    }

    /**
     * Wait until at least one byte is available, the timeout elapses, or closed.
     *
     * @return number of bytes currently available (0 on timeout)
     */
    public int awaitData(long timeoutMillis) throws InterruptedException {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must not be negative");
        }
        synchronized (lock) {
            if (timeoutMillis == 0) {
                return count;
            }
            return awaitDataNanosHeld(timeoutMillis * 1_000_000L);
        }
    }

    /**
     * {@link #awaitData} variant for callers that already hold {@link #monitor()}.
     */
    public int awaitDataHeld(long timeoutMillis) throws InterruptedException {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must not be negative");
        }
        if (timeoutMillis == 0) {
            return count;
        }
        return awaitDataNanosHeld(timeoutMillis * 1_000_000L);
    }

    /**
     * Wait up to {@code timeoutNanos} for data to appear. Caller must hold
     * {@link #monitor()} when reusing the session monitor; otherwise this method
     * acquires it.
     *
     * @return number of bytes available when the wait ends (0 on timeout)
     */
    public int awaitDataNanos(long timeoutNanos) throws InterruptedException {
        synchronized (lock) {
            return awaitDataNanosHeld(timeoutNanos);
        }
    }

    /**
     * Wait variant for callers that already hold {@link #monitor()}
     * (e.g. the session that shares this buffer's monitor).
     */
    public int awaitDataNanosHeld(long timeoutNanos) throws InterruptedException {
        if (timeoutNanos < 0) {
            throw new IllegalArgumentException("timeoutNanos must not be negative");
        }
        long deadline = System.nanoTime() + timeoutNanos;
        while (count == 0 && !closed) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            lock.wait(remainingNanos / 1_000_000L,
                    (int) (remainingNanos % 1_000_000L));
        }
        return count;
    }

    /**
     * Wait until at least {@code required} bytes are available, the timeout
     * elapses, EOF/close occurs, or any new byte arrives (checked via
     * {@code initialCount}). Caller must hold {@link #monitor()}.
     *
     * @return number of bytes available when the wait ends
     */
    public int awaitCountHeld(int required, int initialCount, long timeoutNanos)
            throws InterruptedException {
        if (timeoutNanos < 0) {
            throw new IllegalArgumentException("timeoutNanos must not be negative");
        }
        long deadline = System.nanoTime() + timeoutNanos;
        while (count < required && count == initialCount && !closed) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            lock.wait(remainingNanos / 1_000_000L,
                    (int) (remainingNanos % 1_000_000L));
        }
        return count;
    }

    /**
     * Discard every buffered byte.
     */
    public void clear() {
        synchronized (lock) {
            head = 0;
            count = 0;
            lock.notifyAll();
        }
    }

    /**
     * Block until at least {@code required} bytes are free, the timeout elapses,
     * or the buffer is closed. Caller must hold {@link #monitor()}.
     *
     * @return {@code true} if the space became available, {@code false} otherwise
     */
    public boolean awaitSpace(int required, long timeoutMillis) throws InterruptedException {
        synchronized (lock) {
            return awaitSpaceHeld(required, timeoutMillis);
        }
    }

    /**
     * {@link #awaitSpace} variant for callers that already hold {@link #monitor()}.
     */
    public boolean awaitSpaceHeld(int required, long timeoutMillis)
            throws InterruptedException {
        if (required <= 0) {
            return true;
        }
        if (timeoutMillis == 0) {
            return capacity - count >= required;
        }
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (capacity - count < required && !closed) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                return false;
            }
            lock.wait(remainingNanos / 1_000_000L,
                    (int) (remainingNanos % 1_000_000L));
        }
        return capacity - count >= required;
    }

    /**
     * Append bytes that were previously gated by {@link #awaitSpace}. No capacity
     * check is performed. Caller must hold {@link #monitor()}.
     */
    public void appendGated(byte[] src) {
        copyIn(src, 0, src.length);
        if (src.length > 0) {
            lock.notifyAll();
        }
    }

    private void copyIn(byte[] src, int srcOffset, int n) {
        int tail = (head + count) % capacity;
        for (int i = 0; i < n; i++) {
            ring[tail] = src[srcOffset + i];
            tail = (tail + 1) % capacity;
        }
        count += n;
    }
}
