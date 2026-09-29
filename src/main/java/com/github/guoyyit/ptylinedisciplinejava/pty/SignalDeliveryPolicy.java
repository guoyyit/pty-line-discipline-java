package com.github.guoyyit.ptylinedisciplinejava.pty;

/**
 * Per-control-party policy for signals that cannot be observed immediately.
 */
public enum SignalDeliveryPolicy {
    /** Keep signals in a bounded queue until the control party polls them. */
    QUEUE,
    /** Drop signals that cannot be delivered immediately and count them. */
    DISCARD
}
