package com.github.guoyyit.ptylinedisciplinejava.pty.internal;

import com.github.guoyyit.ptylinedisciplinejava.pty.PtySession;
import com.github.guoyyit.ptylinedisciplinejava.pty.PtySessionManager;
import com.github.guoyyit.ptylinedisciplinejava.pty.SessionConfig;
import com.github.guoyyit.ptylinedisciplinejava.pty.SessionHolder;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

/**
 * Default {@link PtySessionManager} implementation.
 */
public final class DefaultPtySessionManager implements PtySessionManager {

    private final Map<String, SessionHolder> holders = new ConcurrentHashMap<>();
    private final Map<String, PtySession> sessions = new ConcurrentHashMap<>();

    public DefaultPtySessionManager() {
    }

    @Override
    public SessionHolder createHolder(String holderId) {
        Objects.requireNonNull(holderId, "holderId");
        return holders.compute(holderId, (key, existing) -> {
            if (existing != null && !existing.isExited()) {
                throw new IllegalArgumentException("holder already exists: " + key);
            }
            return new ManagedSessionHolder(key, this);
        });
    }

    @Override
    public PtySession getSession(String sessionId) {
        PtySession session = sessions.get(sessionId);
        if (session != null && session.isClosed()) {
            sessions.remove(sessionId, session);
            return null;
        }
        return session;
    }

    @Override
    public void shutdown() {
        for (SessionHolder holder : Map.copyOf(holders).values()) {
            holder.close();
        }
        holders.clear();
        sessions.clear();
    }

    static String newSessionId() {
        return "pty-" + UUID.randomUUID();
    }

    void registerSession(PtySession session) {
        sessions.put(session.id(), session);
    }

    void unregisterSession(String sessionId) {
        sessions.remove(sessionId);
    }

    /**
     * Holder whose created sessions are also indexed by the owning manager.
     */
    private static final class ManagedSessionHolder extends DefaultSessionHolder {

        private final DefaultPtySessionManager manager;

        ManagedSessionHolder(String id, DefaultPtySessionManager manager) {
            super(id);
            this.manager = manager;
        }

        @Override
        public PtySession createSession(SessionConfig config) {
            if (isExited()) {
                throw new IllegalStateException("holder has exited: " + id());
            }
            Objects.requireNonNull(config, "config");
            String sessionId = DefaultPtySessionManager.newSessionId();
            DefaultPtySession session = new DefaultPtySession(
                    sessionId, config, () -> manager.unregisterSession(sessionId));
            addSession(session);
            manager.registerSession(session);
            return session;
        }
    }
}
