package com.janemichael.jmrecruitingcrm.ai;

/** A picture the AI made, and what it cost. */
public record AiPicture(byte[] content, String contentType, AiUsage usage) {

    public int costCents() {
        return usage.costCents();
    }
}
