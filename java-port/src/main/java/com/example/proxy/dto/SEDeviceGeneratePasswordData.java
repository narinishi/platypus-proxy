package com.example.proxy.dto;

import com.google.gson.annotations.SerializedName;

public class SEDeviceGeneratePasswordData {
    @SerializedName("device_password")
    private String devicePassword;

    public String getDevicePassword() { return devicePassword; }
}
