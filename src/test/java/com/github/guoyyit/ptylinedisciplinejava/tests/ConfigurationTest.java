package com.github.guoyyit.ptylinedisciplinejava.tests;

import com.github.guoyyit.ptylinedisciplinejava.pty.BufferLimits;
import com.github.guoyyit.ptylinedisciplinejava.pty.ControlCharacters;
import com.github.guoyyit.ptylinedisciplinejava.pty.Defaults;
import com.github.guoyyit.ptylinedisciplinejava.pty.LineDisciplineMode;
import com.github.guoyyit.ptylinedisciplinejava.pty.OverflowPolicy;
import com.github.guoyyit.ptylinedisciplinejava.pty.SessionConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationTest {

    @Test
    void defaultControlCharactersMatchTermiosDefaults() {
        ControlCharacters chars = ControlCharacters.defaults();
        assertEquals(Defaults.VERASE, chars.erase());
        assertEquals(Defaults.VKILL, chars.kill());
        assertEquals(Defaults.VEOF, chars.eof());
        assertEquals(Defaults.VINTR, chars.intr());
        assertEquals(Defaults.VQUIT, chars.quit());
        assertEquals(Defaults.VSTOP, chars.stop());
        assertEquals(Defaults.VSTART, chars.start());
    }

    @Test
    void specialCharacterCanBeDisabledAndReconfigured() {
        ControlCharacters chars = ControlCharacters.defaults()
                .withIntr('z')
                .withQuit(Defaults.DISABLED);
        assertEquals('z', chars.intr());
        assertTrue(chars.matches('z', chars.intr()));
        assertFalse(chars.matches(Defaults.VINTR, chars.intr()));
        assertEquals(Defaults.DISABLED, chars.quit());
        assertFalse(chars.matches(Defaults.VQUIT, chars.quit()));
    }

    @Test
    void invalidSpecialCharacterIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ControlCharacters.defaults().withErase(256));
        assertThrows(IllegalArgumentException.class,
                () -> ControlCharacters.defaults().withKill(-2));
    }

    @Test
    void configValidationRejectsBadWatermarksAndVmin() {
        assertThrows(IllegalArgumentException.class,
                () -> new SessionConfig(
                        LineDisciplineMode.RAW, ControlCharacters.defaults(),
                        BufferLimits.inputDefaults(),
                        new BufferLimits(10, OverflowPolicy.BLOCK),
                        20, 5, 1, 0, 64,
                        com.github.guoyyit.ptylinedisciplinejava.pty.SignalDeliveryPolicy.QUEUE));
        assertThrows(IllegalArgumentException.class,
                () -> SessionConfig.defaults().withVminVtime(300, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new BufferLimits(0, OverflowPolicy.BLOCK));
    }

    @Test
    void withersKeepOtherSettingsStable() {
        SessionConfig base = SessionConfig.defaults();
        SessionConfig raw = base.withMode(LineDisciplineMode.RAW).withVminVtime(2, 100);
        assertEquals(LineDisciplineMode.CANONICAL, base.mode());
        assertEquals(LineDisciplineMode.RAW, raw.mode());
        assertEquals(2, raw.vmin());
        assertEquals(100, raw.vtimeMillis());
        assertEquals(base.controlCharacters(), raw.controlCharacters());
    }
}
