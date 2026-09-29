package com.github.guoyyit.ptylinedisciplinejava.pty;

/**
 * A single pseudo-terminal session with an input/output byte stream,
 * a configurable line discipline and signal/flow-control observers.
 */
public interface PtySession {

    /**
     * @return unique session identifier
     */
    String id();

    /**
     * Write bytes from the terminal (master side) into the input stream.
     * In canonical mode editing/signal special characters are interpreted.
     */
    WriteResult writeInput(byte[] data, long timeoutMillis) throws InterruptedException;

    /**
     * Read bytes delivered to the program (slave side) from the input stream.
     */
    ReadResult readInput(int maxBytes, long timeoutMillis) throws InterruptedException;

    /**
     * Write bytes produced by the program (slave side) into the output stream.
     */
    WriteResult writeOutput(byte[] data, long timeoutMillis) throws InterruptedException;

    /**
     * Read bytes displayed at the terminal (master side) from the output stream.
     */
    ReadResult readOutput(int maxBytes, long timeoutMillis) throws InterruptedException;

    /**
     * Register a control party receiving INTERRUPT/QUIT/HANGUP signals.
     */
    ControlParty registerControlParty(String partyId, SignalDeliveryPolicy policy,
                                      int queueCapacity);

    /**
     * Register an output writer observing high/low watermark flow control.
     */
    FlowControlHandle registerFlowControl(String writerId, int eventQueueCapacity);

    /**
     * @return current line discipline mode
     */
    LineDisciplineMode mode();

    /**
     * Switch line discipline mode atomically. Already queued (deliverable) bytes
     * are preserved; an unfinished canonical edit line is discarded.
     */
    void setMode(LineDisciplineMode newMode);

    /**
     * @return active special character configuration
     */
    ControlCharacters controlCharacters();

    /**
     * Replace the special character configuration atomically; the change applies
     * to subsequently received bytes only.
     */
    void setControlCharacters(ControlCharacters characters);

    /**
     * Update raw-mode VMIN/VTIME readiness semantics.
     */
    void setVminVtime(int vmin, int vtimeMillis);

    /**
     * @return current input buffer fill level
     */
    int inputBufferSize();

    /**
     * @return current output buffer fill level
     */
    int outputBufferSize();

    /**
     * @return whether the session has been closed
     */
    boolean isClosed();

    /**
     * Close the session: deliver HANGUP to control parties, release all buffers
     * and registrations. Idempotent.
     */
    void close();
}
