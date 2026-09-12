package com.example.toiletapi.review;

/** Only fixed, user-safe messages. Never include request bodies or device coordinates. */
public final class ReviewFailure extends RuntimeException {
    private final int status;
    private final String code;
    private final ReviewModels.CreationStatus creationStatus;
    public ReviewFailure(int status, String code, String message) {
        this(status, code, message, null);
    }
    public ReviewFailure(int status, String code, String message, ReviewModels.CreationStatus creationStatus) {
        super(message); this.status = status; this.code = code; this.creationStatus = creationStatus;
    }
    public int status() { return status; }
    public String code() { return code; }
    public ReviewModels.CreationStatus creationStatus() { return creationStatus; }
}
