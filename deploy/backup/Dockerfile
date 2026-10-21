# Backup runner: Postgres 18 client tools (pg_dump matches the server) plus rclone for R2.
# Also has the server binaries, which restore-test.sh uses to restore into a throwaway cluster.
FROM postgres:18
RUN apt-get update \
    && apt-get install -y --no-install-recommends rclone curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY backup.sh restore-test.sh schedule.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/backup.sh /usr/local/bin/restore-test.sh /usr/local/bin/schedule.sh
ENTRYPOINT []
CMD ["schedule.sh"]
