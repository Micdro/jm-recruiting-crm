package com.janemichael.jmrecruitingcrm.ai;

import com.janemichael.jmrecruitingcrm.ai.AiService.Status;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The AI settings: which provider writes, the OpenAI key, and the shared spending limits. */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    public record ProviderRequest(
            @NotNull(message = "Choose Claude or OpenAI")
            AiProviderId provider,

            @Size(max = 100, message = "Choose one of the models listed")
            String model
    ) {
    }

    public record KeyRequest(
            @NotBlank(message = "Paste the API key")
            @Size(max = 300, message = "That's too long for an API key")
            String apiKey
    ) {
    }

    public record LimitsRequest(
            // The same bounds as on AI Found, which saves the same limits.
            @NotNull(message = "Enter a daily limit")
            @Min(value = 1, message = "The daily limit must be at least $1, what one prospect search needs")
            @Max(value = 100, message = "The daily limit can be up to $100")
            Integer dailyLimitDollars,

            @NotNull(message = "Enter a monthly limit")
            @Min(value = 0, message = "The monthly limit can't be negative")
            @Max(value = 1000, message = "The monthly limit can be up to $1,000")
            Integer monthlyLimitDollars
    ) {
    }

    private final AiService ai;

    public AiController(AiService ai) {
        this.ai = ai;
    }

    @GetMapping
    public Status status() {
        return ai.status();
    }

    @PutMapping("/provider")
    public Status choose(@Valid @RequestBody ProviderRequest request) {
        return ai.choose(request.provider(), request.model());
    }

    @PutMapping("/openai-key")
    public Status saveOpenAiKey(@Valid @RequestBody KeyRequest request) {
        return ai.saveOpenAiKey(request.apiKey());
    }

    @DeleteMapping("/openai-key")
    public Status removeOpenAiKey() {
        return ai.removeOpenAiKey();
    }

    @PutMapping("/limits")
    public Status saveLimits(@Valid @RequestBody LimitsRequest request) {
        return ai.saveLimits(request.dailyLimitDollars(), request.monthlyLimitDollars());
    }
}
