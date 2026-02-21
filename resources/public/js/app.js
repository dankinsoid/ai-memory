import { html, render } from 'htm/preact'
import { activeView, tags, stats } from './lib/store.js'
import { fetchTags, fetchStats } from './lib/api.js'
import { NavBar } from './components/nav-bar.js'
import { StatBar } from './components/stat-bar.js'
import { ExploreView } from './views/explore.js'
import { GraphView } from './views/graph.js'

// --- Init: load tags and stats ---

async function init() {
  try {
    const [tagsData, statsData] = await Promise.all([
      fetchTags(500),
      fetchStats()
    ])
    // Tags come as [{tag/name: "x", tag/node-count: N}, ...]
    tags.value = (tagsData || []).map(t => ({
      name: t['tag/name'] || t.name,
      count: t['tag/node-count'] || t.count || 0
    }))
    stats.value = {
      factCount: statsData['fact-count'] || 0,
      tagCount: statsData['tag-count'] || 0,
      edgeCount: statsData['edge-count'] || 0,
      tick: statsData.tick || 0
    }
  } catch (e) {
    console.error('Init failed:', e)
  }
}

// Refresh stats periodically
setInterval(async () => {
  try {
    const data = await fetchStats()
    stats.value = {
      factCount: data['fact-count'] || 0,
      tagCount: data['tag-count'] || 0,
      edgeCount: data['edge-count'] || 0,
      tick: data.tick || 0
    }
  } catch (e) { /* ignore */ }
}, 30000)

// --- App component ---

function App() {
  const view = activeView.value
  return html`
    <${NavBar} />
    <${StatBar} />
    ${view === 'explore' && html`<${ExploreView} />`}
    ${view === 'graph' && html`<${GraphView} />`}
  `
}

// --- Mount ---

init()
render(html`<${App} />`, document.getElementById('app'))
