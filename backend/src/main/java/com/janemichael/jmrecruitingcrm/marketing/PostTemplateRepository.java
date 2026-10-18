package com.janemichael.jmrecruitingcrm.marketing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PostTemplateRepository extends JpaRepository<PostTemplate, Long> {

    @Query("select t from PostTemplate t left join fetch t.owner")
    List<PostTemplate> findAllWithOwner();
}
