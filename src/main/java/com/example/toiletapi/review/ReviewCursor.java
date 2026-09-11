package com.example.toiletapi.review;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

/** Untrusted pagination position, never an authorization token. Scope is always reapplied in SQL. */
record ReviewCursor(LocalDateTime createdAt, long id) {
    String encode() { return Base64.getUrlEncoder().withoutPadding().encodeToString((createdAt+"|"+id).getBytes(StandardCharsets.US_ASCII)); }
    static ReviewCursor parse(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        try {
            if (encoded.length()>100) throw new IllegalArgumentException();
            String[] parts = new String(Base64.getUrlDecoder().decode(encoded),StandardCharsets.US_ASCII).split("\\|",-1);
            if(parts.length!=2)throw new IllegalArgumentException();
            var date=LocalDateTime.parse(parts[0]);long id=Long.parseLong(parts[1]);
            if(id<=0 || date.getYear()<1000 || date.getYear()>9999)throw new IllegalArgumentException();
            return new ReviewCursor(date,id);
        } catch(RuntimeException invalid) {throw new IllegalArgumentException("목록 위치가 올바르지 않아요. 처음부터 다시 불러와 주세요.");}
    }
}
