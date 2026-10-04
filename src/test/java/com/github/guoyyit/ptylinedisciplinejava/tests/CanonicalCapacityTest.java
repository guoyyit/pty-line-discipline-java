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
import com.github.guoyyit.ptylinedisciplinejava.pty.WriteResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Capacity enforcement for canonical-mode line writes: a whole line must never
 * bypass the configured input buffer capacity.
 */
class CanonicalCapacityTest {

    private PtySessionManager manager;
    private int holderSeq;

    @BeforeEach
    void setUp() {
        manager = PtySessionManager.create();
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    private PtySession newSession(int capacity, OverflowPolicy policy) {
        SessionHolder holder = manager.createHolder("holder-cap-" + (holderSeq++));
        return holder.createSession(new SessionConfig(
                LineDisciplineMode.CANONICAL,
                ControlCharacters.defaults(),
                new BufferLimits(capacity, policy),
                BufferLimits.outputDefaults(),
                Defaults.HIGH_WATERMARK, Defaults.LOW_WATERMARK, 1, 0, 64,
                SignalDeliveryPolicy.QUEUE));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void blockPolicyRejectsOversizedLineWithoutExceedingCapacity() throws Exception {
        PtySession session = newSession(8, OverflowPolicy.BLOCK);
        byte[] line = bytes("abcdefghijklmnopqrs\n"); // 20 bytes, capacity 8

        long start = System.currentTimeMillis();
        WriteResult result = session.writeInput(line, 200);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(8, result.bytesAccepted(), result::toString);
        assertEquals(12, result.bytesDiscarded(), result::toString);
        assertTrue(result.timedOut(), result::toString);
        assertTrue(elapsed >= 150, "BLOCK must wait for the caller timeout: " + elapsed);
        assertTrue(elapsed < 3000, "BLOCK must not block indefinitely: " + elapsed);
        assertTrue(session.inputBufferSize() <= 8,
                "occupancy must not exceed capacity: " + session.inputBufferSize());

        // The incomplete line stays in the edit buffer: nothing is readable.
        ReadResult read = session.readInput(64, 100);
        assertEquals(0, read.bytesRead());
        assertTrue(read.timedOut());

        // After VKILL the session recovers and a fitting line is delivered.
        session.writeInput(new byte[]{Defaults.VKILL}, 0);
        WriteResult ok = session.writeInput(bytes("hi\n"), 200);
        assertTrue(ok.fullyAccepted(3), ok::toString);
        assertArrayEquals(bytes("hi\n"), session.readInput(64, 200).data());
        assertEquals(0, session.inputBufferSize());
    }

    @Test
    void discardPolicyDropsOverflowBytesAndReportsCounts() throws Exception {
        PtySession session = newSession(8, OverflowPolicy.DISCARD);
        byte[] line = bytes("abcdefghijklmnopqrs\n"); // 20 bytes, capacity 8

        WriteResult result = session.writeInput(line, 0);

        assertEquals(8, result.bytesAccepted(), result::toString);
        assertEquals(12, result.bytesDiscarded(), result::toString);
        assertFalse(result.timedOut(), result::toString);
        assertTrue(session.inputBufferSize() <= 8,
                "occupancy must not exceed capacity: " + session.inputBufferSize());

        // No partial line is delivered to the reader.
        ReadResult read = session.readInput(64, 60);
        assertEquals(0, read.bytesRead());
        assertTrue(read.timedOut());

        session.writeInput(new byte[]{Defaults.VKILL}, 0);
        WriteResult ok = session.writeInput(bytes("hi\n"), 0);
        assertTrue(ok.fullyAccepted(3), ok::toString);
        assertArrayEquals(bytes("hi\n"), session.readInput(64, 200).data());
    }

    @Test
    void oversizedLineDoesNotOverwritePreviouslyCommittedLines() throws Exception {
        PtySession session = newSession(8, OverflowPolicy.DISCARD);

        assertTrue(session.writeInput(bytes("ab\n"), 0).fullyAccepted(3));
        assertEquals(3, session.inputBufferSize());

        // 5 free slots remain: only "cdefg" of the next line is admitted.
        WriteResult overflow = session.writeInput(bytes("cdefghijklmnop\n"), 0);
        assertEquals(5, overflow.bytesAccepted(), overflow::toString);
        assertEquals(10, overflow.bytesDiscarded(), overflow::toString);
        assertEquals(3, session.inputBufferSize());

        // The previously committed line is delivered intact and unharmed.
        assertArrayEquals(bytes("ab\n"), session.readInput(64, 200).data());
        ReadResult empty = session.readInput(64, 60);
        assertEquals(0, empty.bytesRead());
        assertTrue(empty.timedOut());
    }

    @Test
    void lineFittingExactlyIntoCapacityIsDelivered() throws Exception {
        PtySession session = newSession(8, OverflowPolicy.BLOCK);
        WriteResult result = session.writeInput(bytes("abcdefg\n"), 200);
        assertTrue(result.fullyAccepted(8), result::toString);
        assertEquals(8, session.inputBufferSize());
        assertArrayEquals(bytes("abcdefg\n"), session.readInput(64, 200).data());
        assertEquals(0, session.inputBufferSize());
    }

    @Test
    void blockPolicyWaitsForReaderToFreeSpaceForWholeLine() throws Exception {
        PtySession session = newSession(8, OverflowPolicy.BLOCK);
        assertTrue(session.writeInput(bytes("ab\n"), 0).fullyAccepted(3));

        AtomicInteger accepted = new AtomicInteger(-1);
        Thread writer = new Thread(() -> {
            try {
                // 6 bytes: needs 6 free slots, only 5 available until a read.
                accepted.set(session.writeInput(bytes("cdefg\n"), 2000).bytesAccepted());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        writer.start();
        Thread.sleep(150);
        assertTrue(session.inputBufferSize() <= 8);
        assertArrayEquals(bytes("ab\n"), session.readInput(64, 200).data());
        writer.join(3000);
        assertFalse(writer.isAlive(), "writer must finish once space frees up");
        assertEquals(6, accepted.get());
        assertArrayEquals(bytes("cdefg\n"), session.readInput(64, 200).data());
    }

    @Test
    void eraseFreesReservedSlotsNearCapacity() throws Exception {
        PtySession session = newSession(8, OverflowPolicy.DISCARD);

        // Fill the edit buffer up to capacity, then overflow by one byte.
        WriteResult fill = session.writeInput(bytes("abcdefgh"), 0);
        assertTrue(fill.fullyAccepted(8), fill::toString);
        WriteResult extra = session.writeInput(bytes("X"), 0);
        assertEquals(0, extra.bytesAccepted(), extra::toString);
        assertEquals(1, extra.bytesDiscarded(), extra::toString);

        // Erasing two characters frees reserved slots for two new ones;
        // the completed line "abcdefY\n" is exactly 8 bytes and fits.
        assertTrue(session.writeInput(
                new byte[]{Defaults.VERASE, Defaults.VERASE}, 0).fullyAccepted(2));
        WriteResult tail = session.writeInput(bytes("Y\n"), 0);
        assertTrue(tail.fullyAccepted(2), tail::toString);

        assertArrayEquals(bytes("abcdefY\n"), session.readInput(64, 200).data());
        assertEquals(0, session.inputBufferSize());
    }

    @Test
    void killReleasesWholePendingLineNearCapacity() throws Exception {
        PtySession session = newSession(8, OverflowPolicy.DISCARD);
        assertTrue(session.writeInput(bytes("abcdefgh"), 0).fullyAccepted(8));
        assertEquals(1, session.writeInput(bytes("X"), 0).bytesDiscarded());

        assertTrue(session.writeInput(new byte[]{Defaults.VKILL}, 0).fullyAccepted(1));
        WriteResult ok = session.writeInput(bytes("hi\n"), 0);
        assertTrue(ok.fullyAccepted(3), ok::toString);
        assertArrayEquals(bytes("hi\n"), session.readInput(64, 200).data());
    }
}
