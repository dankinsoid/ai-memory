const BASE = '/api'
const TOKEN = new URLSearchParams(window.location.search).get('token')

function authHeaders() {
  const h = {}
  if (TOKEN) h['Authorization'] = `Bearer ${TOKEN}`
  return h
}

async function get(path) {
  const res = await fetch(BASE + path, { headers: authHeaders() })
  if (!res.ok) throw new Error(`GET ${path}: ${res.status}`)
  return res.json()
}

async function post(path, body) {
  const res = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify(body)
  })
  if (!res.ok) throw new Error(`POST ${path}: ${res.status}`)
  return res.json()
}

async function del(path) {
  const res = await fetch(BASE + path, { method: 'DELETE', headers: authHeaders() })
  if (!res.ok) throw new Error(`DELETE ${path}: ${res.status}`)
  return res.json()
}

// --- Stats ---

export async function fetchStats() {
  return get('/stats')
}

// --- Tags ---

export async function fetchTags(limit = 500, offset = 0) {
  return get(`/tags?limit=${limit}&offset=${offset}`)
}

// --- Facts ---

export async function fetchFacts({ tags = [], query = '', sortBy = 'weight', limit = 50, offset = 0 } = {}) {
  const filter = { limit, offset, sort_by: sortBy }
  if (tags.length > 0) filter.tags = tags
  if (query) filter.query = query
  return post('/tags/facts', { filters: [filter] })
}

// --- Fact detail ---

export async function fetchFactDetail(id) {
  return get(`/facts/${id}`)
}

// --- Graph ---

export async function fetchTopNodes(limit = 30, tag = null) {
  let url = `/graph/top-nodes?limit=${limit}`
  if (tag) url += `&tag=${encodeURIComponent(tag)}`
  return get(url)
}

export async function fetchNeighborhood(nodeId, depth = 1, limit = 50) {
  return get(`/graph/neighborhood?node_id=${nodeId}&depth=${depth}&limit=${limit}`)
}

// --- Tag counts ---

export async function fetchTagCounts(tagSets) {
  return post('/tags/count', { tag_sets: tagSets })
}

// --- Admin ---

export async function deleteFact(id) {
  return del(`/facts/${id}`)
}

export async function resetDb() {
  return post('/admin/reset', {})
}
