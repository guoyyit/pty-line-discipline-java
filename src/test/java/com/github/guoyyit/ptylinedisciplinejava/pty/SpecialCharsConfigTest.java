package com.github.guoyyit.ptylinedisciplinejava.pty;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpecialCharsConfigTest {

    @Test
    void changingInterruptCharTakesEffectImmediately() {
        SessionOptions options = new SessionOptions();
        options.setMode(LineMode.RAW);
        PtySession session = new PtySessionManager().createSession(options);
        BufferedControlParty party = new BufferedControlParty(8);
        session.setControlParty(party);

        SpecialChars chars = session.getSpecialChars();
        chars.setInterrupt('j');
        session.setSpecialChars(chars);

        session.writeInput(new byte[]{'j'});
        session.writeInput(new byte[]{0x03});

        assertEquals(SignalType.INTERRUPT, party.poll().type());
        assertEquals(null, party.poll());
    }

    @Test
    void disabledControlCharBecomesData() throws Exception {
        SessionOptions options = new SessionOptions();
        options.setMode(LineMode.RAW);
        PtySession session = new PtySessionManager().createSession(options);
        BufferedControlParty party = new BufferedControlParty(8);
        session.setControlParty(party);

        SpecialChars chars = session.getSpecialChars();
        chars.setInterrupt(SpecialChars.DISABLED);
        session.setSpecialChars(chars);

        session.writeInput(new byte[]{0x03, 0x04});

        byte[] buffer = new byte[8];
        int read = session.readInput(buffer, 0, buffer.length, -1, TimeUnit.MILLISECONDS);
        assertArrayEquals(new byte[]{0x03, 0x04}, Arrays.copyOf(buffer, read));
        assertEquals(0, party.queuedCount());
    }

    @Test
    void reconfiguringEraseChangesEditingSemantics() throws Exception {
        PtySession session = new PtySessionManager().createSession(new SessionOptions());

        SpecialChars chars = session.getSpecialChars();
        chars.setErase('x');
        session.setSpecialChars(chars);

        session.writeInput("abx".getBytes());
        session.writeInput("c\n".getBytes());

        byte[] buffer = new byte[8];
        int read = session.readInput(buffer, 0, buffer.length, -1, TimeUnit.MILLISECONDS);
        assertArrayEquals("ac\n".getBytes(), Arrays.copyOf(buffer, read));
    }

    @Test
    void alreadyDeliveredDataUnaffectedByReconfiguration() throws Exception {
        PtySession session = new PtySessionManager().createSession(new SessionOptions());

        session.writeInput("first\n".getBytes());
        byte[] first = new byte[8];
        int firstRead = session.readInput(first, 0, first.length, -1, TimeUnit.MILLISECONDS);
        assertArrayEquals("first\n".getBytes(), Arrays.copyOf(first, firstRead));

        SpecialChars chars = session.getSpecialChars();
        chars.setLineEnd('!');
        session.setSpecialChars(chars);

        session.writeInput("second!".getBytes());
        byte[] second = new byte[8];
        int secondRead = session.readInput(second, 0, second.length, -1, TimeUnit.MILLISECONDS);
        assertArrayEquals("second!".getBytes(), Arrays.copyOf(second, secondRead));
    }

    @Test
    void rejectsOutOfRangeControlChar() {
        SpecialChars chars = new SpecialChars();
        assertThrows(IllegalArgumentException.class, () -> chars.setErase(256));
        assertThrows(IllegalArgumentException.class, () -> chars.setKill(-2));
    }

    @Test
    void copyIsIndependent() {
        SpecialChars chars = new SpecialChars();
        SpecialChars snapshot = chars.copy();
        snapshot.setInterrupt('z');
        assertEquals(0x03, chars.getInterrupt());
    }
}
