package com.example.toiletapi.growth;

/** Fixed, user-safe failures. Never include a review body or location in the response. */
public final class GrowthFailure extends RuntimeException {
    private final int status;
    private final String code;

    public GrowthFailure(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }
}
