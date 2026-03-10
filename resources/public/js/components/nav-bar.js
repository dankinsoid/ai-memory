import { html } from 'htm/preact'
import { useState, useRef } from 'preact/hooks'
import { activeView, setView, stats } from '../lib/store.js'
import { exportSnapshot, importSnapshot } from '../lib/api.js'
import { SearchBar } from './search-bar.js'

const BRAIN_ICON = html`<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2a6 6 0 0 0-6 6c0 1.5.5 2.9 1.4 4A6.002 6.002 0 0 0 12 22a6 6 0 0 0 4.6-9.8A6.01 6.01 0 0 0 18 8a6 6 0 0 0-6-6Z"/><path d="M12 2v20"/><path d="M6.8 12H12"/><path d="M12 12h5.2"/><path d="M8 8h2"/><path d="M14 8h2"/><path d="M8 16h2"/><path d="M14 16h2"/></svg>`

const DOWNLOAD_ICON = html`<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>`

const UPLOAD_ICON = html`<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>`

export function NavBar() {
  const view = activeView.value
  const [exporting, setExporting] = useState(false)
  const [importing, setImporting] = useState(false)
  const [showExportMenu, setShowExportMenu] = useState(false)
  const menuRef = useRef(null)

  async function handleExport(includeVectors) {
    setShowExportMenu(false)
    setExporting(true)
    try {
      const zipBlob = await exportSnapshot({ includeVectors })
      const url = URL.createObjectURL(zipBlob)
      const a = document.createElement('a')
      a.href = url
      const suffix = includeVectors ? '' : '-facts-only'
      a.download = `ai-memory-snapshot${suffix}-${new Date().toISOString().slice(0, 10)}.zip`
      a.click()
      URL.revokeObjectURL(url)
    } catch (e) {
      console.error('Export failed:', e)
      alert('Export failed: ' + e.message)
    } finally {
      setExporting(false)
    }
  }

  function handleImportClick() {
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = '.zip'
    input.onchange = async (e) => {
      const file = e.target.files[0]
      if (!file) return
      if (!window.confirm(`Import snapshot from "${file.name}"?\nThis will merge data into the current database.`)) return
      setImporting(true)
      try {
        const result = await importSnapshot(file)
        const blobs = result['imported-blobs'] || 0
        alert(`Import complete: ${result['imported-nodes']} nodes, ${result['imported-edges']} edges, ${result['imported-vectors']} vectors, ${blobs} blob files`)
        stats.value = { ...stats.value, tick: result['new-tick'] }
        window.location.reload()
      } catch (e) {
        console.error('Import failed:', e)
        alert('Import failed: ' + e.message)
      } finally {
        setImporting(false)
      }
    }
    input.click()
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
      <div class="navbar-actions">
        <div class="dropdown" ref=${menuRef}>
          <button class="btn btn-ghost" onClick=${() => setShowExportMenu(!showExportMenu)} disabled=${exporting}>
            ${DOWNLOAD_ICON} ${exporting ? 'Exporting...' : 'Export'}
          </button>
          ${showExportMenu && html`
            <div class="dropdown-menu" onClick=${(e) => e.stopPropagation()}>
              <button class="dropdown-item" onClick=${() => handleExport(true)}>
                Full snapshot (facts + vectors)
              </button>
              <button class="dropdown-item" onClick=${() => handleExport(false)}>
                Facts only (no vectors)
              </button>
            </div>
          `}
        </div>
        <button class="btn btn-ghost" onClick=${handleImportClick} disabled=${importing}>
          ${UPLOAD_ICON} ${importing ? 'Importing...' : 'Import'}
        </button>
      </div>
    </nav>
  `
}
