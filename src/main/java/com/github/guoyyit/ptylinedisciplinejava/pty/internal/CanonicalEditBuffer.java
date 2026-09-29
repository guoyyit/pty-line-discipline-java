package com.github.guoyyit.ptylinedisciplinejava.pty.internal;

import com.github.guoyyit.ptylinedisciplinejava.pty.OverflowPolicy;
import com.github.guoyyit.ptylinedisciplinejava.pty.internal.boundedbuffer.BoundedByteBuffer;

import java.io.ByteArrayOutputStream;

/**
 * Holds the canonical-mode line currently being edited. Bytes are only admitted
 * once a free slot exists in the deliverable input buffer, so a completed line
 * is always committed atomically and never split across writes.
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
     *
     * @return {@code false} for BLOCK policy if the timeout elapsed before space
     *         was available (caller stops consuming); {@code true} otherwise
     */
    boolean appendReserved(BoundedByteBuffer inputBuffer, byte b, long timeoutMillis,
                           OverflowPolicy policy, Object sessionLock)
            throws InterruptedException {
        if (inputBuffer.awaitSpaceHeld(1, timeoutMillis)) {
            pending.write(b & 0xff);
            return true;
        }
        return policy == OverflowPolicy.DISCARD;
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
