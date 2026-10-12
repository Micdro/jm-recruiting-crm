package com.janemichael.jmrecruitingcrm.candidate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CandidateFileRepository extends JpaRepository<CandidateFile, Long> {

    @Query("select f from CandidateFile f left join fetch f.uploadedBy "
            + "where f.candidate.id = :candidateId order by f.createdAt desc, f.id desc")
    List<CandidateFile> findForCandidate(@Param("candidateId") Long candidateId);

    @Query("select f from CandidateFile f left join fetch f.uploadedBy "
            + "where f.id = :id and f.candidate.id = :candidateId")
    Optional<CandidateFile> findForCandidate(@Param("candidateId") Long candidateId, @Param("id") Long id);
}
