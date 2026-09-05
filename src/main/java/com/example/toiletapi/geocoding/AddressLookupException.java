package com.example.toiletapi.geocoding;

public class AddressLookupException extends RuntimeException {
    public AddressLookupException() {
        super("선택한 위치의 주소를 확인하지 못했습니다. 위치를 확인한 뒤 다시 시도해 주세요.");
    }
}
