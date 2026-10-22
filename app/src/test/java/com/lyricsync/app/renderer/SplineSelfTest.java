package com.lyricsync.app.renderer;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.fail;

public class SplineSelfTest {

    @Test
    public void rejectsInvalidInputs() {
        // Requires matching x/y sizes
        try {
            new Spline(Arrays.asList(0d, 1d), Collections.singletonList(0d));
            fail("requires matching x/y sizes");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        // Requires at least 2 points
        try {
            new Spline(Collections.singletonList(0d), Collections.singletonList(0d));
            fail("requires at least 2 points");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        // Requires increasing x values
        try {
            new Spline(Arrays.asList(0d, 0d), Arrays.asList(0d, 1d));
            fail("requires increasing x values");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
