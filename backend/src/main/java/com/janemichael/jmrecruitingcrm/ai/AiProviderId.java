package com.janemichael.jmrecruitingcrm.ai;

/** The AI services the CRM can write with. The account owner picks one in the AI settings. */
public enum AiProviderId {
    CLAUDE("Claude"),
    OPENAI("OpenAI");

    private final String label;

    AiProviderId(String label) {
        this.label = label;
    }

    /** The name people know it by, for messages like "Drafting needs a Claude API key." */
    public String label() {
        return label;
    }
}
