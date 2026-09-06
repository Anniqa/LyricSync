package com.lyricsync.app.ai.model;

/**
 * Encapsulates quota limit consumption fractions and reset timestamps
 * retrieved from Google Antigravity Cloudcode PA.
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

    public QuotaSummary() {
    }

    public QuotaSummary(float geminiWeeklyFraction, float gemini5hFraction,
                        float thirdPartyWeeklyFraction, float thirdParty5hFraction,
                        String geminiWeeklyResetTime, String gemini5hResetTime,
                        String thirdPartyWeeklyResetTime, String thirdParty5hResetTime) {
        this.geminiWeeklyFraction = geminiWeeklyFraction;
        this.gemini5hFraction = gemini5hFraction;
        this.thirdPartyWeeklyFraction = thirdPartyWeeklyFraction;
        this.thirdParty5hFraction = thirdParty5hFraction;
        this.geminiWeeklyResetTime = geminiWeeklyResetTime;
        this.gemini5hResetTime = gemini5hResetTime;
        this.thirdPartyWeeklyResetTime = thirdPartyWeeklyResetTime;
        this.thirdParty5hResetTime = thirdParty5hResetTime;
    }

    public float getGeminiWeeklyFraction() {
        return geminiWeeklyFraction;
    }

    public void setGeminiWeeklyFraction(float geminiWeeklyFraction) {
        this.geminiWeeklyFraction = geminiWeeklyFraction;
    }

    public float getGemini5hFraction() {
        return gemini5hFraction;
    }

    public void setGemini5hFraction(float gemini5hFraction) {
        this.gemini5hFraction = gemini5hFraction;
    }

    public float getThirdPartyWeeklyFraction() {
        return thirdPartyWeeklyFraction;
    }

    public void setThirdPartyWeeklyFraction(float thirdPartyWeeklyFraction) {
        this.thirdPartyWeeklyFraction = thirdPartyWeeklyFraction;
    }

    public float getThirdParty5hFraction() {
        return thirdParty5hFraction;
    }

    public void setThirdParty5hFraction(float thirdParty5hFraction) {
        this.thirdParty5hFraction = thirdParty5hFraction;
    }

    public String getGeminiWeeklyResetTime() {
        return geminiWeeklyResetTime;
    }

    public void setGeminiWeeklyResetTime(String geminiWeeklyResetTime) {
        this.geminiWeeklyResetTime = geminiWeeklyResetTime;
    }

    public String getGemini5hResetTime() {
        return gemini5hResetTime;
    }

    public void setGemini5hResetTime(String gemini5hResetTime) {
        this.gemini5hResetTime = gemini5hResetTime;
    }

    public String getThirdPartyWeeklyResetTime() {
        return thirdPartyWeeklyResetTime;
    }

    public void setThirdPartyWeeklyResetTime(String thirdPartyWeeklyResetTime) {
        this.thirdPartyWeeklyResetTime = thirdPartyWeeklyResetTime;
    }

    public String getThirdParty5hResetTime() {
        return thirdParty5hResetTime;
    }

    public void setThirdParty5hResetTime(String thirdParty5hResetTime) {
        this.thirdParty5hResetTime = thirdParty5hResetTime;
    }

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
