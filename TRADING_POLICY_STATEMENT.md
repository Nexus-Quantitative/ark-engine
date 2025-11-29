# **NEXUS QUANTITATIVE — TRADING POLICY STATEMENT (TPS)**

| Document ID | NQ-GOV-001 |
| :---- | :---- |
| **Version** | 1.0.1 |
| **Status** | **ACTIVE / IMMUTABLE** |
| **Enforcement** | Automated via risk-guard component |
| **Jurisdiction** | Global / Sovereign (DeFi Preference) |

## **PREAMBLE**

**Nexus Quantitative** operates under a single directive: **Survival is the precursor to success.**

This Constitution defines the hard boundaries within which the *Ark Engine* is permitted to operate. It serves as the supreme law of the system. No algorithmic signal, AI prediction, or human discretionary desire supersedes the risk limits defined herein.

Violations of this document by the software must result in an immediate HALT state and exception throwing. Violations by the human operator constitute a breach of fiduciary duty.

## **ARTICLE I: CAPITAL PRESERVATION (MONEY MANAGEMENT)**
*Objective: Mathematical Survival and Ruin Control.*

### **Section 1.1: Tiered Drawdown Protocol**
The system must enforce forced stops based on Net Asset Value (NAV) performance, strictly adhering to Elder's "6% Rule".

1.  **The Cool-Down (6%):** If the monthly Drawdown hits **6.0%**:
    * **System Action:** State transitions to `PAUSE`. New position entries are blocked for 24 hours to force method re-evaluation.
2.  **The Hard Stop (15%):** If the monthly Drawdown hits **15.0%**:
    * **System Action:** State transitions to `HALT`. The Orchestrator must initiate liquidation of risk positions.
    * **Restriction:** No new risk orders may be signed until the start of the next calendar month.

### **Section 1.2: The Risk Reduction Exception**
* **Rule:** The Risk Module (`risk-guard`) **must approve** any order flagged as `{:reduce-only? true}`, regardless of the current Drawdown state or concentration limits.
* **Logic:** The ability to exit a position (Stop Loss) is sovereign. Risk bureaucracy must never impede the stemming of a bleed.

### **Section 1.3: The Exposure Cap per Trade**
* **Rule:** The Projected Financial Risk (Loss upon Stop Hit) of any individual trade must not exceed **2.0%** of Total Capital.
* **Enforcement:** This calculation must be validated atomically before order submission.

---

## **ARTICLE II: EXPOSURE AND LEVERAGE (THE SPEED LIMITS)**
*Objective: Prevent catastrophic ruin from over-extension and negative expectancy.*

### **Section 2.1: Leverage Constraints**
* **Gross Notional Limit:** The total value of all open positions (Longs + Shorts absolute value) shall never exceed **3.0x** the Net Equity.
* **Net Altcoin Leverage:** The leverage applied to non-major assets (excluding BTC, ETH, Stablecoins) shall never exceed **1.0x** (Spot equivalent).

### **Section 2.2: Negative Carry Protection**
* **Rule:** The system is prohibited from holding a position with **Negative Carry** (e.g., paying high Funding Rates) for longer than **24 hours**, unless the unrealized PnL is positive and exceeds the accumulated cost of carry by a factor of 1.5x.

---

## **ARTICLE III: METHODOLOGICAL INTEGRITY (THE STRATEGY)**
*Objective: Ensure every trade follows a defined, auditable Logic Regime.*

### **Section 3.1: Strategy Classification Mandate**
* **Rule:** Every order must be associated with a registered **Strategy ID** that has been formally approved and deployed to the system.
* **Requirement:** Each strategy must explicitly declare its **Strategic Intent** and operational parameters in a separate Strategy Specification document.

### **Section 3.2: The Multi-Factor Validation Requirement**
* **Rule:** No trade may be executed based on a single signal or data point.
* **Enforcement:** All strategies must implement a multi-factor validation mechanism appropriate to their methodology.
* **Documentation:** The validation logic must be documented and auditable.

### **Section 3.3: Bitemporal Audit (Record Keeping)**
* **Rule:** No order may be submitted to the Exchange without the prior persistence of a `StrategySignal` document in the bitemporal database (XTDB).
* **Content:** The record must capture the exact **Market Context** (Indicator values, Price, Latency) at the moment of decision, ensuring forensic auditability of why the trade was taken.
* **Immutability:** Strategy signal records are append-only and cannot be modified post-execution.

---

## **ARTICLE IV: INFRASTRUCTURAL INTEGRITY (THE IRON CORE)**
*Objective: Protection against distributed environment failures.*

### **Section 4.1: The Latency Circuit Breaker**
* **Rule:** If data ingestion delay (Drift between `EventTime` and `SystemTime`) exceeds **500ms**, the system must reject new entries.
* **Logic:** Operating on stale data violates the principle of statistical advantage.

### **Section 4.2: Data Hygiene**
* **Rule:** All market data must be validated against the `Domain Model` (Malli Schemas) at the system edge.
* **Action:** Invalid data must be routed to Dead Letter Queues (DLQ) and never used for risk calculation.

---

## **ARTICLE V: SOVEREIGNTY AND OPERATION (MIND)**
*Objective: Shielding against Human and Jurisdictional Factors.*

### **Section 5.1: The Non-Intervention Doctrine**
* **Rule:** Manual intervention at runtime (via REPL or Dashboard) to increase risk parameters during trading hours is strictly prohibited ("Revenge Trading").
* **Exception:** Intervention is permitted only for *reducing* exposure or technical shutdown in the event of infrastructure failure.

### **Section 5.2: Custodial Sovereignty**
* **Directive:** The system must prioritize execution in environments that minimize the risk of censorship or asset freezing (preference for DeFi/Hyperliquid or Offshore Structures), provided liquidity conditions are met.

---

## **ARTICLE VI: GOVERNANCE AND INTERVENTION**



### **Section 6.1: Human Intervention (The "Kill Switch")**



The Human Operator ("CEO") retains the right to override the system ONLY under the following conditions (Force Majeure):



1. **Technical Failure:** Infinite loops, API errors, or data corruption.

2. **Black Swan Events:** De-pegging of major stablecoins (USDT/USDC), Exchange insolvency rumors, or geopolitical acts of war affecting global connectivity.

3. **Deployment:** Scheduled updates.



*Discretionary intervention based on "gut feeling" or "fear of missing out" (FOMO) is strictly prohibited.*



### **Section 6.2: Deployment Policy**



* **Rule:** No strategy code is promoted to the main branch (Production) without:

1. Passing the **Generative Test Suite** (test.check).

2. A minimum 3-month Backtest over a volatile period showing positive Expectancy.

3. A Slippage simulation audit.

---


**Signed and Ratified by:**

*Nexus Quantitative Architecture Board*