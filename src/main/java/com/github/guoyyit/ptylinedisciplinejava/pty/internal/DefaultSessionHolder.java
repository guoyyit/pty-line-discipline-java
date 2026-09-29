package com.github.guoyyit.ptylinedisciplinejava.pty.internal;

import com.github.guoyyit.ptylinedisciplinejava.pty.PtySession;
import com.github.guoyyit.ptylinedisciplinejava.pty.SessionConfig;
import com.github.guoyyit.ptylinedisciplinejava.pty.SessionHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link SessionHolder} implementation.
 */
public class DefaultSessionHolder implements SessionHolder {

    private final String id;
    private final Map<String, PtySession> sessions = new ConcurrentHashMap<>();
    private volatile boolean exited;

    public DefaultSessionHolder(String id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public PtySession createSession(SessionConfig config) {
        if (exited) {
            throw new IllegalStateException("holder has exited: " + id);
        }
        Objects.requireNonNull(config, "config");
        DefaultPtySession session =
                new DefaultPtySession(DefaultPtySessionManager.newSessionId(), config);
        sessions.put(session.id(), session);
        return session;
    }

    @Override
    public List<String> sessionIds() {
        return new ArrayList<>(sessions.keySet());
    }

    @Override
    public boolean isExited() {
        return exited;
    }

    @Override
    public void close() {
        if (exited) {
            return;
        }
        exited = true;
        for (PtySession session : new ArrayList<>(sessions.values())) {
            session.close();
        }
        sessions.clear();
    }

    protected final PtySession addSession(PtySession session) {
        sessions.put(session.id(), session);
        return session;
    }
}
