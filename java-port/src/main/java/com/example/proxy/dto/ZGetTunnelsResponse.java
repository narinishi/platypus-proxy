package com.example.proxy.dto;

import com.google.gson.annotations.SerializedName;
import java.util.Map;

public class ZGetTunnelsResponse {
    @SerializedName("agent_key")
    private String agentKey;
    @SerializedName("agent_types")
    private Map<String, String> agentTypes;
    @SerializedName("ip_list")
    private Map<String, String> ipList;
    @SerializedName("port")
    private PortMap port;
    @SerializedName("protocol")
    private Map<String, String> protocol;
    @SerializedName("vendor")
    private Map<String, String> vendor;
    @SerializedName("ztun")
    private Map<String, String[]> ztun;

    public String getAgentKey() { return agentKey; }
    public void setAgentKey(String agentKey) { this.agentKey = agentKey; }
    public Map<String, String> getAgentTypes() { return agentTypes; }
    public void setAgentTypes(Map<String, String> agentTypes) { this.agentTypes = agentTypes; }
    public Map<String, String> getIpList() { return ipList; }
    public void setIpList(Map<String, String> ipList) { this.ipList = ipList; }
    public PortMap getPort() { return port; }
    public void setPort(PortMap port) { this.port = port; }
    public Map<String, String> getProtocol() { return protocol; }
    public void setProtocol(Map<String, String> protocol) { this.protocol = protocol; }
    public Map<String, String> getVendor() { return vendor; }
    public void setVendor(Map<String, String> vendor) { this.vendor = vendor; }
    public Map<String, String[]> getZtun() { return ztun; }
    public void setZtun(Map<String, String[]> ztun) { this.ztun = ztun; }

    public static class PortMap {
        @SerializedName("direct")
        private int direct;
        @SerializedName("hola")
        private int hola;
        @SerializedName("peer")
        private int peer;
        @SerializedName("trial")
        private int trial;
        @SerializedName("trial_peer")
        private int trialPeer;

        public int getDirect() { return direct; }
        public void setDirect(int direct) { this.direct = direct; }
        public int getHola() { return hola; }
        public void setHola(int hola) { this.hola = hola; }
        public int getPeer() { return peer; }
        public void setPeer(int peer) { this.peer = peer; }
        public int getTrial() { return trial; }
        public void setTrial(int trial) { this.trial = trial; }
        public int getTrialPeer() { return trialPeer; }
        public void setTrialPeer(int trialPeer) { this.trialPeer = trialPeer; }
    }
}
