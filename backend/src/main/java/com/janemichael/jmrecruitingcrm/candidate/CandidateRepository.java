package com.janemichael.jmrecruitingcrm.candidate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CandidateRepository extends JpaRepository<Candidate, Long> {

    @Query("select c from Candidate c left join fetch c.owner order by lower(c.name)")
    List<Candidate> findAllWithOwner();

    @Query("select c from Candidate c left join fetch c.owner where c.id = :id")
    Optional<Candidate> findWithOwnerById(@Param("id") Long id);

    boolean existsByEmailHash(String emailHash);

    boolean existsByEmailHashAndIdNot(String emailHash, Long id);

    @Modifying
    @Query("DELETE FROM Candidate c WHERE c.id = :id")
    int deleteCandidateById(@Param("id") Long id);
}
