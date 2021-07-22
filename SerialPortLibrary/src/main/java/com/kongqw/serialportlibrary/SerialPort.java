package com.kongqw.serialportlibrary;

import java.io.FileDescriptor;

public class SerialPort {

    static {
        System.loadLibrary("SerialPort");
    }

    private static final String TAG = SerialPort.class.getSimpleName();

    private FileDescriptor mFd;

    protected FileDescriptor open2(String path, int baudRate, int flags){
        mFd = open(path, baudRate, flags);
        return mFd;
    }

    protected void close2(){
        close();
    }

    // 打开串口
    private native FileDescriptor open(String path, int baudRate, int flags);

    // 关闭串口
    private native void close();
}
