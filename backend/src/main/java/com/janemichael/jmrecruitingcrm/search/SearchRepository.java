package com.janemichael.jmrecruitingcrm.search;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SearchRepository extends JpaRepository<Search, Long> {

    String FETCH = "select s from JobSearch s "
            + "join fetch s.company "
            + "left join fetch s.hiringManager "
            + "left join fetch s.owner ";

    @Query(FETCH + "order by s.createdAt desc, s.id desc")
    List<Search> findAllWithDetails();

    @Query(FETCH + "where s.id = :id")
    Optional<Search> findWithDetailsById(@Param("id") Long id);

    boolean existsByCompanyId(Long companyId);

    /** Rows of [companyId, count] for searches in the given statuses. */
    @Query("select s.company.id, count(s) from JobSearch s where s.status in :statuses group by s.company.id")
    List<Object[]> countByCompanyAndStatusIn(@Param("statuses") Collection<SearchEnums.Status> statuses);

    @Modifying
    @Query("DELETE FROM JobSearch s WHERE s.id = :id")
    int deleteSearchById(@Param("id") Long id);
}
