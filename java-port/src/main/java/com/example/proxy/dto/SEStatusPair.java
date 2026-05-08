package com.example.proxy.dto;

import com.google.gson.annotations.JsonAdapter;

@JsonAdapter(SEStatusPairDeserializer.class)
public class SEStatusPair {
    private long code;
    private String message;

    public SEStatusPair(long code, String message) {
        this.code = code;
        this.message = message;
    }

    public long getCode() { return code; }
    public String getMessage() { return message; }
}
