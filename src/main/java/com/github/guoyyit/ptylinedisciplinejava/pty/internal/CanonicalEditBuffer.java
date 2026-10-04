package com.github.guoyyit.ptylinedisciplinejava.pty.internal;

import com.github.guoyyit.ptylinedisciplinejava.pty.internal.boundedbuffer.BoundedByteBuffer;

import java.io.ByteArrayOutputStream;

/**
 * Holds the canonical-mode line currently being edited. Bytes are only admitted
 * while the deliverable input buffer still has a free slot for them, counting
 * the bytes already pending here: the invariant
 * {@code inputBuffer.size() + pending.size() <= capacity} guarantees that a
 * completed line is always committed atomically, never split across writes and
 * never overflows the configured capacity.
 *
 * <p>All methods must be invoked while holding the session lock (which is also
 * the monitor of {@code inputBuffer}).
 */
final class CanonicalEditBuffer {

    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    int size() {
        return pending.size();
    }

    void reset() {
        pending.reset();
    }

    byte[] peek() {
        return pending.toByteArray();
    }

    void write(int b) {
        pending.write(b);
    }

    /**
     * Erase the most recently admitted byte (VERASE).
     */
    void eraseOne() {
        byte[] current = pending.toByteArray();
        if (current.length == 0) {
            return;
        }
        pending.reset();
        pending.write(current, 0, current.length - 1);
    }

    /**
     * Admit a regular byte into the pending line once input buffer space exists.
     * The pending bytes count as reserved slots, so the wait asks for
     * {@code pending.size() + 1} free bytes rather than a single one.
     *
     * @param waitMillis maximum wait for space (0 = non-blocking check)
     * @return {@code true} if the byte was admitted into the pending line,
     *         {@code false} if no reserved slot was available in time
     */
    boolean appendReserved(BoundedByteBuffer inputBuffer, byte b, long waitMillis)
            throws InterruptedException {
        if (inputBuffer.awaitSpaceHeld(pending.size() + 1, waitMillis)) {
            pending.write(b & 0xff);
            return true;
        }
        return false;
    }

    /**
     * Atomically move the edited line into the deliverable input buffer.
     * Every pending byte previously had a reserved slot, so the whole line fits.
     */
    byte[] drainReserved(BoundedByteBuffer inputBuffer) {
        byte[] line = pending.toByteArray();
        inputBuffer.appendGated(line);
        pending.reset();
        return line;
    }
}
