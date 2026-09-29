package com.github.guoyyit.ptylinedisciplinejava.tests;

import com.github.guoyyit.ptylinedisciplinejava.pty.ControlParty;
import com.github.guoyyit.ptylinedisciplinejava.pty.Defaults;
import com.github.guoyyit.ptylinedisciplinejava.pty.PtySession;
import com.github.guoyyit.ptylinedisciplinejava.pty.PtySignal;
import com.github.guoyyit.ptylinedisciplinejava.pty.PtySessionManager;
import com.github.guoyyit.ptylinedisciplinejava.pty.SessionConfig;
import com.github.guoyyit.ptylinedisciplinejava.pty.SessionHolder;
import com.github.guoyyit.ptylinedisciplinejava.pty.SignalDeliveryPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalDeliveryTest {

    private PtySessionManager manager;
    private SessionHolder holder;
    private PtySession session;

    @BeforeEach
    void setUp() {
        manager = PtySessionManager.create();
        holder = manager.createHolder("holder-signal");
        session = holder.createSession(SessionConfig.defaults());
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    @Test
    void interruptAndQuitAreDeliveredInTriggerOrder() throws Exception {
        ControlParty party = session.registerControlParty(
                "p1", SignalDeliveryPolicy.QUEUE, 16);
        session.writeInput(new byte[]{Defaults.VINTR}, 1000);
        session.writeInput(new byte[]{Defaults.VQUIT}, 1000);
        session.writeInput(new byte[]{Defaults.VINTR}, 1000);

        assertEquals(PtySignal.INTERRUPT, party.pollSignal());
        assertEquals(PtySignal.QUIT, party.pollSignal());
        assertEquals(PtySignal.INTERRUPT, party.pollSignal());
        assertNull(party.pollSignal());
    }

    @Test
    void signalCharsAreNotDeliveredAsInputBytes() throws Exception {
        ControlParty party = session.registerControlParty(
                "p2", SignalDeliveryPolicy.QUEUE, 16);
        session.writeInput(new byte[]{'a', Defaults.VINTR, 'b', Defaults.VQUIT, '\n'},
                1000);
        assertEquals(PtySignal.INTERRUPT, party.pollSignal());
        assertEquals(PtySignal.QUIT, party.pollSignal());
        var read = session.readInput(64, 200);
        assertEquals("ab\n", new String(read.data()));
    }

    @Test
    void everyControlPartyReceivesEachSignalExactlyOnce() throws Exception {
        ControlParty p1 = session.registerControlParty(
                "a", SignalDeliveryPolicy.QUEUE, 16);
        ControlParty p2 = session.registerControlParty(
                "b", SignalDeliveryPolicy.QUEUE, 16);
        session.writeInput(new byte[]{Defaults.VINTR}, 1000);

        assertEquals(PtySignal.INTERRUPT, p1.pollSignal());
        assertEquals(PtySignal.INTERRUPT, p2.pollSignal());
        assertNull(p1.pollSignal());
        assertNull(p2.pollSignal());
    }

    @Test
    void disabledSignalCharacterProducesNoSignal() throws Exception {
        ControlParty party = session.registerControlParty(
                "p3", SignalDeliveryPolicy.QUEUE, 16);
        session.setControlCharacters(
                com.github.guoyyit.ptylinedisciplinejava.pty.ControlCharacters
                        .defaults().withIntr(Defaults.DISABLED));
        session.writeInput(new byte[]{Defaults.VINTR, '\n'}, 1000);
        assertNull(party.pollSignal());
        assertEquals(2, session.readInput(64, 200).bytesRead());
    }

    @Test
    void queuePolicyBuffersSignalsUntilPolled() throws Exception {
        ControlParty party = session.registerControlParty(
                "p4", SignalDeliveryPolicy.QUEUE, 4);
        session.writeInput(new byte[]{Defaults.VINTR}, 1000);
        session.writeInput(new byte[]{Defaults.VQUIT}, 1000);
        assertEquals(PtySignal.INTERRUPT, party.awaitSignal(200));
        assertEquals(PtySignal.QUIT, party.awaitSignal(200));
    }

    @Test
    void discardPolicyCountsDroppedSignals() throws Exception {
        ControlParty party = session.registerControlParty(
                "p5", SignalDeliveryPolicy.DISCARD, 1);
        for (int i = 0; i < 5; i++) {
            session.writeInput(new byte[]{Defaults.VINTR}, 1000);
        }
        PtySignal first = party.pollSignal();
        assertEquals(PtySignal.INTERRUPT, first);
        assertTrue(party.droppedSignals() >= 1,
                "overflowed signals must be counted");
    }

    @Test
    void closeDeliversHangupExactlyOnceAndMarksParty() throws Exception {
        ControlParty party = session.registerControlParty(
                "p6", SignalDeliveryPolicy.QUEUE, 16);
        session.close();
        assertTrue(party.hungUp());
        assertEquals(PtySignal.HANGUP, party.pollSignal());
        assertNull(party.pollSignal());

        session.close();
        assertFalse(session.isClosed() == false);
        assertNull(party.pollSignal());
    }

    @Test
    void holderExitHangsUpAllAssociatedSessions() throws Exception {
        PtySession s1 = holder.createSession(SessionConfig.defaults());
        PtySession s2 = holder.createSession(SessionConfig.defaults());
        ControlParty cp1 = s1.registerControlParty(
                "h1", SignalDeliveryPolicy.QUEUE, 8);
        ControlParty cp2 = s2.registerControlParty(
                "h2", SignalDeliveryPolicy.QUEUE, 8);

        holder.close();

        assertTrue(s1.isClosed());
        assertTrue(s2.isClosed());
        assertTrue(holder.isExited());
        assertEquals(PtySignal.HANGUP, cp1.pollSignal());
        assertEquals(PtySignal.HANGUP, cp2.pollSignal());
    }
}
