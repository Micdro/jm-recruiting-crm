package com.janemichael.jmrecruitingcrm.ai;

/**
 * One AI service the CRM can write with. Features don't call providers directly: they go through
 * {@link AiService}, which picks the provider the owner chose and keeps spending within the limits.
 */
public interface AiProvider {

    AiProviderId id();

    /**
     * Writes the request's fields.
     *
     * @throws AiFailure with a sentence for the team, and whatever the provider charged, when it can't
     */
    AiResult write(String apiKey, String model, AiRequest request);

    /**
     * Checks a key with a call that costs nothing, so a typo shows up when the key is saved rather
     * than the first time someone drafts.
     *
     * @throws com.janemichael.jmrecruitingcrm.common.BadRequestException saying what's wrong with it
     */
    void checkKey(String apiKey);
}
