FROM eclipse-temurin:24-jre-alpine
WORKDIR /app
COPY build/libs/htmlshare-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 7331
ENTRYPOINT ["java", "-jar", "app.jar"]
