package com.github.guoyyit.ptylinedisciplinejava.pty;

/**
 * Outcome of a write operation.
 *
 * @param bytesAccepted bytes actually stored in the target buffer
 * @param bytesDiscarded bytes refused because the buffer was full (DISCARD policy)
 * @param timedOut      BLOCK policy and the timeout elapsed before all bytes fit
 * @param flowStopped   the output flow is currently stopped (high watermark or VSTOP)
 * @param closed        the session was closed and nothing was accepted
 */
public record WriteResult(int bytesAccepted, int bytesDiscarded, boolean timedOut,
                          boolean flowStopped, boolean closed) {

    public boolean fullyAccepted(int offered) {
        return bytesAccepted == offered && bytesDiscarded == 0;
    }
}
