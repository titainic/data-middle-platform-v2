#!/bin/bash

# Create builder (key step)
docker buildx create \
  --name qdata-builder \
  --driver docker-container \
  --use

# Start builder (load QEMU, etc.)
docker buildx inspect --bootstrap

# Build AMD64 (x86_64) version
cd /mnt/c/Users/Ming/Desktop/qData/qdata-server-ce # Change the path to your own path

docker buildx build \
  --platform linux/amd64 \
  --no-cache \
  -t qdata-server-ce:local \
  --file=docker/Dockerfile \
  --load \
  /mnt/c/Users/Ming/Desktop/qData/qdata-server-ce # Change the context path to your own path

# Build ARM64 (adapted to Kunpeng, Feiteng, Raspberry Pi and other ARM servers)
docker buildx build \
  --platform linux/arm64 \
  --no-cache \
  -t qdata-server-ce:local \
  --file=docker/Dockerfile \
  --load \
  /mnt/c/Users/Ming/Desktop/qData/qdata-server-ce # Change the context path to your own path

# Check if ARM64 is supported
docker inspect qdata-server-ce:local --format '{{.Architecture}}'

# Delete the previously built builder (optional but recommended, keep it clean)
docker buildx rm qdata-builder
