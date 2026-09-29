package com.github.guoyyit.ptylinedisciplinejava.pty;

public class SessionClosedException extends RuntimeException {

    public SessionClosedException(String sessionId) {
        super(sessionId);
    }
}
