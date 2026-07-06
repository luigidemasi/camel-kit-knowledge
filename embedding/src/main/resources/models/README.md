# ONNX Model Files

The model files are not stored in git (too large). They are downloaded automatically
by `maven-download-plugin` during the MCP module build, or manually:

```bash
curl -L -o model_quantized.onnx "https://huggingface.co/onnx-community/granite-embedding-small-english-r2-ONNX/resolve/main/onnx/model_quantized.onnx"
curl -L -o model_quantized.onnx_data "https://huggingface.co/onnx-community/granite-embedding-small-english-r2-ONNX/resolve/main/onnx/model_quantized.onnx_data"
```

- **Model:** ibm-granite/granite-embedding-small-english-r2 (ONNX Q8 quantized)
- **Dimensions:** 384
- **Max sequence length:** 8,192 tokens (capped at 2,048 in OnnxEmbeddingProvider; override with `-Dembedding.maxLength`)
- **Size:** ~52 MB (graph: 0.6 MB + weights: 49 MB)
- **Architecture:** ModernBERT (12 layers, 12 heads, 47M params)

## Reranker (cross-encoder)

Downloaded by the MCP module build as `reranker_quantized.onnx` + `reranker-tokenizer.json`:

```bash
curl -L -o reranker_quantized.onnx "https://huggingface.co/Xenova/ms-marco-MiniLM-L-6-v2/resolve/main/onnx/model_quantized.onnx"
curl -L -o reranker-tokenizer.json "https://huggingface.co/Xenova/ms-marco-MiniLM-L-6-v2/resolve/main/tokenizer.json"
```

- **Model:** cross-encoder/ms-marco-MiniLM-L-6-v2 (ONNX Q8 quantized)
- **Purpose:** reranks top hybrid-search candidates in `LuceneSearchService.search()` (see `OnnxReranker`)
- **Max sequence length:** 512 tokens (query + passage pair, longest-first truncation)
- **Size:** ~23 MB
