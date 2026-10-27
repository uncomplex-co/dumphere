class CommentComposer extends HTMLElement {
  connectedCallback() {
    if (this.form) return;
    this.innerHTML = `
      <form class="comment-form">
        <label><span class="composer-label"></span>
          <textarea rows="4" maxlength="10000" required placeholder="Write a comment..."></textarea>
        </label>
        <p class="error" role="alert"></p>
        <div class="comment-form-actions">
          <button class="button" type="button">Cancel</button>
          <button class="button primary" type="submit">Post comment</button>
        </div>
      </form>`;
    this.form = this.querySelector('form');
    this.input = this.querySelector('textarea');
    this.error = this.querySelector('[role="alert"]');
    this.cancelButton = this.querySelector('[type="button"]');
    this.postButton = this.querySelector('[type="submit"]');
    this.querySelector('.composer-label').textContent = this.getAttribute('label') || 'New comment';
    this.input.placeholder = this.getAttribute('placeholder') || 'Write a comment...';
    this.postLabel = this.getAttribute('submit-label') || 'Post comment';
    this.postButton.textContent = this.postLabel;
    if (this.hasAttribute('compact')) this.input.rows = 1;
    this.input.dataset.testid = this.dataset.bodyTestid;
    this.cancelButton.dataset.testid = this.dataset.cancelTestid;
    this.postButton.dataset.testid = this.dataset.postTestid;
    this.setSubmitting(false);

    this.input.addEventListener('input', () => {
      this.postButton.disabled = this.submitting || !this.input.value.trim();
      this.resizeInput();
    });

    this.input.addEventListener('keydown', event => {
      if (event.key === 'Enter' && event.ctrlKey && !event.isComposing) {
        event.preventDefault();
        if (!this.submitting && this.input.value.trim()) this.form.requestSubmit();
      }
    });

    this.cancelButton.addEventListener('click', () => {
      if (this.submitting) return;
      this.complete();
      this.dispatchEvent(new CustomEvent('comment-cancel', { bubbles: true }));
    });
    this.form.addEventListener('submit', event => {
      event.preventDefault();
      if (this.submitting) return;
      const body = this.input.value.trim();
      if (!body) {
        this.fail('Write a comment before posting.');
        this.input.focus();
        return;
      }
      this.error.textContent = '';
      this.setSubmitting(true);
      this.dispatchEvent(new CustomEvent('comment-submit', { bubbles: true, detail: { body } }));
    });
  }

  show() {
    this.hidden = false;
    this.resizeInput();
    this.input.focus();
  }

  complete() {
    this.form.reset();
    this.error.textContent = '';
    this.setSubmitting(false);
    this.hidden = true;
    this.input.style.height = '';
  }

  fail(message) {
    this.error.textContent = message;
    this.setSubmitting(false);
  }

  setSubmitting(submitting) {
    this.submitting = submitting;
    this.postButton.disabled = submitting || !this.input.value.trim();
    this.cancelButton.disabled = submitting;
    this.input.readOnly = submitting;
    this.postButton.textContent = submitting ? 'Posting...' : this.postLabel;
    this.dispatchEvent(new Event('composer-statechange', { bubbles: true }));
  }

  resizeInput() {
    if (!this.hasAttribute('compact')) return;
    this.input.style.height = 'auto';
    this.input.style.height = Math.min(180, this.input.scrollHeight + 2) + 'px';
  }
}

customElements.define('comment-composer', CommentComposer);
