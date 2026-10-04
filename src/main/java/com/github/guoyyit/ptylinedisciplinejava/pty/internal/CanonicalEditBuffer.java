package com.github.guoyyit.ptylinedisciplinejava.pty.internal;

import com.github.guoyyit.ptylinedisciplinejava.pty.internal.boundedbuffer.BoundedByteBuffer;

import java.io.ByteArrayOutputStream;

/**
 * Holds the canonical-mode line currently being edited. Bytes are only admitted
 * once a free slot exists in the deliverable input buffer. A pending byte
 * reserves its slot: {@link #totalOccupied(BoundedByteBuffer)} reports the
 * pending size on top of the deliverable buffer fill level, so a completed line
 * always fits as a whole and can be committed atomically without overwriting
 * bytes that are already queued for the reader.
 *
 * <p>All methods must be invoked while holding the session lock (which is also
 * the monitor of {@code inputBuffer}).
 */
final class CanonicalEditBuffer {

    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    void reset() {
        pending.reset();
    }

    /**
     * @return input bytes not readable yet because the current line is unfinished
     */
    int pendingSize() {
        return pending.size();
    }

    /**
     * @return total input occupancy (deliverable bytes plus the pending line)
     */
    int totalOccupied(BoundedByteBuffer inputBuffer) {
        return inputBuffer.sizeHeld() + pending.size();
    }

    /**
     * @return {@code true} if admitting one more byte would exceed the capacity
     */
    boolean isFull(BoundedByteBuffer inputBuffer) {
        return totalOccupied(inputBuffer) >= inputBuffer.capacity();
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
     * Admit a regular byte into the pending line.
     */
    void admit(byte b) {
        pending.write(b & 0xff);
    }

    /**
     * Drop the whole pending line (DISCARD policy overflow or VKILL).
     */
    void discard() {
        pending.reset();
    }

    /**
     * Move the pending line into the deliverable buffer without a newline:
     * VEOF flushing an unfinished line. The caller must have verified that the
     * pending bytes fit.
     */
    void flushInto(BoundedByteBuffer inputBuffer) {
        byte[] line = pending.toByteArray();
        inputBuffer.appendGated(line);
        pending.reset();
    }

    /**
     * Atomically move the edited line into the deliverable input buffer.
     * Every pending byte previously reserved a slot, so the whole line fits.
     */
    byte[] drainReserved(BoundedByteBuffer inputBuffer) {
        byte[] line = pending.toByteArray();
        inputBuffer.appendGated(line);
        pending.reset();
        return line;
    }
}
