package com.github.guoyyit.ptylinedisciplinejava.pty;

import com.github.guoyyit.ptylinedisciplinejava.pty.internal.DefaultPtySessionManager;

/**
 * Factory and registry for pseudo-terminal sessions and their holders.
 */
public interface PtySessionManager {

    /**
     * Create a new holder.
     */
    SessionHolder createHolder(String holderId);

    /**
     * Look up an existing session by identifier.
     *
     * @return the session, or {@code null} if it does not exist or is closed
     */
    PtySession getSession(String sessionId);

    /**
     * Close every session and forget every holder.
     */
    void shutdown();

    static PtySessionManager create() {
        return new DefaultPtySessionManager();
    }
}
