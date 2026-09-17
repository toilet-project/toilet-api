package com.example.toiletapi.quality.controller;

import com.example.toiletapi.quality.service.DuplicateNameService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DuplicateFeatureGateTest {
    private final DuplicateNameService service = mock(DuplicateNameService.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withBean(DuplicateNameService.class, () -> service)
        .withUserConfiguration(AdminDuplicateNameController.class);

    @Test void missingFlagDoesNotRegisterController() {
        context.run(c -> {
            assertFalse(c.containsBean("adminDuplicateNameController"));
            assertTrue(c.getBeansOfType(AdminDuplicateNameController.class).isEmpty());
            verifyNoInteractions(service);
        });
    }

    @Test void disabledFlagDoesNotRegisterController() {
        context.withPropertyValues("app.duplicate-facilities.enabled=false").run(c -> {
            assertTrue(c.getBeansOfType(AdminDuplicateNameController.class).isEmpty());
            verifyNoInteractions(service);
        });
    }

    @Test void enabledFlagRegistersControllerWithoutWritingData() {
        context.withPropertyValues("app.duplicate-facilities.enabled=true").run(c -> {
            assertEquals(1, c.getBeansOfType(AdminDuplicateNameController.class).size());
            verifyNoInteractions(service);
        });
    }
}
