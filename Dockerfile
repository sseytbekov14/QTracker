FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

# Cache dependencies separately from sources for faster rebuilds
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl unzip \
    && rm -rf /var/lib/apt/lists/*

COPY .mvn/ .mvn/
COPY mvnw ./
COPY pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp -DskipTests dependency:go-offline
COPY src/ src/

RUN ./mvnw -B -ntp -DskipTests clean package

FROM eclipse-temurin:25-jre

WORKDIR /app

RUN groupadd --system app && useradd --system --gid app --home-dir /app app
COPY --from=build /workspace/target/*.jar /app/app.jar
RUN chown app:app /app/app.jar

USER app

EXPOSE 8080

ENTRYPOINT ["java", "-Xmx384m", "-Xms384m", "-Dserver.port=${PORT:8080}", "-jar", "/app/app.jar"]