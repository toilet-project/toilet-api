package com.example.toiletapi.policy.model;

import com.example.toiletapi.auth.model.AppUser;
import com.example.toiletapi.global.time.KoreanTime;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "user_policy_consent")
public class UserPolicyConsent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_policy_consent_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "policy_document_id", nullable = false)
    private PolicyDocument policyDocument;

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_source", nullable = false, length = 30)
    private ConsentSource consentSource;

    @Column(name = "agreed_at", nullable = false)
    private LocalDateTime agreedAt;

    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    public static UserPolicyConsent agree(AppUser user, PolicyDocument policyDocument, ConsentSource source) {
        UserPolicyConsent consent = new UserPolicyConsent();
        consent.user = user;
        consent.policyDocument = policyDocument;
        consent.consentSource = source;
        consent.agreedAt = KoreanTime.now();
        return consent;
    }

    public void restore(ConsentSource source) {
        consentSource = source;
        agreedAt = KoreanTime.now();
        withdrawnAt = null;
    }

    public void withdraw() {
        withdrawnAt = KoreanTime.now();
    }
}
