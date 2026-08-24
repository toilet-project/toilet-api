package com.example.toiletapi.global.exception;

/**
 * 요청한 화장실을 찾을 수 없을 때 발생합니다.
 */
public class ToiletNotFoundException extends RuntimeException {

    public ToiletNotFoundException(Long toiletId) {
        super("화장실을 찾을 수 없습니다. toiletId=" + toiletId);
    }
}
