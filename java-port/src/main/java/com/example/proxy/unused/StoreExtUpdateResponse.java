package com.example.proxy.dto;

import com.google.gson.annotations.SerializedName;

public class StoreExtUpdateResponse {
    @SerializedName("gupdate")
    private Gupdate gupdate;

    public String getVersion() {
        if (gupdate != null && gupdate.app != null && gupdate.app.updatecheck != null) {
            return gupdate.app.updatecheck.version;
        }
        return null;
    }

    static class Gupdate {
        @SerializedName("app")
        private App app;
    }
    static class App {
        @SerializedName("appid")
        String appId;
        @SerializedName("status")
        String status;
        @SerializedName("updatecheck")
        private UpdateCheck updatecheck;
    }
    static class UpdateCheck {
        @SerializedName("version")
        String version;
        @SerializedName("status")
        String status;
    }
}
