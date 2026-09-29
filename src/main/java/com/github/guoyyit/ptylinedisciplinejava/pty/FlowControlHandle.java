package com.github.guoyyit.ptylinedisciplinejava.pty;

/**
 * Per-writer handle for observing output flow control notifications.
 */
public interface FlowControlHandle extends AutoCloseable {

    /**
     * Non-blocking poll of the next STOP/START event.
     *
     * @return the next event, or {@code null} if none is pending
     */
    FlowControlEvent pollEvent();

    /**
     * Wait for the next STOP/START event.
     *
     * @param timeoutMillis maximum time to wait; 0 returns immediately
     * @return the event, or {@code null} on timeout or session close
     * @throws InterruptedException if the waiting thread is interrupted
     */
    FlowControlEvent awaitEvent(long timeoutMillis) throws InterruptedException;

    /**
     * @return current flow state: {@code true} while writers must pause
     */
    boolean isStopped();

    /**
     * @return number of events dropped because this handle's queue was full
     */
    int droppedEvents();

    /**
     * Unregister this handle from the session.
     */
    @Override
    void close();
}
