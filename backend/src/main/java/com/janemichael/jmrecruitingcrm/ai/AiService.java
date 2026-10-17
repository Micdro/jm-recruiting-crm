package com.janemichael.jmrecruitingcrm.ai;

import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.ConflictException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Where every AI feature writes from. It uses the provider and model the account owner chose, stops
 * before a call could run past today's or this month's limit, and counts what each call cost.
 */
@Service
public class AiService {

    /** A call needs at least this much left of today's and this month's limits. */
    static final int MIN_CENTS = 5;
    /** A picture costs more than a draft, so it needs more room. */
    static final int MIN_PICTURE_CENTS = 15;

    private final AiSettings settings;
    private final Map<AiProviderId, AiProvider> providers = new EnumMap<>(AiProviderId.class);
    private final OpenAiProvider pictures;
    private final CurrentUserService currentUser;
    private final Clock clock;

    AiService(AiSettings settings, List<AiProvider> providers, OpenAiProvider pictures, CurrentUserService currentUser,
              Clock clock) {
        this.settings = settings;
        providers.forEach(provider -> this.providers.put(provider.id(), provider));
        this.pictures = pictures;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    /**
     * Writes with the chosen provider.
     *
     * @param feature what's asking, to start sentences like "Drafting needs a Claude API key."
     * @throws BadRequestException when there's no key, or the call didn't work
     * @throws ConflictException   when today's or this month's limit is used up
     */
    public AiResult write(String feature, AiRequest request) {
        AiSettings.Values values = settings.values();
        AiProviderId which = values.provider();
        if (!values.hasKey(which)) {
            String owner = currentUser.accountOwnerName();
            throw new BadRequestException(feature + " needs " + article(which) + " " + which.label() + " API key. "
                    + (owner == null ? "An admin" : owner) + " can add one "
                    + (which == AiProviderId.CLAUDE ? "on AI Found." : "in the AI settings."));
        }
        LocalDate today = LocalDate.now(clock);
        requireRoom(values, today, MIN_CENTS, feature);

        String model = values.model(which);
        AiUsage charged = AiUsage.none(model);
        try {
            AiResult result = provider(which).write(values.key(which), model, request);
            charged = result.usage();
            return result;
        } catch (AiFailure failure) {
            if (failure.usage() != null) {
                charged = failure.usage();
            }
            throw new BadRequestException(failure.getMessage());
        } finally {
            settings.addSpend(today, charged.costCents());
        }
    }

    /**
     * Makes a picture with OpenAI, whichever provider writes: Claude doesn't make pictures.
     *
     * @throws BadRequestException when there's no OpenAI key, or the call didn't work
     * @throws ConflictException   when today's or this month's limit is used up
     */
    public AiPicture picture(String feature, String prompt) {
        AiSettings.Values values = settings.values();
        if (!values.hasKey(AiProviderId.OPENAI)) {
            String owner = currentUser.accountOwnerName();
            throw new BadRequestException(feature + " needs an OpenAI API key, since Claude doesn't make pictures. "
                    + (owner == null ? "An admin" : owner) + " can add one in the AI settings on AI Found.");
        }
        LocalDate today = LocalDate.now(clock);
        requireRoom(values, today, MIN_PICTURE_CENTS, feature);

        AiUsage charged = AiUsage.none(AiModels.PICTURES.id());
        try {
            AiPicture picture = pictures.picture(values.key(AiProviderId.OPENAI), prompt);
            charged = picture.usage();
            return picture;
        } catch (AiFailure failure) {
            if (failure.usage() != null) {
                charged = failure.usage();
            }
            throw new BadRequestException(failure.getMessage());
        } finally {
            settings.addSpend(today, charged.costCents());
        }
    }

    /** Stops before a call that could run past today's or this month's limit. */
    private void requireRoom(AiSettings.Values values, LocalDate today, int needed, String feature) {
        int leftToday = values.dailyLimitDollars() * 100 - settings.spentCents(today);
        int leftThisMonth = values.monthlyLimitDollars() * 100 - settings.spentCents(YearMonth.from(today));
        if (Math.min(leftToday, leftThisMonth) < needed) {
            String state = Math.min(leftToday, leftThisMonth) < MIN_CENTS ? " is used up." : " is nearly used up.";
            throw new ConflictException(leftToday <= leftThisMonth
                    ? "Today's AI limit of $" + values.dailyLimitDollars() + state + " " + feature + " works again tomorrow."
                    : "This month's AI limit of $" + values.monthlyLimitDollars() + state);
        }
    }

    // Settings: the keys, provider and limits are the account owner's ----------------------

    public record ModelOption(String id, String label) {
    }

    public record ProviderStatus(AiProviderId id, String label, boolean hasKey, String keyHint, String model,
                                 List<ModelOption> models) {
    }

    /**
     * @param canManage whether the signed-in person may change these settings
     * @param owner     the account owner's name, for "Ask Michael to add a key"
     */
    public record Status(AiProviderId provider, List<ProviderStatus> providers, int dailyLimitDollars,
                         int monthlyLimitDollars, int spentTodayCents, int spentThisMonthCents, boolean canManage,
                         String owner) {
    }

    public Status status() {
        AiSettings.Values values = settings.values();
        LocalDate today = LocalDate.now(clock);
        List<ProviderStatus> all = Arrays.stream(AiProviderId.values())
                .map(id -> new ProviderStatus(id, id.label(), values.hasKey(id), values.keyHint(id), values.model(id),
                        AiModels.of(id).stream().map(model -> new ModelOption(model.id(), model.label())).toList()))
                .toList();
        return new Status(values.provider(), all, values.dailyLimitDollars(), values.monthlyLimitDollars(),
                settings.spentCents(today), settings.spentCents(YearMonth.from(today)),
                currentUser.isAccountOwnerOrStandIn(currentUser.get()), currentUser.accountOwnerName());
    }

    /** Picks who writes drafts. A provider without a key can be picked; drafting asks for the key. */
    public Status choose(AiProviderId which, String model) {
        currentUser.requireAccountOwner("choose the AI provider");
        String chosen = model == null || model.isBlank() ? AiModels.defaultFor(which).id() : model.trim();
        if (AiModels.find(which, chosen).isEmpty()) {
            throw new BadRequestException("Choose one of the " + which.label() + " models listed.");
        }
        settings.saveProvider(which, chosen);
        return status();
    }

    /**
     * Checks the OpenAI key with OpenAI before saving it, so a typo shows up now. The Claude key is
     * still added on AI Found, because adding it there also sets up the prospect finder.
     */
    public Status saveOpenAiKey(String apiKey) {
        currentUser.requireAccountOwner("change the OpenAI API key");
        String key = apiKey == null ? "" : apiKey.trim();
        String problem = openAiKeyProblem(key);
        if (problem != null) {
            throw new BadRequestException(problem);
        }
        provider(AiProviderId.OPENAI).checkKey(key);
        settings.saveKey(AiProviderId.OPENAI, key);
        return status();
    }

    public Status removeOpenAiKey() {
        currentUser.requireAccountOwner("remove the OpenAI API key");
        settings.saveKey(AiProviderId.OPENAI, null);
        return status();
    }

    public Status saveLimits(int dailyLimitDollars, int monthlyLimitDollars) {
        currentUser.requireAccountOwner("change the spending limits");
        settings.saveLimits(dailyLimitDollars, monthlyLimitDollars);
        return status();
    }

    /** What's wrong with how a key looks, or null. Catches the easy mix-up of pasting the Claude key. */
    static String openAiKeyProblem(String key) {
        if (key.isEmpty() || key.contains(" ")) {
            return "Paste the whole key, without spaces.";
        }
        if (key.startsWith("sk-ant-")) {
            return "That's a Claude key. OpenAI keys come from the OpenAI platform.";
        }
        if (!key.startsWith("sk-")) {
            return "That doesn't look like an OpenAI API key. Keys start with sk-.";
        }
        return null;
    }

    private AiProvider provider(AiProviderId which) {
        AiProvider provider = providers.get(which);
        if (provider == null) {
            throw new IllegalStateException("No provider for " + which);
        }
        return provider;
    }

    private static String article(AiProviderId which) {
        return which == AiProviderId.OPENAI ? "an" : "a";
    }
}
