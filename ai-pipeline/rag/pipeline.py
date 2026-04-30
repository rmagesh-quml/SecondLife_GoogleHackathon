"""
ai-pipeline/rag/pipeline.py

FAISS-backed RAG pipeline for SecondLife.

Ingestion:
  1. Reads protocols.json to get the list of PDFs.
  2. Parses each PDF with PyPDF2, chunks into 512-token windows with 64-token overlap.
  3. Embeds chunks with fastembed (BAAI/bge-small-en-v1.5, 384-dim, CPU-only ONNX).
  4. Builds a FAISS IndexFlatIP index and saves it to ai-pipeline/rag/index/.

Retrieval:
  embed(query) → top-k cosine search → list[RagChunk]
"""

from __future__ import annotations

import json
import pickle
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import faiss
import numpy as np
from fastembed import TextEmbedding
from PyPDF2 import PdfReader

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
INDEX_DIR = Path(__file__).resolve().parent / "index"
PROTOCOLS_JSON = REPO_ROOT / "data" / "protocols" / "protocols.json"
PROTOCOLS_DIR = REPO_ROOT / "data" / "protocols"

CHUNK_TOKENS = 512
OVERLAP_TOKENS = 64
TOP_K = 3
EMBED_MODEL = "BAAI/bge-small-en-v1.5"


@dataclass
class RagChunk:
    text: str
    source: str   # filename, e.g. "sepsis_bundle.pdf"
    page: int
    chunk_id: int


class RagPipeline:
    def __init__(self) -> None:
        self._embedder = TextEmbedding(model_name=EMBED_MODEL)
        self._index: Optional[faiss.Index] = None
        self._chunks: list[RagChunk] = []
        self._load_index_if_exists()

    # ── Ingestion ──────────────────────────────────────────────────────────────

    def build_index(self) -> None:
        """Parse all PDFs listed in protocols.json and rebuild the FAISS index."""
        with open(PROTOCOLS_JSON) as f:
            meta = json.load(f)

        all_chunks: list[RagChunk] = []
        for doc in meta["documents"]:
            pdf_path = PROTOCOLS_DIR / doc["filename"]
            if not pdf_path.exists():
                print(f"[WARN] Missing PDF: {pdf_path.name} — skipping")
                continue
            chunks = self._chunk_pdf(pdf_path, doc["filename"])
            all_chunks.extend(chunks)
            print(f"[INFO] {doc['filename']}: {len(chunks)} chunks")

        if not all_chunks:
            raise RuntimeError("No chunks produced — check that PDFs exist in data/protocols/")

        texts = [c.text for c in all_chunks]
        embeddings = np.array(list(self._embedder.embed(texts)), dtype=np.float32)
        faiss.normalize_L2(embeddings)

        index = faiss.IndexFlatIP(embeddings.shape[1])
        index.add(embeddings)

        INDEX_DIR.mkdir(parents=True, exist_ok=True)
        faiss.write_index(index, str(INDEX_DIR / "protocols.faiss"))
        with open(INDEX_DIR / "chunks.pkl", "wb") as f:
            pickle.dump(all_chunks, f)

        self._index = index
        self._chunks = all_chunks
        print(f"[INFO] Index built: {len(all_chunks)} chunks, dim={embeddings.shape[1]}")

    def _chunk_pdf(self, path: Path, filename: str) -> list[RagChunk]:
        reader = PdfReader(str(path))
        chunks: list[RagChunk] = []
        chunk_id = 0

        for page_num, page in enumerate(reader.pages):
            text = (page.extract_text() or "").strip()
            if not text:
                continue
            words = text.split()
            step = CHUNK_TOKENS - OVERLAP_TOKENS
            for i in range(0, max(1, len(words) - OVERLAP_TOKENS), step):
                window = words[i : i + CHUNK_TOKENS]
                if len(window) < 20:
                    continue
                chunks.append(RagChunk(
                    text=" ".join(window),
                    source=filename,
                    page=page_num + 1,
                    chunk_id=chunk_id,
                ))
                chunk_id += 1

        return chunks

    # ── Retrieval ──────────────────────────────────────────────────────────────

    def retrieve(self, query: str, top_k: int = TOP_K) -> list[RagChunk]:
        """Return the top-k most relevant chunks for the given query."""
        if self._index is None or not self._chunks:
            return []

        q_emb = np.array(list(self._embedder.embed([query])), dtype=np.float32)
        faiss.normalize_L2(q_emb)
        _, indices = self._index.search(q_emb, top_k)

        return [self._chunks[i] for i in indices[0] if i < len(self._chunks)]

    def format_context(self, chunks: list[RagChunk]) -> tuple[str, str]:
        """
        Returns (context_text, citation_string) ready for the LLM prompt.
        citation_string example: "sepsis_bundle.pdf, p.3"
        """
        if not chunks:
            return "", ""
        context = "\n\n".join(
            f"[{c.source}, p.{c.page}]\n{c.text}" for c in chunks
        )
        citation = f"{chunks[0].source}, p.{chunks[0].page}"
        return context, citation

    # ── Persistence ───────────────────────────────────────────────────────────

    def _load_index_if_exists(self) -> None:
        index_path = INDEX_DIR / "protocols.faiss"
        chunks_path = INDEX_DIR / "chunks.pkl"
        if index_path.exists() and chunks_path.exists():
            self._index = faiss.read_index(str(index_path))
            with open(chunks_path, "rb") as f:
                self._chunks = pickle.load(f)
            print(f"[INFO] Loaded existing index: {len(self._chunks)} chunks")
