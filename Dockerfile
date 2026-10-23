FROM eclipse-temurin:21-jre

WORKDIR /app

RUN groupadd --system app && useradd --system --gid app --home-dir /app app

COPY target/*.jar /app/app.jar

RUN chown app:app /app/app.jar

USER app

# Порт и HTTPS задаются переменными окружения (SERVER_PORT, SERVER_SSL_*) в docker-compose.yml.
# Не задавайте их здесь параметрами -D: такие параметры перебивают переменные окружения.
EXPOSE 8081

# Память меняется без пересборки: JAVA_OPTS в .env
ENV JAVA_OPTS="-Xms256m -Xmx768m"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]