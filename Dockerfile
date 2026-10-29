FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app
COPY . .
RUN mkdir -p out && javac --add-modules jdk.httpserver -d out src/main/java/BlogServer.java
EXPOSE 8080
ENV PORT=8080
CMD ["java", "--add-modules", "jdk.httpserver", "-cp", "out", "BlogServer"]
