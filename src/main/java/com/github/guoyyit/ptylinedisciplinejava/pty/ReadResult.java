package com.github.guoyyit.ptylinedisciplinejava.pty;

import java.util.Arrays;

/**
 * Outcome of a read operation.
 *
 * @param data      bytes delivered (never {@code null}; may be empty)
 * @param eof       an end-of-file condition was reached
 * @param hangup    the session was closed and no more data will follow
 * @param timedOut  a wait elapsed before the readiness condition was met:
 *                  the caller-supplied timeout expired (possibly delivering
 *                  fewer than VMIN bytes, in which case {@code data} holds the
 *                  partial result), or a raw-mode VMIN=0/VTIME&gt;0 wait
 *                  elapsed with no data
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
