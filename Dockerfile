FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace

# Skip apt-get update in offline environment - assume base image has required tools
# If curl/unzip needed, they should be pre-installed in base image

COPY .mvn/ .mvn/
COPY mvnw ./
COPY pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp -DskipTests dependency:go-offline
COPY src/ src/

RUN ./mvnw -B -ntp -DskipTests clean package

FROM eclipse-temurin:21-jre

WORKDIR /app

RUN groupadd --system app && useradd --system --gid app --home-dir /app app
COPY --from=build /workspace/target/*.jar /app/app.jar
RUN chown app:app /app/app.jar

USER app

EXPOSE 8080

ENTRYPOINT ["java", "-Xmx384m", "-Xms384m", "-Dserver.port=${PORT:8080}", "-jar", "/app/app.jar"]