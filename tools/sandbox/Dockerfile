# Build sandbox for this plugin: the same JDK distribution as CI (Temurin) on
# the same Ubuntu release as the pinned runners (24.04 "noble"), plus the few
# tools sandbox.sh needs. Nothing plugin-specific is baked in -- the checkout is
# mounted at /work and the Maven repository lives in a named volume, so the
# image only changes when the JDK does. See tools/sandbox/README.md.
ARG JDK=21
FROM eclipse-temurin:${JDK}-jdk-noble
RUN apt-get update \
 && apt-get install -y --no-install-recommends git curl python3 \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /work
