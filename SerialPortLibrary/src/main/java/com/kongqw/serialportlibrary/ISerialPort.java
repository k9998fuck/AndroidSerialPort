package com.kongqw.serialportlibrary;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface ISerialPort {

    // 打开串口
    boolean open(String path, int baudRate, int flags) throws IOException;

    InputStream getInputStream() throws IOException;

    OutputStream getOutputStream() throws IOException;

    // 关闭串口
    void close();

}
