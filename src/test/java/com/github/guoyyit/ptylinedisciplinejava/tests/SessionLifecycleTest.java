package com.github.guoyyit.ptylinedisciplinejava.tests;

import com.github.guoyyit.ptylinedisciplinejava.pty.ControlParty;
import com.github.guoyyit.ptylinedisciplinejava.pty.PtySession;
import com.github.guoyyit.ptylinedisciplinejava.pty.PtySessionManager;
import com.github.guoyyit.ptylinedisciplinejava.pty.PtySignal;
import com.github.guoyyit.ptylinedisciplinejava.pty.SessionConfig;
import com.github.guoyyit.ptylinedisciplinejava.pty.SessionHolder;
import com.github.guoyyit.ptylinedisciplinejava.pty.SignalDeliveryPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionLifecycleTest {

    @Test
    void sessionsAreRegisteredAndLookedUpViaManager() {
        PtySessionManager manager = PtySessionManager.create();
        SessionHolder holder = manager.createHolder("owner");
        PtySession session = holder.createSession(SessionConfig.defaults());

        assertNotNull(manager.getSession(session.id()));
        assertTrue(holder.sessionIds().contains(session.id()));

        manager.shutdown();
    }

    @Test
    void closingSessionReleasesBuffersAndRegistry() throws Exception {
        PtySessionManager manager = PtySessionManager.create();
        SessionHolder holder = manager.createHolder("owner2");
        PtySession session = holder.createSession(SessionConfig.defaults());
        session.writeInput("data\n".getBytes(), 1000);

        session.close();
        assertTrue(session.isClosed());
        assertEquals(0, session.inputBufferSize());
        assertEquals(0, session.outputBufferSize());
        assertNull(manager.getSession(session.id()));

        session.close();
        assertTrue(session.isClosed());

        var write = session.writeInput(new byte[]{1}, 100);
        assertTrue(write.closed());
        var read = session.readInput(64, 100);
        assertTrue(read.hangup());

        manager.shutdown();
    }

    @Test
    void cannotRegisterObserversOnClosedSession() {
        PtySessionManager manager = PtySessionManager.create();
        SessionHolder holder = manager.createHolder("owner3");
        PtySession session = holder.createSession(SessionConfig.defaults());
        session.close();

        assertThrows(IllegalStateException.class, () ->
                session.registerControlParty(
                        "x", SignalDeliveryPolicy.QUEUE, 8));
        assertThrows(IllegalStateException.class, () ->
                session.registerFlowControl("y", 8));

        manager.shutdown();
    }

    @Test
    void creatingSessionAfterHolderExitIsRejected() {
        PtySessionManager manager = PtySessionManager.create();
        SessionHolder holder = manager.createHolder("owner4");
        holder.close();
        assertTrue(holder.isExited());
        assertThrows(IllegalStateException.class, () ->
                holder.createSession(SessionConfig.defaults()));
        manager.shutdown();
    }

    @Test
    void duplicateHolderAndPartyIdsAreRejected() {
        PtySessionManager manager = PtySessionManager.create();
        SessionHolder holder = manager.createHolder("dup");
        assertThrows(IllegalArgumentException.class, () ->
                manager.createHolder("dup"));

        PtySession session = holder.createSession(SessionConfig.defaults());
        session.registerControlParty("p", SignalDeliveryPolicy.QUEUE, 4);
        assertThrows(IllegalArgumentException.class, () ->
                session.registerControlParty("p", SignalDeliveryPolicy.QUEUE, 4));

        manager.shutdown();
    }

    @Test
    void shutdownHangsUpEverySessionOfEveryHolder() throws Exception {
        PtySessionManager manager = PtySessionManager.create();
        SessionHolder h1 = manager.createHolder("a");
        SessionHolder h2 = manager.createHolder("b");
        PtySession s1 = h1.createSession(SessionConfig.defaults());
        PtySession s2 = h2.createSession(SessionConfig.defaults());
        ControlParty cp1 = s1.registerControlParty(
                "c1", SignalDeliveryPolicy.QUEUE, 4);
        ControlParty cp2 = s2.registerControlParty(
                "c2", SignalDeliveryPolicy.QUEUE, 4);

        manager.shutdown();

        assertTrue(s1.isClosed());
        assertTrue(s2.isClosed());
        assertEquals(PtySignal.HANGUP, cp1.pollSignal());
        assertEquals(PtySignal.HANGUP, cp2.pollSignal());
    }
}
