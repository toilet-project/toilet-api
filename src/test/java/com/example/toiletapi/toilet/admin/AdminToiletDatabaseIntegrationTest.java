package com.example.toiletapi.toilet.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false"
})
@EnabledIfEnvironmentVariable(named = "SPRING_DB_URL", matches = ".+")
class AdminToiletDatabaseIntegrationTest {
    @Autowired AdminToiletService service;

    @Test void partialNameSearchSuggestionsRegionsAndDetailUseActualReadOnlyData() {
        var page = service.search("대학", "", "", 0, 15);
        assertFalse(page.items().isEmpty());
        assertTrue(page.items().stream().allMatch(item -> item.name().contains("대학")));

        var suggestions = service.suggestions("대학", 8);
        assertFalse(suggestions.isEmpty());
        assertTrue(suggestions.stream().allMatch(item -> item.name().contains("대학")));

        var detail = service.detail(page.items().getFirst().id());
        assertEquals(page.items().getFirst().id(), detail.id());
        assertEquals(64, detail.snapshotToken().length());
        assertFalse(service.regions().isEmpty());
    }
}
