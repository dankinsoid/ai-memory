// @ai-generated(solo)
// Obsidian plugin: renders ai-memory session transcripts as styled chat bubbles.
//
// READING MODE only. Obsidian renders markdown in sections — HTML <div> blocks
// and their content end up as separate DOM siblings. This plugin walks the full
// DOM via active-leaf-change + MutationObserver, collects content between
// chat-msg markers, and moves it inside the div for proper bubble styling.
//
// Edit mode (Live Preview) is left untouched — transcripts are read-only
// artifacts, so raw source view is fine there.

/* eslint-disable no-undef */
const { Plugin, MarkdownView } = require("obsidian");

/**
 * Format an ISO timestamp into a short human-readable form (HH:MM).
 *
 * @param {string} iso - ISO-8601 timestamp
 * @returns {string} formatted time or empty string
 */
function formatTimestamp(iso) {
	if (!iso) return "";
	try {
		const d = new Date(iso);
		return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
	} catch {
		return "";
	}
}

/** @param {HTMLElement} el */
function isChatBoundary(el) {
	if (!el || !el.classList) return false;
	return el.classList.contains("chat-msg") || el.classList.contains("chat-refs");
}

/**
 * Walk the .markdown-preview-sizer, find chat-msg/chat-refs divs,
 * and adopt sibling sections as children (Obsidian renders them as siblings).
 *
 * @param {HTMLElement} container - the .markdown-preview-sizer element
 */
function processContainer(container) {
	if (!container) return;

	const sections = Array.from(container.children);
	let i = 0;

	while (i < sections.length) {
		const section = sections[i];
		const chatDiv =
			section.querySelector(":scope > div.chat-msg") ||
			(section.classList.contains("chat-msg") ? section : null);
		const refsDiv =
			section.querySelector(":scope > div.chat-refs") ||
			(section.classList.contains("chat-refs") ? section : null);

		const targetDiv = chatDiv || refsDiv;
		if (!targetDiv) {
			i++;
			continue;
		}

		if (targetDiv.dataset.chatProcessed) {
			i++;
			continue;
		}
		targetDiv.dataset.chatProcessed = "true";

		// Collect subsequent sections until the next chat boundary
		const toAdopt = [];
		let j = i + 1;
		while (j < sections.length) {
			const nextSection = sections[j];
			const hasChat =
				nextSection.querySelector(
					":scope > div.chat-msg, :scope > div.chat-refs",
				) || isChatBoundary(nextSection);
			if (hasChat) break;
			toAdopt.push(nextSection);
			j++;
		}

		// Move content into the target div
		for (const adopted of toAdopt) {
			while (adopted.firstChild) {
				targetDiv.appendChild(adopted.firstChild);
			}
			adopted.remove();
		}

		if (chatDiv) {
			styleChatMessage(chatDiv);
		}

		i++;
		sections.length = 0;
		sections.push(...container.children);
	}
}

/**
 * Add role label, hide markdown label, set timestamp on a chat-msg div.
 *
 * @param {HTMLElement} msgDiv
 */
function styleChatMessage(msgDiv) {
	const role = msgDiv.dataset.role;
	if (!role) return;

	const label = document.createElement("span");
	label.className = "chat-role-label";
	label.textContent = role === "human" ? "You" : "Assistant";
	msgDiv.insertBefore(label, msgDiv.firstChild);

	const firstP = msgDiv.querySelector("p");
	if (firstP) {
		const text = firstP.textContent.trim().toLowerCase();
		if (text === "human:" || text === "assistant:") {
			firstP.classList.add("chat-original-label");
		}
	}

	const ts = msgDiv.dataset.ts;
	if (ts) {
		msgDiv.dataset.tsDisplay = formatTimestamp(ts);
	}
}

/**
 * Process reading view if active.
 *
 * @param {MarkdownView} view
 */
function processReadingView(view) {
	if (!view || view.getMode() !== "preview") return;
	const sizer = view.contentEl.querySelector(".markdown-preview-sizer");
	if (sizer) processContainer(sizer);
}

module.exports = class ChatViewPlugin extends Plugin {
	/** @type {MutationObserver|null} */
	_observer = null;

	onload() {
		this.registerEvent(
			this.app.workspace.on("active-leaf-change", () => {
				setTimeout(() => {
					const view = this.app.workspace.getActiveViewOfType(MarkdownView);
					if (view) this._observeReadingView(view);
				}, 100);
			}),
		);

		this.registerEvent(
			this.app.workspace.on("layout-change", () => {
				setTimeout(() => {
					const view = this.app.workspace.getActiveViewOfType(MarkdownView);
					if (view) this._observeReadingView(view);
				}, 100);
			}),
		);

		setTimeout(() => {
			const view = this.app.workspace.getActiveViewOfType(MarkdownView);
			if (view) this._observeReadingView(view);
		}, 500);
	}

	onunload() {
		if (this._observer) {
			this._observer.disconnect();
			this._observer = null;
		}
	}

	/**
	 * Set up MutationObserver for incremental reading-view renders.
	 *
	 * @param {MarkdownView} view
	 */
	_observeReadingView(view) {
		if (this._observer) this._observer.disconnect();

		processReadingView(view);

		if (view.getMode() !== "preview") return;

		const sizer = view.contentEl.querySelector(".markdown-preview-sizer");
		if (!sizer) return;

		this._observer = new MutationObserver(() => {
			clearTimeout(this._debounceTimer);
			this._debounceTimer = setTimeout(
				() => processContainer(sizer),
				50,
			);
		});

		this._observer.observe(sizer, { childList: true, subtree: false });
	}
};
