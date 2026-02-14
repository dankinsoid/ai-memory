# ADR-008: Theoretical Foundations

**Status:** Reference
**Date:** 2026-02-14

## Context

Scientific areas and theories directly relevant to the memory system design. To be studied before finalizing architecture.

## Cognitive Psychology

**Spreading Activation Theory** (Collins & Loftus, 1975) — semantic memory as a graph where activation propagates through edges with attenuation. The basis for our retrieval model.

**Ebbinghaus Forgetting Curve** — exponential memory decay without reinforcement. Our lazy decay formula is essentially this.

**Spaced Repetition** (Piotr Wozniak / SuperMemo) — reinforcement at the right moment slows forgetting more effectively. Relevant for reinforcement strategy: not just "accessed = strengthened" but "accessed at the right moment = strengthened more".

## Cognitive Architectures (Most Relevant)

**ACT-R** (John Anderson, Carnegie Mellon) — cognitive architecture with a detailed memory model: activation-based retrieval, base-level decay, spreading activation, partial matching. Has formal math for activation computation, decay, context influence on retrieval. **Must-read before finalizing the model.**

**SOAR** (Laird, Newell, Rosenbloom) — cognitive architecture focused on long-term and working memory, learning through chunking.

## Neuroscience

**Hebbian Learning** — "neurons that fire together wire together". Nodes activated together strengthen their connection. Model for edge weight reinforcement.

**Long-term potentiation / depression (LTP/LTD)** — biological mechanism of synapse strengthening and weakening. Analogue of edge weights.

**Memory Consolidation** — transition from short-term to long-term memory. New nodes could start "fragile" (fast decay) and "consolidate" on repeated reinforcement (decay rate slows down).

## Graph Theory and Network Science

**Scale-free Networks** (Barabási–Albert) — real knowledge networks have power-law degree distribution: few hubs with many connections, many nodes with few. Hub nodes are a natural consequence of scale-free topology.

**Small-world Networks** (Watts–Strogatz) — short paths between any nodes via hubs. Explains why associative recall works in 2-3 hops.

## ML / AI (Future Learning Layer)

**MemGPT** (2023) — virtual memory management for LLMs with paging, similar to OS memory.

**Graph Neural Networks** — for automatic weight learning instead of formula-based decay.

**Knowledge Graph Embeddings** (TransE, RotatE) — methods for vectorizing graph structures. May be useful for vector search component.

## Recommended Reading Priority

1. **ACT-R** — ready-made math for activation, decay, retrieval
2. **Spreading Activation** (Collins & Loftus) — base theoretical framework
3. **MemGPT paper** — LLM + memory specifics
4. **Barabási on scale-free networks** — topology understanding
