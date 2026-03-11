# @ai-generated(solo)
"""Vector store package.

Public API re-exported here so existing imports remain stable:
  from lib.vector_store import tag_store, VectorStore, get_vector_store, ...
"""

from .base import SearchResult, VectorPoint, VectorStore, cosine
from .factory import get_vector_store
from .json_store import JsonVectorStore
from .qdrant_store import QdrantVectorStore
from .tag_store import TagVectorStore, tag_store

__all__ = [
    "VectorStore",
    "SearchResult",
    "VectorPoint",
    "cosine",
    "JsonVectorStore",
    "QdrantVectorStore",
    "TagVectorStore",
    "tag_store",
    "get_vector_store",
]
