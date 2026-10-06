ARG RUNTIME_IMAGE=eclipse-temurin:25.0.4.1_1-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab

FROM ${RUNTIME_IMAGE} AS extract
ARG SERVICE
WORKDIR /app
RUN case "${SERVICE}" in \
      gateway|core|search) ;; \
      "") echo "SERVICE is required: gateway, core or search" >&2; exit 1 ;; \
      *) echo "Invalid SERVICE: expected gateway, core or search" >&2; exit 1 ;; \
    esac
COPY ${SERVICE}.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM ${RUNTIME_IMAGE} AS runtime
ARG SERVICE
ARG REVISION=unknown
ARG SOURCE=""

LABEL org.opencontainers.image.title="${SERVICE}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.source="${SOURCE}"

RUN groupadd --system --gid 10001 vitrin \
    && useradd --system --uid 10001 --gid 10001 \
       --no-create-home --shell /usr/sbin/nologin vitrin \
    && mkdir -p /app \
    && chown root:root /app \
    && chmod 0755 /app
WORKDIR /app

# Stable dependencies survive application-only changes.
COPY --from=extract /app/extracted/dependencies/ ./
COPY --from=extract /app/extracted/spring-boot-loader/ ./
COPY --from=extract /app/extracted/snapshot-dependencies/ ./
COPY --from=extract /app/extracted/application/ ./

USER 10001:10001
ENTRYPOINT ["java", "-jar", "app.jar"]
