package com.janemichael.jmrecruitingcrm.contact;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ContactRepository extends JpaRepository<Contact, Long> {

    /** Companies found by the prospect finder that nobody has accepted yet. */
    String NOT_REVIEWED = "com.janemichael.jmrecruitingcrm.company.CompanyStatus.AI_FOUND";

    /** Names are encrypted, so callers sort by name themselves. */
    @Query("select c from Contact c join fetch c.company left join fetch c.owner")
    List<Contact> findAllWithDetails();

    @Query("select c from Contact c join fetch c.company left join fetch c.owner where c.id = :id")
    Optional<Contact> findWithDetailsById(@Param("id") Long id);

    /** For bulk actions on the Contacts list. */
    @Query("select c from Contact c join fetch c.company left join fetch c.owner where c.id in :ids")
    List<Contact> findWithDetailsByIds(@Param("ids") java.util.Collection<Long> ids);

    boolean existsByCompanyId(Long companyId);

    /** The people at these companies, for hiring signals. */
    @Query("select c from Contact c join fetch c.company where c.company.id in :companyIds")
    List<Contact> findByCompanyIds(@Param("companyIds") java.util.Collection<Long> companyIds);

    List<Contact> findByCompanyId(Long companyId);

    boolean existsByEmailHash(String emailHash);

    boolean existsByEmailHashAndIdNot(String emailHash, Long id);

    /** Rows of [companyId, count]. */
    @Query("select c.company.id, count(c) from Contact c group by c.company.id")
    List<Object[]> countByCompany();

    /**
     * New contacts with nothing scheduled, owned by this user or by nobody, oldest first. Contacts at
     * companies the prospect finder found, and nobody has reviewed yet, wait until the company is accepted.
     */
    @Query("select c from Contact c join fetch c.company co left join c.owner o "
            + "where c.status = :status and c.nextActionDate is null and (o is null or o.id = :ownerId) "
            + "and co.status <> " + NOT_REVIEWED + " "
            + "order by c.createdAt asc, c.id asc")
    List<Contact> findUnscheduled(@Param("status") OutreachStatus status, @Param("ownerId") Long ownerId);

    @Query("select count(c) from Contact c join c.company co left join c.owner o "
            + "where c.status = :status and c.nextActionDate is null and (o is null or o.id = :ownerId) "
            + "and co.status <> " + NOT_REVIEWED)
    long countUnscheduled(@Param("status") OutreachStatus status, @Param("ownerId") Long ownerId);

    /** Rows of [status, count], leaving out contacts at companies nobody has reviewed yet. */
    @Query("select c.status, count(c) from Contact c join c.company co where co.status <> " + NOT_REVIEWED
            + " group by c.status")
    List<Object[]> countByStatus();

    @Modifying
    @Query("DELETE FROM Contact c WHERE c.id = :id")
    int deleteContactById(@Param("id") Long id);
}
