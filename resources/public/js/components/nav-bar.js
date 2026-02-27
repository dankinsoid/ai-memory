import { html } from 'htm/preact'
import { useState } from 'preact/hooks'
import { activeView, setView, facts, factsTotal, stats } from '../lib/store.js'
import { resetDb } from '../lib/api.js'
import { SearchBar } from './search-bar.js'

const BRAIN_ICON = html`<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2a6 6 0 0 0-6 6c0 1.5.5 2.9 1.4 4A6.002 6.002 0 0 0 12 22a6 6 0 0 0 4.6-9.8A6.01 6.01 0 0 0 18 8a6 6 0 0 0-6-6Z"/><path d="M12 2v20"/><path d="M6.8 12H12"/><path d="M12 12h5.2"/><path d="M8 8h2"/><path d="M14 8h2"/><path d="M8 16h2"/><path d="M14 16h2"/></svg>`

export function NavBar() {
  const view = activeView.value
  const [resetting, setResetting] = useState(false)

  async function handleReset() {
    if (!window.confirm('Reset entire database? All facts, edges, and blobs will be permanently deleted.')) return
    setResetting(true)
    try {
      await resetDb()
      facts.value = []
      factsTotal.value = 0
      stats.value = { ...stats.value, factCount: 0, edgeCount: 0 }
    } catch (e) {
      console.error('Failed to reset DB:', e)
      alert('Failed to reset: ' + e.message)
    } finally {
      setResetting(false)
    }
  }

  return html`
    <nav class="navbar">
      <div class="navbar-brand">
        ${BRAIN_ICON}
        <span>ai-memory</span>
      </div>
      <div class="navbar-tabs">
        <button class="navbar-tab ${view === 'explore' ? 'active' : ''}"
                onClick=${() => setView('explore')}>Explore</button>
        <button class="navbar-tab ${view === 'graph' ? 'active' : ''}"
                onClick=${() => setView('graph')}>Graph</button>
      </div>
      <div class="navbar-search">
        <${SearchBar} />
      </div>
      <button class="btn btn-danger navbar-reset" onClick=${handleReset} disabled=${resetting}>
        ${resetting ? 'Resetting…' : 'Reset DB'}
      </button>
    </nav>
  `
}
