package com.kongqw.serialportlibrary;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;

import com.kongqw.serialportlibrary.listener.OnOpenSerialPortListener;
import com.kongqw.serialportlibrary.listener.OnSerialPortDataListener;
import com.kongqw.serialportlibrary.thread.SerialPortReadThread;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Created by Kongqw on 2017/11/13.
 * SerialPortManager
 */

public class SerialPortManager {

    private static final String TAG = SerialPortManager.class.getSimpleName();
    private InputStream mFileInputStream;
    private OutputStream mFileOutputStream;
    private OnOpenSerialPortListener mOnOpenSerialPortListener;
    private OnSerialPortDataListener mOnSerialPortDataListener;

    private HandlerThread mSendingHandlerThread;
    private Handler mSendingHandler;
    private SerialPortReadThread mSerialPortReadThread;

    private ISerialPort serialPort;

    public SerialPortManager() {
        this.serialPort = new ISerialPort() {

            final SerialPort serialPort = new SerialPort();
            FileDescriptor fileDescriptor = null;
            InputStream inputStream = null;
            OutputStream outputStream = null;

            @Override
            public boolean open(String path, int baudRate, int flags) {
                fileDescriptor = serialPort.open2(path, baudRate, flags);
                return true;
            }

            @Override
            public InputStream getInputStream() throws IOException {
                if (fileDescriptor == null) {
                    throw new IOException("Serial port is not initialized");
                }
                if (inputStream == null) {
                    inputStream = new FileInputStream(fileDescriptor);
                }
                return inputStream;
            }

            @Override
            public OutputStream getOutputStream() throws IOException {
                if (fileDescriptor == null) {
                    throw new IOException("Serial port is not initialized");
                }
                if (outputStream == null) {
                    outputStream = new FileOutputStream(fileDescriptor);
                }
                return outputStream;
            }

            @Override
            public void close() {
                if (fileDescriptor != null) {
                    fileDescriptor = null;
                    serialPort.close2();
                }
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    inputStream = null;
                }
                if (outputStream != null) {
                    try {
                        outputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    outputStream = null;
                }
            }
        };
    }

    public SerialPortManager(ISerialPort serialPort) {
        this.serialPort = serialPort;
    }

    /**
     * 文件设置最高权限 777 可读 可写 可执行
     *
     * @param file 文件
     * @return 权限修改是否成功
     */
    private boolean chmod777(File file) {
        if (null == file || !file.exists()) {
            // 文件不存在
            return false;
        }
        try {
            // 获取ROOT权限
            Process su = Runtime.getRuntime().exec("/system/bin/su");
            // 修改文件属性为 [可读 可写 可执行]
            String cmd = "chmod 777 " + file.getAbsolutePath() + "\n" + "exit\n";
            su.getOutputStream().write(cmd.getBytes());
            if (0 == su.waitFor() && file.canRead() && file.canWrite() && file.canExecute()) {
                return true;
            }
        } catch (IOException | InterruptedException e) {
            // 没有ROOT权限
            e.printStackTrace();
        }
        return false;
    }

    /**
     * 打开串口
     *
     * @param device   串口设备
     * @param baudRate 波特率
     * @return 打开是否成功
     */
    public boolean openSerialPort(File device, int baudRate) {
        Log.i(TAG, "openSerialPort: " + String.format("打开串口 %s  波特率 %s", device.getPath(), baudRate));

        // 校验串口权限
        if (!device.canRead() || !device.canWrite()) {
            boolean chmod777 = chmod777(device);
            if (!chmod777) {
                Log.i(TAG, "openSerialPort: 没有读写权限");
                if (null != mOnOpenSerialPortListener) {
                    mOnOpenSerialPortListener.onFail(device, OnOpenSerialPortListener.Status.NO_READ_WRITE_PERMISSION);
                }
                return false;
            }
        }

        try {
            closeSerialPort();
            serialPort.open(device.getAbsolutePath(), baudRate, 0);
            mFileInputStream = serialPort.getInputStream();
            mFileOutputStream = serialPort.getOutputStream();
            Log.i(TAG, "openSerialPort: 串口已经打开");
            if (null != mOnOpenSerialPortListener) {
                mOnOpenSerialPortListener.onSuccess(device);
            }
            // 开启发送消息的线程
            startSendThread();
            // 开启接收消息的线程
            startReadThread();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            if (null != mOnOpenSerialPortListener) {
                mOnOpenSerialPortListener.onFail(device, OnOpenSerialPortListener.Status.OPEN_FAIL);
            }
            closeSerialPort();
        }
        return false;
    }

    /**
     * 关闭串口
     */
    public void closeSerialPort() {
        serialPort.close();
        // 停止发送消息的线程
        stopSendThread();
        // 停止接收消息的线程
        stopReadThread();

        if (null != mFileInputStream) {
            try {
                mFileInputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mFileInputStream = null;
        }

        if (null != mFileOutputStream) {
            try {
                mFileOutputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mFileOutputStream = null;
        }

    }

    /**
     * 添加打开串口监听
     *
     * @param listener listener
     * @return SerialPortManager
     */
    public SerialPortManager setOnOpenSerialPortListener(OnOpenSerialPortListener listener) {
        mOnOpenSerialPortListener = listener;
        return this;
    }

    /**
     * 添加数据通信监听
     *
     * @param listener listener
     * @return SerialPortManager
     */
    public SerialPortManager setOnSerialPortDataListener(OnSerialPortDataListener listener) {
        mOnSerialPortDataListener = listener;
        return this;
    }

    /**
     * 开启发送消息的线程
     */
    private void startSendThread() {
        // 开启发送消息的线程
        mSendingHandlerThread = new HandlerThread("mSendingHandlerThread");
        mSendingHandlerThread.start();
        // Handler
        mSendingHandler = new Handler(mSendingHandlerThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                byte[] sendBytes = (byte[]) msg.obj;

                if (null != mFileOutputStream && null != sendBytes && 0 < sendBytes.length) {
                    try {
                        mFileOutputStream.write(sendBytes);
                        if (null != mOnSerialPortDataListener) {
                            mOnSerialPortDataListener.onDataSent(sendBytes);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        try {
                            if (null != mOnSerialPortDataListener) {
                                mOnSerialPortDataListener.onError(e);
                            }
                        } catch (Exception e2) {
                            e2.printStackTrace();
                        }
                    }
                }
            }
        };
    }

    /**
     * 停止发送消息线程
     */
    private void stopSendThread() {
        mSendingHandler = null;
        if (null != mSendingHandlerThread) {
            mSendingHandlerThread.interrupt();
            mSendingHandlerThread.quit();
            mSendingHandlerThread = null;
        }
    }

    /**
     * 开启接收消息的线程
     */
    private void startReadThread() {
        mSerialPortReadThread = new SerialPortReadThread(mFileInputStream) {
            @Override
            public void onDataReceived(byte[] bytes) {
                if (null != mOnSerialPortDataListener) {
                    mOnSerialPortDataListener.onDataReceived(bytes);
                }
            }

            @Override
            public void onError(Exception e) {
                if (null != mOnSerialPortDataListener) {
                    mOnSerialPortDataListener.onError(e);
                }
            }
        };
        mSerialPortReadThread.start();
    }

    /**
     * 停止接收消息的线程
     */
    private void stopReadThread() {
        if (null != mSerialPortReadThread) {
            mSerialPortReadThread.release();
        }
    }

    /**
     * 发送数据
     *
     * @param sendBytes 发送数据
     * @return 发送是否成功
     */
    public boolean sendBytes(byte[] sendBytes) {
        if (null != mFileInputStream && null != mFileOutputStream) {
            if (null != mSendingHandler) {
                Message message = Message.obtain();
                message.obj = sendBytes;
                return mSendingHandler.sendMessage(message);
            }
        }
        return false;
    }
}
