package com.example.proxy.dto;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class SEDiscoverResponse {
    @SerializedName("data")
    private DiscoverData data;
    @SerializedName("return_code")
    private SEStatusPair status;

    public List<SEIPEntry> getIPs() { return data != null ? data.ips : null; }
    public SEStatusPair getStatus() { return status; }

    private static class DiscoverData {
        @SerializedName("ips")
        private List<SEIPEntry> ips;
    }
}
