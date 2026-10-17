package com.janemichael.jmrecruitingcrm.ai;

/**
 * A call that didn't produce a usable answer. The message is a sentence for the team. {@code usage}
 * is what the provider charged anyway (an answer that came back cut off, say); it's zero when the
 * request never got an answer.
 */
public class AiFailure extends RuntimeException {

    private final AiUsage usage;

    public AiFailure(String message, AiUsage usage) {
        super(message);
        this.usage = usage;
    }

    public AiUsage usage() {
        return usage;
    }
}
