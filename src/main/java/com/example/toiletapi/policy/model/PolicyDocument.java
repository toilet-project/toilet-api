package com.example.toiletapi.policy.model;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "policy_document")
public class PolicyDocument {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "policy_document_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "policy_key", nullable = false, length = 40)
    private PolicyKey policyKey;

    @Column(nullable = false, length = 20)
    private String version;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false)
    private boolean required;

    @Column(name = "effective_at", nullable = false)
    private LocalDate effectiveAt;

    @Column(name = "content_path", nullable = false, length = 255)
    private String contentPath;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
