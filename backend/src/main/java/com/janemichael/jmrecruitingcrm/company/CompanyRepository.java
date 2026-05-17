package com.janemichael.jmrecruitingcrm.company;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CompanyRepository extends JpaRepository<Company, Long> {

    @Modifying
    @Query("DELETE FROM Company c WHERE c.id = :id")
    int deleteCompanyById(@Param("id") Long id);
}