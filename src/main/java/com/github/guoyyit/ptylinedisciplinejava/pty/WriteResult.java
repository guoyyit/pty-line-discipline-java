package com.github.guoyyit.ptylinedisciplinejava.pty;

/**
 * Outcome of a write operation.
 *
 * @param bytesAccepted bytes actually stored in the target buffer. In canonical
 *                      mode, editing/signal characters processed without storing
 *                      data (VERASE, VKILL, VINTR, ...) are not counted here
 * @param bytesDiscarded bytes refused because they could not fit (DISCARD
 *                      policy; a canonical line is always accepted or rejected
 *                      as a whole)
 * @param timedOut      BLOCK policy and the caller timeout elapsed before all
 *                      bytes were admitted
 * @param flowStopped   the output flow is currently stopped (high watermark or VSTOP)
 * @param closed        the session was closed and nothing was accepted
 */
public record WriteResult(int bytesAccepted, int bytesDiscarded, boolean timedOut,
                          boolean flowStopped, boolean closed) {

    public boolean fullyAccepted(int offered) {
        return bytesAccepted == offered && bytesDiscarded == 0;
    }
}
