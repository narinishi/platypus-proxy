package com.example.proxy.dto;

import com.google.gson.annotations.SerializedName;

public class SERegisterSubscriberResponse {
    @SerializedName("data")
    private Object data;
    @SerializedName("return_code")
    private SEStatusPair status;

    public SEStatusPair getStatus() { return status; }
}
