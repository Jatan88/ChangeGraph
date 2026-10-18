FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /src
COPY mvnw mvnw.cmd ./
COPY .mvn .mvn
COPY pom.xml .
RUN chmod +x mvnw && ./mvnw -B -DskipTests dependency:go-offline
COPY src src
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:25-jre-noble
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home /app app
WORKDIR /app
COPY --from=build /src/target/ChangeGraph-Backend-*.jar /app/app.jar
USER app
EXPOSE 1213
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
