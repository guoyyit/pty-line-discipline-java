package com.github.guoyyit.ptylinedisciplinejava.pty;

@FunctionalInterface
public interface ControlParty {

    SignalDeliveryResult signal(SignalType type, String sessionId);
}
