# Ark Engine Environment

This document describes how to set up and run the local development environment for Ark Engine.

## Prerequisites

- [Docker](https://www.docker.com/) installed and running.
- [Docker Compose](https://docs.docker.com/compose/) (usually included with Docker Desktop/Engine).

## Quick Start

To start the infrastructure services (Redis):

```bash
docker-compose up -d
```

To stop the services:

```bash
docker-compose down
```

## Services

### Redis
- **Image**: `redis:alpine`
- **Port**: `6379`
- **Persistence**: AOF (Append Only File) enabled for data safety.
- **Volume**: `redis_data` persists Redis data between restarts.

### XTDB / RocksDB Storage
- **Volume**: `xtdb_data`
- This volume is defined to store XTDB (RocksDB) data. Since XTDB runs embedded within the application (not as a separate container), this volume is prepared for future containerization of the app or for mapping data if needed.

# Development Philosophy: 
Interactive REPL-driven exploration for I/O boundaries, solidified by generative property-based testing for core financial logic.