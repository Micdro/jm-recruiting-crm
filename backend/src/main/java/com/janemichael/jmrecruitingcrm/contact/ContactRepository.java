package com.janemichael.jmrecruitingcrm.contact;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ContactRepository extends JpaRepository<Contact, Long> {

    /** Names are encrypted, so callers sort by name themselves. */
    @Query("select c from Contact c join fetch c.company left join fetch c.owner")
    List<Contact> findAllWithDetails();

    @Query("select c from Contact c join fetch c.company left join fetch c.owner where c.id = :id")
    Optional<Contact> findWithDetailsById(@Param("id") Long id);

    boolean existsByCompanyId(Long companyId);

    boolean existsByEmailHash(String emailHash);

    boolean existsByEmailHashAndIdNot(String emailHash, Long id);

    /** Rows of [companyId, count]. */
    @Query("select c.company.id, count(c) from Contact c group by c.company.id")
    List<Object[]> countByCompany();

    /** New contacts with nothing scheduled, owned by this user or by nobody, oldest first. */
    @Query("select c from Contact c join fetch c.company left join c.owner o "
            + "where c.status = :status and c.nextActionDate is null and (o is null or o.id = :ownerId) "
            + "order by c.createdAt asc, c.id asc")
    List<Contact> findUnscheduled(@Param("status") OutreachStatus status, @Param("ownerId") Long ownerId);

    @Query("select count(c) from Contact c left join c.owner o "
            + "where c.status = :status and c.nextActionDate is null and (o is null or o.id = :ownerId)")
    long countUnscheduled(@Param("status") OutreachStatus status, @Param("ownerId") Long ownerId);

    /** Rows of [status, count]. */
    @Query("select c.status, count(c) from Contact c group by c.status")
    List<Object[]> countByStatus();

    @Modifying
    @Query("DELETE FROM Contact c WHERE c.id = :id")
    int deleteContactById(@Param("id") Long id);
}
