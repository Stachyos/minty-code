FROM node:22-bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-venv openjdk-17-jdk-headless maven && rm -rf /var/lib/apt/lists/*
RUN python3 -m venv /opt/test-env && /opt/test-env/bin/pip install --no-cache-dir pytest==8.3.5
ENV PATH="/opt/test-env/bin:${PATH}" PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
WORKDIR /workspace
