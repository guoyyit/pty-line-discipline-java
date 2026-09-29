package com.github.guoyyit.ptylinedisciplinejava.pty;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawModeTest {

    private SessionOptions options(int vmin, int vtime) {
        SessionOptions options = new SessionOptions();
        options.setMode(LineMode.RAW);
        options.setVMIN(vmin);
        options.setVTIME(vtime);
        return options;
    }

    @Test
    void deliversBytesImmediatelyWithoutLineEnd() throws Exception {
        PtySession session = new PtySessionManager().createSession(options(1, 0));

        WriteResult result = session.writeInput("abc".getBytes());

        assertTrue(result.accepted());
        byte[] buffer = new byte[8];
        int read = session.readInput(buffer, 0, buffer.length, -1, TimeUnit.MILLISECONDS);
        assertEquals(3, read);
        assertArrayEquals("abc".getBytes(), Arrays.copyOf(buffer, read));
    }

    @Test
    void vminZeroWithTimeoutReturnsZeroWhenIdle() throws Exception {
        PtySession session = new PtySessionManager().createSession(options(0, 1));

        byte[] buffer = new byte[8];
        long start = System.nanoTime();
        int read = session.readInput(buffer, 0, buffer.length, 2, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(0, read);
        assertTrue(elapsedMs >= 80L, "expected VTIME delay, was " + elapsedMs + "ms");
        assertTrue(elapsedMs < 1000L, "should return after ~1 tenth, was " + elapsedMs + "ms");
    }

    @Test
    void vminZeroReturnsAvailableBytesImmediately() throws Exception {
        PtySession session = new PtySessionManager().createSession(options(0, 10));

        session.writeInput(new byte[]{42});

        byte[] buffer = new byte[8];
        int read = session.readInput(buffer, 0, buffer.length, 5, TimeUnit.SECONDS);
        assertEquals(1, read);
        assertEquals(42, buffer[0]);
    }

    @Test
    void vminPositiveTimeoutReturnsBytesAlreadyPresent() throws Exception {
        PtySession session = new PtySessionManager().createSession(options(3, 0));

        session.writeInput(new byte[]{1});
        byte[] buffer = new byte[8];
        int peek = session.readInput(buffer, 0, buffer.length, 50, TimeUnit.MILLISECONDS);
        assertEquals(1, peek, "caller timeout returns bytes already present");
        assertEquals(1, buffer[0]);
    }

    @Test
    void vminAndVtimeReturnsEarlyOnInterByteTimeout() throws Exception {
        PtySession session = new PtySessionManager().createSession(options(5, 2));

        session.writeInput(new byte[]{1, 2});

        byte[] buffer = new byte[8];
        long start = System.nanoTime();
        int read = session.readInput(buffer, 0, buffer.length, 5, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(2, read);
        assertArrayEquals(new byte[]{1, 2}, Arrays.copyOf(buffer, read));
        assertTrue(elapsedMs >= 150L, "expected VTIME wait, was " + elapsedMs + "ms");
        assertTrue(elapsedMs < 1000L, "should return after ~2 tenths, was " + elapsedMs + "ms");
    }

    @Test
    void vminBlocksUntilEnoughBytesArrive() throws Exception {
        PtySession session = new PtySessionManager().createSession(options(3, 0));
        byte[] buffer = new byte[8];

        Thread feeder = new Thread(() -> {
            try {
                Thread.sleep(80L);
                session.writeInput(new byte[]{7, 8, 9});
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        feeder.start();

        int read = session.readInput(buffer, 0, buffer.length, 5, TimeUnit.SECONDS);
        assertEquals(3, read);
        assertArrayEquals(new byte[]{7, 8, 9}, Arrays.copyOf(buffer, read));
        feeder.join(1000L);
    }

    @Test
    void vminChangeTakesEffectImmediately() throws Exception {
        PtySession session = new PtySessionManager().createSession(options(3, 0));
        session.setReadSemantics(1, 0);

        session.writeInput(new byte[]{1});

        byte[] buffer = new byte[8];
        int read = session.readInput(buffer, 0, buffer.length, 1, TimeUnit.SECONDS);
        assertEquals(1, read);
    }

    @Test
    void switchingToRawDiscardsPendingEditBufferButKeepsQueuedLines() throws Exception {
        PtySession session = new PtySessionManager().createSession(new SessionOptions());

        session.writeInput("done\n".getBytes());
        session.writeInput("typing".getBytes());
        assertEquals(6, session.canonicalBufferSize());

        session.setMode(LineMode.RAW);

        assertEquals(0, session.canonicalBufferSize());
        byte[] buffer = new byte[16];
        int read = session.readInput(buffer, 0, buffer.length, -1, TimeUnit.MILLISECONDS);
        assertArrayEquals("done\n".getBytes(), Arrays.copyOf(buffer, read));

        session.writeInput(new byte[]{9});
        int rawRead = session.readInput(buffer, 0, buffer.length, 1, TimeUnit.SECONDS);
        assertEquals(1, rawRead);
        assertEquals(9, buffer[0]);
    }
}
