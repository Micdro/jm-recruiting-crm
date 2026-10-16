package com.janemichael.jmrecruitingcrm.pipeline;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PipelineRepository extends JpaRepository<PipelineEntry, Long> {

    String FETCH = "select p from PipelineEntry p "
            + "join fetch p.search s "
            + "join fetch s.company "
            + "join fetch p.candidate ";

    @Query(FETCH + "order by s.id, p.stageChangedAt desc")
    List<PipelineEntry> findAllWithDetails();

    @Query(FETCH + "where s.id = :searchId order by p.stageChangedAt desc")
    List<PipelineEntry> findForSearch(@Param("searchId") Long searchId);

    @Query(FETCH + "where p.candidate.id = :candidateId order by p.stageChangedAt desc")
    List<PipelineEntry> findForCandidate(@Param("candidateId") Long candidateId);

    /** Pipeline entries with an interview on or before this day, for Today's interviews and debriefs. */
    @Query(FETCH + "where p.interviewDate is not null and p.interviewDate <= :day")
    List<PipelineEntry> findWithInterviewOnOrBefore(@Param("day") java.time.LocalDate day);

    @Query(FETCH + "where p.id = :id")
    Optional<PipelineEntry> findWithDetailsById(@Param("id") Long id);

    @Query(FETCH + "where s.id = :searchId and p.candidate.id = :candidateId")
    Optional<PipelineEntry> findBySearchAndCandidate(
            @Param("searchId") Long searchId,
            @Param("candidateId") Long candidateId
    );

    boolean existsBySearchIdAndCandidateId(Long searchId, Long candidateId);

    /** Rows of [searchId, stage, count]. */
    @Query("select p.search.id, p.stage, count(p) from PipelineEntry p group by p.search.id, p.stage")
    List<Object[]> countBySearchAndStage();

    /** Rows of [candidateId, count] for entries in the given stages. */
    @Query("select p.candidate.id, count(p) from PipelineEntry p where p.stage in :stages group by p.candidate.id")
    List<Object[]> countByCandidateAndStageIn(@Param("stages") Collection<PipelineStage> stages);

    @Modifying
    @Query("DELETE FROM PipelineEntry p WHERE p.id = :id")
    int deleteEntryById(@Param("id") Long id);
}
