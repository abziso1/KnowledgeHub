FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential ca-certificates curl netcat-openbsd perl \
    && rm -rf /var/lib/apt/lists/*
COPY versions.env /tmp/versions.env
RUN set -eu; . /tmp/versions.env; \
    mkdir /tmp/fastdfs-build; cd /tmp/fastdfs-build; \
    for entry in "libfastcommon:$LIBFASTCOMMON_VERSION" "libserverframe:$LIBSERVERFRAME_VERSION" "fastdfs:$FASTDFS_VERSION"; do \
        project=${entry%:*}; revision=${entry#*:}; mkdir "$project"; \
        curl -fsSL --retry 3 --connect-timeout 15 "https://codeload.github.com/happyfish100/$project/tar.gz/refs/tags/$revision" -o "$project.tar.gz"; \
        tar -xzf "$project.tar.gz" -C "$project" --strip-components=1; \
        (cd "$project"; ./make.sh; ./make.sh install); \
        ldconfig; \
    done; \
    rm -rf /tmp/fastdfs-build /tmp/versions.env
COPY tracker.conf storage.conf /etc/fdfs/
COPY entrypoint.sh /usr/local/bin/fastdfs-entrypoint
ENTRYPOINT ["/bin/sh", "/usr/local/bin/fastdfs-entrypoint"]
