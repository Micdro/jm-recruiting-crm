package com.janemichael.jmrecruitingcrm.activity;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ActivityRepository extends JpaRepository<Activity, Long> {

    String FETCH = "select a from Activity a "
            + "left join fetch a.user "
            + "left join fetch a.company "
            + "left join fetch a.contact "
            + "left join fetch a.candidate "
            + "left join fetch a.search ";

    @Query(FETCH + "where a.contact.id = :id order by a.occurredAt desc, a.id desc")
    List<Activity> findForContact(@Param("id") Long contactId);

    @Query(FETCH + "where a.candidate.id = :id order by a.occurredAt desc, a.id desc")
    List<Activity> findForCandidate(@Param("id") Long candidateId);

    @Query(FETCH + "where a.search.id = :id order by a.occurredAt desc, a.id desc")
    List<Activity> findForSearch(@Param("id") Long searchId);

    @Query(FETCH + "where a.company.id = :id order by a.occurredAt desc, a.id desc")
    List<Activity> findForCompany(@Param("id") Long companyId);

    @Query(FETCH + "order by a.occurredAt desc, a.id desc")
    List<Activity> findRecent(Pageable pageable);

    @Query(FETCH + "order by a.occurredAt desc, a.id desc")
    List<Activity> findAllWithDetails();

    @Query("select count(a) from Activity a "
            + "where a.touch = true and a.user.id = :userId and a.occurredAt >= :from and a.occurredAt < :to")
    long countTouches(@Param("userId") Long userId, @Param("from") Instant from, @Param("to") Instant to);

    /** Rows of [userId, count] for touches in the time range. */
    @Query("select a.user.id, count(a) from Activity a "
            + "where a.touch = true and a.occurredAt >= :from and a.occurredAt < :to "
            + "group by a.user.id")
    List<Object[]> countTouchesByUser(@Param("from") Instant from, @Param("to") Instant to);
}
