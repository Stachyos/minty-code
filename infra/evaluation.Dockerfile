FROM python:3.11-slim
RUN apt-get update && apt-get install -y --no-install-recommends git build-essential && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir swebench==3.0.15
ENV PYTHONUNBUFFERED=1
WORKDIR /run
