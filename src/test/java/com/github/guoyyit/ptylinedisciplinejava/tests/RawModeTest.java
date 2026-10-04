package com.github.guoyyit.ptylinedisciplinejava.tests;

import com.github.guoyyit.ptylinedisciplinejava.pty.PtySession;
import com.github.guoyyit.ptylinedisciplinejava.pty.PtySessionManager;
import com.github.guoyyit.ptylinedisciplinejava.pty.SessionConfig;
import com.github.guoyyit.ptylinedisciplinejava.pty.SessionHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawModeTest {

    private PtySessionManager manager;
    private PtySession session;

    @BeforeEach
    void setUp() {
        manager = PtySessionManager.create();
        SessionHolder holder = manager.createHolder("holder-raw");
        session = holder.createSession(rawConfig(1, 0));
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    private static SessionConfig rawConfig(int vmin, int vtimeMillis) {
        return SessionConfig.defaults()
                .withMode(com.github.guoyyit.ptylinedisciplinejava.pty.LineDisciplineMode.RAW)
                .withVminVtime(vmin, vtimeMillis);
    }

    @Test
    void bytesAreDeliveredImmediatelyWithoutLineBuffering() throws Exception {
        session.writeInput(new byte[]{'x'}, 1000);
        assertArrayEquals(new byte[]{'x'}, session.readInput(64, 500).data());
    }

    @Test
    void vminZeroVtimeZeroReturnsImmediately() throws Exception {
        PtySession s = manager.createHolder("h2").createSession(rawConfig(0, 0));
        ReadResultProbe result = read(s);
        assertEquals(0, result.bytes);
        assertFalse(result.timedOut);
    }

    @Test
    void vminZeroVtimePositiveTimesOutWhenEmpty() throws Exception {
        PtySession s = manager.createHolder("h3").createSession(rawConfig(0, 80));
        long start = System.currentTimeMillis();
        var result = s.readInput(64, 5000);
        long elapsed = System.currentTimeMillis() - start;
        assertEquals(0, result.bytesRead());
        assertTrue(result.timedOut());
        assertTrue(elapsed >= 60, "should wait ~VTIME: " + elapsed);
    }

    @Test
    void vminZeroVtimePositiveReturnsAsSoonAsDataArrives() throws Exception {
        PtySession s = manager.createHolder("h4").createSession(rawConfig(0, 5000));
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(100);
                s.writeInput(new byte[]{'q'}, 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        writer.start();
        long start = System.currentTimeMillis();
        var result = s.readInput(64, 5000);
        writer.join();
        assertArrayEquals(new byte[]{'q'}, result.data());
        assertTrue(System.currentTimeMillis() - start < 2000);
    }

    @Test
    void vminPositiveBlocksUntilEnoughBytes() throws Exception {
        PtySession s = manager.createHolder("h-vmin3").createSession(rawConfig(3, 0));
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(80);
                s.writeInput(new byte[]{'a'}, 1000);
                Thread.sleep(80);
                s.writeInput(new byte[]{'b', 'c'}, 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        writer.start();
        var result = s.readInput(3, 5000);
        writer.join();
        assertArrayEquals(new byte[]{'a', 'b', 'c'}, result.data());
    }

    @Test
    void vminPositiveWithVtimeReturnsAvailableAfterIdleTimeout() throws Exception {
        PtySession s = manager.createHolder("h5").createSession(rawConfig(4, 120));
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(80);
                s.writeInput(new byte[]{'a', 'b'}, 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        writer.start();
        long start = System.currentTimeMillis();
        var result = s.readInput(64, 5000);
        writer.join();
        long elapsed = System.currentTimeMillis() - start;
        assertArrayEquals(new byte[]{'a', 'b'}, result.data());
        assertTrue(elapsed >= 150, "should idle out after ~VTIME: " + elapsed);
    }

    @Test
    void callerTimeoutStillAppliesInRawMode() throws Exception {
        long start = System.currentTimeMillis();
        var result = session.readInput(64, 60);
        assertTrue(result.timedOut());
        assertTrue(System.currentTimeMillis() - start >= 40);
    }

    @Test
    void callerTimeoutAppliesWhenVminPartiallyFilledAndVtimeZero() throws Exception {
        PtySession s = manager.createHolder("h-vmin-partial").createSession(rawConfig(5, 0));
        s.writeInput(new byte[]{'a', 'b'}, 1000);

        long start = System.currentTimeMillis();
        var result = s.readInput(64, 150);
        long elapsed = System.currentTimeMillis() - start;

        // Must return near the caller timeout, not block until VMIN is met.
        assertTrue(elapsed >= 120, "should wait for the caller timeout: " + elapsed);
        assertTrue(elapsed < 3000, "caller timeout must bound the wait: " + elapsed);
        // The partially buffered bytes are delivered, flagged as a timeout.
        assertArrayEquals(new byte[]{'a', 'b'}, result.data());
        assertTrue(result.timedOut());
        assertFalse(result.eof());
        assertFalse(result.hangup());
    }

    @Test
    void callerTimeoutWithEmptyBufferAndVtimeZeroReturnsNoData() throws Exception {
        PtySession s = manager.createHolder("h-vmin-empty").createSession(rawConfig(4, 0));
        long start = System.currentTimeMillis();
        var result = s.readInput(64, 120);
        long elapsed = System.currentTimeMillis() - start;
        assertEquals(0, result.bytesRead());
        assertTrue(result.timedOut());
        assertTrue(elapsed >= 90, "should wait for the caller timeout: " + elapsed);
        assertTrue(elapsed < 3000, "caller timeout must bound the wait: " + elapsed);
    }

    @Test
    void callerTimeoutBeatsLongVtimeIdleTimer() throws Exception {
        PtySession s = manager.createHolder("h-vmin-vtime-caller")
                .createSession(rawConfig(10, 5000));
        s.writeInput(new byte[]{'x', 'y', 'z'}, 1000);

        long start = System.currentTimeMillis();
        var result = s.readInput(64, 150);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 2000, "caller timeout must win over VTIME: " + elapsed);
        assertArrayEquals(new byte[]{'x', 'y', 'z'}, result.data());
        assertTrue(result.timedOut());
    }

    @Test
    void callerTimeoutBeatsLongVtimeWhenBufferEmpty() throws Exception {
        PtySession s = manager.createHolder("h-v0-vtime-caller")
                .createSession(rawConfig(0, 5000));
        long start = System.currentTimeMillis();
        var result = s.readInput(64, 120);
        long elapsed = System.currentTimeMillis() - start;
        assertEquals(0, result.bytesRead());
        assertTrue(result.timedOut());
        assertTrue(elapsed < 2000, "caller timeout must win over VTIME: " + elapsed);
    }

    @Test
    void satisfiedVminIsNotReportedAsTimeout() throws Exception {
        PtySession s = manager.createHolder("h-vmin-met").createSession(rawConfig(2, 0));
        s.writeInput(new byte[]{'p', 'q'}, 1000);
        var result = s.readInput(64, 1000);
        assertArrayEquals(new byte[]{'p', 'q'}, result.data());
        assertFalse(result.timedOut());
    }

    private record ReadResultProbe(int bytes, boolean timedOut) {
    }

    private static ReadResultProbe read(PtySession s) throws InterruptedException {
        var r = s.readInput(64, 0);
        return new ReadResultProbe(r.bytesRead(), r.timedOut());
    }
}
