# Use Eclipse Temurin JDK 17 slim
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# Copy jar created by maven package (run `mvn -DskipTests package` first)
COPY target/rag-learning-1.0-SNAPSHOT.jar app.jar

# Expose port
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
