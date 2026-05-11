package com.example.proxy.dto;

import java.util.List;

public class SEIPEntry {
    private SEGeoEntry geo;
    private String ip;
    private List<Integer> ports;

    public SEGeoEntry getGeo() { return geo; }
    public void setGeo(SEGeoEntry geo) { this.geo = geo; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public List<Integer> getPorts() { return ports; }
    public void setPorts(List<Integer> ports) { this.ports = ports; }

    public String netAddr() {
        if (ports == null || ports.isEmpty()) {
            return ip + ":443";
        }
        return ip + ":" + ports.get(0);
    }
}
