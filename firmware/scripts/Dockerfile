# Pinned NCS toolchain + what native_sim (twister unit tests) needs on top:
# make for the native_simulator runner, 32-bit libc for the default native_sim.
ARG NCS_VERSION=v3.4.1
FROM ghcr.io/nrfconnect/sdk-nrf-toolchain:${NCS_VERSION}
RUN apt-get update \
 && apt-get install -y --no-install-recommends make gcc-multilib g++-multilib \
 && rm -rf /var/lib/apt/lists/*
