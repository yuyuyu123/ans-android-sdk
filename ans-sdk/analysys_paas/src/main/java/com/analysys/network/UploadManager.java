package com.analysys.network;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;

import com.analysys.database.TableAllInfo;
import com.analysys.process.AgentProcess;
import com.analysys.process.LifeCycleConfig;
import com.analysys.strategy.BaseSendStatus;
import com.analysys.strategy.PolicyManager;
import com.analysys.utils.CheckUtils;
import com.analysys.utils.CommonUtils;
import com.analysys.utils.Constants;
import com.analysys.utils.LogPrompt;
import com.analysys.utils.SharedUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Map;
import java.util.Random;

/**
 * @Copyright © 2018 EGuan Inc. All rights reserved.
 * @Description: 上传管理
 * @Version: 1.0
 * @Create: 2018/2/3 17:31
 * @Author: Wang-X-C
 */

public class UploadManager {

    private Context mContext;
    private SendHandler mHandler;
    private int constantlySend = 1;
    private String spv = "";

    public UploadManager() {
        HandlerThread thread = new HandlerThread(Constants.THREAD_NAME, Thread.MIN_PRIORITY);
        thread.start();
        mHandler = new SendHandler(thread.getLooper());
    }

    public static UploadManager getInstance(Context context) {
        Holder.INSTANCE.initContext(context);
        return Holder.INSTANCE;
    }

    /**
     * flush接口调用
     * SP_POLICY_NO 0=智能 1实时发送 2间隔发送
     */
    public void flushSendManager() {
        if (CommonUtils.isMainProcess(mContext)) {
            long servicePolicyNo = SharedUtil.getLong(mContext, Constants.SP_POLICY_NO, -1L);
            if (servicePolicyNo == -1 || servicePolicyNo == 1) {
                sendMessage();
            }
        } else {
            LogPrompt.processFailed();
        }
    }

    /**
     * 判断 发送数据
     */
    public void sendManager(String type, JSONObject sendData) {
        if (CommonUtils.isEmpty(sendData)) {
            return;
        }
        long maxCount = AgentProcess.getInstance(mContext).getMaxCacheSize();
        long count = TableAllInfo.getInstance(mContext).selectCount();
        if (maxCount <= count) {
            TableAllInfo.getInstance(mContext).delete(Constants.DELETE_COUNT);
        }
        TableAllInfo.getInstance(mContext).insert(String.valueOf(sendData), type);
        if (CommonUtils.isMainProcess(mContext)) {
            if (Constants.ALIAS.equals(type)) {
                BaseSendStatus sendStatus = PolicyManager.getPolicyType(mContext);
                if (sendStatus.isSend(mContext)) {
                    if (mHandler.hasMessages(constantlySend)) {
                        mHandler.removeMessages(constantlySend);
                    }
                    sendMessage();
                }
            } else {
                if (mHandler.hasMessages(constantlySend)) {
                    mHandler.removeMessages(constantlySend);
                }
                sendMessage();
            }
        } else {
            LogPrompt.processFailed();
        }
    }

    /**
     * 发送实时消息
     */
    public void sendMessage() {
        if (!mHandler.hasMessages(constantlySend)) {
            Message msg = Message.obtain();
            msg.what = constantlySend;
            mHandler.sendMessage(msg);
        }
    }

    /**
     * 发送delay消息
     */
    public void sendDelayedMessage(long time) {
        if (!mHandler.hasMessages(constantlySend)) {
            Message msg = Message.obtain();
            msg.what = constantlySend;
            mHandler.sendMessageDelayed(msg, time);
        }
    }

    private void initContext(Context context) {
        if (mContext == null && context != null) {
            mContext = context;
        }
    }

    /**
     * 数据加密
     */
    private void encryptData(String url, String value) throws IOException {
        if (CommonUtils.isEmpty(spv)) {
            spv = CommonUtils.getSpvInfo(mContext);
        }
        Map<String, String> headInfo = null;
        String encryptData;
        if (Constants.encryptType != 0) {
            encryptData = encrypt(value, Constants.encryptType);
            if (!TextUtils.isEmpty(encryptData)) {
                headInfo = getHeadInfo();
                if (!CommonUtils.isEmpty(headInfo)) {
                    LogPrompt.encryptLog(true);
                } else {
                    encryptData = value;
                }
            } else {
                encryptData = value;
            }
        } else {
            encryptData = value;
        }
        String zipData = CommonUtils.messageZip(encryptData);
        sendRequest(url, zipData, headInfo);
    }

    /**
     * 发送数据
     */
    private void sendRequest(String url, String dataInfo, Map<String, String> headInfo) {
        try {
            String returnInfo;
            if (url.startsWith(Constants.HTTP)) {
                returnInfo = RequestUtils.postRequest(url, dataInfo, spv, headInfo);
            } else {
                returnInfo = RequestUtils.postRequestHttps(mContext, url, dataInfo, spv, headInfo);
            }
            policyAnalysis(analysisStrategy(returnInfo));
        } catch (Throwable e) {

        }
    }

    /**
     * 数据加密压缩编码或只压缩编码
     */
    private String encrypt(String data, int type) {
        try {
            if (LifeCycleConfig.encryptJson != null) {
                String path = LifeCycleConfig.encryptJson.optString("start");
                if (!TextUtils.isEmpty(path)) {
                    Object object = CommonUtils.reflexUtils(
                            CommonUtils.getClassPath(path),
                            CommonUtils.getMethod(path),
                            new Class[]{String.class, String.class, String.class, int.class},
                            CommonUtils.getAppKey(mContext),
                            Constants.DEV_SDK_VERSION, data, type);
                    if (object != null) {
                        return String.valueOf(object);
                    }
                }
            }
        } catch (Throwable throwable) {
        }
        return null;
    }

    /**
     * 获取数据加密后上传头信息
     */
    private Map<String, String> getHeadInfo() {
        if (LifeCycleConfig.uploadHeadJson != null) {
            String path = LifeCycleConfig.uploadHeadJson.optString("start");
            if (!TextUtils.isEmpty(path)) {
                int index = path.lastIndexOf(".");
                Object object = CommonUtils.reflexUtils(
                        path.substring(0, index),
                        path.substring(index + 1, path.length()));
                if (object != null) {
                    return (Map<String, String>) object;
                }
            }
        }
        return null;
    }

    /**
     * 返回值解密转json
     */
    private JSONObject analysisStrategy(String policy) {
        try {
            if (CommonUtils.isEmpty(policy)) {
                return null;
            }
            String unzip = CommonUtils.messageUnzip(policy);
            LogPrompt.showReturnCode(unzip);
            return new JSONObject(unzip);
        } catch (Throwable e) {
            try {
                return new JSONObject(policy);
            } catch (Throwable e1) {
                return null;
            }
        }
    }

    /**
     * 解析返回策略
     */
    private void policyAnalysis(JSONObject json) {
        try {
            if (!CommonUtils.isEmpty(json)) {
                int code = json.optInt(Constants.SERVICE_CODE, -1);
                if (code == 200 || code == 4200 || code == 400) {
                    resetReUploadParams();
                    TableAllInfo.getInstance(mContext).deleteData();
                    SharedUtil.setLong(mContext, Constants.SP_SEND_TIME,
                            System.currentTimeMillis());
                    LogPrompt.showSendResults(true);
                } else {
                    JSONObject policyJson = json.optJSONObject(Constants.SERVICE_POLICY);
                    if (!CommonUtils.isEmpty(policyJson)) {
                        String serviceHash = SharedUtil.getString(mContext,
                                Constants.SP_SERVICE_HASH, null);
                        if (CommonUtils.isEmpty(serviceHash)
                                || !serviceHash.equals(
                                policyJson.optString(Constants.SERVICE_HASH))) {
                            PolicyManager.analysisStrategy(mContext, policyJson);
                        }
                    }
                    LogPrompt.showSendResults(false);
                    reUpload();
                }
            } else {
                reUpload();
            }
        } catch (Throwable throwable) {
            try {
                reUpload();
            } catch (Throwable t) {
            }
        }
    }

    /**
     * 重传逻辑
     * 发送失败的次数是否大于设置的发送失败次数
     * 发送失败时间大于0 且 当前时间减上次上传失败的时间大于时间间隔立即发送,
     * 否则delay发送,delay时间范围内随机
     * 发送失败次数大于默认次数,清空重传次数,set失败时间点,delay发送时间为设置重传间隔时间
     */
    private synchronized void reUpload() {
        int failureCount = SharedUtil.getInt(mContext, Constants.SP_FAILURE_COUNT, 0);
        long intervalTime = getReUploadIntervalTime();
        if (failureCount < getReUploadCount()) {
            SharedUtil.setInt(mContext, Constants.SP_FAILURE_COUNT, failureCount + 1);
            long failureTime = SharedUtil.getLong(mContext, Constants.SP_FAILURE_TIME, -1L);
            // 获取上传失败时间,如果上传失败时间为0，发送delay任务
            if (failureTime == 0) {
                sendDelayedMessage(intervalTime + getRandomNumb());
            } else {
                // 如果当前时间 减 失败时间 大于 间隔时间 则立即上传
                long difference = Math.abs(System.currentTimeMillis() - failureTime);
                if (difference > intervalTime) {
                    sendMessage();
                } else {
                    // 如果当前时间减失败时间小于间隔时间，则间隔时间减当前时间与失败时间的差值加随机数delay
                    sendDelayedMessage(intervalTime - difference + getRandomNumb());
                }
            }
        } else {
            SharedUtil.remove(mContext, Constants.SP_FAILURE_COUNT);
            SharedUtil.setLong(mContext, Constants.SP_FAILURE_TIME, System.currentTimeMillis());
        }
        SharedUtil.setLong(mContext, Constants.SP_FAILURE_TIME, System.currentTimeMillis());
    }

    /**
     * 重置重传数据
     */
    private void resetReUploadParams() {
        SharedUtil.remove(mContext, Constants.SP_FAILURE_COUNT);
        SharedUtil.remove(mContext, Constants.SP_FAILURE_TIME);
    }

    /**
     * 获取随机数
     */
    private int getRandomNumb() {
        Random random = new Random();
        return random.nextInt(30 * 1000 - 10 * 1000) + 10 * 1000;
    }

    /**
     * 重传间隔时间
     */
    private long getReUploadIntervalTime() {
        return SharedUtil.getLong(mContext,
                Constants.SP_FAIL_TRY_DELAY, Constants.FAILURE_INTERVAL_TIME);
    }

    /**
     * 失败次数
     */
    private long getReUploadCount() {
        return SharedUtil.getLong(mContext,
                Constants.SP_FAIL_COUNT, Constants.FAILURE_COUNT);
    }

    private static class Holder {
        public static final UploadManager INSTANCE = new UploadManager();
    }

    /**
     * 处理数据压缩,上传和返回值解析
     */
    private class SendHandler extends Handler {
        private SendHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            try {
                if (msg.what == constantlySend) {
                    if (!CommonUtils.isNetworkAvailable(mContext)) {
                        LogPrompt.networkErr();
                        return;
                    }
                    String url = CommonUtils.getUrl(mContext);
                    if (CommonUtils.isEmpty(url)) {
                        LogPrompt.showErrLog(LogPrompt.URL_ERR);
                        return;
                    }
                    JSONArray selectInfo = TableAllInfo.getInstance(mContext).select();
                    // 异常数据过滤
                    selectInfo = CheckUtils.checkEvent(selectInfo);
                    if (!CommonUtils.isEmpty(selectInfo)) {
                        LogPrompt.showSendMessage(url, selectInfo);
                        encryptData(url, String.valueOf(selectInfo));
                    } else {
                        TableAllInfo.getInstance(mContext).deleteData();
                    }
                }
            } catch (Throwable throwable) {
            }
        }
    }
}
