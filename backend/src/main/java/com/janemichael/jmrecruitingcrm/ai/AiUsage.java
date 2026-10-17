package com.janemichael.jmrecruitingcrm.ai;

/** What one or more calls used, and from that what they cost. */
public record AiUsage(String model, long inputTokens, long outputTokens, long cacheWriteTokens, long cacheReadTokens) {

    public static AiUsage none(String model) {
        return new AiUsage(model, 0, 0, 0, 0);
    }

    public AiUsage plus(AiUsage other) {
        if (other == null) {
            return this;
        }
        return new AiUsage(model,
                inputTokens + other.inputTokens,
                outputTokens + other.outputTokens,
                cacheWriteTokens + other.cacheWriteTokens,
                cacheReadTokens + other.cacheReadTokens);
    }

    /** Rounded up to the next cent, so many small calls never add up to more than was counted. */
    public int costCents() {
        AiModels.Model prices = AiModels.pricing(model);
        double dollars = inputTokens * prices.inputPerMillion() / 1e6
                + outputTokens * prices.outputPerMillion() / 1e6
                + cacheWriteTokens * prices.cacheWritePerMillion() / 1e6
                + cacheReadTokens * prices.cacheReadPerMillion() / 1e6;
        return (int) Math.ceil(dollars * 100);
    }
}
