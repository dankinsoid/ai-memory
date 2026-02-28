import { html } from 'htm/preact'
import { useEffect, useState } from 'preact/hooks'
import { selectedFact, closeFact, detailData, detailLoading, activeView, graphFocusNode, removeFact } from '../lib/store.js'
import { fetchFactDetail, deleteFact, updateFact } from '../lib/api.js'
import { weightColor, timeAgo, tagColor, tagBg } from '../lib/utils.js'

const CLOSE_ICON = html`<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>`

async function loadDetail(id) {
  detailLoading.value = true
  try {
    const data = await fetchFactDetail(id)
    detailData.value = data
  } catch (e) {
    console.error('Failed to load fact detail:', e)
  } finally {
    detailLoading.value = false
  }
}

export function FactDetail() {
  const fact = selectedFact.value
  const isOpen = fact != null
  const detail = detailData.value
  const loading = detailLoading.value
  const [deleting, setDeleting] = useState(false)
  const [editing, setEditing] = useState(false)
  const [saving, setSaving] = useState(false)
  const [editContent, setEditContent] = useState('')
  const [editTags, setEditTags] = useState([])
  const [editWeight, setEditWeight] = useState(0)
  const [newTagInput, setNewTagInput] = useState('')

  // Load detail when fact selected
  useEffect(() => {
    if (fact) {
      const id = fact['db/id'] || fact.id
      if (id) loadDetail(id)
      setEditing(false)
    }
  }, [fact])

  // Close on Escape
  useEffect(() => {
    const handler = (e) => {
      if (e.key === 'Escape' && isOpen) {
        if (editing) setEditing(false)
        else closeFact()
      }
    }
    document.addEventListener('keydown', handler)
    return () => document.removeEventListener('keydown', handler)
  }, [isOpen, editing])

  function showInGraph() {
    const id = fact['db/id'] || fact.id
    graphFocusNode.value = id
    activeView.value = 'graph'
    closeFact()
  }

  async function handleDelete() {
    const id = fact['db/id'] || fact.id
    if (!window.confirm(`Delete fact #${id}? This cannot be undone.`)) return
    setDeleting(true)
    try {
      await deleteFact(id)
      removeFact(id)
      closeFact()
    } catch (e) {
      console.error('Failed to delete fact:', e)
      alert('Failed to delete fact: ' + e.message)
    } finally {
      setDeleting(false)
    }
  }

  function enterEditMode() {
    setEditContent(content)
    setEditTags([...tagNames])
    setEditWeight(fact['node/weight'] ?? fact.weight ?? 0)
    setNewTagInput('')
    setEditing(true)
  }

  function cancelEdit() {
    setEditing(false)
  }

  async function handleSave() {
    const id = fact['db/id'] || fact.id
    setSaving(true)
    try {
      await updateFact(id, {
        content: editContent,
        tags: editTags,
        weight: editWeight
      })
      await loadDetail(id)
      setEditing(false)
    } catch (e) {
      console.error('Failed to update fact:', e)
      alert('Failed to update fact: ' + e.message)
    } finally {
      setSaving(false)
    }
  }

  function addTag() {
    const name = newTagInput.trim().toLowerCase()
    if (name && !editTags.includes(name)) {
      setEditTags([...editTags, name])
    }
    setNewTagInput('')
  }

  function removeEditTag(name) {
    setEditTags(editTags.filter(t => t !== name))
  }

  function handleTagKeyDown(e) {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault()
      addTag()
    }
  }

  const content = fact?.['node/content'] || fact?.content || ''
  const tags = fact?.['node/tag-refs'] || fact?.tags || []
  const tagNames = tags.map(t => typeof t === 'string' ? t : (t['tag/name'] || t.name || ''))

  return html`
    <div class="detail-overlay ${isOpen ? 'open' : ''}" onClick=${() => { if (!editing) closeFact() }} />
    <div class="detail-panel ${isOpen ? 'open' : ''}">
      <div class="detail-header">
        <h3>${editing ? 'Edit Fact' : 'Fact Detail'}</h3>
        <button class="detail-close" onClick=${() => { if (editing) cancelEdit(); else closeFact() }}>${CLOSE_ICON}</button>
      </div>
      <div class="detail-body">
        ${loading && html`<div style="display: flex; justify-content: center; padding: 32px;"><div class="spinner" /></div>`}
        ${!loading && fact && !editing && html`
          <div class="detail-section">
            <div class="detail-label">Content</div>
            <div class="detail-content">${content}</div>
          </div>

          ${tagNames.length > 0 && html`
            <div class="detail-section">
              <div class="detail-label">Tags</div>
              <div class="detail-tags">
                ${tagNames.map(t => html`
                  <span key=${t} class="fact-card-tag" style="color: ${tagColor(t)}; background: ${tagBg(t)}">${t}</span>
                `)}
              </div>
            </div>
          `}

          <div class="detail-section">
            <div class="detail-label">Metadata</div>
            <div class="detail-meta-grid">
              <span class="detail-meta-key">ID</span>
              <span class="detail-meta-val">${fact['db/id'] || fact.id}</span>
              ${detail?.['effective-weight'] != null && html`
                <span class="detail-meta-key">Effective weight</span>
                <span class="detail-meta-val" style="color: ${weightColor(detail['effective-weight'])}">
                  ${detail['effective-weight'].toFixed(3)}
                </span>
              `}
              ${(fact['node/weight'] || fact.weight) != null && html`
                <span class="detail-meta-key">Base weight</span>
                <span class="detail-meta-val">${(fact['node/weight'] || fact.weight).toFixed(2)}</span>
              `}
              ${detail?.['created-at'] && html`
                <span class="detail-meta-key">Created</span>
                <span class="detail-meta-val">${timeAgo(detail['created-at'])} — ${new Date(detail['created-at']).toLocaleString()}</span>
              `}
              ${detail?.['updated-at'] && html`
                <span class="detail-meta-key">Updated</span>
                <span class="detail-meta-val">${timeAgo(detail['updated-at'])} — ${new Date(detail['updated-at']).toLocaleString()}</span>
              `}
              ${detail?.['blob-dir'] && html`
                <span class="detail-meta-key">Blob</span>
                <span class="detail-meta-val">${detail['blob-dir']}</span>
              `}
              ${detail?.['session-id'] && html`
                <span class="detail-meta-key">Session</span>
                <span class="detail-meta-val">${detail['session-id']}</span>
              `}
            </div>
          </div>

          ${detail?.edges?.length > 0 && html`
            <div class="detail-section">
              <div class="detail-label">Edges (${detail.edges.length})</div>
              <div class="detail-edges">
                ${detail.edges.slice(0, 20).map((e, i) => html`
                  <div key=${i} class="detail-edge">
                    <span style="flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">
                      → #${e.target}
                    </span>
                    <span class="detail-edge-weight">w:${e.weight?.toFixed(2)}</span>
                  </div>
                `)}
                ${detail.edges.length > 20 && html`
                  <div style="font-size: 11px; color: var(--text-muted); padding: 4px 12px;">
                    +${detail.edges.length - 20} more
                  </div>
                `}
              </div>
            </div>
          `}

          <div class="detail-section detail-actions">
            <button class="btn" onClick=${showInGraph}>Show in Graph</button>
            <button class="btn" onClick=${enterEditMode}>Edit</button>
            <button class="btn btn-danger" onClick=${handleDelete} disabled=${deleting}>
              ${deleting ? 'Deleting…' : 'Delete'}
            </button>
          </div>
        `}

        ${!loading && fact && editing && html`
          <div class="detail-section">
            <div class="detail-label">Content</div>
            <textarea
              class="edit-textarea"
              value=${editContent}
              onInput=${e => setEditContent(e.target.value)}
              rows="6"
            />
          </div>

          <div class="detail-section">
            <div class="detail-label">Tags</div>
            <div class="edit-tag-list">
              ${editTags.map(t => html`
                <span key=${t} class="edit-tag-pill" style="color: ${tagColor(t)}; background: ${tagBg(t)}">
                  ${t}
                  <button class="edit-tag-remove" onClick=${() => removeEditTag(t)}>×</button>
                </span>
              `)}
              <input
                class="edit-tag-input"
                type="text"
                placeholder="add tag…"
                value=${newTagInput}
                onInput=${e => setNewTagInput(e.target.value)}
                onKeyDown=${handleTagKeyDown}
                onBlur=${addTag}
              />
            </div>
          </div>

          <div class="detail-section">
            <div class="detail-label">Base weight <span style="color: var(--text-muted); font-weight: normal;">(0 – 1, where 1 = eternal)</span></div>
            <input
              class="edit-weight-input"
              type="number"
              min="0"
              max="1"
              step="0.01"
              value=${editWeight}
              onInput=${e => setEditWeight(parseFloat(e.target.value) || 0)}
            />
          </div>

          <div class="detail-section detail-actions">
            <button class="btn btn-primary" onClick=${handleSave} disabled=${saving}>
              ${saving ? 'Saving…' : 'Save'}
            </button>
            <button class="btn" onClick=${cancelEdit} disabled=${saving}>Cancel</button>
          </div>
        `}
      </div>
    </div>
  `
}
