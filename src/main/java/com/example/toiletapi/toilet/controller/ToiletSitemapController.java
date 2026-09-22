package com.example.toiletapi.toilet.controller;

import com.example.toiletapi.toilet.service.ToiletSitemapService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/toilets/sitemap")
public class ToiletSitemapController {
    private final ToiletSitemapService service;

    @GetMapping("/shards")
    public ResponseEntity<List<Long>> shards(@RequestParam(required = false) String locale) {
        return response(locale == null ? service.shards() : service.localizedShards(locale));
    }

    @GetMapping("/ids")
    public ResponseEntity<List<Long>> ids(@RequestParam long shard) { return response(service.ids(shard)); }

    @GetMapping("/entries")
    public ResponseEntity<List<ToiletSitemapService.SitemapEntry>> entries(
            @RequestParam long shard, @RequestParam(defaultValue = "ko") String locale) {
        return response(service.entries(shard, locale));
    }

    private <T> ResponseEntity<List<T>> response(List<T> entries) {
        return ResponseEntity.ok().header("X-Robots-Tag", "noindex")
                .header("Cache-Control", "public, max-age=300").body(entries);
    }
}
