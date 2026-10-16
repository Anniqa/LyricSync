package com.lyricsync.app.ai.model;

/**
 * Encapsulates quota limit consumption fractions and reset timestamps
 * retrieved from Google Antigravity Cloudcode PA.
 *
 * <p>Plain public fields: the only producer ({@code AntigravityQuotaClient.parseQuota}) and the
 * only consumers are in this app, and they already read the fields directly.
 */
public class QuotaSummary {

    public float geminiWeeklyFraction;
    public float gemini5hFraction;
    public float thirdPartyWeeklyFraction;
    public float thirdParty5hFraction;
    public String geminiWeeklyResetTime;
    public String gemini5hResetTime;
    public String thirdPartyWeeklyResetTime;
    public String thirdParty5hResetTime;

    @Override
    public String toString() {
        return "QuotaSummary{" +
                "geminiWeekly=" + geminiWeeklyFraction +
                ", gemini5h=" + gemini5hFraction +
                ", thirdPartyWeekly=" + thirdPartyWeeklyFraction +
                ", thirdParty5h=" + thirdParty5hFraction +
                ", geminiWeeklyResetTime='" + geminiWeeklyResetTime + '\'' +
                ", gemini5hResetTime='" + gemini5hResetTime + '\'' +
                ", thirdPartyWeeklyResetTime='" + thirdPartyWeeklyResetTime + '\'' +
                ", thirdParty5hResetTime='" + thirdParty5hResetTime + '\'' +
                '}';
    }
}
