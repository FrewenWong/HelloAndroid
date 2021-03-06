package com.frewen.network.request;

import android.content.Context;
import android.text.TextUtils;

import com.frewen.network.core.FreeRxHttp;
import com.frewen.network.model.HttpHeaders;
import com.frewen.network.model.HttpParams;


import okhttp3.HttpUrl;

/**
 * @filename: BaseRequest
 * @introduction:
 * @author: Frewen.Wong
 * @time: 2019/4/15 0015 下午4:39
 * Copyright ©2019 Frewen.Wong. All Rights Reserved.
 */
public abstract class BaseRequest<R extends BaseRequest> {


    private Context mContext;
    protected String baseUrl;                                              //BaseUrl
    protected String url;                                                  //请求url
    protected long readTimeOut;                                            //读超时
    protected long writeTimeOut;                                           //写超时
    protected long connectTimeout;                                         //链接超时
    protected int retryCount;                                              //重试次数默认3次

    private HttpUrl httpUrl;
    protected HttpHeaders headers = new HttpHeaders();
    protected HttpParams params = new HttpParams();                        //添加的param

    public BaseRequest(String url) {
        this.url = url;
        mContext = FreeRxHttp.getInstance().getContext();

        FreeRxHttp httpClient = FreeRxHttp.getInstance();

        this.baseUrl = httpClient.getBaseUrl();

        if (!TextUtils.isEmpty(this.baseUrl)) {
            httpUrl = HttpUrl.parse(baseUrl);
        }

        if (null == this.baseUrl && !TextUtils.isEmpty(this.url)
                && (url.startsWith("http://") || url.startsWith("https://"))) {
            httpUrl = HttpUrl.parse(url);
            baseUrl = httpUrl.url().getProtocol() + "://" + httpUrl.url().getHost() + "/";
        }
        //超时重试次数
        retryCount = httpClient.getRetryCount();

        //默认添加 Accept-Language
        String acceptLanguage = HttpHeaders.getAcceptLanguage();
        if (!TextUtils.isEmpty(acceptLanguage)) {
            addHeader(HttpHeaders.HEAD_KEY_ACCEPT_LANGUAGE, acceptLanguage);
        }

        //默认添加 User-Agent
        String userAgent = HttpHeaders.getUserAgent();
        if (!TextUtils.isEmpty(userAgent)) {
            addHeader(HttpHeaders.HEAD_KEY_USER_AGENT, userAgent);
        }
        //添加公共请求参数
        if (httpClient.getCommonParams() != null) {
            params.put(httpClient.getCommonParams());
        }
        // 添加公共请求参数头
        if (httpClient.getCommonHeaders() != null) {
            headers.put(httpClient.getCommonHeaders());
        }
    }

    /**
     * 添加头信息
     */
    public R addHeaders(HttpHeaders headers) {
        this.headers.put(headers);
        return (R) this;
    }

    /**
     * 添加头信息
     */
    public R addHeader(String key, String value) {
        headers.put(key, value);
        return (R) this;
    }

}
