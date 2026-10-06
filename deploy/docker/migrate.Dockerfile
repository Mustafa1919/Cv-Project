ARG FLYWAY_IMAGE=flyway/flyway:13.9.0-alpine@sha256:ae8e985a8caf73181a39aad823690f27d55c850d4c5cea92a94c0ce4b3943156
ARG RUNTIME_IMAGE=eclipse-temurin:25.0.4.1_1-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab

FROM ${FLYWAY_IMAGE} AS flyway
# Only PostgreSQL is migrated. The other bundled drivers are 290 MB of unused code whose
# vulnerabilities would otherwise have to be suppressed one by one.
RUN find /flyway/drivers -mindepth 1 -maxdepth 1 ! -name 'postgresql-*.jar' -exec rm -rf {} + \
    && test -n "$(find /flyway/drivers -name 'postgresql-*.jar')" \
    # The matching database plugins must go too: they fail to load without their drivers.
    && find /flyway/lib/flyway -maxdepth 1 -type f \( \
         -name 'flyway-database-*.jar' -o -name 'flyway-nc-*.jar' -o -name 'flyway-gcp-*.jar' \
         -o -name 'flyway-mysql-*.jar' -o -name 'flyway-sqlserver-*.jar' \
         -o -name 'flyway-singlestore-*.jar' -o -name 'flyway-firebird-*.jar' \
         -o -name 'flyway-locations-s3-*.jar' \
       \) ! -name 'flyway-database-postgresql-*.jar' -delete \
    && test -n "$(find /flyway/lib/flyway -name 'flyway-database-postgresql-*.jar')"

# Same runtime as the services: one base image to patch and scan.
FROM ${RUNTIME_IMAGE}

ARG REVISION=unknown
ARG SOURCE=""

LABEL org.opencontainers.image.title="migrate" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.source="${SOURCE}"

COPY --from=flyway /flyway /flyway
COPY migrations/ /flyway/sql/
COPY --chmod=0755 entrypoint.sh /usr/local/bin/vitrin-migrate

ENV PATH="/flyway:${PATH}"
WORKDIR /flyway
USER 10001:10001

ENTRYPOINT ["/usr/local/bin/vitrin-migrate"]
CMD ["migrate"]
