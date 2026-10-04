package com.github.guoyyit.ptylinedisciplinejava.pty;

import java.util.Arrays;

/**
 * Outcome of a read operation.
 *
 * @param data      bytes delivered (never {@code null}; may be empty)
 * @param eof       an end-of-file condition was reached
 * @param hangup    the session was closed and no more data will follow
 * @param timedOut  the wait ended on a timeout rather than on the requested
 *                  readiness condition. In raw mode with VMIN&gt;0 this may be
 *                  combined with a non-empty {@code data}: the caller timeout
 *                  elapsed before VMIN bytes accumulated, so the buffered
 *                  partial bytes are delivered early. A VTIME inter-byte idle
 *                  expiry that returns partial bytes is reported as data
 *                  without this flag.
 */
public record ReadResult(byte[] data, boolean eof, boolean hangup, boolean timedOut) {

    public int bytesRead() {
        return data.length;
    }

    @Override
    public String toString() {
        return "ReadResult[bytes=" + data.length
                + ", eof=" + eof
                + ", hangup=" + hangup
                + ", timedOut=" + timedOut
                + ", data=" + Arrays.toString(data) + "]";
    }
}
