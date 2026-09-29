package com.github.guoyyit.ptylinedisciplinejava.tests;

import com.github.guoyyit.ptylinedisciplinejava.pty.LineDisciplineMode;
import com.github.guoyyit.ptylinedisciplinejava.pty.PtySession;
import com.github.guoyyit.ptylinedisciplinejava.pty.PtySessionManager;
import com.github.guoyyit.ptylinedisciplinejava.pty.SessionConfig;
import com.github.guoyyit.ptylinedisciplinejava.pty.SessionHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ModeSwitchTest {

    private PtySessionManager manager;
    private PtySession session;

    @BeforeEach
    void setUp() {
        manager = PtySessionManager.create();
        SessionHolder holder = manager.createHolder("holder-mode");
        session = holder.createSession(SessionConfig.defaults());
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    @Test
    void switchingToRawDiscardsUnfinishedEditLineButKeepsQueuedLines()
            throws Exception {
        session.writeInput("finished\npartial".getBytes(StandardCharsets.UTF_8), 1000);
        session.setMode(LineDisciplineMode.RAW);

        assertArrayEquals("finished\n".getBytes(StandardCharsets.UTF_8),
                session.readInput(64, 200).data());

        session.writeInput(new byte[]{'x', 'y'}, 1000);
        assertArrayEquals(new byte[]{'x', 'y'},
                session.readInput(64, 200).data());
    }

    @Test
    void switchingBackToCanonicalStartsFreshEditLine() throws Exception {
        session.setMode(LineDisciplineMode.RAW);
        session.writeInput(new byte[]{'a', 'b'}, 1000);
        assertEquals(2, session.readInput(64, 200).bytesRead());

        session.setMode(LineDisciplineMode.CANONICAL);
        session.writeInput("line\n".getBytes(StandardCharsets.UTF_8), 1000);
        assertArrayEquals("line\n".getBytes(StandardCharsets.UTF_8),
                session.readInput(64, 200).data());
    }

    @Test
    void vminVtimeAppliesOnlyInRawMode() throws Exception {
        session.setMode(LineDisciplineMode.RAW);
        session.setVminVtime(2, 0);
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(80);
                session.writeInput(new byte[]{'z', 'z'}, 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        writer.start();
        assertArrayEquals(new byte[]{'z', 'z'},
                session.readInput(64, 1000).data());
        writer.join();

        session.setMode(LineDisciplineMode.CANONICAL);
        session.writeInput("ok\n".getBytes(StandardCharsets.UTF_8), 1000);
        assertArrayEquals("ok\n".getBytes(StandardCharsets.UTF_8),
                session.readInput(64, 200).data());
    }

    @Test
    void setModeIsIdempotentAndReportsCurrentMode() {
        assertEquals(LineDisciplineMode.CANONICAL, session.mode());
        session.setMode(LineDisciplineMode.CANONICAL);
        assertEquals(LineDisciplineMode.CANONICAL, session.mode());
        session.setMode(LineDisciplineMode.RAW);
        assertEquals(LineDisciplineMode.RAW, session.mode());
    }
}
