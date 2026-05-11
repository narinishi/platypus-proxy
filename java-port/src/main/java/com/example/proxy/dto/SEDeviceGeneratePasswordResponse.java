package com.example.proxy.dto;

import com.google.gson.annotations.SerializedName;

public class SEDeviceGeneratePasswordResponse {
    @SerializedName("data")
    private SEDeviceGeneratePasswordData data;
    @SerializedName("return_code")
    private SEStatusPair status;

    public SEDeviceGeneratePasswordData getData() { return data; }
    public SEStatusPair getStatus() { return status; }
}
