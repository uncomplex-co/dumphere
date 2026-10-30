// Emoji Mart owns search, categories and keyboard navigation. SamePage owns placement and reactions.
class ReactionPicker {
  constructor(state, trigger) {
    this.state = state;
    this.trigger = trigger;
    this.node = document.getElementById('reaction-picker');
    this.ids = this.node.dataset;
    this.node.addEventListener('toggle', event => {
      if (event.newState === 'closed' && this.state.view.reactionPicker != null) this.close();
    });
    this.node.addEventListener('keydown', event => {
      // Escape dismisses only the picker, never the discussion panel or a draft.
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        this.close(true);
      }
    }, true);
    this.observer = new ResizeObserver(() => this.position());
    this.observer.observe(this.node);
    window.addEventListener('resize', () => this.position());
    window.addEventListener('scroll', () => this.position(), true);
  }

  async load() {
    if (!this.loading) {
      this.loading = Promise.all([
        new Promise((resolve, reject) => {
          if (window.EmojiMart) return resolve();
          const script = document.createElement('script');
          script.src = '/viewer/vendor/emoji-mart-5.6.0.js';
          script.onload = resolve;
          script.onerror = () => { script.remove(); reject(new Error('Could not load emoji picker. Try again.')); };
          document.head.append(script);
        }),
        fetch('/viewer/vendor/emoji-mart-data.json').then(response => {
          if (!response.ok) throw new Error('Could not load emojis. Try again.');
          return response.json();
        })
      ]).then(([, data]) => data).catch(error => { this.loading = null; throw error; });
    }
    return this.loading;
  }

  close(focus = false) {
    const id = this.state.view.reactionPicker;
    this.state.change({ reactionPicker: null });
    if (focus) this.trigger(id)?.focus({ preventScroll: true });
  }

  choose(emoji) {
    const id = this.state.view.reactionPicker;
    if (id != null) {
      this.state.react(id, emoji);
      this.trigger(id)?.closest('.comment').focus({ preventScroll: true });
    }
  }

  async render() {
    const id = this.state.view.reactionPicker;
    if (id == null) {
      this.commentId = null;
      this.controlObserver?.disconnect();
      if (this.node.matches(':popover-open')) this.node.hidePopover();
      return;
    }
    if (this.commentId === id) return;
    this.commentId = id;
    const loading = document.createElement('p');
    loading.setAttribute('role', 'status');
    loading.textContent = 'Loading emojis...';
    this.node.replaceChildren(loading);
    if (!this.node.matches(':popover-open')) this.node.showPopover();
    this.position();
    try {
      const data = await this.load();
      if (this.state.view.reactionPicker !== id) return;
      const picker = new EmojiMart.Picker({
        data, set: 'native', theme: 'light', emojiVersion: 15,
        skin: 1, skinTonePosition: 'none', previewPosition: 'none',
        autoFocus: true, dynamicWidth: true, maxFrequentRows: 0,
        onEmojiSelect: emoji => this.choose(emoji.native)
      });
      // Shadow-root styles stay external and self-hosted, matching the viewer's CSP.
      const overrides = document.createElement('link');
      overrides.rel = 'stylesheet';
      overrides.href = '/viewer/reaction-picker.css';
      picker.shadowRoot.append(overrides);
      const tagControls = () => {
        const search = picker.shadowRoot.querySelector('input');
        if (search) search.dataset.testid = this.ids.searchTestid;
        for (const control of picker.shadowRoot.querySelectorAll('button')) {
          control.dataset.testid = control.closest('#nav') ? this.ids.categoryTestid
            : control.getAttribute('aria-label') === 'Clear' ? this.ids.clearTestid : this.ids.emojiTestid;
        }
      };
      this.controlObserver?.disconnect();
      this.controlObserver = new MutationObserver(tagControls);
      this.controlObserver.observe(picker.shadowRoot, { childList: true, subtree: true });
      tagControls();
      const quick = document.createElement('div');
      quick.className = 'reaction-quick';
      quick.setAttribute('aria-label', 'Quick reactions');
      for (const emoji of ['👍', '👏', '🙂', '😀', '😂', '😣', '😡', '🎉', '➕']) {
        const control = document.createElement('button');
        const name = Object.values(data.emojis).find(item => item.skins[0].native === emoji)?.name || emoji;
        control.type = 'button';
        control.dataset.testid = this.ids.quickTestid;
        control.dataset.emoji = emoji;
        control.textContent = emoji;
        control.title = control.ariaLabel = name;
        control.addEventListener('click', () => this.choose(emoji));
        quick.append(control);
      }
      this.node.replaceChildren(picker, quick);
      this.position();
    } catch (error) {
      if (this.state.view.reactionPicker !== id) return;
      this.close(true);
      this.state.change({ error: error.message });
    }
  }

  position() {
    if (!this.node.matches(':popover-open')) return;
    const anchor = this.trigger(this.state.view.reactionPicker)?.getBoundingClientRect();
    if (!anchor) return;
    const width = this.node.offsetWidth;
    const height = this.node.offsetHeight;
    const below = anchor.bottom + 6;
    this.node.style.left = Math.max(8, Math.min(anchor.right - width, innerWidth - width - 8)) + 'px';
    this.node.style.top = Math.max(8, Math.min(below + height <= innerHeight - 8 ? below : anchor.top - height - 6, innerHeight - height - 8)) + 'px';
  }
}
