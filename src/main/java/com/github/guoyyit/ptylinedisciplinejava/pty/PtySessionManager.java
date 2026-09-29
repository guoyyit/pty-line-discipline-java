package com.github.guoyyit.ptylinedisciplinejava.pty;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class PtySessionManager {

    private final ConcurrentHashMap<String, PtySession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong();

    public PtySessionManager() {
    }

    public PtySession createSession() {
        return createSession((SessionHolder) null, new SessionOptions());
    }

    public PtySession createSession(SessionOptions options) {
        return createSession((SessionHolder) null, options);
    }

    public PtySession createSession(SessionHolder holder) {
        return createSession(holder, new SessionOptions());
    }

    public PtySession createSession(SessionHolder holder, SessionOptions options) {
        SessionOptions effectiveOptions = options == null ? new SessionOptions() : options;
        effectiveOptions.validate();
        String id = "session-" + idSequence.incrementAndGet();
        PtySession session = new PtySession(id, effectiveOptions);
        sessions.put(id, session);
        session.setOnClosed(() -> {
            sessions.remove(id);
            if (holder != null) {
                holder.detach(session);
            }
        });
        if (holder != null) {
            holder.attach(session);
        }
        return session;
    }

    public SessionHolder createHolder() {
        return new SessionHolder(this);
    }

    public Optional<PtySession> findSession(String id) {
        return Optional.ofNullable(sessions.get(id));
    }

    public boolean closeSession(String id) {
        PtySession session = sessions.get(id);
        if (session == null) {
            return false;
        }
        session.close();
        return true;
    }

    public int activeSessionCount() {
        return sessions.size();
    }

    public void closeAll() {
        for (PtySession session : sessions.values()) {
            session.close();
        }
    }
}
