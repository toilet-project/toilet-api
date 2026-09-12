package com.example.toiletapi.photo;

public interface PhotoStore {
    void put(String key, byte[] image);
    byte[] get(String key);
    void delete(String key);
}
