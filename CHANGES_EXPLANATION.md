# Refactoring Documentation: Strict Domain & Persistence Hardening

## Overview
This document outlines the recent refactoring applied to the `ark-engine` workspace. The changes primarily focus on hardening the domain model for financial precision, standardizing data schemas, and ensuring robust data ingestion and persistence.

## 1. Domain Model Hardening (`components/domain-model`)

### Strict Decimal Handling
**Change:** Removed the automatic coercion of `number` (Double/Float) to `BigDecimal` in the Malli type registry.
**Reasoning:** Financial applications cannot tolerate the precision loss inherent in IEEE 754 floating-point arithmetic. By removing this coercion, we force the Ingestor and other upstream components to provide decimals as **Strings**. This ensures that values like "98000.50" are parsed exactly as `98000.50M` and never as `98000.500000000001`.

### Schema Standardization & Expansion
**Change:**
- **Renamed Keys:** Expanded abbreviated keys to their full descriptive names for clarity and maintainability.
    - `:c` -> `:close`
    - `:o` -> `:open`
    - `:h` -> `:high`
    - `:l` -> `:low`
    - `:v` -> `:volume`
    - `:tf` -> `:timeframe`
    - `:ts` -> `:timestamp`
- **Mandatory OHLC:** The `Candle` schema now enforces the presence of all OHLC fields (`:closed true`).
- **New Schemas:** Added `WireTick` and `Tick` schemas to support tick-level data ingestion.
- **Wire vs. Domain:** Clearly distinguished between `Wire*` schemas (tolerant, for ingestion) and domain entities (strict, for internal logic).

**Reasoning:**
- **Clarity:** Full names reduce cognitive load and ambiguity.
- **Integrity:** Enforcing mandatory OHLC fields prevents "partial" or corrupted candles from entering the persistence layer and downstream logic.
- **Extensibility:** Adding Tick support prepares the engine for higher-frequency strategies.

## 2. Ingestor Updates (`components/ingestor`)

### JSON Parsing
**Change:** Updated tests and implied logic to handle JSON strings instead of EDN.
**Reasoning:** External exchanges and data providers predominantly use JSON. The ingestor must natively handle JSON parsing, including the critical step of parsing numeric fields as strings (to satisfy the domain model's strict decimal requirement) or directly to BigDecimal.

## 3. Temporal DB Adaptation (`components/temporal-db`)

### Schema Alignment
**Change:** Updated `ingest-bar!` and `get-bar` to respect the new, verbose schema keys (`:timeframe`, `:timestamp`, `:close`, etc.).

### Data Cleaning & Persistence
**Change:**
- **Discriminator Removal:** The `:type` field is `dissoc`'ed before persistence.
- **Time Conversion (The Bridge):** Explicitly converts `java.time.Instant` (Domain Type) to `java.util.Date` (XTDB Type) at the persistence boundary.

**Reasoning:**
- **Storage Efficiency:** Removing the discriminator key before storage avoids redundancy if the doc-type is handled via XTDB metadata or separate indices.
- **Compatibility:** XTDB's valid-time axis requires `java.util.Date`.

## 4. Test Suite Alignment

### Type Safety in Tests
**Change:** Updated all tests to use `java.time.Instant` (e.g., via `Instant/parse`) instead of `java.util.Date` (via `#inst`).
**Reasoning:** The Domain Model now strictly enforces `Instant`. Passing `Date` objects from tests caused `ClassCastException` in the coercion logic. The tests now accurately reflect the production flow where the Ingestor produces `Instant` objects.

### Verification
**Status:** All tests passed.
- `clj -M:test:runner-fast`: Verified unit logic and schema validation.
- `clj -M:test:runner-all`: Verified integration with XTDB and correct time-travel querying.

## Summary of Impact
These changes collectively raise the "robustness level" of the engine. We have moved from a prototype-friendly, loose schema approach to a production-grade, strict financial data pipeline. The risk of silent data corruption due to floating-point errors or partial data has been significantly reduced.
