package com.knowledgehub.media;

import java.io.IOException;
import java.io.InputStream;

public interface BlobStore {

    String put(byte[] data, String extension) throws IOException;

    InputStream open(String key) throws IOException;

    void delete(String key) throws IOException;
}
