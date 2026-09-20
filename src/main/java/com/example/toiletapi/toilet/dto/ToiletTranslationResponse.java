package com.example.toiletapi.toilet.dto;

import com.example.toiletapi.toilet.translation.ToiletTranslationModels.Text;

/**
 * 언어별 화장실 표시 문구입니다. 원본 필드는 기존 응답에 그대로 유지됩니다.
 */
public record ToiletTranslationResponse(
        String name,
        String roadAddress,
        String jibunAddress
) {
    public static ToiletTranslationResponse from(Text text) {
        return new ToiletTranslationResponse(text.name(), text.roadAddress(), text.jibunAddress());
    }
}
