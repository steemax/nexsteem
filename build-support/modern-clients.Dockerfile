FROM debian:13-slim
RUN apt-get update && apt-get install -y --no-install-recommends cargo rustc git git-lfs composer unzip ca-certificates python3 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /work
