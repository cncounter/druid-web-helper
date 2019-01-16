package com.cncounter.druid.web.util;


import org.apache.http.*;
import org.apache.http.client.HttpClient;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.client.params.CookiePolicy;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.message.HeaderGroup;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;
import org.apache.http.util.EntityUtils;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.BitSet;
import java.util.Enumeration;
import java.util.Formatter;

/**
 * Http 代理请求工具
 */

public class HttpProxyUtil {

    public static final String ATTR_STAT_NODE = "HttpProxyUtil.STAT_NODE";

    /**
     * 代理获取内容...
     * @param servletRequest
     * @param targetUri
     * @return
     * @throws Exception
     */
    public static String proxy(HttpServletRequest servletRequest, String targetUri)
            throws Exception {
        String result = "";
        URI targetUriObj = new URI(targetUri);
        HttpHost targetHost = URIUtils.extractHost(targetUriObj);

        HttpParams hcParams = new BasicHttpParams();
        hcParams.setParameter(ClientPNames.COOKIE_POLICY, CookiePolicy.IGNORE_COOKIES);
        hcParams.setBooleanParameter(ClientPNames.HANDLE_REDIRECTS, false); // See #70

        HttpClient proxyClient = createHttpClient(hcParams);

        // Make the Request
        String method = servletRequest.getMethod();
        String proxyRequestUri = rewriteUrlFromRequest(servletRequest, targetUri);
        HttpRequest proxyRequest = new BasicHttpRequest(method, proxyRequestUri);

        copyRequestHeaders(servletRequest, proxyRequest, targetHost);
        setXForwardedForHeader(servletRequest, proxyRequest);

        HttpResponse proxyResponse = null;
        try {

            proxyResponse = proxyClient.execute(targetHost, proxyRequest);

            HttpEntity entity = proxyResponse.getEntity();
            if (entity != null) {
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                entity.writeTo(byteArrayOutputStream);
                result = byteArrayOutputStream.toString("UTF-8");
                //
                closeQuietly(byteArrayOutputStream);
            }

        } finally {
            // make sure the entire entity was consumed, so the connection is released
            if (proxyResponse != null){
                consumeQuietly(proxyResponse.getEntity());
            }
        }
        return result;
    }


    public static HttpClient createHttpClient(HttpParams hcParams) {
        try {
            //as of HttpComponents v4.2, this class is better since it uses System
            // Properties:
            Class<?> clientClazz = Class.forName("org.apache.http.impl.client.SystemDefaultHttpClient");
            Constructor<?> constructor = clientClazz.getConstructor(HttpParams.class);
            return (HttpClient) constructor.newInstance(hcParams);
        } catch (ClassNotFoundException e) {
            //no problem; use v4.1 below
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        //Fallback on using older client:
        return new DefaultHttpClient(new ThreadSafeClientConnManager(), hcParams);
    }



    public static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException e) {
        }
    }

    public static void consumeQuietly(HttpEntity entity) {
        try {
            EntityUtils.consume(entity);
        } catch (IOException e) {//ignore
        }
    }

    /** These are the "hop-by-hop" headers that should not be copied.
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html
     * I use an HttpClient HeaderGroup class instead of Set&lt;String&gt; because this
     * approach does case insensitive lookup faster.
     */
    public static final HeaderGroup hopByHopHeaders;
    static {
        hopByHopHeaders = new HeaderGroup();
        String[] headers = new String[] {
                "Connection", "Keep-Alive", "Proxy-Authenticate", "Proxy-Authorization",
                "TE", "Trailers", "Transfer-Encoding", "Upgrade" };
        for (String header : headers) {
            hopByHopHeaders.addHeader(new BasicHeader(header, null));
        }
    }

    /** Copy request headers from the servlet client to the proxy request. */
    public static void copyRequestHeaders(HttpServletRequest servletRequest, HttpRequest proxyRequest,HttpHost targetHost) {
        // Get an Enumeration of all of the header names sent by the client
        @SuppressWarnings("unchecked")
        Enumeration<String> enumerationOfHeaderNames = servletRequest.getHeaderNames();
        while (enumerationOfHeaderNames.hasMoreElements()) {
            String headerName = enumerationOfHeaderNames.nextElement();
            copyRequestHeader(servletRequest, proxyRequest, headerName, targetHost);
        }
        // 增加 处理头
        Object _statNodeHeaderName = servletRequest.getAttribute(ATTR_STAT_NODE);
        if(null != _statNodeHeaderName){
            String statNodeHeaderName = _statNodeHeaderName.toString();
            Object statNodeHeaderValue = servletRequest.getAttribute(statNodeHeaderName);
            proxyRequest.addHeader(statNodeHeaderName, String.valueOf(statNodeHeaderValue));
        }
    }

    /**
     * Copy a request header from the servlet client to the proxy request.
     * This is easily overwritten to filter out certain headers if desired.
     */
    public static void copyRequestHeader(HttpServletRequest servletRequest, HttpRequest proxyRequest,
                                     String headerName,HttpHost targetHost) {
        //Instead the content-length is effectively set via InputStreamEntity
        if (headerName.equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH))
            return;
        if (hopByHopHeaders.containsHeader(headerName))
            return;

        @SuppressWarnings("unchecked")
        Enumeration<String> headers = servletRequest.getHeaders(headerName);
        while (headers.hasMoreElements()) {//sometimes more than one value
            String headerValue = headers.nextElement();
            // In case the proxy host is running multiple virtual servers,
            // rewrite the Host header to ensure that we get content from
            // the correct virtual server
            if (headerName.equalsIgnoreCase(HttpHeaders.HOST)) {
                HttpHost host = targetHost;
                headerValue = host.getHostName();
                if (host.getPort() != -1)
                    headerValue += ":"+host.getPort();
            }
            proxyRequest.addHeader(headerName, headerValue);
        }
    }

    private static void setXForwardedForHeader(HttpServletRequest servletRequest,
                                        HttpRequest proxyRequest) {
            String headerName = "X-Forwarded-For";
            String newHeader = servletRequest.getHeader(headerName);
            if(null != newHeader && newHeader.trim().isEmpty()){
                newHeader = servletRequest.getRemoteAddr();
            }
            proxyRequest.setHeader(headerName, newHeader);
    }


    public static String rewriteUrlFromRequest(HttpServletRequest servletRequest,String targetUri) {
        StringBuilder uri = new StringBuilder(500);
        uri.append(targetUri);
        // Handle the path given to the servlet
        final String SLASH = "/";
        String pathInfo = servletRequest.getPathInfo();
        if (pathInfo != null) {//ex: /my/path.html
            if(targetUri.endsWith(SLASH) && pathInfo.startsWith(SLASH)){
                pathInfo = pathInfo.substring(1);
            }
            uri.append(encodeUriQuery(pathInfo));
        }
        // Handle the query string & fragment
        String queryString = servletRequest.getQueryString();//ex:(following '?'): name=value&foo=bar#fragment
        String fragment = null;
        //split off fragment from queryString, updating queryString if found
        if (queryString != null) {
            int fragIdx = queryString.indexOf('#');
            if (fragIdx >= 0) {
                fragment = queryString.substring(fragIdx + 1);
                queryString = queryString.substring(0,fragIdx);
            }
        }

        if (queryString != null && queryString.length() > 0) {
            uri.append('?');
            uri.append(encodeUriQuery(queryString));
        }

        if (fragment != null) {
            uri.append('#');
            uri.append(encodeUriQuery(fragment));
        }
        return uri.toString();
    }


    public static CharSequence encodeUriQuery(CharSequence in) {
        //Note that I can't simply use URI.java to encode because it will escape pre-existing escaped things.
        StringBuilder outBuf = null;
        Formatter formatter = null;
        for(int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            boolean escape = true;
            if (c < 128) {
                if (asciiQueryChars.get((int)c)) {
                    escape = false;
                }
            } else if (!Character.isISOControl(c) && !Character.isSpaceChar(c)) {//not-ascii
                escape = false;
            }
            if (!escape) {
                if (outBuf != null)
                    outBuf.append(c);
            } else {
                //escape
                if (outBuf == null) {
                    outBuf = new StringBuilder(in.length() + 5*3);
                    outBuf.append(in,0,i);
                    formatter = new Formatter(outBuf);
                }
                //leading %, 0 padded, width 2, capital hex
                formatter.format("%%%02X",(int)c);//TODO
            }
        }
        return outBuf != null ? outBuf : in;
    }

    public static final BitSet asciiQueryChars;
    static {
        char[] c_unreserved = "_-!.~'()*".toCharArray();//plus alphanum
        char[] c_punct = ",;:$&+=".toCharArray();
        char[] c_reserved = "?/[]@".toCharArray();//plus punct

        asciiQueryChars = new BitSet(128);
        for(char c = 'a'; c <= 'z'; c++) asciiQueryChars.set((int)c);
        for(char c = 'A'; c <= 'Z'; c++) asciiQueryChars.set((int)c);
        for(char c = '0'; c <= '9'; c++) asciiQueryChars.set((int)c);
        for(char c : c_unreserved) asciiQueryChars.set((int)c);
        for(char c : c_punct) asciiQueryChars.set((int)c);
        for(char c : c_reserved) asciiQueryChars.set((int)c);

        asciiQueryChars.set((int)'%');//leave existing percent escapes in place
    }

}
