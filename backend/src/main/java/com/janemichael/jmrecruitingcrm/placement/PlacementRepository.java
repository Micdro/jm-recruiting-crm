package com.janemichael.jmrecruitingcrm.placement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PlacementRepository extends JpaRepository<Placement, Long> {

    String FETCH = "select p from Placement p "
            + "join fetch p.search s "
            + "join fetch s.company "
            + "join fetch p.candidate ";

    @Query(FETCH + "order by p.startDate desc nulls last, p.id desc")
    List<Placement> findAllWithDetails();

    @Query(FETCH + "where p.id = :id")
    Optional<Placement> findWithDetailsById(@Param("id") Long id);

    boolean existsBySearchId(Long searchId);

    boolean existsByCandidateId(Long candidateId);

    boolean existsBySearchIdAndCandidateId(Long searchId, Long candidateId);

    boolean existsBySearchIdAndCandidateIdAndIdNot(Long searchId, Long candidateId, Long id);

    boolean existsByInvoiceNumberAndIdNot(String invoiceNumber, Long id);

    @Query("select p.invoiceNumber from Placement p where p.invoiceNumber like concat(:prefix, '%')")
    List<String> findInvoiceNumbersStartingWith(@Param("prefix") String prefix);

    /** Each company with a placement, and the latest start date there (placements without one are left out). */
    @Query("select p.search.company.id, max(p.startDate) from Placement p where p.startDate is not null "
            + "group by p.search.company.id")
    List<Object[]> findLastStartByCompany();

    @Query("select p.candidate.id from Placement p where p.search.id = :searchId")
    List<Long> findCandidateIdsForSearch(@Param("searchId") Long searchId);

    @Query("select p.search.id from Placement p where p.candidate.id = :candidateId")
    List<Long> findSearchIdsForCandidate(@Param("candidateId") Long candidateId);

    @Modifying
    @Query("DELETE FROM Placement p WHERE p.id = :id")
    int deletePlacementById(@Param("id") Long id);
}
