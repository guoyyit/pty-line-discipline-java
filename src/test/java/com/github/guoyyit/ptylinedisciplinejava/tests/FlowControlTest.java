package com.github.guoyyit.ptylinedisciplinejava.tests;

import com.github.guoyyit.ptylinedisciplinejava.pty.BufferLimits;
import com.github.guoyyit.ptylinedisciplinejava.pty.Defaults;
import com.github.guoyyit.ptylinedisciplinejava.pty.FlowControlEvent;
import com.github.guoyyit.ptylinedisciplinejava.pty.FlowControlHandle;
import com.github.guoyyit.ptylinedisciplinejava.pty.LineDisciplineMode;
import com.github.guoyyit.ptylinedisciplinejava.pty.OverflowPolicy;
import com.github.guoyyit.ptylinedisciplinejava.pty.PtySession;
import com.github.guoyyit.ptylinedisciplinejava.pty.PtySessionManager;
import com.github.guoyyit.ptylinedisciplinejava.pty.SessionConfig;
import com.github.guoyyit.ptylinedisciplinejava.pty.SessionHolder;
import com.github.guoyyit.ptylinedisciplinejava.pty.WriteResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowControlTest {

    private PtySessionManager manager;
    private PtySession session;

    private static final int CAPACITY = 100;
    private static final int HIGH = 80;
    private static final int LOW = 20;

    @BeforeEach
    void setUp() {
        manager = PtySessionManager.create();
        SessionHolder holder = manager.createHolder("holder-flow");
        session = holder.createSession(new SessionConfig(
                LineDisciplineMode.RAW,
                com.github.guoyyit.ptylinedisciplinejava.pty.ControlCharacters.defaults(),
                BufferLimits.inputDefaults(),
                new BufferLimits(CAPACITY, OverflowPolicy.BLOCK),
                HIGH, LOW, 1, 0, 64,
                com.github.guoyyit.ptylinedisciplinejava.pty.SignalDeliveryPolicy.QUEUE));
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    @Test
    void stopIsEmittedAtHighWatermarkAndStartAtLowWatermark() throws Exception {
        FlowControlHandle handle = session.registerFlowControl("writer", 16);

        writeOutput(80);
        assertTrue(handle.isStopped());
        FlowControlEvent stop = handle.pollEvent();
        assertTrue(stop.stopped());

        WriteResult refused = session.writeOutput(new byte[10], 100);
        assertEquals(0, refused.bytesAccepted());
        assertTrue(refused.flowStopped());

        session.readOutput(61, 500);
        assertTrue(session.outputBufferSize() < LOW);
        assertFalse(handle.isStopped());
        FlowControlEvent start = handle.pollEvent();
        assertFalse(start.stopped());

        WriteResult accepted = session.writeOutput(new byte[5], 100);
        assertEquals(5, accepted.bytesAccepted());
        assertFalse(accepted.flowStopped());
    }

    @Test
    void blockPolicyNeverExceedsHighWatermarkBeforeStop() throws Exception {
        FlowControlHandle handle = session.registerFlowControl("writer2", 16);
        WriteResult result = session.writeOutput(new byte[95], 200);
        assertTrue(result.flowStopped() || session.outputBufferSize() <= HIGH);
        assertTrue(session.outputBufferSize() <= HIGH);
        assertTrue(handle.isStopped());
    }

    @Test
    void newlyRegisteredWriterLearnsCurrentStopState() throws Exception {
        writeOutput(80);
        FlowControlHandle late = session.registerFlowControl("late", 16);
        assertTrue(late.isStopped());
        assertTrue(late.pollEvent().stopped());
    }

    @Test
    void vstopSuspendsOutputDeliveryAndVstartResumes() throws Exception {
        PtySession canonical = manager.createHolder("h-xon")
                .createSession(SessionConfig.defaults());
        canonical.writeOutput("hello".getBytes(), 1000);
        canonical.writeInput(new byte[]{Defaults.VSTOP}, 1000);

        var suspended = canonical.readOutput(64, 100);
        assertEquals(0, suspended.bytesRead());
        assertTrue(suspended.timedOut());

        canonical.writeInput(new byte[]{Defaults.VSTART}, 1000);
        var resumed = canonical.readOutput(64, 500);
        assertEquals("hello", new String(resumed.data()));
    }

    @Test
    void discardOverflowPolicyReportsDiscardedBytes() throws Exception {
        PtySession s = manager.createHolder("h-discard").createSession(
                new SessionConfig(
                        LineDisciplineMode.RAW,
                        com.github.guoyyit.ptylinedisciplinejava.pty.ControlCharacters.defaults(),
                        BufferLimits.inputDefaults(),
                        new BufferLimits(10, OverflowPolicy.DISCARD),
                        10, 2, 1, 0, 64,
                        com.github.guoyyit.ptylinedisciplinejava.pty.SignalDeliveryPolicy.QUEUE));
        WriteResult result = s.writeOutput(new byte[25], 0);
        assertEquals(10, result.bytesAccepted());
        assertEquals(15, result.bytesDiscarded());
    }

    private void writeOutput(int n) throws InterruptedException {
        WriteResult result = session.writeOutput(new byte[n], 1000);
        assertEquals(n, result.bytesAccepted(), result::toString);
    }
}
