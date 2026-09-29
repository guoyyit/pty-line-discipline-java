package com.github.guoyyit.ptylinedisciplinejava.pty;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalModeTest {

    private PtySession session(SessionOptions options) {
        return new PtySessionManager().createSession(options);
    }

    private SessionOptions options() {
        SessionOptions options = new SessionOptions();
        options.setMode(LineMode.CANONICAL);
        return options;
    }

    private int readAvailable(PtySession session, byte[] buffer) throws Exception {
        return session.readInput(buffer, 0, buffer.length, -1, TimeUnit.MILLISECONDS);
    }

    @Test
    void buffersUntilLineEndThenDeliversWholeLine() throws Exception {
        PtySession session = session(options());
        byte[] data = "hello world\n".getBytes();

        WriteResult result = session.writeInput(data);

        assertTrue(result.accepted());
        byte[] buffer = new byte[64];
        int read = readAvailable(session, buffer);
        assertEquals(data.length, read);
        assertArrayEquals(data, java.util.Arrays.copyOf(buffer, read));
    }

    @Test
    void deliversNothingBeforeLineEnd() throws Exception {
        PtySession session = session(options());

        session.writeInput("abc".getBytes());

        byte[] buffer = new byte[16];
        assertEquals(0, readAvailable(session, buffer));
        assertEquals(3, session.canonicalBufferSize());
    }

    @Test
    void eraseRemovesLastPendingChar() throws Exception {
        PtySession session = session(options());

        session.writeInput("ab".getBytes());
        session.writeInput(new byte[]{0x7F});
        session.writeInput("c\n".getBytes());

        byte[] buffer = new byte[16];
        int read = readAvailable(session, buffer);
        assertArrayEquals("ac\n".getBytes(), java.util.Arrays.copyOf(buffer, read));
    }

    @Test
    void killDeletesWholePendingLine() throws Exception {
        PtySession session = session(options());

        session.writeInput("abcdef".getBytes());
        session.writeInput(new byte[]{0x15});
        assertEquals(0, session.canonicalBufferSize());
        session.writeInput("x\n".getBytes());

        byte[] buffer = new byte[16];
        int read = readAvailable(session, buffer);
        assertArrayEquals("x\n".getBytes(), java.util.Arrays.copyOf(buffer, read));
    }

    @Test
    void eofDeliversPendingLineWithoutIncludingItself() throws Exception {
        PtySession session = session(options());

        session.writeInput("abc".getBytes());
        session.writeInput(new byte[]{0x04});

        byte[] buffer = new byte[16];
        int read = readAvailable(session, buffer);
        assertArrayEquals("abc".getBytes(), java.util.Arrays.copyOf(buffer, read));
    }

    @Test
    void multipleLinesRemainOrderedInQueue() throws Exception {
        PtySession session = session(options());

        session.writeInput("a\nb\n".getBytes());

        byte[] buffer = new byte[16];
        int read = readAvailable(session, buffer);
        assertArrayEquals("a\nb\n".getBytes(), java.util.Arrays.copyOf(buffer, read));
    }

    @Test
    void eraseAtEmptyLineIsNoOp() throws Exception {
        PtySession session = session(options());

        WriteResult result = session.writeInput(new byte[]{0x7F});

        assertTrue(result.accepted());
        assertEquals(0, session.canonicalBufferSize());
    }
}
