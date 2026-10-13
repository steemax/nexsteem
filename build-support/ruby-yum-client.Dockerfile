FROM debian:13-slim
RUN apt-get update && apt-get install -y --no-install-recommends ruby ruby-bundler rpm dnf python3 curl ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /work
