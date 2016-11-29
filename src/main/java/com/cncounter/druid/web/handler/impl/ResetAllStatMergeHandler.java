package com.cncounter.druid.web.handler.impl;

import com.alibaba.fastjson.JSONObject;
import com.cncounter.druid.web.handler.DruidStatMergeHandlerAPI;

import java.util.List;

/**
 * reset-all.json 解析处理器
 */
public class ResetAllStatMergeHandler implements DruidStatMergeHandlerAPI {

    public final Integer ONE = 1;

    private String canProcessUriStr = "/reset-all.json";
    public String canProcessUri() {
        return canProcessUriStr;
    }
    /**
     * 合并结果
     *
     * @param multiResponse 请求得到的多个结果
     * @return 合并后的单个结果
     */
    @Override
    public JSONObject merge(List<JSONObject> multiResponse) {
        JSONObject result = null;
        if(null == multiResponse || multiResponse.isEmpty()){
            return null;
        }
        if(1 == multiResponse.size()){
            result = multiResponse.get(0);
            return result;
        }
        //
        //
        for(JSONObject responseObject : multiResponse){
            if(null == responseObject){continue;}
            Integer ResultCode = responseObject.getInteger("ResultCode");
            if (ONE.equals(ResultCode)){
                // 此接口;成功 1个即可
                result = responseObject;
                return result;
            }
        }
        result = multiResponse.get(0);
        return result;
    }
}
