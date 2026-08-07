package io.github.ralfspoeth.xldr.swift.mt;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;

final class AsciiReader extends Reader {

    private final InputStream source;

    AsciiReader(InputStream source) {this.source = source;}

    @Override
    public int read(char[] cbuf, int off, int len) throws IOException {
        byte[] bytes = new byte[len];
        int read = source.read(bytes);
        if (read > -1) {
            for (int i = 0; i < len; i++) {
                cbuf[off + i] = (char) bytes[i];
            }
        }
        return read;
    }

    @Override
    public void close() throws IOException {
        source.close();
    }
}
