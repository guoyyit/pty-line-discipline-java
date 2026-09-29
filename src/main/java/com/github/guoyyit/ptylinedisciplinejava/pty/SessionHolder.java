package com.github.guoyyit.ptylinedisciplinejava.pty;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public final class SessionHolder {

    private static final AtomicLong ID_SEQUENCE = new AtomicLong();

    private final String id;
    private final PtySessionManager manager;
    private final Set<PtySession> sessions = new LinkedHashSet<>();
    private boolean exited;

    SessionHolder(PtySessionManager manager) {
        this("holder-" + ID_SEQUENCE.incrementAndGet(), manager);
    }

    SessionHolder(String id, PtySessionManager manager) {
        this.id = id;
        this.manager = manager;
    }

    public String getId() {
        return id;
    }

    public void attach(PtySession session) {
        if (session == null) {
            throw new IllegalArgumentException("session must not be null");
        }
        PtySession[] toClose = null;
        synchronized (this) {
            if (exited) {
                toClose = new PtySession[]{session};
            } else {
                sessions.add(session);
            }
        }
        if (toClose != null) {
            session.close();
        }
    }

    public void detach(PtySession session) {
        synchronized (this) {
            sessions.remove(session);
        }
    }

    public Set<String> sessionIds() {
        synchronized (this) {
            Set<String> ids = new LinkedHashSet<>();
            for (PtySession session : sessions) {
                ids.add(session.getId());
            }
            return Collections.unmodifiableSet(ids);
        }
    }

    public boolean isExited() {
        synchronized (this) {
            return exited;
        }
    }

    public void exit() {
        PtySession[] snapshot;
        synchronized (this) {
            if (exited) {
                return;
            }
            exited = true;
            snapshot = sessions.toArray(new PtySession[0]);
            sessions.clear();
        }
        for (PtySession session : snapshot) {
            session.close();
        }
    }
}
