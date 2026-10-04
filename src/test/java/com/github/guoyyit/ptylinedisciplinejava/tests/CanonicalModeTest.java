package com.github.guoyyit.ptylinedisciplinejava.tests;

import com.github.guoyyit.ptylinedisciplinejava.pty.Defaults;
import com.github.guoyyit.ptylinedisciplinejava.pty.PtySession;
import com.github.guoyyit.ptylinedisciplinejava.pty.PtySessionManager;
import com.github.guoyyit.ptylinedisciplinejava.pty.ReadResult;
import com.github.guoyyit.ptylinedisciplinejava.pty.SessionHolder;
import com.github.guoyyit.ptylinedisciplinejava.pty.WriteResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalModeTest {

    private PtySessionManager manager;
    private SessionHolder holder;
    private PtySession session;

    @BeforeEach
    void setUp() {
        manager = PtySessionManager.create();
        holder = manager.createHolder("holder-canonical");
        session = holder.createSession(
                com.github.guoyyit.ptylinedisciplinejava.pty.SessionConfig.defaults());
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    @Test
    void lineIsBufferedUntilNewlineThenDeliveredAtOnce() throws Exception {
        writeInput("hel");
        ReadResult early = session.readInput(64, 50);
        assertEquals(0, early.bytesRead());
        assertTrue(early.timedOut());

        writeInput("lo\n");
        ReadResult result = session.readInput(64, 50);
        assertArrayEquals("hello\n".getBytes(StandardCharsets.UTF_8), result.data());
    }

    @Test
    void eraseRewritesThePendingLine() throws Exception {
        writeInput("abx" + (char) Defaults.VERASE + "c\n");
        ReadResult result = session.readInput(64, 50);
        assertArrayEquals("abc\n".getBytes(StandardCharsets.UTF_8), result.data());
    }

    @Test
    void killDeletesTheWholePendingLine() throws Exception {
        writeInput("abcdef" + (char) Defaults.VKILL + "xyz\n");
        ReadResult result = session.readInput(64, 50);
        assertArrayEquals("xyz\n".getBytes(StandardCharsets.UTF_8), result.data());
    }

    @Test
    void eraseAndKillCanBeCombined() throws Exception {
        String erase = String.valueOf((char) Defaults.VERASE);
        String kill = String.valueOf((char) Defaults.VKILL);
        writeInput("abc" + erase + erase + "DE" + kill + "done\n");
        ReadResult result = session.readInput(64, 50);
        assertArrayEquals("done\n".getBytes(StandardCharsets.UTF_8), result.data());
    }

    @Test
    void eofOnEmptyLineSignalsEndOfFile() throws Exception {
        writeInput(String.valueOf((char) Defaults.VEOF));
        ReadResult result = session.readInput(64, 50);
        assertEquals(0, result.bytesRead());
        assertTrue(result.eof());
        assertFalse(result.hangup());
    }

    @Test
    void eofFlushesPartialLineWithoutNewline() throws Exception {
        writeInput("partial" + (char) Defaults.VEOF);
        ReadResult first = session.readInput(64, 50);
        assertArrayEquals("partial".getBytes(StandardCharsets.UTF_8), first.data());

        ReadResult second = session.readInput(64, 50);
        assertTrue(second.timedOut());
    }

    @Test
    void consecutiveLinesAreDeliveredInOrder() throws Exception {
        writeInput("one\ntwo\nthree\n");
        assertArrayEquals("one\n".getBytes(StandardCharsets.UTF_8),
                session.readInput(64, 50).data());
        assertArrayEquals("two\n".getBytes(StandardCharsets.UTF_8),
                session.readInput(64, 50).data());
        assertArrayEquals("three\n".getBytes(StandardCharsets.UTF_8),
                session.readInput(64, 50).data());
    }

    @Test
    void readSmallerThanLineSplitsAcrossReads() throws Exception {
        writeInput("abcdef\n");
        ReadResult first = session.readInput(3, 50);
        assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), first.data());
        ReadResult second = session.readInput(3, 50);
        assertArrayEquals("def".getBytes(StandardCharsets.UTF_8), second.data());
        ReadResult third = session.readInput(3, 50);
        assertArrayEquals("\n".getBytes(StandardCharsets.UTF_8), third.data());
    }

    private void writeInput(String text) throws InterruptedException {
        byte[] offered = text.getBytes(StandardCharsets.UTF_8);
        WriteResult result = session.writeInput(
                offered, 1000);
        assertFalse(result.timedOut(), result::toString);
        assertEquals(0, result.bytesDiscarded(), result::toString);
        assertTrue(result.bytesAccepted() >= 0
                        && result.bytesAccepted() <= offered.length,
                result::toString);
    }
}
