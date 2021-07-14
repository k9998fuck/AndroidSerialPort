package com.kongqw.serialportlibrary.listener;

/**
 * Created by Kongqw on 2017/11/14.
 * 串口消息监听
 */

public interface OnSerialPortDataListener {

    /**
     * 数据接收
     *
     * @param bytes 接收到的数据
     */
    void onDataReceived(byte[] bytes);

    /**
     * 数据发送
     *
     * @param bytes 发送的数据
     */
    void onDataSent(byte[] bytes);

    /**
     * 发生错误
     * @param e 具体异常
     */
    void onError(Exception e);

}
