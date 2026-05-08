package com.example.proxy.dto;

import com.google.gson.annotations.SerializedName;

public class SERegisterDeviceData {
    @SerializedName("client_type")
    private String clientType;
    @SerializedName("device_id")
    private String deviceId;
    @SerializedName("device_password")
    private String devicePassword;

    public String getDeviceId() { return deviceId; }
    public String getDevicePassword() { return devicePassword; }
}
