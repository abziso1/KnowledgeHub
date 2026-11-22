FROM maven:3.9.12-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY ops/fastdfs/install-java-sdk.sh ops/fastdfs/versions.env ops/fastdfs/
RUN bash ops/fastdfs/install-java-sdk.sh
COPY backend/pom.xml backend/pom.xml
COPY backend/src backend/src
RUN mvn -B -ntp -f backend/pom.xml -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-noble@sha256:ac595159aa2209f07a2331efa8a8ce4278b77a7b76a9365a0f173786d5067a9e
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/backend/target/knowledgehub-0.1.0-SNAPSHOT.jar app.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
