/*
 * Copyright 1999-2101 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cncounter.druid.web.filter;

import com.alibaba.druid.stat.DruidStatService;
import com.alibaba.druid.support.logging.Log;
import com.alibaba.druid.support.logging.LogFactory;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.cncounter.druid.web.handler.DruidStatMergeHandlerAPI;
import com.cncounter.druid.web.handler.impl.ResetAllStatMergeHandler;
import com.cncounter.druid.web.handler.impl.SqlStatMergeHandler;
import com.cncounter.druid.web.handler.impl.WebAppStatMergeHandler;
import com.cncounter.druid.web.handler.impl.WebUriStatMergeHandler;
import com.cncounter.druid.web.util.HttpProxyUtil;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用于拦截StatView的监控请求,并收集集群中的数据,进行合并处理
 */
public class StatViewClusterFilter implements Filter {

    private final static Log LOG = LogFactory.getLog(StatViewClusterFilter.class);
    // 有意义的错误提示信息
    private static final ThreadLocal<String> errorTip = new ThreadLocal<String>();

    public static final String PARAM_NAME_DRUID_PATH = "druidServletPath";
    public static final String PARAM_NAME_CLUSTERLIST = "druidClusterList";
    public static final String PARAM_NAME_MERGEHANDLERMAPPING = "mergeHandlerMapping";
    /**
     * 预处理的HTTP请求头名称,如果含有此请求头,则不进行拦截
     * 如果没有,且符合拦截规则,则进行拦截,并(加入此请求头)请求各台集群服务器,将结果汇总后返回.
     */
    public final static String HTTP_HEADER_NAME_STAT_NODE = "x-stat-cluster-node";

    // 节点名称,用来设置 HTTP_HEADER_NAME_STAT_PROCESSED; 判断是否是本机
    public String STAT_NODE_NAME = UUID.randomUUID().toString().toLowerCase().replace("-", "");
    public String druidServletPath = "/druid";
    public Set<String> mergeHandlerClassNames = new HashSet<String>();
    public Map<String, DruidStatMergeHandlerAPI> mergeHandlerMapping = new ConcurrentHashMap<String, DruidStatMergeHandlerAPI>();
    private List<String> clusterList = new ArrayList<String>();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        errorTip.remove();
        // 放过
        if (isProcessed(httpRequest)) {
            chain.doFilter(request, response);
            return;
        }
        // 执行代理
        boolean processOk = false;
        String errorMessage = "StatViewClusterFilter处理错误!";
        try{
            processOk = process(httpRequest, httpResponse);
            if(!processOk && null != errorTip.get()){
                errorMessage = errorTip.get();
            }
        } catch (Throwable ex){
            errorMessage = ex.getMessage();
            LOG.error(errorMessage, ex);
        }
        //
        if(!processOk){
            //writeError(httpRequest, httpResponse, errorMessage);
            chain.doFilter(request, response); // 如果出错, 直接放过
        }
        errorTip.remove();
    }

    private void writeError(HttpServletRequest httpRequest, HttpServletResponse httpResponse, String errorMessage) throws IOException {
        String result = DruidStatService.returnJSONResult(DruidStatService.RESULT_CODE_ERROR, errorMessage);
        httpResponse.getWriter().print(result);
    }

    private boolean process(HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException {


        // 获取URI
        String requestPath = getRequestURIPath(httpRequest);
        if (requestPath == null) {
            return false;
        }
        // 执行 cluster 请求
        List<JSONObject> multiResponse = doProxyClusterQuery(httpRequest);

        if(null == multiResponse || multiResponse.isEmpty()){
            return false;
        }

        DruidStatMergeHandlerAPI handler = getHandler(requestPath);
        if(null == handler){
            return false;
        }
        // 执行合并
        String result = "";
        JSONObject content = handler.merge(multiResponse);
        if(null == content){
            result = DruidStatService.returnJSONResult(DruidStatService.RESULT_CODE_ERROR, "合并结果集失败");
        } else {
            content.putIfAbsent(HTTP_HEADER_NAME_STAT_NODE, STAT_NODE_NAME);
            result = content.toJSONString();
        }

        httpResponse.getWriter().print(result);

        return true;
    }

    private List<JSONObject> doProxyClusterQuery(HttpServletRequest request) {
        List<JSONObject> multiResponse = Collections.synchronizedList(new ArrayList<JSONObject>());
        // 获取
        // 执行代理

        // 采用线程池并发执行请求: 设置超时机制; 错误的结果集-忽略
        // 如果所有请求全部错误,则返回错误信息

        // 需要处理的数据: -- 最好找一个 HTTP Proxy... 只改写目的地址,以及 header
        // get 数据
        // post 数据
        // header 信息头


        /**
         * 示例:
         http://dev.exam.yiboshi.com/manage/druid/weburi.json?orderBy=URI&orderType=desc&page=1&perPageCount=1000000&
         */

        // 请求资源URI
        String requestPath = getRequestURIPath(request);
        //
        request.setAttribute(HttpProxyUtil.ATTR_STAT_NODE, HTTP_HEADER_NAME_STAT_NODE);
        request.setAttribute(HTTP_HEADER_NAME_STAT_NODE, STAT_NODE_NAME);
        // 请求URL
        // 获取cluster服务器URL
        for(String targetHostPrefix : clusterList){
            targetHostPrefix = targetHostPrefix.trim();
            // + druidServletPath
            String targetUri =  targetHostPrefix;// + requestPath;
            //
            // 依次处理
            try {
                String result = HttpProxyUtil.proxy(request, targetUri);
                if(null != result && result.trim().startsWith("{")){
                    JSONObject jsonObject = JSON.parseObject(result);
                    if(null != jsonObject){
                        multiResponse.add(jsonObject);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        //
        return multiResponse;
    }


    public String getRequestURI(HttpServletRequest request) {
        return request.getRequestURI();
    }

    public String getRequestURIPath(HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        if(null == requestURI){return null;}
        String contextPath = request.getContextPath();

        int offset = contextPath.length() + druidServletPath.length();
        if(requestURI.length() < offset){
            return requestURI;
        }
        String requestPath = requestURI.substring(offset);
        //
        return requestPath;
    }

    // 返回 true,则表示已经处理,直接放过
    public boolean isProcessed(HttpServletRequest request) {
        if (null == request) {
            return true;
        }
        // 已有拦截器处理标记,直接放过:
        String nodeHeader = request.getHeader(HTTP_HEADER_NAME_STAT_NODE);
        if (null != nodeHeader && !nodeHeader.trim().isEmpty()) {
            return true;
        }
        String nodeParam = request.getParameter(HTTP_HEADER_NAME_STAT_NODE);
        if (null != nodeParam && !nodeParam.trim().isEmpty()) {
            return true;
        }
        //
        String requestPath = getRequestURIPath(request);
        if (requestPath == null) {
            return true;
        }
        //  对比时校验时去除;jsessionid=
        //  对比时去除大写的 ;jsessionid=
        int index = requestPath.indexOf(";jsessionid=");
        if (index != -1) {
            requestPath = requestPath.substring(0, index);
        }
        int index2 = requestPath.indexOf(";JSESSIONID=");
        if (index2 != -1) {
            requestPath = requestPath.substring(0, index2);
        }
        // 如果不在可处理请求URL列表之中,则直接放过,不进行处理
        if(canProcess(requestPath)){
            return false;
        }


        return true;
    }
    public boolean canProcess(String requestURI) {
        if (requestURI == null) {
            return false;
        }
        // 集群列表为空,或者只有1个,则不进行拦截
        if(null == clusterList || clusterList.isEmpty()){
            return false;
        }
        if(1== clusterList.size()){
            // return false; // 单个也进行处理,方便测试
        }
        // 根据配置的映射列表,查找URI
        if(mergeHandlerMapping.containsKey(requestURI)){
            return true;
        }
        //
        return false;
    }

    public DruidStatMergeHandlerAPI getHandler(String requestURI) {
        return mergeHandlerMapping.get(requestURI);
    }

    @Override
    public void init(FilterConfig config) throws ServletException {
        {
            String _clusterList = null;//Config.getItem(PARAM_NAME_CLUSTERLIST, "");
            if(null == _clusterList || _clusterList.isEmpty()){
                _clusterList = config.getInitParameter(PARAM_NAME_CLUSTERLIST);
            }
            if (_clusterList != null && !_clusterList.trim().isEmpty()) {
                clusterList =Arrays.asList(_clusterList.split("\\s*(,|;|\\n)\\s*"));
            }
        }

        {
            String _druidServletPath = config.getInitParameter(PARAM_NAME_DRUID_PATH);
            if (_druidServletPath != null && !_druidServletPath.trim().isEmpty()) {
                druidServletPath = _druidServletPath.trim();
            }
        }

        {
            String _mergeHandlerMapping = config.getInitParameter(PARAM_NAME_MERGEHANDLERMAPPING);
            if (_mergeHandlerMapping != null && !_mergeHandlerMapping.trim().isEmpty()) {
                _mergeHandlerMapping = _mergeHandlerMapping.trim();
                String[] handlerMappingStr =  _mergeHandlerMapping.split("\\s*(,|;|\\n)\\s*");
                for(String mapping : handlerMappingStr){
                    if(null == mapping || mapping.trim().isEmpty()){
                        continue;
                    }
                    mapping = mapping.trim();
                    String[] mappingArray = mapping.split("=");
                    if(null == mappingArray || mappingArray.length != 2){
                        continue;
                    }
                    String key = mappingArray[0];
                    String className = mappingArray[1];
                    className = className.trim();
                    if(className.isEmpty()){
                        continue;
                    }
                    //
                    try {
                        Class clazz = Class.forName(className);
                        Object obj = clazz.newInstance();
                        if(obj instanceof DruidStatMergeHandlerAPI){
                            mergeHandlerMapping.put(key,(DruidStatMergeHandlerAPI)obj);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    // 获取内置的 Handler； 可以由客户自定义配置给覆盖.
    public Set<String> getBuiltInHandlerNames(){
        Set<String> classNames = new HashSet<String>();
        //
        classNames.add(ResetAllStatMergeHandler.class.getName());
        classNames.add(SqlStatMergeHandler.class.getName());
        classNames.add(WebAppStatMergeHandler.class.getName());
        classNames.add(WebUriStatMergeHandler.class.getName());
        //
        return classNames;
    }

    @Override
    public void destroy() {
        //
    }
}
