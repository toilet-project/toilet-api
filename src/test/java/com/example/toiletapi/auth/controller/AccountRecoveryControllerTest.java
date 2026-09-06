package com.example.toiletapi.auth.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.toiletapi.auth.service.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class AccountRecoveryControllerTest {
    private final RecoveryChallengeStore challenges = mock(RecoveryChallengeStore.class);
    private final AccountRecoveryService recovery = mock(AccountRecoveryService.class);
    private final AccountErasureService erasure = mock(AccountErasureService.class);
    private final AuthTokenService tokens = mock(AuthTokenService.class);
    private final AccountRecoveryController controller = new AccountRecoveryController(challenges, recovery, erasure, tokens);

    @Test void rejectsForeignOriginBeforeReadingProofOrChangingAccount() {
        for (String origin : new String[]{"https://evil.example", "https://geupddong.com.evil.example", "null"}) {
            var request = new MockHttpServletRequest(); request.addHeader("Origin", origin);
            assertThatThrownBy(() -> controller.decide(new AccountRecoveryController.Decision("RESTORE"), request, new MockHttpServletResponse()))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }
        verifyNoInteractions(challenges, recovery, erasure, tokens);
    }
    @Test void deletionFailureReturnsAcceptedNotFalseSuccessAndInvalidatesProof() {
        var request = new MockHttpServletRequest(); request.addHeader("Origin", "https://geupddong.com");
        request.setCookies(new Cookie("geupddong_recovery", "proof"));
        var proof = new RecoveryChallengeStore.Proof(3L, "generation");
        when(challenges.read("proof")).thenReturn(proof); when(recovery.requestImmediateErasure(proof)).thenReturn(3L);
        when(erasure.eraseIfDue(eq(3L), any())).thenThrow(new IllegalStateException());
        var response = controller.decide(new AccountRecoveryController.Decision("ERASE"), request, new MockHttpServletResponse());
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        verify(erasure).recordFailure(3L); verify(challenges).delete("proof"); verifyNoInteractions(tokens);
    }
    @Test void unknownDecisionDoesNotRestoreOrErase() {
        var request = new MockHttpServletRequest(); request.addHeader("Origin", "https://preview.geupddong.com");
        when(challenges.read(null)).thenReturn(new RecoveryChallengeStore.Proof(1L, "g"));
        assertThatThrownBy(() -> controller.decide(new AccountRecoveryController.Decision("REJOIN_BY_EMAIL"), request, new MockHttpServletResponse()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(recovery, erasure, tokens);
    }
}
