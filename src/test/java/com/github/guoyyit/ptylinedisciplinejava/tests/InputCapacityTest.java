package com.github.guoyyit.ptylinedisciplinejava.tests;

import com.github.guoyyit.ptylinedisciplinejava.pty.BufferLimits;
import com.github.guoyyit.ptylinedisciplinejava.pty.ControlCharacters;
import com.github.guoyyit.ptylinedisciplinejava.pty.Defaults;
import com.github.guoyyit.ptylinedisciplinejava.pty.LineDisciplineMode;
import com.github.guoyyit.ptylinedisciplinejava.pty.OverflowPolicy;
import com.github.guoyyit.ptylinedisciplinejava.pty.PtySession;
import com.github.guoyyit.ptylinedisciplinejava.pty.PtySessionManager;
import com.github.guoyyit.ptylinedisciplinejava.pty.ReadResult;
import com.github.guoyyit.ptylinedisciplinejava.pty.SessionConfig;
import com.github.guoyyit.ptylinedisciplinejava.pty.SessionHolder;
import com.github.guoyyit.ptylinedisciplinejava.pty.SignalDeliveryPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputCapacityTest {

    private PtySessionManager manager;

    @BeforeEach
    void setUp() {
        manager = PtySessionManager.create();
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    private PtySession canonicalSession(int capacity, OverflowPolicy policy) {
        SessionHolder holder =
                manager.createHolder("holder-cap-" + capacity + "-" + policy);
        SessionConfig config = new SessionConfig(
                LineDisciplineMode.CANONICAL,
                ControlCharacters.defaults(),
                new BufferLimits(capacity, policy),
                new BufferLimits(64, OverflowPolicy.BLOCK),
                64, 32, 1, 0, 64,
                SignalDeliveryPolicy.QUEUE);
        return holder.createSession(config);
    }

    @Test
    void discardPolicyRejectsWholeLineLongerThanCapacity() throws Exception {
        PtySession session = canonicalSession(8, OverflowPolicy.DISCARD);
        byte[] line = "0123456789012345678\n".getBytes(StandardCharsets.UTF_8);

        WriteResultProbe result = WriteResultProbe.of(session.writeInput(line, 0));

        assertEquals(0, result.accepted, "no partial line may be buffered: " + result);
        assertEquals(line.length, result.discarded, "whole line is discarded: " + result);
        assertFalse(result.timedOut);
        assertTrue(session.inputBufferSize() <= 8,
                "occupancy must not exceed capacity: " + session.inputBufferSize());

        ReadResult read = session.readInput(64, 50);
        assertEquals(0, read.bytesRead(), "no broken line is delivered");
        assertTrue(read.timedOut(), "reader keeps waiting for a complete line");
    }

    @Test
    void discardPolicyPreservesExistingDataWhenLineDoesNotFit() throws Exception {
        PtySession session = canonicalSession(8, OverflowPolicy.DISCARD);
        assertAccepted(session.writeInput("abcd\n".getBytes(StandardCharsets.UTF_8), 0), 5);

        byte[] tooLong = "01234567\n".getBytes(StandardCharsets.UTF_8);
        WriteResultProbe result =
                WriteResultProbe.of(session.writeInput(tooLong, 0));
        assertEquals(0, result.accepted);
        assertEquals(tooLong.length, result.discarded);

        assertArrayEquals("abcd\n".getBytes(StandardCharsets.UTF_8),
                session.readInput(64, 50).data());
        assertAccepted(session.writeInput("xy\n".getBytes(StandardCharsets.UTF_8), 0), 3);
        assertArrayEquals("xy\n".getBytes(StandardCharsets.UTF_8),
                session.readInput(64, 50).data());
    }

    @Test
    void discardPolicyAcceptsFirstLineAndRejectsLaterOverflowLine() throws Exception {
        PtySession session = canonicalSession(8, OverflowPolicy.DISCARD);
        byte[] chunk = "ab\n0123456789\n".getBytes(StandardCharsets.UTF_8);

        WriteResultProbe result = WriteResultProbe.of(session.writeInput(chunk, 0));

        assertEquals(3, result.accepted, "\"ab\\n\" is delivered: " + result);
        assertEquals(chunk.length - 3, result.discarded, "overflow line is discarded");
        assertArrayEquals("ab\n".getBytes(StandardCharsets.UTF_8),
                session.readInput(64, 50).data());
    }

    @Test
    void blockPolicyNeverOverwritesAndReturnsOnTimeout() throws Exception {
        PtySession session = canonicalSession(8, OverflowPolicy.BLOCK);
        byte[] line = "0123456789012345678\n".getBytes(StandardCharsets.UTF_8);

        long start = System.currentTimeMillis();
        WriteResultProbe result = WriteResultProbe.of(session.writeInput(line, 120));
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(result.timedOut, "write returns after the caller timeout: " + result);
        assertEquals(0, result.discarded, "BLOCK never silently drops bytes");
        assertTrue(result.accepted <= 8,
                "accepted prefix stays within capacity: " + result.accepted);
        assertTrue(session.inputBufferSize() <= 8,
                "occupancy must not exceed capacity: " + session.inputBufferSize());
        assertTrue(elapsed >= 90, "must wait up to the timeout: " + elapsed);
        assertTrue(elapsed < 1000, "must not block far beyond the timeout: " + elapsed);

        ReadResult read = session.readInput(64, 50);
        assertEquals(0, read.bytesRead(), "no broken line is delivered");
        assertFalse(read.hangup());
    }

    @Test
    void blockPolicyCompletesLineAsSoonAsReaderFreesSpace() throws Exception {
        PtySession session = canonicalSession(8, OverflowPolicy.BLOCK);
        assertAccepted(session.writeInput("abcd\n".getBytes(StandardCharsets.UTF_8), 0), 5);

        byte[] line = "xy\n".getBytes(StandardCharsets.UTF_8);
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(60);
                session.writeInput(line, 2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        writer.start();

        assertArrayEquals("abcd\n".getBytes(StandardCharsets.UTF_8),
                session.readInput(64, 1000).data());
        writer.join(3000);
        assertFalse(writer.isAlive(), "writer finishes once space is available");
        assertArrayEquals("xy\n".getBytes(StandardCharsets.UTF_8),
                session.readInput(64, 1000).data());
    }

    @Test
    void eraseFreesReservedCapacityNearLimit() throws Exception {
        PtySession session = canonicalSession(8, OverflowPolicy.BLOCK);
        assertAccepted(session.writeInput("abcd\n".getBytes(StandardCharsets.UTF_8), 0), 5);
        String erase = String.valueOf((char) Defaults.VERASE);

        Thread writer = new Thread(() -> {
            try {
                session.writeInput(
                        ("efghiz" + erase + "\n").getBytes(StandardCharsets.UTF_8), 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        writer.start();

        Thread.sleep(150);
        assertArrayEquals("abcd\n".getBytes(StandardCharsets.UTF_8),
                session.readInput(64, 500).data());
        writer.join(3000);
        assertFalse(writer.isAlive(), "writer completes after erase frees a slot");
        assertTrue(session.inputBufferSize() <= 8);
        assertArrayEquals("efghi\n".getBytes(StandardCharsets.UTF_8),
                session.readInput(64, 500).data());
    }

    @Test
    void killDiscardsPendingLineAndFreesCapacity() throws Exception {
        PtySession session = canonicalSession(8, OverflowPolicy.DISCARD);
        assertAccepted(session.writeInput("abcd\n".getBytes(StandardCharsets.UTF_8), 0), 5);
        String kill = String.valueOf((char) Defaults.VKILL);
        session.writeInput(
                ("efghijkl" + kill + "z\n").getBytes(StandardCharsets.UTF_8), 0);

        assertTrue(session.inputBufferSize() <= 8);
        assertArrayEquals("abcd\n".getBytes(StandardCharsets.UTF_8),
                session.readInput(64, 100).data());
        assertAccepted(session.writeInput("z\n".getBytes(StandardCharsets.UTF_8), 0), 2);
        assertArrayEquals("z\n".getBytes(StandardCharsets.UTF_8),
                session.readInput(64, 100).data());
    }

    @Test
    void concurrentCanonicalWritersNeverExceedCapacity() throws Exception {
        int capacity = 16;
        PtySession session = canonicalSession(capacity, OverflowPolicy.DISCARD);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch start = new CountDownLatch(1);

        for (int t = 0; t < threads; t++) {
            final int tag = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 100; i++) {
                        String line = String.format("w%d-very-long-line-%d\n", tag, i);
                        session.writeInput(line.getBytes(StandardCharsets.UTF_8), 0);
                        assertTrue(session.inputBufferSize() <= capacity);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        pool.submit(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    ReadResult read = session.readInput(64, 20);
                    byte[] data = read.data();
                    if (data.length > 0) {
                        String text = new String(data, StandardCharsets.UTF_8);
                        assertTrue(text.endsWith("\n"), "only intact lines are read: " + text);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        start.countDown();
        Thread.sleep(1500);
        pool.shutdownNow();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        assertTrue(session.inputBufferSize() <= capacity);
    }

    @Test
    void concurrentBlockWritersHonorTimeoutAndCapacity() throws Exception {
        int capacity = 8;
        PtySession session = canonicalSession(capacity, OverflowPolicy.BLOCK);
        assertAccepted(session.writeInput("abcd\n".getBytes(StandardCharsets.UTF_8), 0), 5);

        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        java.util.List<WriteResultProbe> outcomes =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    outcomes.add(WriteResultProbe.of(session.writeInput(
                            "xyz-long\n".getBytes(StandardCharsets.UTF_8), 120)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        long began = System.currentTimeMillis();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS),
                "no writer blocks indefinitely with a positive timeout");
        long elapsed = System.currentTimeMillis() - began;
        assertTrue(elapsed < 5000, "writers return near their timeout: " + elapsed);
        assertEquals(threads, outcomes.size());
        for (WriteResultProbe outcome : outcomes) {
            assertEquals(0, outcome.discarded, "BLOCK never discards: " + outcome);
            assertTrue(outcome.timedOut, "line could not fit: " + outcome);
            assertTrue(outcome.accepted <= capacity);
        }
        assertTrue(session.inputBufferSize() <= capacity);
        assertArrayEquals("abcd\n".getBytes(StandardCharsets.UTF_8),
                session.readInput(64, 100).data());
    }

    private static void assertAccepted(com.github.guoyyit.ptylinedisciplinejava.pty.WriteResult r,
                                       int expected) {
        assertEquals(expected, r.bytesAccepted(), r::toString);
        assertEquals(0, r.bytesDiscarded(), r::toString);
    }

    private record WriteResultProbe(int accepted, int discarded, boolean timedOut) {
        static WriteResultProbe of(com.github.guoyyit.ptylinedisciplinejava.pty.WriteResult r) {
            return new WriteResultProbe(r.bytesAccepted(), r.bytesDiscarded(), r.timedOut());
        }
    }
}
