package com.github.guoyyit.ptylinedisciplinejava.pty;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SignalDeliveryTest {

    private PtySession canonical() {
        SessionOptions options = new SessionOptions();
        options.setMode(LineMode.CANONICAL);
        return new PtySessionManager().createSession(options);
    }

    @Test
    void interruptAndQuitAreDeliveredInTriggerOrder() {
        PtySession session = canonical();
        BufferedControlParty party = new BufferedControlParty(8);
        session.setControlParty(party);

        session.writeInput(new byte[]{0x03});
        session.writeInput(new byte[]{0x1C});
        session.writeInput(new byte[]{0x03});

        List<SignalType> types = new ArrayList<>();
        BufferedControlParty.SignalEvent event;
        while ((event = party.poll()) != null) {
            types.add(event.type());
        }
        assertEquals(List.of(SignalType.INTERRUPT, SignalType.QUIT, SignalType.INTERRUPT), types);
    }

    @Test
    void signalsAreNotDeliveredTwiceForOneByte() {
        PtySession session = canonical();
        BufferedControlParty party = new BufferedControlParty(8);
        session.setControlParty(party);

        session.writeInput(new byte[]{0x03});

        assertEquals(1, party.queuedCount());
        assertSame(SignalType.INTERRUPT, party.poll().type());
        assertNull(party.poll());
    }

    @Test
    void interruptFlushesCanonicalLineAsSignal() throws Exception {
        PtySession session = canonical();
        BufferedControlParty party = new BufferedControlParty(8);
        session.setControlParty(party);

        session.writeInput("abc".getBytes());
        session.writeInput(new byte[]{0x03});

        byte[] buffer = new byte[16];
        int read = session.readInput(buffer, 0, buffer.length, -1, TimeUnit.MILLISECONDS);
        assertEquals(3, read);
        assertEquals(SignalType.INTERRUPT, party.poll().type());
        assertEquals(0, session.canonicalBufferSize());
    }

    @Test
    void queuedWhenControlPartyNotReadyAndDroppedWhenFull() {
        PtySession session = canonical();
        List<SignalDeliveryResult> results = new ArrayList<>();
        session.setControlParty(new RecordingControlParty(results, 1));
        session.writeInput(new byte[]{0x03});
        session.writeInput(new byte[]{0x03});

        assertEquals(List.of(SignalDeliveryResult.QUEUED, SignalDeliveryResult.DROPPED), results);
    }

    @Test
    void closeDeliversSingleHangupAndIsIdempotent() {
        PtySession session = canonical();
        BufferedControlParty party = new BufferedControlParty(8);
        session.setControlParty(party);

        session.close();
        session.close();

        assertEquals(1, count(party, SignalType.HANGUP));
    }

    private int count(BufferedControlParty party, SignalType type) {
        int total = 0;
        BufferedControlParty.SignalEvent event;
        while ((event = party.poll()) != null) {
            if (event.type() == type) {
                total++;
            }
        }
        return total;
    }

    private static final class RecordingControlParty implements ControlParty {
        private final List<SignalDeliveryResult> results;
        private final int capacity;
        private int stored;

        RecordingControlParty(List<SignalDeliveryResult> results, int capacity) {
            this.results = results;
            this.capacity = capacity;
        }

        @Override
        public SignalDeliveryResult signal(SignalType type, String sessionId) {
            SignalDeliveryResult result = stored < capacity
                    ? SignalDeliveryResult.QUEUED
                    : SignalDeliveryResult.DROPPED;
            if (result == SignalDeliveryResult.QUEUED) {
                stored++;
            }
            results.add(result);
            return result;
        }
    }
}
