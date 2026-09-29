package com.github.guoyyit.ptylinedisciplinejava.pty;

/**
 * Signals delivered to a session's control party.
 */
public enum PtySignal {
    /** Delivered when the interrupt control character (VINTR) arrives. */
    INTERRUPT,
    /** Delivered when the quit control character (VQUIT) arrives. */
    QUIT,
    /** Delivered to every associated session when the holder exits / session closes. */
    HANGUP
}
