package com.frewen.android.demo.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.StrictMode;
import android.util.Log;

import com.androidnetworking.AndroidNetworking;
import com.frewen.android.demo.BuildConfig;
import com.frewen.android.demo.di.AppInjector;
import com.frewen.android.demo.network.MyNetworkConfig;
import com.frewen.android.demo.network.VideoApiService;
import com.frewen.android.demo.performance.AppBlockCanaryContext;
import com.frewen.android.demo.performance.LaunchTimeRecord;
import com.frewen.android.demo.samples.hook.HookHelper;
import com.frewen.android.demo.samples.network.Constant;
import com.frewen.aura.framework.app.BaseMVPApp;
import com.frewen.aura.framework.taskstarter.ModuleProvider;
import com.frewen.aura.toolkits.concurrent.ThreadFactoryImpl;
import com.frewen.aura.toolkits.core.AuraToolKits;
import com.frewen.demo.library.network.core.NetworkApi;
import com.frewen.keepservice.KeepLiveService;
import com.frewen.network.core.AuraRxHttp;
import com.frewen.aura.toolkits.utils.ProcessInfoUtils;
import com.github.anrwatchdog.ANRError;
import com.github.anrwatchdog.ANRWatchDog;
import com.github.moduth.blockcanary.BlockCanary;
import com.tencent.bugly.crashreport.CrashReport;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;

import androidx.core.os.TraceCompat;
import dagger.android.AndroidInjector;
import dagger.android.DispatchingAndroidInjector;
import dagger.android.HasActivityInjector;

import static com.frewen.android.demo.constant.AppKeyConstants.APP_ID_BUGLY;

/**
 * NyxAndroidApp
 *
 * Application的启动的异步优化：
 *
 * 1、Theme的切换
 * 2、异步优化：
 * 让子线程来分担主线程的任务，通过并行的任务来进行减少执行时间
 */
public class NyxAndroidApp extends BaseMVPApp implements HasActivityInjector, ModuleProvider {

    private static final String TAG = "T:NyxAndroidApp";
    /**
     * 分发Activity的注入
     * <p>
     * 在Activity调用AndroidInjection.inject(this)时
     * 从Application获取一个DispatchingAndroidInjector<Activity>，并将activity传递给inject(activity)
     * DispatchingAndroidInjector通过AndroidInjector.Factory创建AndroidInjector
     */
    @Inject
    DispatchingAndroidInjector<Activity> dispatchingAndroidInjector;

    private String processName;

    //// 根据CPU的核心数来进行设置核心线程的数量
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int CORE_POOL_SIZE = Math.max(2, Math.min(CPU_COUNT - 1, 4));

    /**
     * 这句话是什么意思？？
     */
    private CountDownLatch mCountDownLatch = new CountDownLatch(1);

    @Override
    protected void attachBaseContext(Context base) {
        LaunchTimeRecord.INSTANCE.startRecord("Application");
        // 在这里调用Context的方法会崩溃
        super.attachBaseContext(base);
        // 在这里可以正常调用Context的方法
        HookHelper.hookActivityManager();
    }

    @Override
    public void onCreate() {
        super.onCreate();

        //CPU的数量. 我们在启动的时候尽量使用CPU密集型的线程池，我们尽量榨取CPU执行的调度能力
        ExecutorService executorService = Executors.newFixedThreadPool(CORE_POOL_SIZE, new ThreadFactoryImpl("Application"));
        executorService.submit(() -> {
            initAuraHttp();
            mCountDownLatch.countDown();
        });

        // 生成TraceView的，文件默认存储8M的信息
        // Debug.startMethodTracing("AndroidSamples");
        // 使用Systrace进行分析
        TraceCompat.beginSection("NyxAndroid Start");

        initPerformanceDetector();

        AuraToolKits.init(null, "AndroidSamples");

        initX5Browser();



        initNetworkApi();
        //Application级别注入
        AppInjector.INSTANCE.inject(this);

        /**
         * 将任务会在这个地方进行等待。需要判断mCountDownLatch是否需要满足等待。
         * 我们这个对象的在实例化的时候，我们可以判断徐亚满足几次
         * 任务的最后需要阻塞等待，一直等所有需要满足的任务执行完毕，再通过
         */
        try {
            mCountDownLatch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // 执行结束的时候录制TraceView的相关信息
        // Debug.stopMethodTracing();
        TraceCompat.endSection();
    }

    /**
     * 初始化性能监控的相关逻辑检测器
     */
    private void initPerformanceDetector() {
        //initBlockCanary();
        //initAnrWatchDog();
        //initStrictMode();
        // 初始化Bugly
        initBugly();
    }

    private void initAnrWatchDog() {
        int timeoutMillis = BuildConfig.DEBUG ? 5000 : 10000;
        ANRWatchDog anrWatchDog = new ANRWatchDog(timeoutMillis /*timeout*/).setIgnoreDebugger(true);
        anrWatchDog.setANRListener(new ANRWatchDog.ANRListener() {
            @Override
            public void onAppNotResponding(ANRError error) {
                // TODO 异常的上报和恢复
                throw error;
            }
        });
        anrWatchDog.start();
    }

    private void initBlockCanary() {
        // 在主进程初始化调用
        BlockCanary.install(this, new AppBlockCanaryContext()).start();
    }

    /**
     * 初始化严格模式
     * TODO 待学习，提升APP卡顿的性能
     */
    private void initStrictMode() {

        if (BuildConfig.DEBUG) {
            StrictMode
                    .setThreadPolicy(new StrictMode.ThreadPolicy
                            .Builder()
                            .detectCustomSlowCalls()
                            .detectDiskReads()
                            .detectDiskWrites()
                            .detectNetwork()
                            .penaltyLog()
                            .penaltyDialog()
                            .build());
            StrictMode
                    .setVmPolicy(new StrictMode.VmPolicy
                            .Builder()
                            .detectLeakedClosableObjects()
                            .detectActivityLeaks()
                            .detectLeakedRegistrationObjects()
                            .detectAll()
                            .penaltyLog()
                            .build());
        }

    }

    /**
     * 初始化X5内核的浏览器
     * 1、Gradle方式集成 您可以在使用SDK的模块的dependencies中添加引用进行集成：
     */
    private void initX5Browser() {

    }

    /**
     * https://bugly.qq.com/docs/user-guide/instruction-manual-android/?v=20200203205953
     * 为了保证运营数据的准确性，建议不要在异步线程初始化Bugly。
     */
    private void initBugly() {
        Context context = getApplicationContext();
        // 获取当前包名
        String packageName = context.getPackageName();
        // 获取当前进程名
        String processName = ProcessInfoUtils.getProcessName(this);
        Log.d(TAG, "FMsg:initBugly() processName:" + processName);
        Log.d(TAG, "FMsg:initBugly() packageName:" + packageName);
        // 设置是否为上报进程的策略
        CrashReport.UserStrategy strategy = new CrashReport.UserStrategy(context);
        strategy.setUploadProcess(processName == null || processName.equals(packageName));
        // 初始化Bugly:
        CrashReport.initCrashReport(context, APP_ID_BUGLY, BuildConfig.DEBUG, strategy);
        // 如果通过“AndroidManifest.xml”来配置APP信息，初始化方法如下
        // CrashReport.initCrashReport(context, strategy);
    }

    private void initNetworkApi() {
        NetworkApi.init(new MyNetworkConfig(this));

        // 初始化AndroidNetworking网络请求框架
        // https://github.com/amitshekhariitbhu/Fast-Android-Networking
        AndroidNetworking.initialize(getApplicationContext());
    }

    private void initAuraHttp() {
        AuraRxHttp.init(this);
        AuraRxHttp.getInstance()
                .setBaseUrl(Constant.BASE_URL)
                .setDebug("AuraRxHttp", true)
                .setReadTimeOut(5 * 1000)
                .setWriteTimeOut(10 * 1000)
                .setCacheMaxSize(100 * 1024 * 1024)//设置缓存大小为100M
                .setConnectTimeout(5 * 1000)
                .setApiService(VideoApiService.class)
                //默认网络不好自动重试3次
                .setRetryCount(3);
    }

    private void attach() {
        processName = ProcessInfoUtils.getProcessName(this);
        if (processName != null) {
            boolean defaultProcess = processName.equals(getPackageName());
            if (defaultProcess) {
                startService(new Intent(this, KeepLiveService.class));
            } else if (processName.contains(":live")) {
                Log.i(TAG, "FMsg:attach() called : ");
            }
        }
    }

    @Override
    public AndroidInjector<Activity> activityInjector() {
        return this.dispatchingAndroidInjector;
    }
}
