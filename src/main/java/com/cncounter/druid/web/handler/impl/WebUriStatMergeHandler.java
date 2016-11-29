package com.cncounter.druid.web.handler.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.cncounter.druid.web.handler.DruidStatMergeHandlerAPI;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * weburi.json 解析处理器
 */
public class WebUriStatMergeHandler implements DruidStatMergeHandlerAPI {

    public final Integer ONE = 1;
    //
    private String keyFieldname = "URI";

    private Set<String> sumFieldNames = getSumFieldNames();
    private Set<String> maxFieldNames = getMaxFieldNames();
    private Set<String> sumArrayFieldNames = getSumArrayFieldNames();

    private String canProcessUriStr = "/weburi.json";
    public String canProcessUri() {
        return canProcessUriStr;
    }
    //
    public String getKeyFieldName(){
        return keyFieldname;
    }
    public Set<String> getSumFieldNames(){
        final String[] fieldNames = {
                "RequestCount",
                "RequestTimeMillis",
                "RunningCount",
                "JdbcExecuteCount",
                "JdbcExecuteErrorCount",
                "JdbcExecuteTimeMillis",
                "JdbcCommitCount",
                "JdbcRollbackCount",
                "JdbcFetchRowCount",
                "JdbcRollbackCount",
                "JdbcUpdateCount"
        };
        //
        Set<String> fieldSet = new HashSet<String>();
        for(String field: fieldNames){
            fieldSet.add(field);
        }
        return fieldSet;
    }
    // 数组内部依次相加
    public Set<String> getSumArrayFieldNames(){
        String[] fieldNames = {
                "Histogram"
        };
        //
        Set<String> fieldSet = new HashSet<String>();
        for(String field: fieldNames){
            fieldSet.add(field);
        }
        return fieldSet;
    }
    // 求最大值的集合
    public Set<String> getMaxFieldNames(){
        String[] fieldNames = {
                "ConcurrentMax"
        };
        Set<String> fieldSet = new HashSet<String>();
        for(String field: fieldNames){
            fieldSet.add(field);
        }
        return fieldSet;
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
        List<JSONObject> okResponse = new ArrayList<JSONObject>();
        //
        for(JSONObject responseObject : multiResponse){
            if(null == responseObject){continue;}
            Integer ResultCode = responseObject.getInteger("ResultCode");
            if (ONE.equals(ResultCode)){
                // 成功
                okResponse.add(responseObject);
            }
        }
        if(okResponse.isEmpty()){
            result = multiResponse.get(0);
        } else {
            result = doMergeUri(okResponse);
        }
        return result;
    }
    //
    private JSONObject doMergeUri(List<JSONObject> okResponseList){
        // 值为数组
        //
        JSONObject result = new JSONObject();
        //
        JSONArray resultContent = new JSONArray();
        result.put("ResultCode", ONE);
        result.put("Content", resultContent);
        //
        for(JSONObject temp : okResponseList){
            //
            JSONArray Content = temp.getJSONArray("Content");
            //
            if(null == Content || Content.isEmpty()){
                continue;
            }
            // 遍历 Content
            int size = Content.size();
            for(int i =0 ; i< size; i++){
                JSONObject t = Content.getJSONObject(i);
                if(null == t){continue;}
                //
                String keyValue = t.getString(keyFieldname);

                JSONObject r = findBy(resultContent, keyValue);
                if(null == r){
                    r = new JSONObject();
                    r.put(keyFieldname, keyValue);
                    resultContent.add(r);
                }
                //
                try{
                    doMergeSingleRecord(r, t);
                } catch (Exception e){
                    // ?
                }
            }


            Set<String> keySet = temp.keySet();
            // 依次遍历所有属性
            for(String key: keySet){
                Object value = temp.get(key);

                //
                doMergeSingleRecord(result, temp);
            }
        }
        return result;
    }

    //找出同一组KEY的对象
    private JSONObject findBy(JSONArray resultContent, String keyValue) {
        int size = resultContent.size();
        for(int i =0 ; i< size; i++){
            JSONObject r = resultContent.getJSONObject(i);
            if(null == r){continue;}
            //
            String keyV = r.getString(keyFieldname);
            if(null != keyValue && keyValue.equals(keyV)){
                return r;
            }
        }
        return null;
    }

    private void doMergeSingleRecord(JSONObject result, JSONObject temp){
        // 值为数组
        //
        Set<String> keySet = temp.keySet();
        // 依次遍历所有属性
        for(String key: keySet){
            Object value = temp.get(key);
            if(null == value){
                continue;
            }
            // 如果不包含此KEY,直接设置值
            if(!result.containsKey(key)){
                result.put(key, value);
                continue;
            }
            Object v = result.get(key);
            // 如果值为NULL,则直接赋值
            if(null == v){
                result.put(key, value);
                continue;
            }
            //
            if(sumFieldNames.contains(key)){
                int intV = result.getIntValue(key);
                int intVTemp = temp.getIntValue(key);
                int sum = intV + intVTemp;
                result.put(key, sum);
                continue;
            }
            //
            if(maxFieldNames.contains(key)){
                int intV = result.getIntValue(key);
                int intVTemp = temp.getIntValue(key);
                int max =  Math.max(intV, intVTemp);
                result.put(key, max);
                continue;
            }
            //
            if(sumArrayFieldNames.contains(key)){
                // 获取Array
                JSONArray array = result.getJSONArray(key);
                JSONArray arrayTemp = result.getJSONArray(key);
                //
                int minSize = Math.min(array.size(), arrayTemp.size());
                for(int i=0; i<minSize; i++){
                    //
                    int intV = array.getIntValue(i);
                    int intVTemp = arrayTemp.getIntValue(i);
                    int sum = intV + intVTemp;
                    array.set(i, sum);
                }
                result.put(key, array);
                continue;
            }
            // 直接设置值? 忽略?
        }
    }
}
