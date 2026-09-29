package com.github.guoyyit.ptylinedisciplinejava.pty;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapacityOverflowTest {

    private SessionOptions options(LineMode mode, OverflowPolicy inputPolicy,
                                   OverflowPolicy outputPolicy) {
        SessionOptions options = new SessionOptions();
        options.setMode(mode);
        options.setInputCapacity(4);
        options.setOutputCapacity(4);
        options.setHighWaterMark(4);
        options.setLowWaterMark(0);
        options.setInputOverflowPolicy(inputPolicy);
        options.setOutputOverflowPolicy(outputPolicy);
        return options;
    }

    @Test
    void rawInputBlockPolicyRejectsWhenFull() {
        PtySession session = new PtySessionManager()
                .createSession(options(LineMode.RAW, OverflowPolicy.BLOCK, OverflowPolicy.BLOCK));

        WriteResult first = session.writeInput(new byte[4]);
        assertTrue(first.accepted());
        assertEquals(4, first.bytesAccepted());

        WriteResult second = session.writeInput(new byte[]{1});
        assertFalse(second.accepted());
        assertEquals(WriteRejectReason.BUFFER_FULL, second.reason());
        assertEquals(4, session.inputQueueSize());
    }

    @Test
    void rawInputDropPolicyAcceptsPartialAndReportsCount() {
        PtySession session = new PtySessionManager()
                .createSession(options(LineMode.RAW, OverflowPolicy.DROP, OverflowPolicy.BLOCK));

        WriteResult result = session.writeInput(new byte[10]);

        assertTrue(result.accepted());
        assertEquals(4, result.bytesAccepted());
        assertEquals(4, session.inputQueueSize());
    }

    @Test
    void outputBlockPolicyRejectsAtCapacity() {
        PtySession session = new PtySessionManager()
                .createSession(options(LineMode.RAW, OverflowPolicy.BLOCK, OverflowPolicy.BLOCK));

        assertTrue(session.writeOutput(new byte[4], 0, 4).accepted());
        WriteResult overflow = session.writeOutput(new byte[]{9}, 0, 1);
        assertFalse(overflow.accepted());
        assertEquals(WriteRejectReason.FLOW_STOPPED, overflow.reason());
        assertEquals(4, session.outputQueueSize());
    }

    @Test
    void outputDropPolicyReportsAcceptedBytesWithoutGrowingBeyondCapacity() {
        PtySession session = new PtySessionManager()
                .createSession(options(LineMode.RAW, OverflowPolicy.BLOCK, OverflowPolicy.DROP));

        WriteResult result = session.writeOutput(new byte[10], 0, 10);

        assertTrue(result.accepted());
        assertEquals(4, result.bytesAccepted());
        assertEquals(4, session.outputQueueSize());
    }

    @Test
    void blockingInputWriteWaitsUntilReaderDrains() throws Exception {
        PtySession session = new PtySessionManager()
                .createSession(options(LineMode.RAW, OverflowPolicy.BLOCK, OverflowPolicy.BLOCK));

        assertEquals(4, session.writeInput(new byte[4]).bytesAccepted());

        Thread writer = new Thread(() -> {
            try {
                WriteResult result = session.writeInput(new byte[]{5, 6}, 0, 2, 2, TimeUnit.SECONDS);
                org.junit.jupiter.api.Assertions.assertTrue(result.accepted());
                org.junit.jupiter.api.Assertions.assertEquals(2, result.bytesAccepted());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        writer.start();

        Thread.sleep(80L);
        byte[] buffer = new byte[4];
        int read = session.readInput(buffer, 0, 4, 1, TimeUnit.SECONDS);
        assertEquals(4, read);
        writer.join(2000L);
        assertFalse(writer.isAlive());
        assertEquals(2, session.inputQueueSize());
    }

    @Test
    void writesAfterCloseReportClosed() {
        PtySession session = new PtySessionManager()
                .createSession(options(LineMode.RAW, OverflowPolicy.BLOCK, OverflowPolicy.BLOCK));
        session.close();

        assertEquals(WriteRejectReason.CLOSED, session.writeInput(new byte[]{1}).reason());
        assertEquals(WriteRejectReason.CLOSED, session.writeOutput(new byte[]{1}, 0, 1).reason());
    }
}
