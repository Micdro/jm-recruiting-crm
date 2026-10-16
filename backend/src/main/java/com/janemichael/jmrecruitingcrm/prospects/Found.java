package com.janemichael.jmrecruitingcrm.prospects;

import com.fasterxml.jackson.databind.JsonNode;
import com.janemichael.jmrecruitingcrm.company.CompanyEnums.HiringNeed;
import com.janemichael.jmrecruitingcrm.company.CompanyEnums.Industry;
import com.janemichael.jmrecruitingcrm.company.CompanyEnums.Segment;
import com.janemichael.jmrecruitingcrm.contact.BuyingRole;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The companies Claude recorded, cleaned up and checked. Contact details are kept only when they
 * appeared in what the web searches returned: a LinkedIn page, email or phone number Claude might
 * have pieced together itself is left out, so nothing in the CRM is a guess.
 */
final class Found {

    static final int MAX_PEOPLE = 3;
    static final int MAX_SOURCES = 5;

    record Company(String name, String website, String location, String focus, Industry industry,
                   List<Segment> segments, HiringNeed hiringNeed, String signal, List<String> sources,
                   List<Person> people) {
    }

    record Person(String name, String title, BuyingRole role, String linkedinUrl, String email, String phone) {
    }

    /** The cleaned companies, and how many contact details were left out because the searches didn't show them. */
    record Result(List<Company> companies, int withheld) {
    }

    private static final Pattern LINKEDIN = Pattern.compile(
            "^(?:https?://)?(?:[a-z]{2,3}\\.)?(?:www\\.)?linkedin\\.com/in/([^/?#\\s]+)/?(?:[?#].*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    /** Shared inboxes, not a person's own address. */
    private static final Set<String> SHARED_INBOXES = Set.of("info", "contact", "hello", "hi", "sales", "support",
            "help", "careers", "jobs", "recruiting", "talent", "hr", "press", "media", "pr", "team", "admin",
            "office", "privacy", "legal", "partners", "partnerships", "marketing", "investors", "ir", "security",
            "noreply", "no-reply", "general", "inquiries", "enquiries", "business", "bd");
    private static final Pattern PHONE_IN_TEXT = Pattern.compile("\\+?\\d[\\d\\s().-]{8,}\\d");

    private Found() {
    }

    /**
     * Reads the input of Claude's record_prospects call.
     *
     * @param evidence everything the searches returned, lowercased (see {@link Evidence})
     */
    static Result read(JsonNode input, Evidence evidence, int limit) {
        List<Company> companies = new ArrayList<>();
        int withheld = 0;
        JsonNode list = input == null ? null : input.get("companies");
        if (list == null || !list.isArray()) {
            return new Result(companies, 0);
        }
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode node : list) {
            if (companies.size() >= limit) {
                break;
            }
            String name = text(node, "name", 120);
            String signal = text(node, "signal", 600);
            if (name == null || signal == null || CompanyNames.keys(name).isEmpty()) {
                continue;
            }
            // The same company twice in one answer.
            if (CompanyNames.keys(name).stream().anyMatch(seen::contains)) {
                continue;
            }
            seen.addAll(CompanyNames.keys(name));

            List<Person> people = new ArrayList<>();
            JsonNode peopleNode = node.get("people");
            if (peopleNode != null && peopleNode.isArray()) {
                for (JsonNode personNode : peopleNode) {
                    if (people.size() >= MAX_PEOPLE) {
                        break;
                    }
                    String personName = text(personNode, "name", 120);
                    String title = text(personNode, "title", 255);
                    if (personName == null || title == null) {
                        continue;
                    }
                    String linkedin = linkedin(text(personNode, "linkedin_url", 500));
                    if (linkedin != null && !evidence.showsLinkedin(linkedin)) {
                        linkedin = null;
                        withheld++;
                    }
                    String email = email(text(personNode, "email", 255));
                    if (email != null && !evidence.contains(email)) {
                        email = null;
                        withheld++;
                    }
                    String phone = phone(text(personNode, "phone", 50));
                    if (phone != null && !evidence.showsPhone(phone)) {
                        phone = null;
                        withheld++;
                    }
                    people.add(new Person(personName, title, role(text(personNode, "role", 20)), linkedin, email, phone));
                }
            }

            companies.add(new Company(
                    name,
                    website(text(node, "website", 500)),
                    text(node, "location", 255),
                    text(node, "what_they_do", 500),
                    industry(text(node, "industry", 40)),
                    segments(node.get("segments")),
                    hiringNeed(text(node, "hiring_need", 20)),
                    signal,
                    sources(node.get("sources")),
                    people));
        }
        return new Result(companies, withheld);
    }

    // Fields -----------------------------------------------------------------

    /** Trimmed, with runs of whitespace made single, cut to {@code max}; null when blank. */
    static String text(JsonNode node, String field, int max) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }
        String text = value.asText().replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max - 1).trim() + "…";
    }

    /** A profile's address in one form, https://www.linkedin.com/in/name; null if it isn't a profile. */
    static String linkedin(String url) {
        if (url == null) {
            return null;
        }
        Matcher matcher = LINKEDIN.matcher(url.trim());
        return matcher.matches() ? "https://www.linkedin.com/in/" + matcher.group(1) : null;
    }

    static String email(String value) {
        if (value == null) {
            return null;
        }
        String email = value.replaceFirst("(?i)^mailto:", "").trim();
        if (!EMAIL.matcher(email).matches()) {
            return null;
        }
        String local = email.substring(0, email.indexOf('@')).toLowerCase(Locale.ROOT);
        return SHARED_INBOXES.contains(local) ? null : email;
    }

    static String phone(String value) {
        if (value == null) {
            return null;
        }
        String phone = value.replaceAll("[^0-9+().\\-\\s]", "").replaceAll("\\s+", " ").trim();
        int digits = phone.replaceAll("\\D", "").length();
        return digits >= 10 && digits <= 15 ? phone : null;
    }

    /** An http(s) address with a real host, as https; null otherwise. */
    static String website(String value) {
        if (value == null) {
            return null;
        }
        String url = value.matches("(?i)^https?://.*") ? value : "https://" + value;
        try {
            URI uri = URI.create(url);
            if (uri.getHost() == null || !uri.getHost().contains(".")) {
                return null;
            }
            String path = uri.getRawPath() == null || uri.getRawPath().equals("/") ? "" : uri.getRawPath();
            return "https://" + uri.getHost().toLowerCase(Locale.ROOT) + path;
        } catch (IllegalArgumentException notAUrl) {
            return null;
        }
    }

    static List<String> sources(JsonNode node) {
        Set<String> sources = new LinkedHashSet<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String url = item.isValueNode() ? item.asText().trim() : "";
                if (sources.size() < MAX_SOURCES && url.length() <= 500 && url.matches("(?i)^https?://[^\\s]+$")) {
                    try {
                        if (URI.create(url).getHost() != null) {
                            sources.add(url);
                        }
                    } catch (IllegalArgumentException notAUrl) {
                        // Skipped.
                    }
                }
            }
        }
        return List.copyOf(sources);
    }

    static Industry industry(String value) {
        Industry industry = enumValue(Industry.class, value);
        return industry == null ? Industry.UNCLEAR : industry;
    }

    static HiringNeed hiringNeed(String value) {
        HiringNeed need = enumValue(HiringNeed.class, value);
        return need == null ? HiringNeed.NOT_SURE : need;
    }

    static BuyingRole role(String value) {
        BuyingRole role = enumValue(BuyingRole.class, value);
        return role == null ? BuyingRole.OTHER : role;
    }

    static List<Segment> segments(JsonNode node) {
        Set<Segment> segments = new LinkedHashSet<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                Segment segment = enumValue(Segment.class, item.asText());
                if (segment != null) {
                    segments.add(segment);
                }
            }
        }
        return List.copyOf(segments);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        if (value == null) {
            return null;
        }
        String name = value.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(name)) {
                return constant;
            }
        }
        return null;
    }

    /**
     * Everything the web searches gave back in one response: result addresses and titles, and what
     * Claude's filtering code printed from them. Claude only sees what's in here, so a detail that
     * isn't in it didn't come from the web.
     */
    static final class Evidence {

        private final StringBuilder text = new StringBuilder();
        private String lowered;
        private Set<String> phones;

        /** Adds every readable value of a tool result block (not the opaque encrypted ones). */
        void addResult(JsonNode block) {
            collect(block, null);
            lowered = null;
            phones = null;
        }

        private void collect(JsonNode node, String field) {
            if (node == null) {
                return;
            }
            if (node.isTextual()) {
                if (field == null || !field.startsWith("encrypted")) {
                    text.append(node.asText()).append('\n');
                }
            } else if (node.isArray()) {
                node.forEach(item -> collect(item, field));
            } else if (node.isObject()) {
                node.properties().forEach(entry -> collect(entry.getValue(), entry.getKey()));
            }
        }

        private String lowered() {
            if (lowered == null) {
                String all = text.toString().toLowerCase(Locale.ROOT);
                lowered = all + "\n" + decode(all);
            }
            return lowered;
        }

        boolean contains(String value) {
            return lowered().contains(value.toLowerCase(Locale.ROOT));
        }

        /** True when the profile's address (linkedin.com/in/name) was in the results. */
        boolean showsLinkedin(String profileUrl) {
            String path = profileUrl.substring(profileUrl.indexOf("linkedin.com/in/")).toLowerCase(Locale.ROOT);
            String all = lowered();
            return matchesWhole(all, path) || matchesWhole(all, decode(path));
        }

        /** Percent-escapes turned back into characters ("%C3%A9" is "é"); the text as it was if they're broken. */
        private static String decode(String value) {
            try {
                return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException brokenEscape) {
                return value;
            }
        }

        /** The address must end where the profile name ends: linkedin.com/in/ann isn't linkedin.com/in/anna. */
        private static boolean matchesWhole(String text, String path) {
            int from = 0;
            while (true) {
                int at = text.indexOf(path, from);
                if (at < 0) {
                    return false;
                }
                int end = at + path.length();
                if (end == text.length() || !Character.isLetterOrDigit(text.charAt(end)) && text.charAt(end) != '-'
                        && text.charAt(end) != '_' && text.charAt(end) != '%') {
                    return true;
                }
                from = at + 1;
            }
        }

        /** True when a number in the results has the same last ten digits. */
        boolean showsPhone(String phone) {
            String digits = phone.replaceAll("\\D", "");
            String last = digits.substring(digits.length() - 10);
            if (phones == null) {
                phones = new LinkedHashSet<>();
                Matcher matcher = PHONE_IN_TEXT.matcher(text);
                while (matcher.find()) {
                    phones.add(matcher.group().replaceAll("\\D", ""));
                }
            }
            return phones.stream().anyMatch(found -> found.endsWith(last));
        }

        boolean isEmpty() {
            return text.isEmpty();
        }
    }
}
