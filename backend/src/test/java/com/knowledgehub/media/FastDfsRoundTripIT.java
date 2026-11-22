package com.knowledgehub.media;

import java.io.FileNotFoundException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FastDfsRoundTripIT {

    @Test
    void trackerSelectedStorageWorksFromBackendNetwork() throws Exception {
        String tracker = System.getenv("KH_FDFS_TRACKER");
        assertNotNull(tracker, "KH_FDFS_TRACKER is required for the FastDFS test");
        assertFalse(tracker.isBlank(), "KH_FDFS_TRACKER is required for the FastDFS test");
        BlobStore store = new FastDfsBlobStore(tracker);
        byte[] bytes = {10, 20, 30};
        String key = store.put(bytes, "png");
        try (var data = store.open(key)) {
            assertArrayEquals(bytes, data.readAllBytes());
        } finally {
            store.delete(key);
        }
        store.delete(key);
        assertThrows(FileNotFoundException.class, () -> store.open(key));
    }
}
