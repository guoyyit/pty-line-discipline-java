package com.github.guoyyit.ptylinedisciplinejava.pty;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionLifecycleTest {

    @Test
    void createFindAndCloseUnregistersSession() {
        PtySessionManager manager = new PtySessionManager();
        PtySession session = manager.createSession();

        assertTrue(manager.findSession(session.getId()).isPresent());
        assertEquals(1, manager.activeSessionCount());

        assertTrue(manager.closeSession(session.getId()));
        assertTrue(manager.findSession(session.getId()).isEmpty());
        assertEquals(0, manager.activeSessionCount());
        assertTrue(session.isClosed());
    }

    @Test
    void closingUnknownSessionReturnsFalse() {
        assertFalse(new PtySessionManager().closeSession("missing"));
    }

    @Test
    void holderExitHangsUpAllSessionsAndReleasesBuffers() {
        PtySessionManager manager = new PtySessionManager();
        SessionHolder holder = manager.createHolder();
        List<SignalType> signals = new ArrayList<>();

        PtySession first = manager.createSession(holder);
        PtySession second = manager.createSession(holder);
        ControlParty party = (type, sessionId) -> {
            synchronized (signals) {
                signals.add(type);
            }
            return SignalDeliveryResult.DELIVERED;
        };
        first.setControlParty(party);
        second.setControlParty(party);

        first.writeInput(new byte[]{1, 2, 3});
        first.writeOutput(new byte[]{9}, 0, 1);

        holder.exit();

        assertTrue(first.isClosed());
        assertTrue(second.isClosed());
        assertTrue(holder.isExited());
        assertEquals(2, signals.stream().filter(type -> type == SignalType.HANGUP).count());
        assertEquals(0, first.inputQueueSize());
        assertEquals(0, first.outputQueueSize());
        assertEquals(0, manager.activeSessionCount());
        assertTrue(holder.sessionIds().isEmpty());
    }

    @Test
    void exitIsIdempotentAndDoesNotReDeliverHangup() {
        PtySessionManager manager = new PtySessionManager();
        SessionHolder holder = manager.createHolder();
        List<SignalType> signals = new ArrayList<>();
        PtySession session = manager.createSession(holder);
        session.setControlParty((type, id) -> {
            signals.add(type);
            return SignalDeliveryResult.DELIVERED;
        });

        holder.exit();
        holder.exit();

        assertEquals(1, signals.size());
        assertEquals(SignalType.HANGUP, signals.get(0));
    }

    @Test
    void attachingSessionToExitedHolderClosesItImmediately() {
        PtySessionManager manager = new PtySessionManager();
        SessionHolder holder = manager.createHolder();
        holder.exit();

        PtySession session = manager.createSession(holder);

        assertTrue(session.isClosed());
        assertEquals(0, manager.activeSessionCount());
    }

    @Test
    void closeAllClosesEveryActiveSession() {
        PtySessionManager manager = new PtySessionManager();
        PtySession first = manager.createSession();
        PtySession second = manager.createSession();

        manager.closeAll();

        assertTrue(first.isClosed());
        assertTrue(second.isClosed());
        assertEquals(0, manager.activeSessionCount());
    }
}
