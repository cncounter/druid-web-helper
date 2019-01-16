package com.cncounter.druid.web.handler;

import com.alibaba.fastjson.JSONObject;

import java.util.List;

/**
 * 状态合并
 */
public interface DruidStatMergeHandlerAPI {

    /**
     * 可以处理的URI-模式列表
     * @return
     */
    public List<String> canProcessUriPatterns();
    /**
     * 合并结果
     * @param multiResponse 请求得到的多个结果
     * @return 合并后的单个结果
     */
    public JSONObject merge(List<JSONObject> multiResponse);
}
