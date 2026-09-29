package com.github.guoyyit.ptylinedisciplinejava.pty;

/**
 * A registered observer of control signals for one session (e.g. a remote
 * session bridge or an interactive tool controller).
 */
public interface ControlParty extends AutoCloseable {

    /**
     * @return stable identifier, unique within the owning session
     */
    String id();

    /**
     * Non-blocking poll of the next signal in trigger order.
     *
     * @return the next signal, or {@code null} if none is pending
     */
    PtySignal pollSignal();

    /**
     * Wait for the next signal in trigger order.
     *
     * @param timeoutMillis maximum time to wait; 0 returns immediately
     * @return the signal, or {@code null} on timeout or session close
     * @throws InterruptedException if the waiting thread is interrupted
     */
    PtySignal awaitSignal(long timeoutMillis) throws InterruptedException;

    /**
     * @return number of signals discarded because delivery was impossible
     */
    int droppedSignals();

    /**
     * @return whether HANGUP has been delivered to (or flushed for) this party
     */
    boolean hungUp();

    /**
     * Unregister this control party from the session.
     */
    @Override
    void close();
}
