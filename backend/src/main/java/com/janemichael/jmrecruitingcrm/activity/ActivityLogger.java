package com.janemichael.jmrecruitingcrm.activity;

import com.janemichael.jmrecruitingcrm.auth.AppUser;
import com.janemichael.jmrecruitingcrm.candidate.Candidate;
import com.janemichael.jmrecruitingcrm.company.Company;
import com.janemichael.jmrecruitingcrm.contact.Contact;
import com.janemichael.jmrecruitingcrm.search.Search;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/** Writes activity log entries. Every service records its history through this. */
@Component
public class ActivityLogger {

    private static final int MAX_SUMMARY = 2000;

    private final ActivityRepository activityRepository;
    private final Clock clock;

    public ActivityLogger(ActivityRepository activityRepository, Clock clock) {
        this.activityRepository = activityRepository;
        this.clock = clock;
    }

    public Activity forContact(Contact contact, ActivityType type, boolean touch, String summary, AppUser user) {
        Activity activity = base(type, touch, summary, user);
        activity.setContact(contact);
        activity.setCompany(contact.getCompany());
        return activityRepository.save(activity);
    }

    public Activity forCandidate(Candidate candidate, Search search, ActivityType type, boolean touch,
                                 String summary, AppUser user) {
        Activity activity = base(type, touch, summary, user);
        activity.setCandidate(candidate);
        if (search != null) {
            activity.setSearch(search);
            activity.setCompany(search.getCompany());
        }
        return activityRepository.save(activity);
    }

    public Activity forSearch(Search search, ActivityType type, String summary, AppUser user) {
        Activity activity = base(type, false, summary, user);
        activity.setSearch(search);
        activity.setCompany(search.getCompany());
        return activityRepository.save(activity);
    }

    public Activity forCompany(Company company, ActivityType type, String summary, AppUser user) {
        Activity activity = base(type, false, summary, user);
        activity.setCompany(company);
        return activityRepository.save(activity);
    }

    private Activity base(ActivityType type, boolean touch, String summary, AppUser user) {
        Activity activity = new Activity();
        activity.setType(type);
        activity.setTouch(touch);
        activity.setSummary(truncate(summary));
        activity.setOccurredAt(Instant.now(clock));
        activity.setUser(user);
        return activity;
    }

    private static String truncate(String summary) {
        if (summary == null || summary.isBlank()) {
            return null;
        }
        String trimmed = summary.trim();
        return trimmed.length() > MAX_SUMMARY ? trimmed.substring(0, MAX_SUMMARY) : trimmed;
    }
}
