import { signal, computed, batch } from '@preact/signals'

// --- Tags ---
export const tags = signal([])              // [{name, count}, ...]
export const selectedTags = signal([])      // ["clj", "pattern"]
export const tagSearch = signal('')         // filter tag list by name

// --- Facts ---
export const facts = signal([])             // current loaded facts
export const factsTotal = signal(0)         // total matching
export const factsLoading = signal(false)
export const factsOffset = signal(0)        // pagination offset
export const sortBy = signal('weight')      // "weight" | "date"

// --- Search ---
export const searchQuery = signal('')       // semantic search text

// --- Graph ---
export const graphNodes = signal([])
export const graphEdges = signal([])
export const graphLoading = signal(false)
export const graphFocusNode = signal(null)
export const graphLayoutRunning = signal(false)

// --- UI ---
export const activeView = signal('explore') // "explore" | "graph"
export const selectedFact = signal(null)    // fact object for detail panel
export const detailLoading = signal(false)
export const detailData = signal(null)      // full fact detail from API
export const sidebarOpen = signal(true)

// --- Stats ---
export const stats = signal({ factCount: 0, tagCount: 0, edgeCount: 0, tick: 0 })

// --- Derived ---
export const filteredTags = computed(() => {
  const q = tagSearch.value.toLowerCase()
  const t = tags.value
  if (!q) return t
  return t.filter(tag => tag.name.toLowerCase().includes(q))
})

export const hasMore = computed(() => {
  return facts.value.length < factsTotal.value
})

// --- Actions ---

export function toggleTag(tagName) {
  const sel = selectedTags.value
  if (sel.includes(tagName)) {
    selectedTags.value = sel.filter(t => t !== tagName)
  } else {
    selectedTags.value = [...sel, tagName]
  }
  // Reset pagination when filter changes
  factsOffset.value = 0
  facts.value = []
  factsTotal.value = 0
}

export function removeTag(tagName) {
  selectedTags.value = selectedTags.value.filter(t => t !== tagName)
  factsOffset.value = 0
  facts.value = []
  factsTotal.value = 0
}

export function clearFilters() {
  batch(() => {
    selectedTags.value = []
    searchQuery.value = ''
    factsOffset.value = 0
    facts.value = []
    factsTotal.value = 0
  })
}

export function setSort(value) {
  sortBy.value = value
  factsOffset.value = 0
  facts.value = []
  factsTotal.value = 0
}

export function selectFact(fact) {
  selectedFact.value = fact
}

export function closeFact() {
  selectedFact.value = null
  detailData.value = null
}

export function setView(view) {
  activeView.value = view
}
