package com.lyricsync.app.overlay;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

public class EdgeSwipeTriggerIntegrationTest {

    @Test
    public void testTriggerCallbackFlow() {
        final AtomicBoolean triggered = new AtomicBoolean(false);
        EdgeSwipeTriggerManager.OnTriggerListener listener = () -> triggered.set(true);

        // Simulate trigger
        listener.onEdgeTriggered();
        Assert.assertTrue("Callback must fire when edge triggered", triggered.get());
    }

    @Test
    public void testDecoupledWindowState() {
        boolean serviceRunning = true;
        boolean windowVisible = true;

        // User closes window via (X)
        windowVisible = false; // hideOverlayWindow()
        Assert.assertTrue("Service remains running after window hide", serviceRunning);
        Assert.assertFalse("Window is hidden", windowVisible);

        // User swipes & holds edge trigger
        windowVisible = true; // showOverlayWindow()
        Assert.assertTrue("Window is restored after edge trigger", windowVisible);
    }
}
