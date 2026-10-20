# Financial Advisor AI

> A production-grade **Multimodal Financial RAG (Retrieval-Augmented Generation) System** built with **Spring Boot 3**, **Spring AI**, **PostgreSQL + PGVector**, **PyMuPDF**, **Google Gemini Vision**, **Cohere Neural Reranker**, and **Ollama (Qwen)**.

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen)
![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0-blue)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16%20%2B%20PGVector-blue)
![Ollama](https://img.shields.io/badge/Ollama-Qwen-black)
![Cohere](https://img.shields.io/badge/Cohere-Rerank%20v3.5-darkgreen)
![Gemini](https://img.shields.io/badge/Gemini-Vision%20Multimodal-blueviolet)

---

## Overview

Financial reports (10-Ks, asset management outlooks, economic research) contain dense tabular data, multi-panel visual charts, and complex financial terminology. Traditional RAG systems fail on financial documents because naive text splitters corrupt table columns, ignore charts, and suffer from vocabulary mismatches during vector search.

**Financial Advisor AI** solves this with an end-to-end multimodal pipeline:
1. **Multimodal Ingestion**: PyMuPDF extracts spatial geometry, tables, and vector graphics. Complex financial tables and charts are interpreted into structured JSON via **Gemini Vision**.
2. **Canonical Chunking**: Implements a **two-tier hierarchy** for tables and figures (table summaries for broad discovery + context-injected row chunks for numerical precision).
3. **Extraction Quality Gate**: Automated pre-embedding validation score (0–100) ensuring data completeness before vector storage.
4. **Multi-Channel Hybrid Retrieval**: Combines dense semantic vector search (Nomic-Embed-Text via pgvector) with lexical keyword search.
5. **Reciprocal Rank Fusion (RRF) & Neural Reranking**: Merges candidate lists using RRF ($k=60$) and reranks candidates using **Cohere Neural Reranker (`rerank-v3.5`)** with automatic fallback to metadata-based heuristics.
6. **Grounded Financial Synthesis**: Prompts local **Ollama (Qwen)** with strict temporal anchoring, column isolation, and `<scratchpad>` numerical verification.

---

## Architecture

```
                             +-----------------------+
                             |      Client / User    |
                             +-----------+-----------+
                                         |  GET /chat?prompt=...
                                         v
                             +-----------------------+
                             |     ChatController    |
                             +-----------+-----------+
                                         |
                                         v
                         +-------------------------------+
                         |     HybridRetrievalService    |
                         +---------------+---------------+
                                         |
                +------------------------+------------------------+
                |                                                 |
                v                                                 v
   +--------------------------+                      +--------------------------+
   |   Dense Vector Search    |                      |   Lexical Keyword Search |
   |  (PGVector / Nomic-Embed)|                      |      (PostgreSQL SQL)    |
   +------------+-------------+                      +------------+-------------+
                |                                                 |
                +------------------------+------------------------+
                                         |
                                         v
                         +-------------------------------+
                         |    ReciprocalRankFuser (RRF)  |
                         +---------------+---------------+
                                         |
                                         v
                         +-------------------------------+
                         |      CompositeReranker        |
                         |  (Primary: Cohere rerank-v3.5 |
                         |   Fallback: MetadataReranker) |
                         +---------------+---------------+
                                         | Top K Context Chunks
                                         v
                         +-------------------------------+
                         |     Ollama ChatClient (Qwen)  |
                         |  <scratchpad> + Grounded Math |
                         +---------------+---------------+
                                         |
                                         v
                             +-----------------------+
                             |    Grounded Answer    |
                             +-----------------------+
```

---

## Ingestion & Document Pipeline

```
  PDF Document
       │
       ▼
  PyMuPDF Python Extractor (Layout Analysis & Geometry)
       │
       ▼
  ContentClassifier (Layout Sorting)
   ├── Simple Tables   ──> CanonicalTableParser ──> CanonicalTableChunker (2-Tier Chunks)
   ├── Complex Tables  ──> Gemini Vision (Extract markdown & numerical data)
   ├── Visual Figures  ──> Gemini Vision (Multimodal interpretation & panel extraction)
   └── Narrative Text  ──> TextNormalizer (Reading-order sorting & section awareness)
       │
       ▼
  ExtractionQualityValidator (Quality Gate: Text completeness, table structure, metadata)
       │
       ▼
  Batched Vector Ingestion (PostgreSQL + PGVector)
```

---

## Key Features & Engineering Decisions

### 1. Two-Tier Canonical Table & Figure Chunking
* **Tier 1 (Summary Chunk)**: High-level overview of table/figure metadata (entities, metrics, periods) to match broad conversational queries.
* **Tier 2 (Row/Panel Chunks)**: Self-contained data rows with prepended table titles and column headers, preventing schema corruption and metric detachment.
* **Deduplication & Compaction**: Reduces chunk explosion on dense historical tables (e.g., from 4,700 naive chunks down to ~26 grouped chunks).

### 2. Multi-Channel Hybrid Retrieval & RRF
* Combines **Dense Vector Search** (capturing semantic synonyms like *"net profit"* $\approx$ *"earnings"*) with **Lexical Keyword Search** (capturing exact tickers and symbols like *"S&P 500"*, *"10-K"*).
* Fused via **Reciprocal Rank Fusion**:
  $$\text{RRF Score}(d) = \sum_{m \in M} \frac{1}{60 + \text{rank}_m(d)}$$

### 3. Cross-Encoder Neural Reranking (Cohere `rerank-v3.5`)
* Candidate documents from hybrid retrieval are scored jointly with the query through Cohere's state-of-the-art cross-encoder API.
* Implements the **Strategy & Composite Pattern** (`Reranker` interface) with automated fallback to `MetadataReranker` if API limits or network issues occur.

### 4. Grounded Synthesis with Scratchpad Reasoning
* System prompt enforces strict temporal anchoring (anchoring generic columns like `Current` or `YTD` to the active document date).
* Mandatory `<scratchpad>` block forces the model to extract and list every candidate value before declaring the highest or lowest performer, eliminating LLM comparison errors.

---

## Tech Stack

| Component | Technology | Description |
|-----------|------------|-------------|
| **Backend** | Java 21, Spring Boot 3.x | Core application framework |
| **AI Framework** | Spring AI 1.0.0 | LLM and vector store abstractions |
| **Vector Store** | PostgreSQL 16 + PGVector | High-performance vector database |
| **Embeddings** | Nomic-Embed-Text (Ollama) | 768-dimensional local embeddings |
| **Local LLM** | Qwen 2.5 (Ollama) | Local generation & financial reasoning |
| **Vision Model** | Google Gemini 2.5 Flash | Multimodal chart & complex table extraction |
| **Neural Reranker** | Cohere `rerank-v3.5` | Cross-encoder relevance scoring |
| **PDF Extraction** | Python 3 + PyMuPDF (`fitz`) | Coordinate-based visual layout parsing |
| **Orchestration** | Docker Compose | One-command local database setup |

---

## Getting Started

### Prerequisites

* **Java 21** or later
* **Maven** (or use `./mvnw`)
* **Docker Desktop** (for PostgreSQL + PGVector)
* **Ollama** installed locally
* **Python 3.10+** (with `pymupdf`)
* *(Optional)* Cohere API Key & Google Gemini API Key

### 1. Clone the Repository

```bash
git clone https://github.com/Abdulrahman-AlSayed-1/financial-advisor-ai.git
cd financial-advisor-ai
```

### 2. Start PostgreSQL + PGVector

Start the database using the provided Docker Compose file:

```bash
docker compose up -d
```

### 3. Start Ollama Models

Ensure Ollama is running and pull the required models:

```bash
ollama serve
ollama pull qwen:latest
ollama pull nomic-embed-text:latest
```

### 4. Configure Environment Variables (Optional)

Configure your API keys in `.env` or pass them as environment variables:

```bash
export COHERE_API_KEY="your-cohere-api-key"
export GEMINI_API_KEY="your-gemini-api-key"
```

### 5. Run the Application

```bash
./mvnw spring-boot:run
```

On first startup, `IngestionService` automatically parses and indexes `article_thebeatoct2024.pdf`. If the document is already indexed in PGVector, ingestion is safely skipped.

---

## Example Usage

### Chat Query

```bash
curl -G "http://localhost:8080/chat" \
  --data-urlencode "prompt=Which S&P 500 sectors delivered the highest YTD total return?"
```

### Response

```text
<scratchpad>
- S&P 500 Utilities: 30.63%
- S&P 500 Info Tech: 30.31%
- S&P 500 Real Estate: 14.31%
- S&P 500 Materials: 14.14%
- S&P 500 Industrials: 20.20%
- S&P 500 Energy: 8.36%
- S&P 500 Health Care: 14.35%
- S&P 500 Financials: 21.91%
- S&P 500 Cons Staples: 18.74%
- S&P 500 Comm. Services: 28.81%
</scratchpad>

The S&P 500 sector that delivered the highest YTD total return is S&P 500 Utilities with a return of 30.63%.
```

---

## Evaluation & Benchmarking

The project includes an automated evaluation suite to benchmark retrieval and generation fidelity:
* **Hit Rate @ K & MRR** across numerical, comparative, and qualitative financial queries.
* Endpoints for on-demand evaluation: `POST /evaluate` and `GET /evaluate/debug`.

---

## Author

**Abdulrahman Al-Sayed**
* GitHub: [Abdulrahman-AlSayed-1](https://github.com/Abdulrahman-AlSayed-1)
