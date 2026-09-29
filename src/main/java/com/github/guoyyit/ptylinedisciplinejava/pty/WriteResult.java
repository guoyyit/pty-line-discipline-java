package com.github.guoyyit.ptylinedisciplinejava.pty;

public record WriteResult(boolean accepted, int bytesAccepted, WriteRejectReason reason) {

    public static WriteResult accepted(int bytesAccepted) {
        return new WriteResult(true, bytesAccepted, null);
    }

    public static WriteResult rejected(WriteRejectReason reason) {
        return new WriteResult(false, 0, reason);
    }
}
