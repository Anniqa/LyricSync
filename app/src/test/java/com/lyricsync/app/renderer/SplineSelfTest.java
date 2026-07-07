package com.lyricsync.app.renderer;

import java.util.Arrays;
import java.util.Collections;

public class SplineSelfTest {
    public static void main(String[] args) {
        assertRejects("requires matching x/y sizes", Arrays.asList(0d, 1d), Collections.singletonList(0d));
        assertRejects("requires at least 2 points", Collections.singletonList(0d), Collections.singletonList(0d));
        assertRejects("requires increasing x values", Arrays.asList(0d, 0d), Arrays.asList(0d, 1d));
    }

    private static void assertRejects(String message, java.util.List<Double> xs, java.util.List<Double> ys) {
        try {
            new Spline(xs, ys);
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
