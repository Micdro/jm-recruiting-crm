package com.janemichael.jmrecruitingcrm.signals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Guesses the names a company's job board might go by, from its website and its name. Boards are
 * usually named after the company's domain ("acme" for acme.ai) or the company itself ("acmeai",
 * "acme-ai").
 */
public final class BoardFinder {

    /** The order boards are tried in. */
    public static final List<JobBoardProvider> PROVIDERS =
            List.of(JobBoardProvider.GREENHOUSE, JobBoardProvider.ASHBY, JobBoardProvider.LEVER);

    static final int MAX_NAMES = 5;

    private static final Set<String> LEGAL = Set.of(
            "inc", "llc", "ltd", "limited", "corp", "corporation", "co", "company", "gmbh", "plc", "sa", "ag", "bv",
            "the");

    /** Words often left out of a board's name: "Acme AI" usually posts as "acme". */
    private static final Set<String> GENERIC = Set.of(
            "ai", "technologies", "technology", "tech", "labs", "lab", "software", "systems", "solutions", "group",
            "hq", "io", "app", "data", "analytics", "financial", "finance", "global");

    /** Second-level parts of country domains, so acme.co.uk gives "acme". */
    private static final Set<String> SECOND_LEVEL = Set.of("co", "com", "org", "net", "ac", "gov");

    private BoardFinder() {
    }

    /** Board names to try, most likely first. */
    public static List<String> names(String companyName, String website) {
        Set<String> names = new LinkedHashSet<>();

        String domain = domainName(website);
        if (domain != null) {
            names.add(domain);
            names.add(domain.replace("-", ""));
        }

        List<String> words = words(companyName);
        List<String> legalLess = words.stream().filter(word -> !LEGAL.contains(word)).toList();
        if (!legalLess.isEmpty()) {
            names.add(String.join("", legalLess));
            names.add(String.join("-", legalLess));
        }
        List<String> core = legalLess.stream().filter(word -> !GENERIC.contains(word)).toList();
        if (!core.isEmpty()) {
            names.add(String.join("", core));
        }

        List<String> result = new ArrayList<>();
        for (String name : names) {
            if (name.length() >= 2 && name.length() <= 60 && result.size() < MAX_NAMES) {
                result.add(name);
            }
        }
        return result;
    }

    /**
     * Whether the name a board gives is this company's. Legal endings and generic words are ignored,
     * so "Acme AI, Inc." matches "Acme".
     */
    public static boolean sameCompany(String crmName, String boardName) {
        if (crmName == null || boardName == null) {
            return false;
        }
        String ours = String.join("", words(crmName).stream().filter(word -> !LEGAL.contains(word)).toList());
        String theirs = String.join("", words(boardName).stream().filter(word -> !LEGAL.contains(word)).toList());
        if (ours.isEmpty() || theirs.isEmpty()) {
            return false;
        }
        if (ours.equals(theirs)) {
            return true;
        }
        String ourCore = core(crmName);
        String theirCore = core(boardName);
        return !ourCore.isEmpty() && ourCore.length() >= 3 && ourCore.equals(theirCore);
    }

    /** "acme" for "https://www.acme.ai/about", "getacme" for "getacme.com", "acme" for "acme.co.uk". */
    static String domainName(String website) {
        if (website == null || website.isBlank()) {
            return null;
        }
        String host = website.trim().toLowerCase(Locale.ROOT)
                .replaceFirst("^[a-z][a-z0-9+.-]*://", "")
                .replaceFirst("[/?#:].*$", "")
                .replaceFirst("^www\\.", "");
        String[] labels = host.split("\\.");
        if (labels.length < 2) {
            return null;
        }
        int index = labels.length - 2;
        if (labels.length >= 3 && SECOND_LEVEL.contains(labels[index]) && labels[labels.length - 1].length() == 2) {
            index--;
        }
        String label = labels[index].replaceAll("[^a-z0-9-]", "");
        return label.isEmpty() ? null : label;
    }

    private static String core(String name) {
        return String.join("", words(name).stream()
                .filter(word -> !LEGAL.contains(word) && !GENERIC.contains(word))
                .toList());
    }

    private static List<String> words(String name) {
        if (name == null) {
            return List.of();
        }
        String cleaned = name.toLowerCase(Locale.ROOT).replace("&", " and ").replaceAll("[^a-z0-9]+", " ").trim();
        return cleaned.isEmpty() ? List.of() : Arrays.asList(cleaned.split(" "));
    }
}
