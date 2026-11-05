FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY backend/pom.xml .
RUN mvn -B dependency:go-offline
COPY backend/src ./src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-pip ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt /app/requirements.txt
RUN pip3 install --no-cache-dir -r requirements.txt
COPY agent.py worker.py agent_context.py coding_tools.py evaluation_worker.py /app/
COPY infra /app/infra
COPY benchmarks /app/benchmarks
COPY --from=build /build/target/minty-assistant-0.1.0.jar /app/app.jar
RUN useradd --create-home --uid 10001 minty && mkdir -p /app/data/workspaces && chown -R minty:minty /app
USER minty
ENV SERVER_ADDRESS=0.0.0.0 AGENT_PYTHON=python3 AGENT_WORKER=/app/worker.py AGENT_WORKSPACES=/app/data/workspaces
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
