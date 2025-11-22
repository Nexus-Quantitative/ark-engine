# **NEXUS QUANTITATIVE — TRADING POLICY STATEMENT (TPS)**

| Document ID | NQ-GOV-001 |
| :---- | :---- |
| **Version** | 1.0.0 |
| **Status** | **ACTIVE / IMMUTABLE** |
| **Enforcement** | Automated via risk-guard component |
| **Jurisdiction** | Global / Sovereign (DeFi Preference) |

## **PREAMBLE**

**Nexus Quantitative** operates under a single directive: **Survival is the precursor to success.**

This Constitution defines the hard boundaries within which the *Ark Engine* is permitted to operate. It serves as the supreme law of the system. No algorithmic signal, AI prediction, or human discretionary desire supersedes the risk limits defined herein.

Violations of this document by the software must result in an immediate HALT state and exception throwing. Violations by the human operator constitute a breach of fiduciary duty.

## **ARTICLE I: CAPITAL PRESERVATION (THE HARD STOPS)**

### **Section 1.1: Global Drawdown Limit**

The preservation of the capital base is paramount to leverage the geometric growth of compound interest.

* **Rule:** If the Total Net Asset Value (NAV) experiences a drawdown of **15.00%** from the monthly High Water Mark (HWM):  
  * **Action:** The System enters EMERGENCY\_LIQUIDATION mode.  
  * **Execution:** All open risk positions (Futures, Margin, Options) must be closed at market immediately.  
  * **Lockout:** Trading is suspended until the start of the next calendar month.

### **Section 1.2: The Ruin Probability Cap**

* **Rule:** No single trade or series of correlated trades shall carry a mathematically projected Risk of Ruin (\>95% VaR) greater than **2.0%** of the Total NAV.

## **ARTICLE II: EXPOSURE AND LEVERAGE (THE SPEED LIMITS)**

### **Section 2.1: Leverage Constraints**

Leverage is a tool for efficiency, not for gambling.

* **Gross Notional Limit:** The total value of all open positions (Longs \+ Shorts absolute value) shall never exceed **3.0x** the Net Equity.  
* **Net Altcoin Leverage:** The leverage applied to non-major assets (excluding BTC, ETH, Gold, Stablecoins) shall never exceed **1.0x** (Spot equivalent). Naked leveraged shorts on low-cap assets are strictly prohibited.

### **Section 2.2: Negative Carry Protection**

* **Rule:** The system is prohibited from holding a position with **Negative Carry** (e.g., paying high Funding Rates) for longer than **24 hours** unless the unrealized PnL is positive and exceeds the cost of carry by a factor of 1.5x.

## **ARTICLE III: DIVERSIFICATION AND CONCENTRATION**

### **Section 3.1: Single Asset Exposure**

To mitigate idiosyncratic risk (project failure, hacks, delisting):

* **Rule:** Maximum allocation to any Single Ticker (excluding BTC, ETH, USD-pegs, Gold-pegs) is capped at **20%** of NAV.  
* **Logic:** A 100% collapse of a single asset must not wipe out more than 20% of the firm's equity.

### **Section 3.2: Counterparty Risk (The "Not Your Keys" Clause)**

* **Rule:** Maximum capital deployed to a single Centralized Exchange (CEX) is capped at **40%** of NAV.  
* **Action:** Excess capital must be rotated to On-Chain Self-Custody (Cold Storage) or Decentralized Protocols (Hyperliquid/dYdX).

## **ARTICLE IV: EXECUTION INTEGRITY (MICROSTRUCTURE)**

### **Section 4.1: Liquidity Filters**

The system shall not trade in "ghost towns" where exit costs are unpredictable.

* **Minimum Volume:** Assets with 24h Volume \< **$10,000,000 USD** are blacklisted.  
* **Spread Limit:** Orders shall not be submitted if the Bid-Ask Spread exceeds **0.15%** (15 bps).

### **Section 4.2: Latency & Infrastructure Health (The Dead Man's Switch)**

* **Rule:** If the market-data-drift (LocalTime \- ExchangeTime) exceeds **500ms** for a duration of 60 seconds:  
  * **Action:** System enters DEFENSIVE\_MODE.  
  * **Execution:** All resting Limit Orders are cancelled. No new entries are permitted.  
  * **Recovery:** Automatic resumption only after drift stabilizes \< 200ms for 5 minutes.

## **ARTICLE V: OPERATIONAL SOVEREIGNTY**

### **Section 5.1: Jurisdictional Agnosticism**

* **Rule:** The system architecture must remain decoupled from specific geographic IPs or identities.  
* **Requirement:** All external connectivity must be routed through obfuscated channels (VPN/WireGuard/Tor) ensuring no direct link between the Operator's physical location and the Execution Venue.

### **Section 5.2: The Accumulation Directive**

* **Rule:** Realized profits are not to be repatriated to local fiat currency (BRL) for consumption purposes until the portfolio reaches the target defined in the *Endgame Strategy*.  
* **Action:** Profits are to be compounded or moved to Cold Storage (Paraguay Protocol).

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

**Signed and Ratified by:**

*Nexus Quantitative Architecture Board*