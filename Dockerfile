FROM eclipse-temurin:17-jre
COPY bot/target/bot-1.0.jar app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
