package com.github.guoyyit.ptylinedisciplinejava.pty;

/**
 * Bounded buffer configuration for one direction of a session.
 *
 * @param capacity       maximum number of bytes the buffer may hold
 * @param overflowPolicy block or discard behaviour when full
 */
public record BufferLimits(int capacity, OverflowPolicy overflowPolicy) {

    public static BufferLimits inputDefaults() {
        return new BufferLimits(Defaults.INPUT_CAPACITY, OverflowPolicy.BLOCK);
    }

    public static BufferLimits outputDefaults() {
        return new BufferLimits(Defaults.OUTPUT_CAPACITY, OverflowPolicy.BLOCK);
    }

    public BufferLimits {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        if (overflowPolicy == null) {
            throw new IllegalArgumentException("overflowPolicy must not be null");
        }
    }
}
