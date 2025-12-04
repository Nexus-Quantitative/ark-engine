# Configuração de Chaves de API (Exchanges)

Este documento descreve o processo de geração e configuração das chaves de API para as corretoras suportadas pelo **Ark Engine**.

## Segurança

*   **NUNCA** comite suas chaves de API no controle de versão (Git).
*   Utilize variáveis de ambiente para injetar as credenciais na aplicação.
*   Restrinja as permissões da API apenas ao necessário (ex: não habilite saques/withdrawals).
*   Se possível, restrinja o acesso por IP.

---

## 1. Bitget

O conector da Bitget utiliza a API V2 para dados de mercado e execução de ordens em Futuros (USDT-M).

### Passo a Passo para Criação

1.  Faça login na sua conta [Bitget](https://www.bitget.com/).
2.  Navegue até o menu de usuário (ícone de perfil) e selecione **Gerenciamento de API** (API Management).
3.  Clique em **Criar nova API** (Create new API).
4.  Selecione **Chave gerada pelo sistema** (System-generated API Key).
5.  Preencha os detalhes:
    *   **Observações**: Dê um nome para identificar a chave (ex: `ArkEngine-Dev`).
    *   **Senha da API (Passphrase)**: Crie uma senha forte e **guarde-a**. Ela é necessária para assinar as requisições, além da Secret Key.
6.  **Permissões**:
    *   **Contrato Futuro (Futures)**: Marque "Ordens" (Orders) e "Holdings" (Posições).
    *   **Carteira (Wallet)**: Opcional, mas útil para ler o saldo.
    *   **NÃO** marque "Transferência" ou "Saque".
7.  **Vinculação de IP**:
    *   Para desenvolvimento local, você pode deixar em branco (menos seguro, a chave expira em 90 dias).
    *   Para produção, insira o IP estático do seu servidor.
8.  Conclua a verificação de segurança (2FA).
9.  **Copie e Salve**:
    *   `Access Key` (API Key)
    *   `Secret Key` (mostrada apenas uma vez!)

### Configuração no Ark Engine

O adaptador da Bitget espera receber um mapa de configuração contendo `:api-key`, `:secret-key` e `:passphrase`.

Recomendamos exportar essas credenciais como variáveis de ambiente:

```bash
# No seu terminal ou arquivo .env (não comitado)
export BITGET_API_KEY="bg_..."
export BITGET_SECRET_KEY="b1..."
export BITGET_PASSPHRASE="SuaSenhaForte"
```

#### Exemplo de Inicialização (Clojure)

```clojure
(require '[com.nexus-quant.ark-engine.connector.bitget :as bitget]
         '[com.nexus-quant.ark-engine.connector.interface :as i])

(def config
  {:api-key    (System/getenv "BITGET_API_KEY")
   :secret-key (System/getenv "BITGET_SECRET_KEY")
   :passphrase (System/getenv "BITGET_PASSPHRASE")})

(def adapter (bitget/create))
(i/initialize! adapter config)
```

---

## 2. Binance (Futuro) - *Planejado*

*Instruções serão adicionadas quando o conector da Binance for implementado.*
