package com.knowledgehub.media;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import org.csource.common.MyException;
import org.csource.fastdfs.ClientGlobal;
import org.csource.fastdfs.ProtoCommon;
import org.csource.fastdfs.StorageClient1;
import org.csource.fastdfs.TrackerGroup;
import org.csource.fastdfs.TrackerServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FastDfsBlobStore implements BlobStore {

    private final String host;
    private final int port;

    public FastDfsBlobStore(@Value("${knowledgehub.fastdfs.tracker}") String tracker) {
        String[] parts = tracker.split(":", -1);
        if (parts.length != 2 || parts[0].isBlank()) {
            throw new IllegalArgumentException("KH_FDFS_TRACKER must be host:port");
        }
        host = parts[0].trim();
        port = Integer.parseInt(parts[1].trim());
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("KH_FDFS_TRACKER port must be between 1 and 65535");
        }
        ClientGlobal.setG_connect_timeout(2_000);
        ClientGlobal.setG_network_timeout(5_000);
        ClientGlobal.g_connection_pool_enabled = false;
        // The SDK needs a group even when every operation supplies its own tracker.
        ClientGlobal.setG_tracker_group(new TrackerGroup(new InetSocketAddress[]{
                InetSocketAddress.createUnresolved(host, port)
        }));
    }

    @Override
    public String put(byte[] data, String extension) throws IOException {
        var client = client();
        try {
            String key = client.upload_file1(data, extension, null);
            if (key == null) {
                throw failure("upload", client.getErrorCode());
            }
            return key;
        } catch (MyException exception) {
            throw new IOException("FastDFS upload failed", exception);
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        var client = client();
        try {
            byte[] data = client.download_file1(key);
            if (data != null) {
                return new ByteArrayInputStream(data);
            }
            if (client.getErrorCode() == ProtoCommon.ERR_NO_ENOENT) {
                throw new FileNotFoundException("FastDFS object is missing");
            }
            throw failure("download", client.getErrorCode());
        } catch (MyException exception) {
            throw new IOException("FastDFS download failed", exception);
        }
    }

    @Override
    public void delete(String key) throws IOException {
        var client = client();
        try {
            int status = client.delete_file1(key);
            if (status != 0 && status != ProtoCommon.ERR_NO_ENOENT) {
                throw failure("delete", status);
            }
        } catch (MyException exception) {
            throw new IOException("FastDFS delete failed", exception);
        }
    }

    private StorageClient1 client() {
        // SDK operations borrow and release both sockets internally.
        return new StorageClient1(new TrackerServer(new InetSocketAddress(host, port), 0));
    }

    private IOException failure(String operation, int status) {
        return new IOException("FastDFS " + operation + " failed with status " + status);
    }
}
