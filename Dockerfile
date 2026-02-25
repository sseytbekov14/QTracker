FROM eclipse-temurin:25-jre

WORKDIR /app

# non-root user
RUN useradd -r -u 10001 appuser

# deterministic jar name: ожидаем, что JAR один и называется qtracker.jar
COPY target/*.jar /app/app.jar

RUN chown -R appuser:appuser /app
USER appuser

EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]