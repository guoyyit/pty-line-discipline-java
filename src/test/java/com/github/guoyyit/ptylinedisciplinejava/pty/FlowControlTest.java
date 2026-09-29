package com.github.guoyyit.ptylinedisciplinejava.pty;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowControlTest {

    private SessionOptions options() {
        SessionOptions options = new SessionOptions();
        options.setMode(LineMode.RAW);
        options.setOutputCapacity(10);
        options.setHighWaterMark(8);
        options.setLowWaterMark(2);
        return options;
    }

    @Test
    void emitsStopAtHighWaterAndStartAtLowWater() throws Exception {
        PtySession session = new PtySessionManager().createSession(options());
        List<FlowState> transitions = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        session.setOutputFlowListener((state, size) -> {
            transitions.add(state);
            sizes.add(size);
        });

        WriteResult result = session.writeOutput(new byte[8], 0, 8);
        assertTrue(result.accepted());
        assertEquals(FlowState.STOPPED, session.getFlowState());

        byte[] buffer = new byte[8];
        int read = session.readOutput(buffer, 0, 8, 1, TimeUnit.SECONDS);
        assertEquals(8, read);
        assertEquals(FlowState.RUNNING, session.getFlowState());

        assertEquals(List.of(FlowState.STOPPED, FlowState.RUNNING), transitions);
        assertEquals(List.of(8, 0), sizes);
    }

    @Test
    void writesAreRejectedWhileFlowStoppedAndAcceptedAfterStart() {
        PtySession session = new PtySessionManager().createSession(options());
        session.writeOutput(new byte[8], 0, 8);

        WriteResult rejected = session.writeOutput(new byte[]{1}, 0, 1);
        assertFalse(rejected.accepted());
        assertEquals(WriteRejectReason.FLOW_STOPPED, rejected.reason());

        byte[] buffer = new byte[8];
        try {
            session.readOutput(buffer, 0, 8, 1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        WriteResult accepted = session.writeOutput(new byte[]{2}, 0, 1);
        assertTrue(accepted.accepted());
    }

    @Test
    void stopAndStartControlCharsDriveFlow() {
        PtySession session = new PtySessionManager().createSession(options());
        List<FlowState> transitions = new ArrayList<>();
        session.setOutputFlowListener((state, size) -> transitions.add(state));

        session.writeInput(new byte[]{0x13});
        assertEquals(FlowState.STOPPED, session.getFlowState());

        WriteResult rejected = session.writeOutput(new byte[]{1}, 0, 1);
        assertEquals(WriteRejectReason.FLOW_STOPPED, rejected.reason());

        session.writeInput(new byte[]{0x11});
        assertEquals(FlowState.RUNNING, session.getFlowState());

        WriteResult accepted = session.writeOutput(new byte[]{1}, 0, 1);
        assertTrue(accepted.accepted());
        assertEquals(List.of(FlowState.STOPPED, FlowState.RUNNING), transitions);
    }

    @Test
    void outputBytesRemainInOrderAcrossReads() throws Exception {
        SessionOptions options = options();
        options.setOutputCapacity(100);
        options.setHighWaterMark(90);
        options.setLowWaterMark(10);
        PtySession session = new PtySessionManager().createSession(options);

        byte[] payload = new byte[100];
        for (int index = 0; index < payload.length; index++) {
            payload[index] = (byte) index;
        }
        WriteResult result = session.writeOutput(payload, 0, payload.length);
        assertTrue(result.accepted());

        byte[] collected = new byte[100];
        int total = 0;
        while (total < 100) {
            int read = session.readOutput(collected, total, 100 - total, 1, TimeUnit.SECONDS);
            total += read;
        }
        assertArrayEquals(payload, collected);
    }

    @Test
    void manualStopStaysEffectiveUntilStartEvenBelowLowWater() {
        SessionOptions options = options();
        PtySession session = new PtySessionManager().createSession(options);

        session.writeOutput(new byte[8], 0, 8);
        assertEquals(FlowState.STOPPED, session.getFlowState());
        session.writeInput(new byte[]{0x13});

        byte[] drained = new byte[8];
        try {
            session.readOutput(drained, 0, 8, 1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        assertEquals(FlowState.STOPPED, session.getFlowState(),
                "manual stop keeps flow halted even after auto condition clears");
        assertEquals(WriteRejectReason.FLOW_STOPPED,
                session.writeOutput(new byte[]{1}, 0, 1).reason());

        session.writeInput(new byte[]{0x11});
        assertEquals(FlowState.RUNNING, session.getFlowState());
        assertTrue(session.writeOutput(new byte[]{1}, 0, 1).accepted());
    }
}
