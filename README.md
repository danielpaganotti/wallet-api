# 💳 Wallet API

API backend para uma **carteira digital**, permitindo a criação de carteiras, operações de crédito e débito e consulta do extrato.

O principal objetivo deste desafio técnico é demonstrar como construir uma **API de transações financeiras segura e consistente sob concorrência**, garantindo operações idempotentes e proteção em diferentes níveis da aplicação e do banco de dados.

Desenvolvida utilizando **Java 17, Spring Boot 3, PostgreSQL, Flyway, JPA e Testcontainers**.

---

## 🎯 Objetivos do desafio

A API foi projetada considerando os seguintes requisitos:

* Criar e consultar carteiras
* Registrar operações de `CREDIT` e `DEBIT`
* Impedir que o saldo de uma carteira fique negativo
* Garantir a idempotência das operações
* Tratar requisições concorrentes sobre a mesma carteira
* Disponibilizar o extrato das transações com paginação
* Validar as requisições
* Possuir testes unitários e de integração
* Controlar a evolução do banco de dados utilizando Flyway

O principal desafio técnico está na **estratégia de concorrência**, que combina bloqueio de linha no banco de dados, idempotência e constraints de banco.

---

# 🏗️ Arquitetura

A aplicação segue uma arquitetura em camadas utilizando Spring Boot:

```text
┌──────────────────────────────────────────────┐
│                  REST API                    │
│       Controllers + DTOs de entrada/saída   │
└──────────────────────┬───────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────┐
│                  Services                    │
│        Regras de negócio + Transações       │
└──────────────────────┬───────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────┐
│                Repositories                  │
│       Spring Data JPA + Specifications      │
└──────────────────────┬───────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────┐
│                 PostgreSQL                   │
│       Carteiras + Transações financeiras    │
└──────────────────────────────────────────────┘
```
---

# 🔐 Autenticação

Para este desafio, a API utiliza um mecanismo simples de autenticação baseado em uma API Key.

```http
X-API-Key: changeme-local-dev-key
```

A chave é configurada através da variável de ambiente:

```text
API_KEY
```

A autenticação de usuários finais utilizando JWT/OAuth está **fora do escopo** deste desafio.

---

# 💰 Modelo de negócio

## Carteira

Uma carteira possui:

* Identificador UUID
* Informações opcionais do titular
* Saldo atual
* Versão para controle de concorrência
* Data de criação
* Data de atualização

```text
Wallet
 ├── id
 ├── ownerName
 ├── ownerDocument
 ├── balance
 ├── version
 ├── createdAt
 └── updatedAt
```

O banco de dados possui uma proteção adicional para garantir que o saldo nunca fique negativo:

```sql
CHECK (balance >= 0)
```

Dessa forma, mesmo que algum caminho inesperado da aplicação tente persistir um saldo negativo, o próprio banco rejeitará a operação.

---

## Transação

Cada operação realizada na carteira gera um registro de transação:

```text
WalletTransaction
 ├── id
 ├── walletId
 ├── type
 ├── amount
 ├── balanceAfter
 ├── idempotencyKey
 ├── description
 └── createdAt
```

Os tipos de operação disponíveis são:

```text
CREDIT
DEBIT
```

Os valores monetários utilizam:

```java
BigDecimal
```

e são armazenados no PostgreSQL como:

```text
NUMERIC(19,2)
```

---

# 🔄 API

| Operação            | Método | Endpoint                     |
| ------------------- | ------ | ---------------------------- |
| Criar carteira      | `POST` | `/wallets`                   |
| Consultar carteira  | `GET`  | `/wallets/{id}`              |
| Registrar transação | `POST` | `/wallets/{id}/transactions` |
| Consultar extrato   | `GET`  | `/wallets/{id}/transactions` |

---

## Criar carteira

```http
POST /wallets
X-API-Key: changeme-local-dev-key
Content-Type: application/json
```

O corpo da requisição é opcional:

```json
{
  "ownerName": "João da Silva",
  "ownerDocument": "12345678900"
}
```

Uma nova carteira é criada com:

```text
balance = 0.00
```

---

## Registrar transação

Toda operação exige uma chave de idempotência:

```http
POST /wallets/{id}/transactions
Idempotency-Key: 7f8a9c2d
X-API-Key: changeme-local-dev-key
Content-Type: application/json
```

### Exemplo de crédito

```json
{
  "type": "CREDIT",
  "amount": 100.00,
  "description": "Depósito inicial"
}
```

### Exemplo de débito

```json
{
  "type": "DEBIT",
  "amount": 30.00,
  "description": "Compra"
}
```

A primeira requisição processada com sucesso retorna:

```text
201 Created
```

Um replay idêntico retorna:

```text
200 OK
```

sem alterar o saldo novamente.

---

# 🛡️ Estratégia de concorrência

A concorrência é o principal desafio técnico desta implementação.

Existem duas regras que precisam ser preservadas em qualquer situação:

### 1. O saldo nunca pode ficar negativo

### 2. Uma mesma transação nunca pode ser aplicada duas vezes

Para garantir isso, foram utilizadas **três camadas de proteção**.

---

## 1. Bloqueio pessimista da linha

Toda operação que modifica uma carteira passa por:

```text
WalletRepository.findByIdForUpdate()
```

que executa no banco:

```sql
SELECT ... FOR UPDATE
```

O bloqueio ocorre dentro da mesma transação utilizada para:

1. Consultar a carteira
2. Verificar o saldo
3. Validar o débito
4. Inserir a transação
5. Atualizar o saldo

Por exemplo, considerando duas requisições simultâneas:

```text
Requisição A ────────┐
                     │
                     ▼
                BLOQUEIO DA LINHA
                     │
                     ▼
                Verifica saldo
                     │
                     ▼
                Executa débito
                     │
                     ▼
                   COMMIT
                     │
                     ▼
Requisição B ────────┘
               obtém o bloqueio
                     │
                     ▼
                Lê novo saldo
```

A segunda requisição aguarda até que a primeira transação seja concluída.

Sem esse mecanismo, poderíamos ter uma condição de corrida como:

```text
Saldo inicial = 100

Requisição A lê 100
Requisição B lê 100

A: 100 - 80 = 20
B: 100 - 80 = 20

As duas operações são aprovadas ❌
```

O resultado seria incorreto, pois foram debitados `160.00` de uma carteira que possuía apenas `100.00`.

Com o bloqueio:

```text
Saldo inicial = 100

Requisição A lê 100
Requisição A → 100 - 80 = 20
Requisição A confirma a transação

Requisição B lê 20
Requisição B → saldo insuficiente

Resultado:

A → sucesso
B → rejeitada
```

Requisições para **carteiras diferentes** não ficam bloqueadas umas pelas outras, pois o bloqueio ocorre no nível da linha e não da tabela.

---

# 🔑 2. Idempotência

Toda requisição de transação exige:

```http
Idempotency-Key
```

A chave é associada à carteira:

```text
(wallet_id, idempotency_key)
```

A verificação da idempotência ocorre **depois da aquisição do bloqueio da linha** e dentro da mesma transação.

Isso evita uma condição de corrida em que duas requisições poderiam verificar simultaneamente se a chave já existe antes de qualquer uma delas realizar o `commit`.

### Mesma chave + mesmo payload

```text
Requisição 1 → CREDIT 100 → 201
Requisição 2 → CREDIT 100 → 200
Requisição 3 → CREDIT 100 → 200
```

Todas as requisições retornam a mesma transação.

O saldo é alterado apenas uma vez.

### Mesma chave + payload diferente

Por exemplo:

```text
Requisição 1:

Idempotency-Key = ABC

CREDIT 100
```

seguida de:

```text
Requisição 2:

Idempotency-Key = ABC

DEBIT 50
```

A segunda requisição é rejeitada:

```text
409 IDEMPOTENCY_KEY_CONFLICT
```

Isso evita que uma nova operação seja executada acidentalmente utilizando uma chave originalmente associada a outra operação.

---

# 🗄️ 3. Constraints no banco de dados

O banco de dados fornece uma camada adicional de proteção:

```sql
UNIQUE (wallet_id, idempotency_key)
```

Essa constraint impede que duas transações com a mesma chave sejam persistidas para a mesma carteira, mesmo que algum caminho futuro da aplicação eventualmente ignore a estratégia de bloqueio.

Também existe:

```sql
CHECK (balance >= 0)
```

garantindo que o saldo nunca seja negativo no nível do banco.

---

# 🧪 Estratégia de testes

O projeto possui testes **unitários e de integração**.

## Testes unitários

Utilizando:

* JUnit 5
* Mockito

São testados:

* Criação de carteira
* Consulta de carteira
* Operações de crédito
* Operações de débito
* Saldo insuficiente
* Saldo exatamente igual a zero
* Replay idempotente
* Conflito de idempotência
* Carteira inexistente

---

## Testes de integração

O teste:

```text
WalletTransactionIntegrationTest
```

utiliza:

* Contexto completo do Spring
* PostgreSQL real
* Testcontainers
* `TestRestTemplate`

Dessa forma, o fluxo completo é testado:

```text
Requisição HTTP
      ↓
Controller
      ↓
Service
      ↓
Transação
      ↓
PostgreSQL
```

São testados:

* Criação de carteira
* Paginação do extrato
* Erro 401
* Erro 404
* Erro 409
* Erro 422
* Requisições concorrentes de débito

---

# ⚡ Teste de concorrência

Um dos testes de integração realiza uma condição de corrida real utilizando múltiplas threads.

São enviadas:

```text
20 requisições simultâneas
DEBIT 10.00
```

para uma carteira contendo:

```text
Saldo = 100.00
```

Resultado esperado:

```text
10 requisições → 201 Created
10 requisições → 422 Insufficient Balance

Saldo final → 0.00

Transações:
1 CREDIT
10 DEBIT
-----------
11 transações
```

Esse cenário também foi validado manualmente contra a aplicação executando em Docker utilizando requisições `curl` realmente paralelas.

---

# 🔁 Teste de idempotência concorrente

Outro teste envia:

```text
15 requisições simultâneas
Mesma Idempotency-Key
Mesmo payload
```

Resultado:

```text
1 → 201 Created
14 → 200 OK

Todas → mesmo ID de transação

Saldo → alterado apenas uma vez
```

Esse teste demonstra que a idempotência continua funcionando corretamente mesmo quando múltiplas requisições chegam praticamente ao mesmo tempo.

---

# ❌ Tratamento de erros

A API utiliza um formato padronizado para os erros:

```json
{
  "timestamp": "2025-01-01T12:00:00Z",
  "status": 422,
  "error": "Unprocessable Entity",
  "code": "INSUFFICIENT_BALANCE",
  "message": "Wallet ... has insufficient balance: current=50.00, requested debit=999.00",
  "path": "/wallets/.../transactions",
  "details": []
}
```

| Situação                    | HTTP | Código                     |
| --------------------------- | ---: | -------------------------- |
| API Key inválida ou ausente |  401 | `UNAUTHORIZED`             |
| Carteira não encontrada     |  404 | `WALLET_NOT_FOUND`         |
| Requisição inválida         |  400 | `VALIDATION_ERROR`         |
| Idempotency-Key ausente     |  400 | `MISSING_HEADER`           |
| JSON malformado             |  400 | `MALFORMED_REQUEST`        |
| Conflito de idempotência    |  409 | `IDEMPOTENCY_KEY_CONFLICT` |
| Saldo insuficiente          |  422 | `INSUFFICIENT_BALANCE`     |
| Erro inesperado             |  500 | `INTERNAL_ERROR`           |

### Por que `422` para saldo insuficiente?

A requisição é válida do ponto de vista sintático:

```text
POST /wallets/{id}/transactions
DEBIT 999.00
```

O problema não é o formato da requisição e também não é uma operação duplicada.

O problema é uma **regra de negócio**: a carteira não possui saldo suficiente para realizar o débito.

Por isso foi utilizado:

```text
422 Unprocessable Entity
```

em vez de `400` ou `409`.

---

# 💵 Precisão monetária

Valores financeiros são representados utilizando:

```java
BigDecimal
```

e armazenados no PostgreSQL como:

```sql
NUMERIC(19,2)
```

Os valores recebidos são normalizados para duas casas decimais utilizando:

```text
HALF_UP
```

antes de serem persistidos ou comparados.

Essa abordagem evita problemas de precisão normalmente associados ao uso de `double` para valores monetários.

---

# 📄 Extrato

O extrato das transações suporta paginação através do `Spring Pageable`.

Exemplo:

```http
GET /wallets/{id}/transactions?page=0&size=20
```

As transações são retornadas da mais recente para a mais antiga:

```text
mais recente
     ↓
     ↓
     ↓
mais antiga
```

Também é possível utilizar filtros opcionais:

```text
from
to
```

utilizando instantes no formato ISO-8601.

Os filtros são implementados através de uma `JPA Specification`, permitindo construir os predicados somente quando os respectivos filtros são informados.

---

# 🗃️ Migrações do banco

A evolução do schema é controlada através do Flyway.

Migrações atuais:

```text
V1 → wallets
V2 → wallet_transactions
```

O Hibernate está configurado com:

```yaml
spring.jpa.hibernate.ddl-auto: validate
```

Portanto, o Hibernate apenas valida o schema existente.

Ele não cria nem altera tabelas automaticamente durante a execução da aplicação.

Isso mantém as alterações do banco explícitas, versionadas e reproduzíveis.

---

# 🚀 Executando a aplicação

## Opção A — Stack completa utilizando Docker

Recomendado:

```bash
docker compose up -d --build
```

Isso inicia:

```text
PostgreSQL
    +
Wallet API
```

A aplicação aguarda o PostgreSQL ficar saudável antes de iniciar.

As migrações do Flyway são executadas automaticamente durante o startup.

A API estará disponível em:

```text
http://localhost:8081
```

Para parar os containers:

```bash
docker compose down
```

Para parar os containers e remover também o volume do banco:

```bash
docker compose down -v
```

---

## Opção B — PostgreSQL no Docker + aplicação local

Inicie somente o banco:

```bash
docker compose up -d db
```

Execute a aplicação:

```bash
./mvnw spring-boot:run
```

A conexão padrão é:

```text
jdbc:postgresql://localhost:5432/wallet_api
```

As seguintes variáveis de ambiente podem ser utilizadas para sobrescrever a configuração:

```text
DB_URL
DB_USERNAME
DB_PASSWORD
API_KEY
```

---

# 📚 Documentação da API

Após iniciar a aplicação, a documentação Swagger estará disponível em:

```text
http://localhost:8081/swagger-ui.html
```

A especificação OpenAPI em formato JSON pode ser acessada em:

```text
http://localhost:8081/v3/api-docs
```

---

# 🧪 Executando os testes

Execute toda a suíte de testes:

```bash
./mvnw test
```

Os testes de integração utilizam Testcontainers e, portanto, é necessário possuir um Docker funcionando localmente.

---

# 📁 Estrutura do projeto

```text
src/main/java/com/walletapi/
│
├── wallet/
│   ├── Wallet entity
│   ├── Repository
│   ├── Service
│   ├── Controller
│   └── DTOs
│
├── transaction/
│   ├── WalletTransaction entity
│   ├── Repository
│   ├── Service
│   ├── Controller
│   └── DTOs
│
├── security/
│   └── Filtro de API Key
│
├── common/
│   ├── ApiError
│   └── GlobalExceptionHandler
│
└── config/
    └── Configuração do OpenAPI

src/main/resources/
│
├── application.yml
└── db/migration/
    ├── V1
    └── V2

src/test/java/com/walletapi/
│
├── wallet/
├── transaction/
└── integration/
```

---

# 🧠 Principais decisões técnicas

| Decisão                   | Motivo                                                         |
| ------------------------- | -------------------------------------------------------------- |
| PostgreSQL                | Consistência transacional e suporte a constraints              |
| Lock pessimista por linha | Evitar condições de corrida em carteiras com alta concorrência |
| Idempotency Key           | Permitir retries seguros e evitar operações duplicadas         |
| Constraint `UNIQUE`       | Garantia adicional de idempotência no banco                    |
| `CHECK (balance >= 0)`    | Proteção contra saldo negativo no banco                        |
| `BigDecimal`              | Precisão adequada para valores monetários                      |
| Flyway                    | Controle explícito e versionado do schema                      |
| JPA Specification         | Filtros dinâmicos no extrato                                   |
| Testcontainers            | Testes de integração utilizando PostgreSQL real                |
| API Key                   | Autenticação simples entre serviços para o contexto do desafio |

---

# 🚫 Fora do escopo

Os seguintes recursos foram intencionalmente deixados fora do desafio:

* Carteiras com múltiplas moedas
* Câmbio (FX)
* Cálculo de juros
* Autenticação de usuários com JWT/OAuth
* Transações agendadas
* Chargebacks
* Frontend/UI

---

# ✅ O que este desafio demonstra

Este projeto foi desenvolvido com foco nos problemas mais críticos de um sistema financeiro:

### Concorrência

Múltiplas requisições modificando a mesma carteira não podem gerar um saldo incorreto.

### Idempotência

Uma nova tentativa da mesma requisição não pode executar a transação novamente.

### Consistência

O saldo da carteira e o registro da transação precisam ser atualizados de forma atômica.

### Defesa em profundidade

A proteção implementada na aplicação é reforçada por constraints no banco de dados.

### Testabilidade

Os principais cenários de concorrência foram validados utilizando PostgreSQL real, Testcontainers e requisições concorrentes.

---

## 🏆 Resumo da solução

A estratégia central combina:

```text
┌─────────────────────────┐
│  Lock pessimista        │
│  SELECT FOR UPDATE      │
└────────────┬────────────┘
             │
             ▼
┌─────────────────────────┐
│  Idempotência           │
│  Idempotency-Key        │
└────────────┬────────────┘
             │
             ▼
┌─────────────────────────┐
│  Constraints do banco   │
│  UNIQUE + CHECK         │
└────────────┬────────────┘
             │
             ▼
┌─────────────────────────┐
│  Transação consistente  │
│  Saldo + operação       │
└─────────────────────────┘
```

Essa combinação garante que as principais regras da carteira sejam preservadas mesmo sob **alto nível de concorrência**, evitando saldo negativo, operações duplicadas e inconsistências entre o saldo e o extrato.
