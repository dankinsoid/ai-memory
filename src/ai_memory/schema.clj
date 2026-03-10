(ns ai-memory.schema
  "Shared schema constants used by all backends.
   Backend-specific schema formats live in their own db/* namespaces.")

(def aspect-tags
  "Tier 2 (aspect) tags — fixed vocabulary for knowledge categorization.
   Seeded at DB startup. Used for structured tag queries."
  ["architecture" "pattern" "idea" "decision" "preference"
   "debugging" "pitfall" "api" "data-model" "tooling"
   "workflow" "performance" "comparison" "testing" "insight"])
