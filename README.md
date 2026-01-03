# Ark Engine

![Stack](https://img.shields.io/badge/Stack-Clojure%20|%20XTDB%20|%20Redis-blue)
![Tests](https://img.shields.io/badge/Tests-Generative%20%26%20Integration-green)

**Ark Engine** is a distributed, bitemporal algorithmic trading infrastructure engineered by **Nexus Quantitative**.

Unlike traditional trading bots that optimize for signal frequency, Ark Engine prioritizes **Survival Architecture**. It implements a "Constitution-as-Code" approach, utilizing an immutable Risk Guard, reactive data ingestion via Redis Streams, and a bitemporal ledger (XTDB) for forensic-grade auditability.

## Architecture

The system follows the **Polylith** modular architecture to enforce strict separation of concerns between I/O, Logic, and Persistence.

```mermaid
graph TD
    subgraph External_World
        EXCHANGE[Exchange WebSocket]
    end

    subgraph Docker_Infrastructure ["Infra (Docker Containers)"]
        REDIS[("Redis (Streams & DLQ)")]
        XTDB_STORE[("XTDB Storage (RocksDB)")]
    end

    subgraph Application_Process ["JVM (Ark Engine Monolith)"]
        CONN[Connector Component]
        
        subgraph Ingestion_Worker
            ING[Ingestor Component]
            MALLI{Malli Validator}
        end
        
        RISK[Risk Guard Component]
        STRAT[Strategy Engine]
    end

    %% Real Implemented Flow (Commits 1-8)
    EXCHANGE -->|1. JSON Ticks| CONN
    CONN -->|2. XADD market-events| REDIS
    
    REDIS -->|3. XREADGROUP| ING
    ING -->|4. Coerce Data| MALLI
    
    MALLI -->|5a. Valid? Put| XTDB_STORE
    MALLI -->|5b. Invalid? XADD DLQ| REDIS

    %% Future/Architectural Flow (Dashed)
    XTDB_STORE -.->|6. Query State| STRAT
    STRAT -.->|7. Signal| RISK
    RISK -.->|8. Approved Order| CONN
    CONN -.->|9. Order Request| EXCHANGE

    classDef storage fill:#f9f,stroke:#333,stroke-width:2px;
    class REDIS,XTDB_STORE storage;
    classDef logic fill:#e1f5fe,stroke:#01579b,stroke-width:2px;
    class ING,MALLI,RISK,STRAT,CONN logic;
````

[Image of system architecture diagram]

### Polylith Component Dependency Graph
```mermaid
graph TD
    %% --- ESTILOS VISUAIS (Semântica) ---
    classDef nucleus fill:#fff176,stroke:#fbc02d,stroke-width:4px,color:black,font-weight:bold;
    classDef logic fill:#e1f5fe,stroke:#0277bd,stroke-width:2px,color:black;
    classDef infra fill:#f3e5f5,stroke:#7b1fa2,stroke-width:2px,color:black;
    classDef base fill:#ffe0b2,stroke:#e65100,stroke-width:3px,color:black;

    %% --- O NÚCLEO (A Verdade Universal) ---
    subgraph Core_Domain ["Core Domain (The Ontology)"]
        direction TB
        DOMAIN((domain-model)):::nucleus
    end

    %% --- CAMADA DE LÓGICA DE NEGÓCIO (Regras Puras) ---
    subgraph Business_Logic ["Business Rules Layer"]
        RISK[risk-guard]:::logic
        STRAT[strategy-engine]:::logic
        CANDLE[candle-aggregator]:::logic
        ORCH[orchestrator]:::logic
        
        %% Lógica depende do Domínio
        RISK -->|Enforces| DOMAIN
        STRAT -->|Computes using| DOMAIN
        CANDLE -->|Aggregates| DOMAIN
        ORCH -->|Orchestrates| DOMAIN
    end

    %% --- CAMADA DE INFRAESTRUTURA (Mecanismos) ---
    subgraph Infrastructure ["Infrastructure & Adapters Layer"]
        INGEST[ingestor]:::infra
        DB[temporal-db]:::infra
        CONN[connector]:::infra
        
        %% Infra depende do Domínio (Para saber o que gravar/ler)
        INGEST -->|Validates via| DOMAIN
        DB -->|Stores| DOMAIN
        CONN -->|Normalizes to| DOMAIN
        
        %% Conexões de Infra
        INGEST -.->|Reads| CONN
        INGEST -.->|Writes| DB
        INGEST -.->|Uses| CANDLE
    end

    %% --- CAMADA DE APLICAÇÃO (Orquestração) ---
    subgraph App_Entry ["Application Layer (Base)"]
        CLI[cli-runner]:::base
        VIZ[backtest-viz]:::base
        
        %% Aplicação amarra tudo
        CLI ==>|Invokes| ORCH
        VIZ -.->|Queries| DB
    end

    %% --- RELAÇÕES TRANSVERSAIS ---
    ORCH -.->|Fetches History| CONN
    ORCH -.->|Reads History| DB
    ORCH -.->|Executes| STRAT
    ORCH -.->|Validates| RISK

    %% Layout forcing (Manter o DOMAIN no centro visual)
    linkStyle default stroke:#333,stroke-width:1px;
`````
### Core Components

| Component | Responsibility | Tech Stack |
| :--- | :--- | :--- |
| **Risk Guard** | The "Constitution". Enforces hard limits on Drawdown and Leverage. Mathematically verified via Generative Testing. | `test.check` |
| **Ingestor** | Reactive worker that consumes market data, handles backpressure, and manages Poison Messages via DLQ. | `Carmine` (Redis) |
| **Temporal DB** | Bitemporal storage engine. Records *Valid Time* (Market Event) vs *Transaction Time* (System Knowledge) for unbiased backtesting. | `XTDB` (RocksDB) |
| **Domain Model** | Central ontology defining strict data contracts. Acts as a firewall against dirty data. | `Malli` |
| **Connector** | Abstraction layer for exchange connectivity. Supports idempotency and connection resilience. | `Aleph` (Netty) |
| **Orchestrator** | System coordinator acting as the central nervous system. Manages Backtesting loops and Live Execution cycles. | `Core.Async` |
| **Candle Aggregator** | Pure logic component for rolling up real-time ticks into OHLCV candles (1m -> 1h -> 4h). | `Clojure` |

## Getting Started

This project is a **Polylith Monorepo**.

### Prerequisites

  * Java 21+ (ZGC recommended)
  * [Clojure CLI](https://clojure.org/guides/install_clojure)
  * Docker & Docker Compose

### 1\. Infrastructure Setup

Start the persistence layer (Redis & XTDB/RocksDB volume).

```bash
docker-compose up -d
```

### 2\. Validation (Run the Tests)

We employ a tiered testing strategy. Ensure infrastructure is up before running integration tests.

| Scope | Command | Description |
| :--- | :--- | :--- |
| **Quick** | `clj -M:test-env:runner-fast` | Runs **Unit Tests** only. Fast, no Docker required. |
| **Full** | `clj -M:test-env:runner-all` | Runs **Integration + Generative**. Requires Redis. |
| **Smart** | `clj -M:poly test` | Runs tests only for **changed** components. |

### 3\. Development (REPL)

Start a REPL with the development profile (includes Portal, Criterium, and all components).

```bash
clj -M:poly shell
```

Inside the REPL:

```clojure
(user/reset) ; Reloads code via tools.namespace
(test-all)   ; Runs tests from the REPL
```

## Engineering Decisions & Trade-offs

### 1\. Persistence: XTDB (Bitemporality) over SQL

  * **The Problem:** Traditional backtesting suffers from "Lookahead Bias"—using data that wasn't actually available at the moment of decision (e.g., restated earnings, corrected price ticks).
  * **The Solution:** **XTDB** (formerly Crux) is a graph database that treats *Time* as a first-class citizen. It records two timelines:
      * **Valid Time:** When the event occurred in the market.
      * **Transaction Time:** When the system *learned* about the event.
  * **The Trade-off:** XTDB queries (Datalog) are computationally more expensive than SQL index lookups. We accept higher query latency for backtests in exchange for forensic-grade accuracy in recreating historical states via `db.asOf(t)`.

### 2\. Ingestion: Redis Streams over Kafka

  * **The Problem:** Market data ingestion requires sub-millisecond write latency to prevent bottlenecks during volatility spikes ("The Firehose").
  * **The Solution:** **Redis Streams** acts as a lightweight, low-latency buffer. It decouples the WebSocket producers from the heavy logic consumers using Consumer Groups for parallel processing.
  * **The Trade-off:** **Durability vs. Latency.** Unlike Kafka, Redis is memory-first. In a catastrophic power failure, unpersisted buffer data might be lost. We accept this risk for the ingestion layer because speed is the priority for the "Nervous System."

### 3\. Safety: Deterministic Risk Guard

  * **The Problem:** "Fat finger" errors or algorithmic bugs can liquidate an account in seconds. Relying on the strategy to check its own risk is a violation of the **Single Responsibility Principle**.
  * **The Solution:** The **Risk Guard** is an isolated component that acts as a firewall. It implements a strict "Constitution" (Max Drawdown, Leverage Limits) that cannot be overridden by the Strategy Engine.
  * **Implementation:** Architectural separation. The Executor component requires a signed `RiskToken` to submit orders, enforcing a "Check-then-Act" flow at the compilation level.

### 4\. Data Integrity: Malli over Raw Maps

  * **The Problem:** Clojure is dynamically typed. Passing raw HashMaps (`{:price 100}`) throughout the system leads to runtime `ClassCastException` errors deep in the core logic.
  * **The Solution:** **Malli** schemas define strict contracts at the system boundaries (Ingestor).
  * **Pattern:** **"Parse, Don't Validate."** Incoming dirty JSON is immediately coerced into strict Domain Types (BigDecimal, Instant). Invalid data is routed to a **Dead Letter Queue (DLQ)** immediately, ensuring the core logic only ever operates on mathematically valid data.

-----

*© 2025 Nexus Quantitative. Engineered for Sovereignty.*
