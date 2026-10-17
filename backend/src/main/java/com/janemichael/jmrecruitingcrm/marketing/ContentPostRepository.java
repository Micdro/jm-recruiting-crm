package com.janemichael.jmrecruitingcrm.marketing;

import com.janemichael.jmrecruitingcrm.marketing.ContentEnums.PostStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface ContentPostRepository extends JpaRepository<ContentPost, Long> {

    /** Posts planned from {@code start} to {@code end}, both included, by day. */
    @Query("select p from ContentPost p left join fetch p.owner "
            + "where p.plannedFor >= :start and p.plannedFor <= :end order by p.plannedFor, p.createdAt")
    List<ContentPost> findPlanned(@Param("start") LocalDate start, @Param("end") LocalDate end);

    /** Ideas and drafts without a date yet, newest first. */
    @Query("select p from ContentPost p left join fetch p.owner "
            + "where p.plannedFor is null and p.status <> :posted order by p.createdAt desc")
    List<ContentPost> findUnplanned(@Param("posted") PostStatus posted);

    @Query("select p from ContentPost p left join fetch p.owner order by p.createdAt desc")
    List<ContentPost> findAllWithOwner();
}
