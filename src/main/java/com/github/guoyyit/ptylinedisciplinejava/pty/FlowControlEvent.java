package com.github.guoyyit.ptylinedisciplinejava.pty;

/**
 * A flow control notification delivered to an output writer.
 */
public record FlowControlEvent(boolean stopped) {

    private static final FlowControlEvent STOP_EVENT = new FlowControlEvent(true);
    private static final FlowControlEvent START_EVENT = new FlowControlEvent(false);

    public static FlowControlEvent stop() {
        return STOP_EVENT;
    }

    public static FlowControlEvent start() {
        return START_EVENT;
    }
}
