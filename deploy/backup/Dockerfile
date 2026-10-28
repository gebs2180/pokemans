# Backup runner: Postgres 18 client tools (pg_dump matches the server) plus rclone for R2.
# Also has the server binaries, which restore-test.sh uses to restore into a throwaway cluster.
FROM postgres:18
# rclone from its release, not Debian's: the packaged 1.60 got 501 NotImplemented from R2 on every first
# attempt (2026-10-28). Bump the version and both checksums together (downloads.rclone.org/<version>/SHA256SUMS).
ARG RCLONE_VERSION=v1.75.2
ARG RCLONE_SHA256_AMD64=349ac8fba6ff65d6247043f1750cdcb518ec5d500ef91463a10d37c0ccdf3702
ARG RCLONE_SHA256_ARM64=7e1e8d69654941b7b7df84ee74c5f7fb09cce7d5496947fb7bf16b55ff427d10
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates unzip \
    && arch=$(dpkg --print-architecture) \
    && case "$arch" in amd64) sum=$RCLONE_SHA256_AMD64 ;; arm64) sum=$RCLONE_SHA256_ARM64 ;; *) echo "no rclone checksum for $arch" >&2; exit 1 ;; esac \
    && curl -fsSLo /tmp/rclone.zip "https://downloads.rclone.org/${RCLONE_VERSION}/rclone-${RCLONE_VERSION}-linux-${arch}.zip" \
    && echo "${sum}  /tmp/rclone.zip" | sha256sum -c - \
    && unzip -q -j /tmp/rclone.zip "*/rclone" -d /usr/local/bin \
    && chmod 755 /usr/local/bin/rclone \
    && rm /tmp/rclone.zip \
    && apt-get purge -y unzip && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/*
COPY backup.sh restore-test.sh schedule.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/backup.sh /usr/local/bin/restore-test.sh /usr/local/bin/schedule.sh
ENTRYPOINT []
CMD ["schedule.sh"]
