package com.github.guoyyit.ptylinedisciplinejava.tests;

import com.github.guoyyit.ptylinedisciplinejava.pty.BufferLimits;
import com.github.guoyyit.ptylinedisciplinejava.pty.LineDisciplineMode;
import com.github.guoyyit.ptylinedisciplinejava.pty.OverflowPolicy;
import com.github.guoyyit.ptylinedisciplinejava.pty.PtySession;
import com.github.guoyyit.ptylinedisciplinejava.pty.PtySessionManager;
import com.github.guoyyit.ptylinedisciplinejava.pty.ReadResult;
import com.github.guoyyit.ptylinedisciplinejava.pty.SessionConfig;
import com.github.guoyyit.ptylinedisciplinejava.pty.SessionHolder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyTest {

    private PtySessionManager manager;

    @BeforeEach
    void setUp() {
        manager = PtySessionManager.create();
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    private PtySession newSession(int capacity, OverflowPolicy policy) {
        SessionHolder holder = manager.createHolder("holder-conc-" + capacity);
        return holder.createSession(new SessionConfig(
                LineDisciplineMode.RAW,
                com.github.guoyyit.ptylinedisciplinejava.pty.ControlCharacters.defaults(),
                new BufferLimits(capacity, policy),
                new BufferLimits(capacity, policy),
                capacity, capacity / 2, 1, 0, 64,
                com.github.guoyyit.ptylinedisciplinejava.pty.SignalDeliveryPolicy.QUEUE));
    }

    @Test
    void concurrentWritersAndReadersPreserveAllBytes() throws Exception {
        PtySession session = newSession(2048, OverflowPolicy.BLOCK);
        int writers = 4;
        int perWriter = 500;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 2);
        CountDownLatch start = new CountDownLatch(1);

        for (int w = 0; w < writers; w++) {
            final byte tag = (byte) ('A' + w);
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perWriter; i++) {
                        session.writeOutput(new byte[]{tag, tag, tag, tag},
                                5000);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        long total = (long) writers * perWriter * 4;
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        Runnable readerTask = () -> {
            try {
                while (received.size() < total) {
                    ReadResult r = session.readOutput(128, 5000);
                    synchronized (received) {
                        received.writeBytes(r.data());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        pool.submit(readerTask);
        pool.submit(readerTask);

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS),
                "concurrent transfer did not finish in time");

        byte[] data = received.toByteArray();
        assertEquals(total, data.length);
        int[] counts = new int[writers];
        for (byte b : data) {
            counts[b - 'A']++;
        }
        for (int w = 0; w < writers; w++) {
            assertEquals(perWriter * 4, counts[w],
                    "writer " + (char) ('A' + w) + " byte count");
        }
    }

    @Test
    void outputBufferNeverExceedsCapacity() throws Exception {
        int capacity = 256;
        PtySession session = newSession(capacity, OverflowPolicy.BLOCK);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch stop = new CountDownLatch(1);

        for (int i = 0; i < 4; i++) {
            pool.submit(() -> {
                try {
                    byte[] chunk = new byte[37];
                    while (stop.getCount() > 0) {
                        session.writeOutput(chunk, 100);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        pool.submit(() -> {
            try {
                while (stop.getCount() > 0) {
                    assertTrue(session.outputBufferSize() <= capacity);
                    session.readOutput(64, 50);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread.sleep(600);
        stop.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(session.outputBufferSize() <= capacity);
    }

    @Test
    void inputBufferHonorsCapacityUnderDiscardPolicy() throws Exception {
        int capacity = 64;
        PtySession session = newSession(capacity, OverflowPolicy.DISCARD);
        var result = session.writeInput(new byte[capacity + 50], 0);
        assertEquals(capacity, result.bytesAccepted());
        assertEquals(50, result.bytesDiscarded());
        assertEquals(capacity, session.inputBufferSize());

        byte[] drained = session.readInput(capacity, 100).data();
        assertEquals(capacity, drained.length);
        assertEquals(0, new String(drained, StandardCharsets.UTF_8).length()
                - drained.length);
    }

    @Test
    void concurrentCanonicalWritersProduceIntactLines() throws Exception {
        SessionHolder holder = manager.createHolder("holder-lines");
        PtySession session = holder.createSession(new SessionConfig(
                LineDisciplineMode.CANONICAL,
                com.github.guoyyit.ptylinedisciplinejava.pty.ControlCharacters.defaults(),
                new BufferLimits(4096, OverflowPolicy.BLOCK),
                new BufferLimits(4096, OverflowPolicy.BLOCK),
                4096, 1024, 1, 0, 64,
                com.github.guoyyit.ptylinedisciplinejava.pty.SignalDeliveryPolicy.QUEUE));

        int threads = 4;
        int linesPerThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            final int tag = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < linesPerThread; i++) {
                        String line = String.format("w%d-n%d\n", tag, i);
                        session.writeInput(line.getBytes(StandardCharsets.UTF_8),
                                5000);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();

        int totalLines = threads * linesPerThread;
        int seen = 0;
        long deadline = System.currentTimeMillis() + 20000;
        while (seen < totalLines && System.currentTimeMillis() < deadline) {
            ReadResult r = session.readInput(256, 5000);
            String line = new String(r.data(), StandardCharsets.UTF_8);
            assertTrue(line.endsWith("\n"), "line must be delivered intact: " + line);
            assertTrue(line.matches("w[0-3]-n[0-9]+\\n"),
                    "line must come from a single writer: " + line);
            seen++;
        }
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
        assertEquals(totalLines, seen);
    }
}
