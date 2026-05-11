package com.example.proxy.dto;

import com.google.gson.annotations.SerializedName;

public class ChromeVerResponse {
    @SerializedName("versions")
    private Version[] versions;

    public String getVersion() {
        if (versions != null && versions.length > 0) {
            return versions[0].version;
        }
        return null;
    }

    static class Version {
        @SerializedName("version")
        String version;
    }
}
