# Backup runner: Postgres 18 client tools (pg_dump matches the server), rclone for R2, and age to encrypt.
# Also has the server binaries, which restore-test.sh (run on Zach's machine with the key) uses to restore into a throwaway cluster.
FROM postgres:18
# rclone from its release, not Debian's: the packaged 1.60 got 501 NotImplemented from R2 on every first
# attempt (2026-10-28). Bump the version and both checksums together (downloads.rclone.org/<version>/SHA256SUMS).
ARG RCLONE_VERSION=v1.75.2
ARG RCLONE_SHA256_AMD64=349ac8fba6ff65d6247043f1750cdcb518ec5d500ef91463a10d37c0ccdf3702
ARG RCLONE_SHA256_ARM64=7e1e8d69654941b7b7df84ee74c5f7fb09cce7d5496947fb7bf16b55ff427d10
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates unzip age \
    && arch=$(dpkg --print-architecture) \
    && case "$arch" in amd64) sum=$RCLONE_SHA256_AMD64 ;; arm64) sum=$RCLONE_SHA256_ARM64 ;; *) echo "no rclone checksum for $arch" >&2; exit 1 ;; esac \
    && curl -fsSLo /tmp/rclone.zip "https://downloads.rclone.org/${RCLONE_VERSION}/rclone-${RCLONE_VERSION}-linux-${arch}.zip" \
    && echo "${sum}  /tmp/rclone.zip" | sha256sum -c - \
    && unzip -q -j /tmp/rclone.zip "*/rclone" -d /usr/local/bin \
    && chmod 755 /usr/local/bin/rclone \
    && rm /tmp/rclone.zip \
    && apt-get purge -y unzip && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/*
COPY age-recipients.sh backup.sh backup-check.sh decrypt-dump.sh restore-test.sh restore-photos.sh schedule.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/backup.sh /usr/local/bin/backup-check.sh /usr/local/bin/decrypt-dump.sh /usr/local/bin/restore-test.sh /usr/local/bin/restore-photos.sh /usr/local/bin/schedule.sh
# The image's own postgres user, not root (security review 2026-10-26, #8): the dumps go over the network, the
# restore test's throwaway server runs as postgres anyway, and nothing here is kept on a volume.
USER postgres
ENTRYPOINT []
CMD ["schedule.sh"]
