package com.janemichael.jmrecruitingcrm.company;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CompanyRepository extends JpaRepository<Company, Long> {

    /** Names are encrypted, so callers sort by name themselves. */
    @Query("select c from Company c left join fetch c.owner")
    List<Company> findAllWithOwner();

    @Query("select c from Company c left join fetch c.owner where c.id = :id")
    Optional<Company> findWithOwnerById(@Param("id") Long id);

    /** For bulk actions on the Companies list. */
    @Query("select c from Company c left join fetch c.owner where c.id in :ids")
    List<Company> findWithOwnerByIds(@Param("ids") java.util.Collection<Long> ids);

    boolean existsByNameHash(String nameHash);

    boolean existsByNameHashAndIdNot(String nameHash, Long id);

    @Modifying
    @Query("DELETE FROM Company c WHERE c.id = :id")
    int deleteCompanyById(@Param("id") Long id);
}
