package com.lyricsync.app.renderer;

/**
 * The resolved combination of one MOTION style and one EFFECT style — the ONLY
 * thing the renderer reads. All combination logic lives in {@link #combine};
 * if a rule isn't written there, it doesn't exist.
 *
 * Pure JVM: no android imports, so the self-tests compile with plain javac.
 */
public final class AnimConfig {

    public final Spline scaleSpline;
    public final Spline yOffsetSpline;
    public final Spline glowSpline;          // guaranteed non-null
    public final Spline letterScaleSpline;
    public final Spline letterYOffsetSpline;

    public final boolean springsEnabled;
    public final boolean glowEnabled;
    public final boolean letterEmphasis;
    public final boolean binaryLetters;
    public final boolean wave;
    public final boolean echo;
    public final boolean shimmer;
    public final boolean flicker;
    public final boolean swing;
    public final boolean classic;
    public final boolean scatter;

    public final float glowBoost;
    public final double scaleFreq;
    public final double scaleDamp;
    public final long letterMinDuration;

    public final String motionKey;
    public final String effectKey;

    private AnimConfig(AnimStyleDef motion, AnimStyleDef effect) {
        // Motion-sourced: how words and letters move.
        scaleSpline = motion.scaleSpline;
        yOffsetSpline = motion.yOffsetSpline;
        letterScaleSpline = motion.letterScaleSpline;
        letterYOffsetSpline = motion.letterYOffsetSpline;
        springsEnabled = motion.springsEnabled;
        letterEmphasis = motion.letterEmphasis;
        binaryLetters = motion.binaryLetters;
        wave = motion.wave;
        swing = motion.swing;
        classic = motion.classic;
        scatter = motion.scatter;
        scaleFreq = motion.scaleFreq;
        scaleDamp = motion.scaleDamp;
        letterMinDuration = motion.letterMinDuration;

        // Effect-sourced: the visual overlay.
        echo = effect.echo;
        shimmer = effect.shimmer;
        flicker = effect.flicker;
        glowBoost = effect.glowBoost;

        // Glow: the effect's signature spline wins when it defines one
        // (glow/echo/flicker do); shimmer and none inherit the motion's.
        glowSpline = effect.glowSpline != null ? effect.glowSpline : motion.glowSpline;
        glowEnabled = motion.glowEnabled || effect != AnimStyleDef.Registry.none();

        motionKey = motion.key;
        effectKey = effect.key;
    }

    public static AnimConfig combine(AnimStyleDef motion, AnimStyleDef effect) {
        if (motion == null) motion = AnimStyleDef.Registry.motionByKey(null);
        if (effect == null) effect = AnimStyleDef.Registry.none();
        return new AnimConfig(motion, effect);
    }
}
