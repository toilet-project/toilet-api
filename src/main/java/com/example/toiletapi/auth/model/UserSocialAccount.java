package com.example.toiletapi.auth.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "user_social_account", uniqueConstraints = {
        @UniqueConstraint(name = "uk_social_provider_subject_hash", columnNames = {"provider", "provider_subject_hash"}),
        @UniqueConstraint(name = "uk_social_user_provider", columnNames = {"user_id", "provider"})})
public class UserSocialAccount {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "social_account_id") private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private AppUser user;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private SocialProvider provider;
    @Column(name = "provider_subject_hash", nullable = false, length = 64) private String providerSubjectHash;
    @Column(name = "provider_email", length = 255) private String providerEmail;
    @Column(name = "linked_at", nullable = false, updatable = false) private LocalDateTime linkedAt;
    @Column(name = "last_login_at") private LocalDateTime lastLoginAt;
    @PrePersist void onCreate() { linkedAt = LocalDateTime.now(); }

    public static UserSocialAccount link(
            AppUser user,
            SocialProvider provider,
            String providerSubjectHash,
            String providerEmail
    ) {
        UserSocialAccount account = new UserSocialAccount();
        account.user = user;
        account.provider = provider;
        account.providerSubjectHash = providerSubjectHash;
        account.providerEmail = providerEmail;
        return account;
    }

    public void recordLogin(String providerEmail) {
        if (providerEmail != null && !providerEmail.isBlank()) {
            this.providerEmail = providerEmail;
        }
        this.lastLoginAt = LocalDateTime.now();
    }
}
