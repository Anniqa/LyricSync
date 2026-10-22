package com.lyricsync.app.renderer;

import org.junit.Test;
import static org.junit.Assert.assertFalse;

public class SpringSelfTest {

    @Test
    public void highVelocityDoesNotSleep() {
        Spring spring = new Spring(0, 0.2, 3.0);
        spring.finalPosition = 0;
        spring.position = 0.1;
        spring.velocity = 25.0;
        spring.update(0.001);
        assertFalse("spring with high velocity must not sleep", spring.sleeping);
    }
}
