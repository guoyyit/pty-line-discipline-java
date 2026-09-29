package com.github.guoyyit.ptylinedisciplinejava.pty;

final class ByteArrayBuffer {

    private byte[] data = new byte[64];
    private int size;

    void append(byte value) {
        ensureCapacity(size + 1);
        data[size++] = value;
    }

    void append(byte[] source, int offset, int length) {
        if (length <= 0) {
            return;
        }
        ensureCapacity(size + length);
        System.arraycopy(source, offset, data, size, length);
        size += length;
    }

    byte removeLast() {
        if (size == 0) {
            throw new IllegalStateException("buffer is empty");
        }
        return data[--size];
    }

    void clear() {
        size = 0;
    }

    void setSize(int newSize) {
        if (newSize < 0 || newSize > size) {
            throw new IllegalArgumentException("invalid size: " + newSize);
        }
        size = newSize;
    }

    int size() {
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }

    byte[] toArray() {
        byte[] copy = new byte[size];
        System.arraycopy(data, 0, copy, 0, size);
        return copy;
    }

    byte[] copyFirst(int count) {
        if (count < 0 || count > size) {
            throw new IllegalArgumentException("invalid count: " + count);
        }
        byte[] copy = new byte[count];
        System.arraycopy(data, 0, copy, 0, count);
        return copy;
    }

    void removeFirst(int count) {
        if (count < 0 || count > size) {
            throw new IllegalArgumentException("invalid count: " + count);
        }
        int remaining = size - count;
        if (remaining > 0) {
            System.arraycopy(data, count, data, 0, remaining);
        }
        size = remaining;
    }

    private void ensureCapacity(int required) {
        if (required <= data.length) {
            return;
        }
        int capacity = Math.max(data.length * 2, required);
        byte[] grown = new byte[capacity];
        System.arraycopy(data, 0, grown, 0, size);
        data = grown;
    }
}
