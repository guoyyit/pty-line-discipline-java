package com.github.guoyyit.ptylinedisciplinejava.pty;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyTest {

    @Test
    void multipleOutputWritersAndReaderPreserveEveryByte() throws Exception {
        SessionOptions options = new SessionOptions();
        options.setMode(LineMode.RAW);
        options.setOutputCapacity(64);
        options.setHighWaterMark(64);
        options.setLowWaterMark(8);
        options.setOutputOverflowPolicy(OverflowPolicy.BLOCK);
        PtySession session = new PtySessionManager().createSession(options);

        int writerCount = 4;
        int perWriter = 100;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(writerCount);

        for (int writer = 0; writer < writerCount; writer++) {
            final byte marker = (byte) (writer + 1);
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    byte[] chunk = new byte[perWriter];
                    for (int index = 0; index < perWriter; index++) {
                        chunk[index] = marker;
                    }
                    int written = 0;
                    while (written < perWriter) {
                        WriteResult result = session.writeOutput(chunk, written,
                                perWriter - written, 5, TimeUnit.SECONDS);
                        if (result.accepted()) {
                            written += result.bytesAccepted();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });
            thread.start();
        }

        start.countDown();

        Map<Byte, AtomicInteger> counts = new ConcurrentHashMap<>();
        AtomicInteger duplicates = new AtomicInteger();
        Thread reader = new Thread(() -> {
            byte[] buffer = new byte[32];
            int total = 0;
            int expected = writerCount * perWriter;
            try {
                while (total < expected) {
                    int read = session.readOutput(buffer, 0, buffer.length, 5, TimeUnit.SECONDS);
                    for (int index = 0; index < read; index++) {
                        counts.computeIfAbsent(buffer[index], key -> new AtomicInteger()).incrementAndGet();
                    }
                    total += read;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        reader.start();

        assertTrue(finished.await(10, TimeUnit.SECONDS), "writers should finish");
        reader.join(10_000L);
        assertTrue(!reader.isAlive(), "reader should consume every byte");

        for (int writer = 1; writer <= writerCount; writer++) {
            assertEquals(perWriter, counts.get((byte) writer).get());
        }
        assertEquals(0, duplicates.get());
        assertEquals(writerCount * perWriter,
                counts.values().stream().mapToInt(AtomicInteger::get).sum());
    }

    @Test
    void closeReleasesBlockedReadersAndWriters() throws Exception {
        SessionOptions options = new SessionOptions();
        options.setMode(LineMode.RAW);
        options.setInputCapacity(8);
        PtySession session = new PtySessionManager().createSession(options);

        Runnable readTask = () -> {
            try {
                session.readInput(new byte[4], 0, 4, 10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Runnable writeTask = () -> {
            try {
                session.writeInput(new byte[32], 0, 32, 10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        Thread reader = new Thread(readTask);
        reader.start();

        session.writeInput(new byte[8], 0, 8);
        Thread writer = new Thread(writeTask);
        writer.start();

        Thread.sleep(100L);
        session.close();

        reader.join(2000L);
        writer.join(2000L);
        assertTrue(!reader.isAlive() && !writer.isAlive(), "close must unblock waiters");
        assertEquals(0, session.inputQueueSize());
        assertEquals(0, session.outputQueueSize());
    }
}
