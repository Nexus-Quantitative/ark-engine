#!/bin/bash
set -e

echo "=== Ark Engine Local CI Runner ==="

# 1. Start Redis (dependency)
echo "Starting Redis via Docker Compose..."
docker compose up -d redis

# 2. Wait for Redis healthcheck
echo "Waiting for Redis to be ready..."
MAX_RETRIES=30
for ((i=1; i<=MAX_RETRIES; i++)); do
    if docker exec ark-redis redis-cli ping > /dev/null 2>&1; then
        echo "Redis is ready!"
        break
    fi
    if [ $i -eq $MAX_RETRIES ]; then
        echo "Error: Redis did not start in time."
        exit 1
    fi
    echo "Waiting for Redis... ($i/$MAX_RETRIES)"
    sleep 1
done

# 3. Run the actual tests (matches ci.yml step)
echo "Running Polylith tests..."
# Note: Polylith only runs tests for changed components. 
# If you see "No tests to run", it means no changes were detected.
# To force run ALL tests using Kaocha directly, use: clojure -M:test:runner-all
clojure -M:poly test :all

echo "=== CI Run Completed Successfully ==="
