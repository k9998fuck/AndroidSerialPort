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
     * ???????????????????????? 777 ?????? ?????? ?????????
     *
     * @param file ??????
     * @return ????????????????????????
     */
    private boolean chmod777(File file) {
        if (null == file || !file.exists()) {
            // ???????????????
            return false;
        }
        try {
            // ??????ROOT??????
            Process su = Runtime.getRuntime().exec("/system/bin/su");
            // ????????????????????? [?????? ?????? ?????????]
            String cmd = "chmod 777 " + file.getAbsolutePath() + "\n" + "exit\n";
            su.getOutputStream().write(cmd.getBytes());
            if (0 == su.waitFor() && file.canRead() && file.canWrite() && file.canExecute()) {
                return true;
            }
        } catch (IOException | InterruptedException e) {
            // ??????ROOT??????
            e.printStackTrace();
        }
        return false;
    }

    /**
     * ????????????
     *
     * @param device   ????????????
     * @param baudRate ?????????
     * @return ??????????????????
     */
    public boolean openSerialPort(File device, int baudRate) {
        Log.i(TAG, "openSerialPort: " + String.format("???????????? %s  ????????? %s", device.getPath(), baudRate));

        // ??????????????????
        if (!device.canRead() || !device.canWrite()) {
            boolean chmod777 = chmod777(device);
            if (!chmod777) {
                Log.i(TAG, "openSerialPort: ??????????????????");
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
            Log.i(TAG, "openSerialPort: ??????????????????");
            if (null != mOnOpenSerialPortListener) {
                mOnOpenSerialPortListener.onSuccess(device);
            }
            // ???????????????????????????
            startSendThread();
            // ???????????????????????????
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
     * ????????????
     */
    public void closeSerialPort() {
        serialPort.close();
        // ???????????????????????????
        stopSendThread();
        // ???????????????????????????
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
     * ????????????????????????
     *
     * @param listener listener
     * @return SerialPortManager
     */
    public SerialPortManager setOnOpenSerialPortListener(OnOpenSerialPortListener listener) {
        mOnOpenSerialPortListener = listener;
        return this;
    }

    /**
     * ????????????????????????
     *
     * @param listener listener
     * @return SerialPortManager
     */
    public SerialPortManager setOnSerialPortDataListener(OnSerialPortDataListener listener) {
        mOnSerialPortDataListener = listener;
        return this;
    }

    /**
     * ???????????????????????????
     */
    private void startSendThread() {
        // ???????????????????????????
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
     * ????????????????????????
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
     * ???????????????????????????
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
     * ???????????????????????????
     */
    private void stopReadThread() {
        if (null != mSerialPortReadThread) {
            mSerialPortReadThread.release();
        }
    }

    /**
     * ????????????
     *
     * @param sendBytes ????????????
     * @return ??????????????????
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
