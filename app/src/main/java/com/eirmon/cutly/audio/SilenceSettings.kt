package com.eirmon.cutly.audio

/**
 * The four numbers that decide where the cuts land.
 *
 * These are sliders in the UI, not constants, because there is no value that is right twice.
 * A phone mic in a quiet room floors around -55 dBFS; the same phone on a street floors around
 * -30, and a -40 threshold that trims the first take cuts nothing at all on the second.
 */
data class SilenceSettings(
    /** A frame quieter than this counts as silence. */
    val thresholdDb: Float = -40f,
    /**
     * The least audio a single cut is allowed to remove.
     *
     * The test is applied after padding, so an interior gap has to run [minSilenceMs] + 2 x
     * [padMs] before it qualifies — the padding is given back to the speech on either side. The
     * number therefore means what it says: no cut ever takes out less than this.
     */
    val minSilenceMs: Long = 350,
    /**
     * Silence left in place at each cut boundary.
     *
     * The RMS threshold crosses a few frames after a word actually starts, so cutting exactly on
     * the crossing shaves the attack off the first consonant. That is the artefact people hear
     * immediately, and it is why this is not zero.
     */
    val padMs: Long = 80,
    /** Kept pieces shorter than this are merged into a neighbour instead of becoming a cut. */
    val minKeepMs: Long = 150,
    /**
     * Ceiling on the number of kept pieces.
     *
     * ponytail: each kept piece becomes its own EditedMediaItem, so an unbounded count is an
     * unbounded Transformer sequence. If this is ever actually hit, raise the effective threshold
     * in one pre-pass rather than raising the cap.
     */
    val maxSpans: Int = 120
)
