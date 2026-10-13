ARG CLIENT_BASE=nexus-modern-clients:debian13
FROM ${CLIENT_BASE}
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
