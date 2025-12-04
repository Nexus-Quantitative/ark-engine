(ns com.nexus-quant.ark-engine.connector.interface)

(defprotocol ExchangeConnector
  "Abstract protocol for interaction with any execution venue.
   Enforces idempotency and standardized error handling."

  (initialize! [this config]
    "Initializes the connection. Returns component instance.")

  (subscribe! [this topics output-channel]
    "Subscribes to public OR private data feeds.
     
     Args:
       topics: Vector of maps e.g., [{:type :ticker :symbol 'BTC/USDT'} {:type :execution-report}]
       output-channel: Channel for normalized events.
     
     Rationale: Mixing market data and private account updates in the same stream
     reduces race conditions between 'Order Sent' and 'Balance Updated'.")

  (submit-order! [this order-params]
    "Submits an execution order.
     
     CRITICAL: order-params MUST contain a unique :client-oid (UUID string).
     This ensures idempotency. If the network fails, re-sending with the same
     client-oid prevents double execution at the exchange level.
     
     Returns: Future resolving to {:status :ack} or {:status :rejected :reason ...}")

  (cancel-order! [this order-id symbol]
    "Cancels an active order.")

  (get-portfolio-state [this]
    "Synchronous fallback for portfolio snapshot. 
     Used for reconciliation loops, not for high-frequency decision making.")

  (disconnect! [this]
    "Terminates connections.")

  (fetch-history [this symbol timeframe limit]
    "Fetches historical candle data via REST API.
     
     Args:
       symbol: Trading pair (e.g., 'BTCUSDT')
       timeframe: Candle interval (e.g., '1h')
       limit: Number of candles to retrieve
     
     Returns:
       Sequence of maps matching the WireCandle format."))