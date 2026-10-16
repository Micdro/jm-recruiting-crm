package com.janemichael.jmrecruitingcrm.signals;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Decides from a job title whether a role is one the firm places: forward deployed engineering or
 * AI platform engineering. Titles are compared as lowercase words, so "Forward-Deployed Engineer",
 * "MLOps Engineer" and "AI/ML Platform Engineer" all match.
 */
public final class RoleMatcher {

    /** Roles that mention the right words but aren't engineering hires the firm makes. */
    private static final List<String> EXCLUDED = List.of(
            "intern", "internship", "recruiter", "recruiting", "talent", "sales", "account executive",
            "account manager", "marketing", "product manager", "product marketing", "designer", "counsel",
            "legal", "partnerships");

    private static final List<String> FDE = List.of(
            "forward deployed", "fde", "deployed engineer", "deployment engineer", "deployed ai engineer",
            "applied ai engineer", "ai deployment", "implementation engineer");

    private static final List<String> AI_PLATFORM = List.of(
            "mlops", "ml ops", "llmops", "llm ops", "ml platform", "machine learning platform", "ai platform",
            "genai platform", "gen ai platform", "generative ai platform", "ml infrastructure", "ml infra",
            "machine learning infrastructure", "ai infrastructure", "ai infra", "llm infrastructure",
            "inference", "model serving", "ml systems", "machine learning systems");

    private RoleMatcher() {
    }

    public static Optional<RoleTrack> classify(String title) {
        if (title == null || title.isBlank()) {
            return Optional.empty();
        }
        String words = words(title);
        if (containsAny(words, EXCLUDED)) {
            return Optional.empty();
        }
        if (containsAny(words, FDE)) {
            return Optional.of(RoleTrack.FDE);
        }
        if (containsAny(words, AI_PLATFORM)) {
            return Optional.of(RoleTrack.AI_PLATFORM);
        }
        return Optional.empty();
    }

    /** " forward deployed engineer nyc " for "Forward-Deployed Engineer (NYC)". */
    static String words(String text) {
        return " " + text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim() + " ";
    }

    private static boolean containsAny(String words, List<String> phrases) {
        for (String phrase : phrases) {
            if (words.contains(" " + phrase + " ")) {
                return true;
            }
        }
        return false;
    }
}
