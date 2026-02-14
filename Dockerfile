FROM clojure:temurin-21-tools-deps AS builder
WORKDIR /app
COPY deps.edn build.clj ./
RUN clj -P
COPY src/ src/
COPY resources/ resources/
RUN clj -T:build uber

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/target/*-standalone.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
