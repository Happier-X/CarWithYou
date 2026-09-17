package com.carwithyou.server.util;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 大端读写。协议里所有多字节整数都是大端，和 ADB 自己的小端相反。
 *
 * 刻意不用 DataInputStream 直接读整个流：控制通道上我们要按类型一个字段一个字段
 * 地读，用它的 readInt/readFloat 更贴近协议描述，也更好对照文档排查。
 */
public final class IO {

    private IO() {
    }

    public static void writeInt(OutputStream out, int value) throws IOException {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    public static void writeByte(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
    }

    public static void writeFloat(OutputStream out, float value) throws IOException {
        writeInt(out, Float.floatToIntBits(value));
    }

    public static void writeBytesWithLength(OutputStream out, byte[] bytes) throws IOException {
        writeInt(out, bytes.length);
        out.write(bytes);
    }

    public static int readInt(InputStream in) throws IOException {
        int b1 = in.read();
        int b2 = in.read();
        int b3 = in.read();
        int b4 = in.read();
        if ((b1 | b2 | b3 | b4) < 0) {
            throw new IOException("读到流末尾");
        }
        return (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;
    }

    public static int readByte(InputStream in) throws IOException {
        int value = in.read();
        if (value < 0) {
            throw new IOException("读到流末尾");
        }
        return value;
    }

    public static float readFloat(InputStream in) throws IOException {
        return Float.intBitsToFloat(readInt(in));
    }

    /** 读满 length 字节，不足就抛（区别于 readInt，这里要区分「流结束」和「读满」）。 */
    public static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        new DataInputStream(in).readFully(buffer);
        return buffer;
    }

    public static byte[] readBytesWithLength(InputStream in, int maxLength) throws IOException {
        int length = readInt(in);
        if (length < 0 || length > maxLength) {
            throw new IOException("长度字段异常：" + length);
        }
        return readFully(in, length);
    }
}