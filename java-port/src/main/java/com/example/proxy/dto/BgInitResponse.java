package com.example.proxy.dto;

import com.google.gson.annotations.SerializedName;

public class BgInitResponse {
    @SerializedName("ver")
    private String ver;
    @SerializedName("key")
    private long key;
    @SerializedName("country")
    private String country;
    @SerializedName("blocked")
    private boolean blocked;
    @SerializedName("permanent")
    private boolean permanent;

    public String getVer() { return ver; }
    public void setVer(String ver) { this.ver = ver; }
    public long getKey() { return key; }
    public void setKey(long key) { this.key = key; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
    public boolean isBlocked() { return blocked; }
    public void setBlocked(boolean blocked) { this.blocked = blocked; }
    public boolean isPermanent() { return permanent; }
    public void setPermanent(boolean permanent) { this.permanent = permanent; }
}
