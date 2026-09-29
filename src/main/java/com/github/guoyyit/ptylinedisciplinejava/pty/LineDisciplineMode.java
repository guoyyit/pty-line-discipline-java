package com.github.guoyyit.ptylinedisciplinejava.pty;

/**
 * Line discipline mode of a PTY session.
 */
public enum LineDisciplineMode {
    /** Input is line buffered and edited until a line terminator is received. */
    CANONICAL,
    /** Input is delivered byte by byte, governed by VMIN/VTIME readiness semantics. */
    RAW
}
