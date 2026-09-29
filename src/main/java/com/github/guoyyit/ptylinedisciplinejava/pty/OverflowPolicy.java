package com.github.guoyyit.ptylinedisciplinejava.pty;

/**
 * Behaviour when a bounded buffer cannot accept all offered bytes.
 */
public enum OverflowPolicy {
    /** Block (possibly up to a timeout) until enough capacity is available. */
    BLOCK,
    /** Accept only the bytes that fit and report the discarded remainder. */
    DISCARD
}
