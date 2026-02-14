# ADR-004: Memory Decay Model

**Status:** Proposed
**Date:** 2026-02-14

## Context

Memory should weaken naturally if not reinforced — mimicking biological forgetting.

## Decision

- Time unit = **work cycle** (one logical tick of agent activity), not wall-clock time.
- Decay is **lazy** — computed on access, not as a background job:
  ```
  effective_weight = base_weight * decay_factor ^ (current_cycle - last_access_cycle)
  ```
- When a memory is accessed or reinforced, `last_access_cycle` resets and `base_weight` may increase.
- Memories below a threshold weight can be pruned or archived.

## Consequences

Lazy decay avoids expensive background recalculations. Temporal DB (Datomic/XTDB) preserves full history even after decay, allowing rollback and analysis.
