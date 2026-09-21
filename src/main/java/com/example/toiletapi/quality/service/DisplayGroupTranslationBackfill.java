package com.example.toiletapi.quality.service;

import com.example.toiletapi.quality.repository.ToiletDisplayGroupRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "display-group-translation.enabled", havingValue = "true")
public class DisplayGroupTranslationBackfill {
    private static final Logger log = LoggerFactory.getLogger(DisplayGroupTranslationBackfill.class);
    private final ToiletDisplayGroupRepository repository;
    private final DisplayGroupTranslator translator;

    @Scheduled(initialDelayString = "${display-group-translation.backfill-initial-delay-ms:30000}",
            fixedDelayString = "${display-group-translation.backfill-delay-ms:300000}")
    public void backfill() {
        var sources = repository.findGroupsNeedingEnglishTranslation(100);
        if (sources.isEmpty()) return;
        try {
            var translated = translator.translate(sources.stream()
                    .map(ToiletDisplayGroupRepository.DisplayGroupSource::sourceName).toList());
            for (int index = 0; index < sources.size(); index++) {
                var source = sources.get(index);
                repository.saveMachineEnglishDisplayName(source.groupId(), source.sourceName(), translated.get(index));
            }
            log.info("display group English translation backfill applied count={}", sources.size());
        } catch (DisplayGroupTranslationException exception) {
            log.warn("display group English translation backfill deferred count={}", sources.size());
        }
    }
}
