# @ai-generated(solo)
from __future__ import annotations
"""VectorStore ABC and shared data types.

SearchResult and VectorPoint mirror the Clojure schema types.
VectorStore mirrors the Clojure VectorStore protocol.
"""

import math
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any


@dataclass
class SearchResult:
    """A single result from VectorStore.search.

    Attributes:
        id:      point identifier (same value passed to upsert)
        score:   cosine similarity in [−1.0, 1.0]
        payload: arbitrary dict stored alongside the vector
    """
    id: str
    score: float
    payload: dict[str, Any] = field(default_factory=dict)


@dataclass
class VectorPoint:
    """A stored vector point returned by VectorStore.scroll_all.

    Attributes:
        id:      point identifier
        vector:  embedding vector
        payload: arbitrary dict stored alongside the vector
    """
    id: str
    vector: list[float]
    payload: dict[str, Any] = field(default_factory=dict)


class VectorStore(ABC):
    """Dense-vector similarity index.

    Mirrors the Clojure VectorStore protocol. All IDs are strings;
    implementations convert internally if the backend requires other types
    (e.g. Qdrant uses UUID strings).
    """

    @abstractmethod
    def ensure_collection(self, dim: int) -> None:
        """Initialize or verify the vector collection.

        Args:
            dim: embedding dimension (e.g. 1536 for text-embedding-3-small)
        """

    @abstractmethod
    def upsert(self, id: str, vector: list[float], payload: dict[str, Any]) -> None:
        """Insert or replace a vector point.

        Args:
            id:      unique point identifier (e.g. tag name or fact path)
            vector:  embedding vector, must match collection dim
            payload: arbitrary metadata stored with the point
        """

    @abstractmethod
    def search(
        self,
        query_vector: list[float],
        top_k: int = 10,
        threshold: float = 0.0,
    ) -> list[SearchResult]:
        """Find nearest neighbours by cosine similarity.

        Args:
            query_vector: query embedding, same dim as stored vectors
            top_k:        max results to return
            threshold:    minimum score to include (0.0 = no filter)

        Returns:
            List of SearchResult sorted by score descending.
        """

    @abstractmethod
    def delete(self, id: str) -> None:
        """Remove a vector point by id. No-op if not found.

        Args:
            id: point identifier passed to upsert
        """

    @abstractmethod
    def get_vectors(self, ids: list[str]) -> dict[str, list[float]]:
        """Return stored vectors for the given ids in a single batch operation.

        Ids not present in the store are simply absent from the result dict.
        Callers use this both to check existence and to retrieve vectors,
        avoiding two separate round-trips.

        Args:
            ids: point identifiers to look up

        Returns:
            Dict mapping found ids to their embedding vectors.
            Missing ids are not included (not mapped to None).
        """

    @abstractmethod
    def scroll_all(self) -> list[VectorPoint]:
        """Return all stored points.

        Returns:
            List of VectorPoint (id, vector, payload).
        """


def cosine(a: list[float], b: list[float]) -> float:
    """Cosine similarity between two equal-length vectors.

    Args:
        a, b: equal-length lists of floats

    Returns:
        Similarity in [−1.0, 1.0]; 0.0 if either vector has zero magnitude.
    """
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(x * x for x in b))
    if na == 0.0 or nb == 0.0:
        return 0.0
    return dot / (na * nb)
