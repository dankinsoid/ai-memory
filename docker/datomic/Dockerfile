FROM eclipse-temurin:21-jre

ARG DATOMIC_VERSION=1.0.7491

RUN apt-get update && apt-get install -y curl unzip && rm -rf /var/lib/apt/lists/*

RUN curl -sSL https://datomic-pro-downloads.s3.amazonaws.com/${DATOMIC_VERSION}/datomic-pro-${DATOMIC_VERSION}.zip -o /tmp/datomic.zip \
    && unzip /tmp/datomic.zip -d /opt \
    && mv /opt/datomic-pro-${DATOMIC_VERSION} /opt/datomic \
    && rm /tmp/datomic.zip

COPY dev-transactor.properties /opt/datomic/config/dev-transactor.properties

WORKDIR /opt/datomic

EXPOSE 4334 4335 4336

CMD ["bin/transactor", "-Xms256m", "config/dev-transactor.properties"]
