package com.example.proxy.dto;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class SEGeoListResponse {
    @SerializedName("data")
    private GeoListData data;
    @SerializedName("return_code")
    private SEStatusPair status;

    public List<SEGeoEntry> getGeos() { return data != null ? data.geos : null; }
    public SEStatusPair getStatus() { return status; }

    private static class GeoListData {
        @SerializedName("geos")
        private List<SEGeoEntry> geos;
    }
}
