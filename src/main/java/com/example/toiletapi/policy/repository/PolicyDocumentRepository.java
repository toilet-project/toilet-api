package com.example.toiletapi.policy.repository;

import com.example.toiletapi.policy.model.PolicyDocument;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyDocumentRepository extends JpaRepository<PolicyDocument, Long> {
    List<PolicyDocument> findAllByActiveTrueOrderByRequiredDescPolicyKeyAsc();
    List<PolicyDocument> findAllByActiveTrueAndRequiredTrueOrderByPolicyKeyAsc();
}
