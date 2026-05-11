package com.example.proxy.dto;

import com.google.gson.annotations.SerializedName;

public class SERegisterDeviceResponse {
    @SerializedName("data")
    private SERegisterDeviceData data;
    @SerializedName("return_code")
    private SEStatusPair status;

    public SERegisterDeviceData getData() { return data; }
    public SEStatusPair getStatus() { return status; }
}
