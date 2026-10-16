package com.janemichael.jmrecruitingcrm.prospects;

import java.net.URI;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Loose matching of company names and websites, so the finder never adds a company the CRM already
 * has. Works like the off-limits matching on the page: "Ledgerlight AI, Inc." and "Ledgerlight" are
 * the same company, and so are "Open AI" and "OpenAI".
 */
final class CompanyNames {

    private static final Set<String> LEGAL = Set.of("inc", "incorporated", "llc", "llp", "lp", "ltd", "limited",
            "corp", "corporation", "co", "company", "plc", "gmbh", "ag", "sa", "bv");
    private static final Set<String> GENERIC = Set.of("ai", "tech", "technology", "technologies", "labs", "software",
            "systems", "group", "holdings", "hq");

    private CompanyNames() {
    }

    /** The ways a company's name can be written, for comparing. Empty for a blank name. */
    static Set<String> keys(String name) {
        if (name == null) {
            return Set.of();
        }
        String lead = name.split("\\s[·|•]\\s|\\(")[0].replace("&", " and ");
        String folded = Normalizer.normalize(lead, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        List<String> words = new ArrayList<>(Arrays.stream(folded.split("[^\\p{L}\\p{N}]+"))
                .filter(word -> !word.isEmpty())
                .toList());
        if (words.size() > 1 && words.getFirst().equals("the")) {
            words.removeFirst();
        }
        while (words.size() > 1 && LEGAL.contains(words.getLast())) {
            words.removeLast();
        }
        if (words.isEmpty()) {
            return Set.of();
        }
        List<String> core = new ArrayList<>(words);
        while (core.size() > 1 && (GENERIC.contains(core.getLast()) || LEGAL.contains(core.getLast()))) {
            core.removeLast();
        }
        Set<String> keys = new LinkedHashSet<>();
        keys.add(String.join(" ", words));
        keys.add(String.join("", words));
        keys.add(String.join(" ", core));
        keys.add(String.join("", core));
        return keys;
    }

    /**
     * The site's host without "www.", for comparing websites: "https://www.acme.ai/about" is "acme.ai".
     * Null when there's no usable host, or for sites that host many companies (LinkedIn, Crunchbase).
     */
    static String domain(String website) {
        if (website == null || website.isBlank()) {
            return null;
        }
        String value = website.trim();
        if (!value.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
            value = "https://" + value;
        }
        try {
            String host = URI.create(value).getHost();
            if (host == null) {
                return null;
            }
            String lower = host.toLowerCase(Locale.ROOT);
            String bare = lower.startsWith("www.") ? lower.substring(4) : lower;
            if (!bare.contains(".")
                    || SHARED_HOSTS.stream().anyMatch(shared -> bare.equals(shared) || bare.endsWith("." + shared))) {
                return null;
            }
            return bare;
        } catch (IllegalArgumentException notAUrl) {
            return null;
        }
    }

    /** Sites that list many companies, so their address says nothing about which one. */
    private static final List<String> SHARED_HOSTS = List.of("linkedin.com", "crunchbase.com", "github.com",
            "medium.com", "substack.com", "notion.site", "wixsite.com", "google.com", "sites.google.com");
}
