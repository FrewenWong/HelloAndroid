package com.frewen.android.demo.logic.samples.tiktok.fragments

import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.paging.DataSource
import androidx.paging.ItemKeyedDataSource
import androidx.paging.PagedList
import com.frewen.android.demo.logic.model.Post
import com.frewen.aura.framework.mvvm.vm.AbsPagedListViewModel
import javax.inject.Inject

/**
 * @filename: MsgViewModel
 * @introduction:
 *
 *
 * @author: Frewen.Wong
 * @time: 2020/9/5 15:01
 * @version: 1.0.0
 * @copyright: Copyright ©2020 Frewen.Wong. All Rights Reserved.
 */
class HomeViewModel @Inject constructor() : AbsPagedListViewModel<Post>() {

    /**
     * Kotlin中，我们使用TAG 一般使用伴生对象
     */
    companion object {
        private const val TAG = "HomeViewModel"
    }

    /**
     * 由于LiveData和MutableLiveData都是一个概念的东西(只是作用范围不同)所以就不重复解释了,直接理解LiveData就可以明白MutableLiveData
     * 直接理解LiveData的字面意思是前台数据,其实这其实是很准确的表达.下面我们来说说LiveData的几个特征:
     * 1.首先LiveData其实与数据实体类(POJO类)是一样的东西,它负责暂存数据.
     * 2.其次LiveData其实也是一个观察者模式的数据实体类,它可以跟它注册的观察者回调数据是否已经更新.
     * 3.LiveData还能知晓它绑定的Activity或者Fragment的生命周期,它只会给前台活动的activity回调(这个很厉害).
     * 这样你可以放心的在它的回调方法里直接将数据添加到View,而不用担心会不会报错.(你也可以不用费心费力判断Fragment是否还存活)
     * LiveData与MutableLiveData区别
     *
     * LiveData与MutableLiveData的其实在概念上是一模一样的.唯一几个的区别如下:
     * 1.MutableLiveData的父类是LiveData
     * 2.LiveData在实体类里可以通知指定某个字段的数据更新.
     * 3.MutableLiveData则是完全是整个实体类或者数据类型变化后才通知.不会细节到某个字段
     */
    private val cacheLiveData: MutableLiveData<PagedList<Post>> = MutableLiveData()

    private var mFeedType: String? = null

    /**
     * 是否是缓存的标志变量
     */
    @Volatile
    private var withCache: Boolean = false


    fun getCacheLiveData(): MutableLiveData<PagedList<Post>> {
        return cacheLiveData
    }

    fun setFeedType(feedType: String?) {
        mFeedType = feedType
    }

    /**
     * 创建PagedList的DataSource
     */
    override fun createDataSource(): DataSource<Int, Post> {
        return PostDataSource()
    }

    /**
     *
     */
    inner class PostDataSource : ItemKeyedDataSource<Int, Post>() {
        override fun getKey(item: Post): Int {
            return item.id
        }

        override fun loadInitial(params: LoadInitialParams<Int>, callback: LoadInitialCallback<Post>) {
            Log.d(Companion.TAG, "loadInitial() called with: params = $params, callback = $callback")
            // 进行ViewModel里面页面逻辑实现的数据请求
            loadData(0, params.requestedLoadSize, callback)
            withCache = false
        }

        override fun loadAfter(params: LoadParams<Int>, callback: LoadCallback<Post>) {
            Log.d(TAG, "loadAfter() called with: params = $params, callback = $callback")
            loadData(params.key, params.requestedLoadSize, callback as LoadInitialCallback<Post>)
        }

        override fun loadBefore(params: LoadParams<Int>, callback: LoadCallback<Post>) {
            Log.d(TAG, "loadBefore() called with: params = $params, callback = $callback")
            callback.onResult(emptyList<Any>() as MutableList<Post>)
        }

    }


    /**
     * ViewModel里面进行数据的逻辑请求的具体方法
     */
    private fun loadData(key: Int, requestedLoadSize: Int, callback: ItemKeyedDataSource.LoadInitialCallback<Post>) {


    }
}