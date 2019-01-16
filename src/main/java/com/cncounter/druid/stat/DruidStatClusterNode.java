package com.cncounter.druid.stat;

/**
 * Druid-监控节点信息
 */
public class DruidStatClusterNode {

    // id,全局唯一, 如123; 不能出现 -
    private String id;
    // 基础url
    // 如 http://127.0.0.1:8080/druid/
    private String baseUrl;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }//
}