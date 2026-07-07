package com.lyricsync.app.renderer;

public class SpringSelfTest {
    public static void main(String[] args) {
        Spring spring = new Spring(0, 0.2, 3.0);
        spring.finalPosition = 0;
        spring.position = 0.1;
        spring.velocity = 25.0;
        spring.update(0.001);
        if (spring.sleeping) {
            throw new AssertionError("spring with high velocity must not sleep");
        }
    }
}
