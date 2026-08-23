# Financial Advisor AI - Evaluation Architecture

## Overview

This document describes the evaluation architecture of the Financial Advisor AI project, showing how chunking evaluation and LLM answer evaluation work from controller to response.

## Project Structure

```mermaid
flowchart TB
    %% Main Application Structure
    subgraph "src/main/java/com/alsayed/financial_advisor_ai"
        %% Controllers
        subgraph "controller"
            EvaluationController
            ChatController
            EvaluationController
        end
        
        %% Services
        subgraph "service"
            IngestionService
        end
        
        %% Evaluation Modules
        subgraph "evaluation"
            %% Core Components
            EvaluationService
            EvaluationDatasetLoader
            EvaluationOrchestrator
            EvaluationRunner
            
            %% Configuration
            subgraph "config"
                EvaluationConfig
            end
            
            %% DTOs
            subgraph "dto"
                EvaluationQuestion
                EvaluationResponse
                EvaluationResult
            end
            
            %% Evaluators
            subgraph "evaluators"
                CustomEvaluators
            end
            
            %% RAG Components
            subgraph "rag"
                RagSystemClient
            end
            
            %% Reporting
            subgraph "reporting"
                ReportGenerator
            end
        end
        
        %% Chunking Evaluation
        subgraph "chunkingEval"
            ChunkingBenchmarkService
            ChunkingStrategy
            FixedSizeChunker
            StructureAwareChunker
            SemanticChunker
            TokenEstimator
            GoldQuestion
            ChunkingBenchmarkResult
        end
    end
    
    %% External Dependencies
    subgraph "external"
        Ollama
        PostgreSQL
        Spring AI RAGAS Metrics
    end
    
    %% Controller Connections
    EvaluationController --> EvaluationService
    EvaluationController --> ChunkingBenchmarkService
    EvaluationController --> IngestionService
    
    %% Service Connections
    EvaluationService --> EvaluationDatasetLoader
    EvaluationService --> RagSystemClient
    EvaluationService --> CustomEvaluators
    EvaluationService --> ReportGenerator
    
    %% Chunking Service Connections
    ChunkingBenchmarkService --> ChunkingStrategy
    ChunkingBenchmarkService --> TokenEstimator
    ChunkingBenchmarkService --> SimpleVectorStore
    
    %% External Connections
    IngestionService --> Ollama
    RagSystemClient --> Ollama
    CustomEvaluators --> Ollama
    ChunkingBenchmarkService --> Ollama
    
    %% Database Connections
    EvaluationService --> PostgreSQL
    IngestionService --> PostgreSQL
    ChunkingBenchmarkService --> PostgreSQL
```

## Evaluation Flow

### 1. LLM Answer Evaluation Flow

```mermaid
flowchart TD
    %% Entry Point
    A[POST /evaluation/chat] --> B[EvaluationController]
    
    %% Controller Processing
    B --> C[EvaluationService.askRagSystem()]
    
    %% RAG System Call
    C --> D[RagSystemClient.queryRagSystem()]
    
    %% RAG Components
    D --> E[ChatClient with QuestionAnswerAdvisor]
    E --> F[VectorStore (PostgreSQL)]
    F --> G[Ollama LLM]
    
    %% Response Processing
    G --> H[Extract Answer + Contexts]
    H --> I[EvaluationResponse]
    
    %% Evaluation Metrics
    I --> J[RelevancyEvaluator]
    I --> K[FactCheckingEvaluator]
    I --> L[CustomEvaluators (Faithfulness, ContextRecall)]
    
    J --> M[Standard Spring AI Evaluation]
    K --> M
    L --> N[LLM-based Custom Evaluation]
    
    M --> O[EvaluationResult]
    N --> O
    
    O --> P[ReportGenerator]
    P --> Q[JSON/CSV/Summary Reports]
    
    %% Quick Run Path
    A -.-> R[POST /evaluation/quick-run]
    R --> S[EvaluationService.runEvaluations(metrics, limit)]
    S --> T[Process N questions with M metrics]
    
    %% Full Run Path
    A -.-> U[POST /evaluation/run]
    U --> V[EvaluationService.runEvaluations(ALL_METRICS, MAX)]
    V --> T
```

### 2. Chunking Evaluation Flow

```mermaid
flowchart TD
    %% Entry Point
    A[GET /evaluation/chunking-benchmark/run] --> B[EvaluationController]
    
    %% Controller Processing
    B --> C[ChunkingBenchmarkService.runSweep()]
    
    %% Benchmark Configuration
    C --> D[Load Source Text]
    C --> E[Load Gold Questions]
    C --> F[Configure Strategies]
    C --> G[Configure Token Sizes]
    C --> H[Configure Overlap Sizes]
    
    %% Strategy Processing
    D --> I[ChunkingStrategy Implementation]
    I --> J[FixedSizeChunker]
    I --> K[StructureAwareChunker]
    I --> L[SemanticChunker]
    
    %% Chunking Process
    J --> M[Fixed-size sliding window]
    K --> N[Structure-aware chunking]
    L --> O[Semanic boundary detection]
    
    %% Vector Store
    M --> P[SimpleVectorStore]
    N --> P
    O --> P
    
    P --> Q[Embedding Generation]
    Q --> R[Index Creation]
    
    %% Evaluation
    R --> S[Similarity Search]
    S --> T[Top-K Retrieval]
    T --> U[Score Calculation]
    
    U --> V[Recall@K Calculation]
    U --> W[MRR Calculation]
    
    V --> X[ChunkingBenchmarkResult]
    W --> X
    
    X --> Y[Write CSV Report]
    Y --> Z[Return Results]
```

## Key Components

### EvaluationController
- **Purpose**: REST API endpoints for evaluation
- **Endpoints**:
  - `POST /evaluation/chat` - Single question evaluation
  - `POST /evaluation/run` - Full evaluation pipeline
  - `POST /evaluation/quick-run` - Quick iteration evaluation
  - `GET /evaluation/chunking-benchmark/run` - Chunking strategy benchmark

### EvaluationService
- **Purpose**: Core evaluation orchestration
- **Responsibilities**:
  - Coordinates RAG system calls
  - Manages evaluation metrics
  - Generates evaluation reports
  - Handles timeouts and retries

### RagSystemClient
- **Purpose**: Client for RAG system interaction
- **Responsibilities**:
  - Queries the RAG system
  - Extracts answers and contexts
  - Handles vector store retrieval

### CustomEvaluators
- **Purpose**: Custom LLM-based evaluation metrics
- **Metrics**:
  - Faithfulness evaluation
  - Context recall evaluation

### ChunkingBenchmarkService
- **Purpose**: Benchmarks chunking strategies
- **Responsibilities**:
  - Runs strategy sweep
  - Calculates performance metrics
  - Generates CSV reports

### Chunking Strategies
- **FixedSizeChunker**: Naive fixed-size sliding window
- **StructureAwareChunker**: Respects document structure
- **SemanticChunker**: Uses embeddings for semantic boundaries

## Libraries and Dependencies

### Spring AI Components
- `ChatClient` - LLM interaction
- `QuestionAnswerAdvisor` - RAG integration
- `RelevancyEvaluator` - Standard relevancy evaluation
- `FactCheckingEvaluator` - Standard fact-checking evaluation
- `PgVectorStore` - PostgreSQL vector store

### Third-party Libraries
- `spring-ai-ragas-metrics` - RAGAS evaluation metrics
- `ai-qa-solutions` - Multi-model executor

### External Services
- **Ollama** - Local LLM and embedding models
- **PostgreSQL** - Vector database

## Configuration

### EvaluationConfig
```java
public class EvaluationConfig {
    public static final String RELEVANCY = "relevancy";
    public static final String FACT_CHECKING = "factChecking";
    public static final String FAITHFULNESS = "faithfulness";
    public static final String CONTEXT_RECALL = "contextRecall";
    
    public static final Set<String> ALL_METRICS = Set.of(RELEVANCY, FACT_CHECKING, FAITHFULNESS, CONTEXT_RECALL);
    public static final Set<String> QUICK_METRICS = Set.of(RELEVANCY);
}
```

### Spring AI Integration
The project uses Spring AI's official evaluation testing framework:
- `Evaluator` interface for evaluation
- `EvaluationRequest` and `EvaluationResponse` classes
- `RelevancyEvaluator` and `FactCheckingEvaluator` implementations

## Data Flow

### LLM Answer Evaluation
1. User submits question via REST API
2. RAG system retrieves contexts and generates answer
3. Standard evaluators (relevancy, fact-checking) run
4. Custom evaluators (faithfulness, context recall) run
5. Results stored and reports generated

### Chunking Evaluation
1. Benchmark endpoint triggered
2. Source text loaded from market PDF
3. Gold questions loaded from dataset
4. All strategy/size/overlap combinations tested
5. Performance metrics calculated
6. CSV report generated

## Performance Considerations

### LLM Answer Evaluation
- **Sequential processing**: One question at a time
- **Timeout handling**: 90-second timeout per metric
- **Retry logic**: Up to 3 attempts per metric
- **Thread pool**: 4 threads for metric execution

### Chunking Evaluation
- **Zero LLM calls**: Only uses embeddings
- **Fast iteration**: Under 1 minute for full sweep
- **In-memory vector store**: No database writes
- **Parallel processing**: All combinations tested

## Testing and Validation

### Integration Tests
- Spring AI evaluation testing framework
- RAG flow validation
- Evaluator integration tests

### Evaluation Reports
- **JSON detail report**: Complete evaluation results
- **CSV report**: Tabular format for analysis
- **Summary report**: Aggregated metrics

## Future Enhancements

1. **Additional evaluators**: Add more Spring AI evaluators
2. **Parallel processing**: Process multiple questions concurrently
3. **Custom metrics**: Add domain-specific evaluation metrics
4. **Real-time monitoring**: Add evaluation monitoring and alerting
5. **A/B testing**: Compare different RAG configurations

## Conclusion

The evaluation architecture provides a comprehensive framework for:
- Evaluating LLM answer quality using both standard and custom metrics
- Benchmarking document chunking strategies
- Generating detailed reports for analysis
- Supporting fast iteration during development
- Producing final reports for production validation

The system is designed for performance, scalability, and ease of use, leveraging Spring AI's evaluation testing framework while maintaining flexibility for custom evaluation needs.