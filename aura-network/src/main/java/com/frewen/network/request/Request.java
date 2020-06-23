package com.frewen.network.request;

import android.content.Context;
import android.text.TextUtils;

import com.frewen.network.api.BaseApiService;
import com.frewen.network.core.AuraRxHttp;
import com.frewen.network.model.HttpHeaders;
import com.frewen.network.model.HttpParams;


import io.reactivex.Observable;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.ResponseBody;
import retrofit2.Retrofit;

/**
 * @filename: BaseRequest
 * @introduction:
 * @author: Frewen.Wong
 * @time: 2019/4/15 0015 下午4:39
 *         Copyright ©2019 Frewen.Wong. All Rights Reserved.
 */
public abstract class Request<R extends Request> {

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
    /**
     * OkHttpClient
     */
    protected OkHttpClient okHttpClient;
    private Retrofit retrofit;
    /**
     *
     */
    protected BaseApiService apiService;

    public Request(String url) {
        this.url = url;
        mContext = AuraRxHttp.getInstance().getContext();

        AuraRxHttp auraRxHttp = AuraRxHttp.getInstance();

        this.baseUrl = auraRxHttp.getBaseUrl();

        if (!TextUtils.isEmpty(this.baseUrl)) {
            httpUrl = HttpUrl.parse(baseUrl);
        }

        if (null == this.baseUrl && !TextUtils.isEmpty(this.url)
                && (url.startsWith("http://") || url.startsWith("https://"))) {
            httpUrl = HttpUrl.parse(url);
            baseUrl = httpUrl.url().getProtocol() + "://" + httpUrl.url().getHost() + "/";
        }
        //超时重试次数
        retryCount = auraRxHttp.getRetryCount();

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
        if (auraRxHttp.getCommonParams() != null) {
            params.put(auraRxHttp.getCommonParams());
        }
        // 添加公共请求参数头
        if (auraRxHttp.getCommonHeaders() != null) {
            headers.put(auraRxHttp.getCommonHeaders());
        }
    }

    protected R build(Class<? extends BaseApiService> clazz) {
        OkHttpClient.Builder okHttpClientBuilder = generateOkClient();
        final Retrofit.Builder retrofitBuilder = generateRetrofit();
        okHttpClient = okHttpClientBuilder.build();
        retrofitBuilder.client(okHttpClient);
        retrofit = retrofitBuilder.build();
        apiService = retrofit.create(clazz);
        return (R) this;
    }

    /**
     * 构建生成的OkHttpClientBuilder
     */
    private OkHttpClient.Builder generateOkClient() {
        if (readTimeOut <= 0 && writeTimeOut <= 0 && connectTimeout <= 0 && headers.isEmpty()) {
            OkHttpClient.Builder builder = AuraRxHttp.getOkHttpClientBuilder();
            return builder;
        }
        return null;
    }

    private Retrofit.Builder generateRetrofit() {
        Retrofit.Builder builder = AuraRxHttp.getRetrofitBuilder();
        return builder;
    }


    private void getOkHttpCall() {

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
     *
     * @param key   HeaderKey
     * @param value HeaderValue
     */
    public R addHeader(String key, String value) {
        headers.put(key, value);
        return (R) this;
    }

    /**
     * 添加参数键值对
     *
     * @param key
     * @param value
     */
    public R addParam(String key, Object value) {
        // 只能是基本类型 int string byte char
        // 我们通过反射去校验
        params.put(key, value);
        return (R) this;
    }

    protected abstract Observable<ResponseBody> generateRequest();

}