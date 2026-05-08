package com.example.proxy.dto;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

public class FallbackConfig {
    private List<FallbackAgent> agents;
    private long updatedAt;
    private long ttlMs;

    public FallbackConfig() {
        this.agents = new ArrayList<>();
        this.updatedAt = System.currentTimeMillis();
        this.ttlMs = 3600000;
    }

    public List<FallbackAgent> getAgents() {
        return agents;
    }

    public void setAgents(List<FallbackAgent> agents) {
        this.agents = agents;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public long getTtlMs() {
        return ttlMs;
    }

    public void setTtlMs(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() - updatedAt > ttlMs;
    }

    public void shuffleAgents() {
        Collections.shuffle(agents);
    }

    public FallbackConfig clone() {
        FallbackConfig copy = new FallbackConfig();
        copy.setAgents(new ArrayList<>(agents));
        copy.setUpdatedAt(updatedAt);
        copy.setTtlMs(ttlMs);
        return copy;
    }

    public static class FallbackAgent {
        private String name;
        private String ip;
        private int port;

        public FallbackAgent() {}

        public FallbackAgent(String name, String ip, int port) {
            this.name = name;
            this.ip = ip;
            this.port = port;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getIp() {
            return ip;
        }

        public void setIp(String ip) {
            this.ip = ip;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String hostname() {
            return name + ".hola.org";
        }

        public String netAddr() {
            return ip + ":" + port;
        }
    }
}