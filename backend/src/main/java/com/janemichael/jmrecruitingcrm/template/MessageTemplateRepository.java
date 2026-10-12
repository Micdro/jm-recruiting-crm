package com.janemichael.jmrecruitingcrm.template;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface MessageTemplateRepository extends JpaRepository<MessageTemplate, Long> {

    @Query("select t from MessageTemplate t left join fetch t.owner")
    List<MessageTemplate> findAllWithOwner();
}
