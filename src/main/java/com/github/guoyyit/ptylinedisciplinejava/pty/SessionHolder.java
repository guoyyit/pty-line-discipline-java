package com.github.guoyyit.ptylinedisciplinejava.pty;

import java.util.List;

/**
 * A logical owner (e.g. an upper-layer component) of one or more sessions.
 * When the holder exits, all associated sessions receive HANGUP and are closed.
 */
public interface SessionHolder extends AutoCloseable {

    /**
     * @return stable holder identifier
     */
    String id();

    /**
     * Create a session associated with this holder.
     */
    PtySession createSession(SessionConfig config);

    /**
     * @return identifiers of currently associated sessions
     */
    List<String> sessionIds();

    /**
     * @return whether this holder has exited
     */
    boolean isExited();

    /**
     * Exit the holder: hang up and release every associated session. Idempotent.
     */
    @Override
    void close();
}
