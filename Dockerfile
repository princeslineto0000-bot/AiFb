FROM eclipse-temurin:17-jdk

WORKDIR /app

COPY GeminiServer.java index.html styles.css script.js ./

RUN javac --add-modules jdk.httpserver GeminiServer.java

EXPOSE 8080

CMD ["java", "--add-modules", "jdk.httpserver", "GeminiServer"]
