package org.mp.naumann.algorithms.fd.structures;

public class IntegerPair {

    private final int a;
    private final int b;

    public IntegerPair(final int a, final int b) {
        this.a = a;
        this.b = b;
    }

    public int a() {
        return this.a;
    }

    public int b() {
        return this.b;
    }

    @Override
    public String toString() {
        return "[a: " + a + ", b: " + b + "]";
    }
}
