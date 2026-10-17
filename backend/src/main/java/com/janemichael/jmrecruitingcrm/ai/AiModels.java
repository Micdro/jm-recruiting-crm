package com.janemichael.jmrecruitingcrm.ai;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The models the account owner can pick, with their prices, so every call's cost counts against
 * the daily and monthly limits. Only listed models can be chosen: a model without a known price
 * could spend past the limits. To offer another model, add a line here.
 */
public final class AiModels {

    /**
     * One model and what it costs, in US dollars per million tokens.
     *
     * @param cacheWritePerMillion Claude's prompt caching; OpenAI has none to pay for
     * @param cacheReadPerMillion  Claude's cache hits. OpenAI's cached input is billed below the input
     *                             price, but it's counted at the full price here, so costs come out
     *                             a little high rather than low.
     */
    public record Model(AiProviderId provider, String id, String label, double inputPerMillion,
                        double outputPerMillion, double cacheWritePerMillion, double cacheReadPerMillion) {
    }

    /** The first model for each provider is its default. */
    public static final List<Model> ALL = List.of(
            // Same model and prices as the prospect finder (ProspectResearch).
            new Model(AiProviderId.CLAUDE, "claude-sonnet-5-5", "Claude Sonnet 5.5", 2.00, 10.00, 2.50, 0.10),
            // OpenAI's API pricing page, October 2026.
            new Model(AiProviderId.OPENAI, "gpt-6.1-sol", "GPT-6.1 Sol", 2.00, 10.00, 0, 0),
            new Model(AiProviderId.OPENAI, "gpt-6-luna", "GPT-6 Luna (lowest cost)", 0.10, 0.50, 0, 0));

    /** Used for a model that's no longer listed (one picked before an update removed it): the priciest one. */
    private static final Model PRICIEST = ALL.stream()
            .max(Comparator.comparingDouble(Model::outputPerMillion))
            .orElseThrow();

    private AiModels() {
    }

    public static List<Model> of(AiProviderId provider) {
        return ALL.stream().filter(model -> model.provider() == provider).toList();
    }

    public static Model defaultFor(AiProviderId provider) {
        return of(provider).getFirst();
    }

    public static Optional<Model> find(AiProviderId provider, String id) {
        return of(provider).stream().filter(model -> model.id().equals(id)).findFirst();
    }

    /** A model's prices. An unlisted model is priced like the priciest listed one, so the limits still hold. */
    static Model pricing(String id) {
        return ALL.stream().filter(model -> model.id().equals(id)).findFirst().orElse(PRICIEST);
    }
}
