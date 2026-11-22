#!/bin/sh
set -eu

case "${1:-}" in
    tracker)
        mkdir -p /data/tracker
        exec fdfs_trackerd -N /etc/fdfs/tracker.conf start
        ;;
    storage)
        mkdir -p /data/storage
        exec fdfs_storaged -N /etc/fdfs/storage.conf start
        ;;
    *)
        echo "Expected tracker or storage" >&2
        exit 2
        ;;
esac
