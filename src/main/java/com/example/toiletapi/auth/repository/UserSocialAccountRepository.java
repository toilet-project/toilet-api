package com.example.toiletapi.auth.repository;

import com.example.toiletapi.auth.model.SocialProvider;
import com.example.toiletapi.auth.model.UserSocialAccount;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSocialAccountRepository extends JpaRepository<UserSocialAccount, Long> {
    Optional<UserSocialAccount> findByProviderAndProviderSubjectHash(SocialProvider provider, String providerSubjectHash);
    void deleteAllByUserId(Long userId);
}
