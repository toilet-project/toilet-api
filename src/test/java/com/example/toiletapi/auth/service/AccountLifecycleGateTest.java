package com.example.toiletapi.auth.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AccountLifecycleGateTest {
    @Test void maintenanceAlwaysOverridesIndividualFlags() {
        for (boolean retention : new boolean[]{false,true}) for (boolean erasure : new boolean[]{false,true}) {
            var gate = new AccountLifecycleGate(true, retention, erasure);
            assertThat(gate.withdrawalAvailable()).isFalse();
            assertThatThrownBy(gate::requireWithdrawal).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(gate::requireRecovery).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(gate::requireErasure).isInstanceOf(ResponseStatusException.class);
        }
    }
    @Test void independentFlagsDoNotAccidentallyEnableOtherOperations() {
        var cleanup = new AccountLifecycleGate(false,false,true);
        cleanup.requireErasure();
        assertThatThrownBy(cleanup::requireWithdrawal).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(cleanup::requireRecovery).isInstanceOf(ResponseStatusException.class);
        var restoreOnly = new AccountLifecycleGate(false,true,false);
        restoreOnly.requireRecovery();
        assertThatThrownBy(restoreOnly::requireWithdrawal).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(restoreOnly::requireErasure).isInstanceOf(ResponseStatusException.class);
        assertThat(new AccountLifecycleGate(false,true,true).withdrawalAvailable()).isTrue();
    }
    @Test void serviceGuardsRunBeforeRepositoryAndLedgerAccess() {
        var gate = new AccountLifecycleGate(true,true,true);
        var users = mock(com.example.toiletapi.auth.repository.AppUserRepository.class);
        var ledger = mock(com.geupddong.account.ErasureLedger.class);
        var account = new AccountService(users,null,null,null,null,null,null,gate);
        var erase = new AccountErasureService(users,null,null,null,null,null,ledger,"production",gate);
        var recovery = new AccountRecoveryService(users,null,null,null,gate);
        var proof = new RecoveryChallengeStore.Proof(1L,"synthetic");
        assertThatThrownBy(() -> account.withdraw(1L,false,null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> erase.eraseIfDue(1L,java.time.LocalDateTime.now())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> recovery.confirm(proof)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> recovery.requestImmediateErasure(proof)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> recovery.status(proof)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(users,ledger);
    }
    @Test void withdrawnOAuthCannotIssueRecoveryOrAttemptErasureDuringMaintenance() {
        var users = mock(com.example.toiletapi.auth.repository.AppUserRepository.class);
        var socials = mock(com.example.toiletapi.auth.repository.UserSocialAccountRepository.class);
        var roles = mock(UserRolePolicyService.class);
        var withdrawals = mock(com.example.toiletapi.auth.repository.AccountWithdrawalRepository.class);
        var erasure = mock(AccountErasureService.class);
        var user = mock(com.example.toiletapi.auth.model.AppUser.class);
        var social = mock(com.example.toiletapi.auth.model.UserSocialAccount.class);
        when(user.getId()).thenReturn(1L);
        when(user.getStatus()).thenReturn(com.example.toiletapi.auth.model.UserStatus.WITHDRAWN);
        when(social.getUser()).thenReturn(user);
        when(socials.findByProviderAndProviderSubjectHash(any(),any())).thenReturn(java.util.Optional.of(social));
        when(users.lockById(1L)).thenReturn(java.util.Optional.of(user));
        var service = new OAuthLoginService(users,socials,roles,null,withdrawals,erasure,new AccountLifecycleGate(true,true,true));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"entityManager",mock(jakarta.persistence.EntityManager.class));
        var oauth = mock(org.springframework.security.oauth2.core.user.OAuth2User.class);
        when(oauth.getAttributes()).thenReturn(java.util.Map.of("sub","synthetic"));
        assertThatThrownBy(() -> service.login("google",oauth)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(withdrawals,erasure,roles);
        verify(social,never()).recordLogin(any());
    }
}
