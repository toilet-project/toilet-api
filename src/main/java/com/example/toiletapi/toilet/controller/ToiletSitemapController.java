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
    public ResponseEntity<List<Long>> shards() { return response(service.shards()); }

    @GetMapping("/ids")
    public ResponseEntity<List<Long>> ids(@RequestParam long shard) { return response(service.ids(shard)); }

    private ResponseEntity<List<Long>> response(List<Long> ids) {
        return ResponseEntity.ok().header("X-Robots-Tag", "noindex")
                .header("Cache-Control", "public, max-age=300").body(ids);
    }
}
