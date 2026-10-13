# Medições — Ark Engine

Benchmarks executados no caminho de escrita real do **backfill**, contra o
**XTDB/RocksDB** embarcado. Números medidos, não estimados.

- **Máquina:** Ryzen 5 3600 (6c/12t), 31 GB RAM, ext4 em NVMe
- **JVM:** OpenJDK 25 (`-Xmx2g`)
- **Método:** harness isolado que reproduz `orchestrator/perform-backfill!`
  trocando o conector Bitget por um stub determinístico (sem rede, sem Redis)
- **Carga:** 50 000 candles de `1m` (≈ 34,7 dias de um par)

---

## 1. Antes: 1 transação por candle

| Métrica | Valor |
| :--- | ---: |
| Wall time do backfill | **8,707 s** |
| Throughput | **5 742 candles/s** |
| Custo por candle | 0,174 ms |
| RSS de pico | **~1 051 MB** |
| Heap usado no fim | 388 MB |
| **`xtdb-data` em disco** | **507 MB** |
| → por candle | **~10,6 KB** |

### Composição do store

```
indexes    358M    (6 arquivos .log de ~54-71 MB)
tx-log      75M    (1 único .log de 71 MB)
documents   75M    (1 único .log de 71 MB)
```

**Causa raiz:** `ingest-bar!` chamava `xt/submit-tx` com **um documento por
transação**, e o `backfill` percorria os candles em `doseq` sequencial. Isso
produzia **50 000 transações** — o tx-log guarda ~1,5 KB por transação,
independentemente do tamanho do documento. O store ficou dominado por **WAL não
compactado** (8 segmentos `.log` contra apenas 2 `.sst`), porque a JVM encerrava
antes de o RocksDB compactar.

---

## 2. Depois: lotes de 500 + `sync!`

Mudanças aplicadas em `temporal-db` e `orchestrator/backfill.clj`:

- `ingest-bars!` — nova função que persiste N documentos numa **única transação**
- `sync!` — flush do stack de armazenamento antes do shutdown
- `backfill` — coage tudo **antes** de agrupar (isola falha por candle), grava em
  `partition-all 500`, e faz `sync!` no fim
- `get-history` — removido o `sort-by` em memória (o `:order-by` do XTDB já
  ordena); a ordem cronológica é restabelecida com `reverse`

| Métrica | Antes | Depois | Δ |
| :--- | ---: | ---: | ---: |
| Wall time | 8,707 s | **5,447 s** | **−37 %** |
| Throughput | 5 742 c/s | **9 179 c/s** | **+60 %** |
| Custo/candle | 0,174 ms | **0,109 ms** | −37 % |
| Heap usado | 388 MB | 337 MB | −13 % |
| **Disco** | 507 MB | **395 MB** | **−22 %** |
| → por candle | 10,6 KB | **7,9 KB** | −25 % |
| Transações XTDB | 50 000 | **100** | **−500×** |
| `.sst` (compactados) | 2 | 3 | — |

### O que o número de transações significa

O ganho de tempo e disco é real, mas **menor do que o −500× em transações
sugere**. Motivo: os 395 MB restantes continuam dominados por WAL grande
(`indexes` caiu de 358 MB para 246 MB, mas `tx-log` e `documents` seguem em
75 MB cada — são arquivos de log de 71 MB, não SSTs compactados).

Ou seja: **o batching reduziu o número de transações drasticamente, mas o
RocksDB não compactou proporcionalmente.** O `sync!` garante durabilidade, não
uma compactação completa. O custo de escrita por documento continua alto.

### Verificação funcional

- `get-history(250)` devolveu **250 documentos**
- Ordem cronológica correta: primeiro `147755M`, último `148004M` (crescente)
- 0 candles descartados pelo schema (o `coerce-all` não logou falhas)

---

## 3. Latência de leitura: `get-history`

| Operação | Tempo |
| :--- | ---: |
| `get-history` com `limit 250` | **2,985 s** |
| `get-history` com `limit 5` (medido antes) | 2,994 s |

**A latência é praticamente a mesma para 5 ou 250 documentos** — o que prova que
o custo **não está nos documentos retornados**, e sim na varredura: o tempo é
gasto antes do limit, resolvendo o triplo `[:symbol :timeframe :doc-type]`.

Isso significa que **remover o `sort-by` em memória não foi suficiente** — ele
eliminou uma reordenação de 5-250 itens (trivial), mas o gargalo real é o **scan
sem índice por `:timestamp`**, que segue sem tratamento.

> **Consequência operacional:** o `orchestrator/fetch-context` chama
> `get-history` com 250 candles **a cada ciclo de 60 s**. Se um ciclo de
> indicadores custa ~3 s (antes mesmo de calcular EMA/RSI), um terço do
> orçamento de tempo do loop é gasto na consulta. Num store maior, piora.

---

## 4. Reproduzindo

```bash
# harness em /tmp/ark-measure/measure2.clj, rodado da raiz do repo:
rm -rf /tmp/ark-measure/xtdb-data-opt
clojure -J-Xmx2g -Sdeps '{:paths ["/tmp/ark-measure"]
 :deps {com.nexus-quant.ark-engine/orchestrator {:local/root "components/orchestrator"}
        com.nexus-quant.ark-engine/temporal-db  {:local/root "components/temporal-db"}
        com.nexus-quant.ark-engine/connector    {:local/root "components/connector"}}}' \
  -M -m measure2 50000
```

---

## 5. Recomendações abertas

| # | Item | Impacto esperado |
| :-- | :--- | :--- |
| 1 | **Índice por `:timestamp`** em `get-history` (ou restringir a query a um range de valid-time) | Ataca os ~3 s de leitura |
| 2 | **Configurar compactação do RocksDB** (ou rodar `sync!`/compactação periódica) | Os 395 MB ainda são majoritariamente WAL |
| 3 | **Paginação no `fetch-history`** | A API do Bitget não aceita `limit: 50000` num request — backfill real precisa de janelas |
| 4 | **Retenção do XTDB** | Hoje cresce sem teto; no nexus já são 224 MB após minutos de ingestão ao vivo |
