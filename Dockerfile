# @ai-generated(solo)
# BACKEND arg selects which store implementation to include: datomic (default) or datalevin.
# Usage: docker build --build-arg BACKEND=datomic .
FROM clojure:temurin-21-tools-deps AS builder
ARG BACKEND=datomic
WORKDIR /app
COPY deps.edn build.clj ./
# Pre-fetch deps for both base and the selected backend alias
RUN clj -P -M:${BACKEND}
COPY src/ src/
COPY src-${BACKEND}/ src-${BACKEND}/
COPY resources/ resources/
RUN clj -T:build uber :backend ${BACKEND}

FROM eclipse-temurin:21-jre
ARG BACKEND=datomic
WORKDIR /app
COPY --from=builder /app/target/*-standalone.jar app.jar
EXPOSE 8080
# Datalevin needs NIO access for LMDB memory-mapped files
ENV BACKEND=${BACKEND}
ENTRYPOINT ["sh", "-c", \
  "if [ \"$BACKEND\" = \"datalevin\" ]; then \
    exec java --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED -jar app.jar; \
  else \
    exec java -jar app.jar; \
  fi"]
