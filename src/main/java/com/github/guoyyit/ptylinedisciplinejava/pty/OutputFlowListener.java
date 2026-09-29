package com.github.guoyyit.ptylinedisciplinejava.pty;

@FunctionalInterface
public interface OutputFlowListener {

    void onFlowStateChange(FlowState newState, int queueSize);
}
