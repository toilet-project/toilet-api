package com.example.toiletapi.policy.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.toiletapi.photo.PhotoSync;
import com.example.toiletapi.policy.dto.*;
import com.example.toiletapi.policy.model.PolicyKey;
import com.example.toiletapi.policy.service.PolicyConsentService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class PolicyPhotoCompletionTest {
    @Test void completingRequiredPoliciesReleasesStagedSignupPhoto() {
        var service=mock(PolicyConsentService.class);var photos=mock(PhotoSync.class);
        var result=new PolicyConsentStatusResponse(false,List.of(),List.of());
        var request=new AgreePoliciesRequest(Set.of(PolicyKey.SERVICE_TERMS));
        when(service.agree(7L,request.policyKeys())).thenReturn(result);
        var jwt=Jwt.withTokenValue("test").header("alg","none").subject("7").build();
        assertSame(result,new PolicyController(service,photos).agree(request,jwt));
        verify(photos).completeSignup(7L);
    }
}
