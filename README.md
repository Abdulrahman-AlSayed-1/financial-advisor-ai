# Financial Advisor AI

> An AI-powered financial document assistant built with **Spring Boot**, **Spring AI**, **PostgreSQL**, **PGVector**, and **Ollama** using **Retrieval-Augmented Generation (RAG)**.

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen)
![Spring AI](https://img.shields.io/badge/Spring%20AI-2.x-blue)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-blue)
![PGVector](https://img.shields.io/badge/PGVector-Enabled-success)
![Ollama](https://img.shields.io/badge/Ollama-Local%20LLM-black)

---

# Overview

Financial Advisor AI is a Retrieval-Augmented Generation (RAG) application that enables users to interact with financial documents using natural language.

Instead of relying solely on an LLM's pre-trained knowledge, the application retrieves the most relevant document chunks from a PostgreSQL vector database using semantic search and provides context-aware, accurate responses using a locally hosted large language model.

This project demonstrates how modern AI applications combine **Large Language Models (LLMs)** with **Vector Databases** to build intelligent document assistants.

---

# Features

- 📄 PDF document ingestion
- ✂️ Automatic document chunking
- 🧠 Embedding generation
- 🗄️ PostgreSQL + PGVector integration
- 🔍 Semantic similarity search
- 🤖 Local LLM using Ollama
- 💬 Spring AI ChatClient
- 📚 Retrieval-Augmented Generation (RAG)
- ⚡ Fast vector retrieval

---

# Architecture

```text
                +------------------+
                |      User        |
                +--------+---------+
                         |
                         v
                +------------------+
                | Spring Boot API  |
                +--------+---------+
                         |
                         v
                +------------------+
                |    ChatClient    |
                +--------+---------+
                         |
            +------------+-------------+
            |                          |
            v                          v
 QuestionAnswerAdvisor          Ollama LLM
            |
            v
     PGVector Vector Store
            |
            v
    PostgreSQL Database
```

---

# RAG Workflow

```text
              PDF Document
                   │
                   ▼
          Text Extraction
                   │
                   ▼
         Document Chunking
                   │
                   ▼
      Generate Embeddings
                   │
                   ▼
      Store in PostgreSQL
             + PGVector
                   │
                   ▼

             User Question
                   │
                   ▼
      Generate Query Embedding
                   │
                   ▼
      Semantic Similarity Search
                   │
                   ▼
     Retrieve Relevant Chunks
                   │
                   ▼
      Context + User Question
                   │
                   ▼
             Ollama LLM
                   │
                   ▼
             AI Response
```

---

# Tech Stack

| Technology | Purpose |
|------------|---------|
| Java 21 | Programming Language |
| Spring Boot | Backend Framework |
| Spring AI | AI Integration |
| Ollama | Local Large Language Model |
| PostgreSQL | Relational Database |
| PGVector | Vector Database Extension |
| Maven | Build Tool |

---

# Project Structure

```text
src
├── main
│   ├── java
│   └── resources
│       ├── application.properties
│       └── documents
└── test
```

---

# Getting Started

## Prerequisites

- Java 21 or later
- Maven
- PostgreSQL
- PGVector extension
- Ollama

---

## Clone the Repository

```bash
git clone https://github.com/Abdulrahman-AlSayed-1/financial-advisor-ai.git

cd financial-advisor-ai
```

---

## Start PostgreSQL

Ensure PostgreSQL is running and the PGVector extension is installed.

---

## Start Ollama

```bash
ollama serve
```

Run your preferred model:

```bash
ollama run llama3
```

---

## Run the Application

```bash
./mvnw spring-boot:run
```

---

# Example Request

```http
GET /chat?prompt=What is the main topic of the document?
```

### Example Response

```text
The document discusses financial market outlooks,
Federal Reserve policy, interest rate expectations,
and investment strategies.
```

---

# Current Capabilities

- Upload and process financial documents
- Generate vector embeddings
- Store embeddings in PGVector
- Semantic document retrieval
- Context-aware AI responses
- Local LLM integration with Ollama

---

# Roadmap

- [ ] Multi-document support
- [ ] Source citations
- [ ] Chat memory
- [ ] Conversation history
- [ ] Streaming responses
- [ ] Hybrid search
- [ ] Metadata filtering
- [ ] Document management
- [ ] Authentication & Authorization
- [ ] React frontend
- [ ] Docker support
- [ ] Cloud deployment

---

# Author

**Abdulrahman Al-Sayed**

- GitHub: https://github.com/Abdulrahman-AlSayed-1
