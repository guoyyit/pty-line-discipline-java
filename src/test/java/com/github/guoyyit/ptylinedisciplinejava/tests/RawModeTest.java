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
    void callerTimeoutReturnsBufferedBytesWhenVminUnmetAndVtimeZero() throws Exception {
        PtySession s = manager.createHolder("h-vmin-timeout")
                .createSession(rawConfig(5, 0));
        s.writeInput(new byte[]{'a', 'b'}, 1000);

        long start = System.currentTimeMillis();
        var result = s.readInput(64, 80);
        long elapsed = System.currentTimeMillis() - start;

        assertArrayEquals(new byte[]{'a', 'b'}, result.data());
        assertTrue(result.timedOut(), "caller timeout ends the wait early");
        assertTrue(elapsed >= 50, "should still wait for the timeout: " + elapsed);
        assertTrue(elapsed < 1000, "must not block until VMIN arrives: " + elapsed);
    }

    @Test
    void callerTimeoutZeroWaitsForVminWhenVtimeZero() throws Exception {
        PtySession s = manager.createHolder("h-vmin-no-timeout")
                .createSession(rawConfig(3, 0));
        s.writeInput(new byte[]{'a'}, 1000);
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(80);
                s.writeInput(new byte[]{'b', 'c'}, 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        writer.start();
        long start = System.currentTimeMillis();
        var result = s.readInput(64, 0);
        writer.join();
        assertArrayEquals(new byte[]{'a', 'b', 'c'}, result.data());
        assertFalse(result.timedOut());
        assertTrue(System.currentTimeMillis() - start >= 60);
    }

    @Test
    void callerTimeoutCapsInterByteTimerWhenVminAndVtimePositive() throws Exception {
        PtySession s = manager.createHolder("h-vmin-vtime-cap")
                .createSession(rawConfig(5, 5000));
        s.writeInput(new byte[]{'a'}, 1000);

        long start = System.currentTimeMillis();
        var result = s.readInput(64, 80);
        long elapsed = System.currentTimeMillis() - start;

        assertArrayEquals(new byte[]{'a'}, result.data());
        assertTrue(result.timedOut());
        assertTrue(elapsed < 1000, "caller timeout caps the inter-byte timer: " + elapsed);
    }

    private record ReadResultProbe(int bytes, boolean timedOut) {
    }

    private static ReadResultProbe read(PtySession s) throws InterruptedException {
        var r = s.readInput(64, 0);
        return new ReadResultProbe(r.bytesRead(), r.timedOut());
    }
}
