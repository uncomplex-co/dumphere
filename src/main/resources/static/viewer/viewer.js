(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const initial = document.body.dataset;
  const id = initial.id;
  let visibility = initial.visibility;
  let publicUntil = initial.publicUntil || null;
  let canManage = initial.canManage === 'true';
  let canView = initial.canView === 'true';
  let version = Number(initial.version);
  let expiryTimer;
  let toastTimer;
  let saving = false;
  let revision = 0;
  let commentVersion;
  let commentsRequest = 0;
  let selectedThreadId = null;
  let discussionEmpty = false;
  let artifactsRequest = 0;
  let attachedXpath = null;
  let attachedRelativeX;
  let attachedRelativeY;
  let attachedVersion;
  const interactions = new ArtifactInteractions($('artifact-frame'), $('artifact-annotations'));
  const daysLeft = () => Math.max(0, Math.ceil((Date.parse(publicUntil) - Date.now()) / 86400000));
  const dateText = () => new Intl.DateTimeFormat(undefined, { dateStyle: 'long', timeStyle: 'short' }).format(new Date(publicUntil));
  const currentUrl = () => location.href;

  function notify(message) {
    clearTimeout(toastTimer);
    $('toast').textContent = message;
    $('toast').hidden = false;
    toastTimer = setTimeout(() => { $('toast').hidden = true; }, 4000);
  }

  function render() {
    const isPublic = visibility === 'public';
    $('visibility-label').textContent = isPublic ? 'Anyone with the link' : 'Internal';
    $('visibility-icon').setAttribute('href', isPublic ? '#icon-globe' : '#icon-lock');
    $('visibility').classList.toggle('is-public', isPublic);
    $('remaining').textContent = isPublic && canManage ? '· ' + daysLeft() + ' days left' : '';
    $('share').hidden = !canManage;
    $('artifacts-toggle').disabled = !canManage;
    if (!canManage) {
      closeArtifacts(false);
      $('artifact-library-list').replaceChildren();
    }
    $('menu-share').disabled = !canManage;
    $('menu-reload').disabled = !canView;
    $('avatar').hidden = !canManage;
    $('download').hidden = !canView;
    $('comments-toggle').hidden = !canView;
    $('content').hidden = !canView;
    $('blocked').hidden = canView;
    if (!canView) {
      $('artifact-frame').src = 'about:blank';
      closeComments(false);
      $('comments-list').replaceChildren();
    }
    if (!canManage && $('share-dialog').open) $('share-dialog').close();
    if ($('share-dialog').open) selectionChanged();
    clearTimeout(expiryTimer);
    if (isPublic && publicUntil) {
      // Recheck long deadlines in bounded intervals; setTimeout is limited to 2^31-1 ms.
      const delay = Date.parse(publicUntil) - Date.now();
      expiryTimer = setTimeout(() => {
        if (Date.parse(publicUntil) <= Date.now()) {
          visibility = 'internal';
          publicUntil = null;
          canView = canManage;
        }
        render();
      }, Math.max(0, Math.min(delay, 60000)));
    }
  }

  const titleMenu = $('artifact-menu');
  const titleTrigger = $('title-menu-trigger');
  window.addEventListener('blur', () => {
    // Iframe interactions do not bubble to the viewer's popover light-dismiss handler.
    setTimeout(() => {
      if (document.activeElement === $('artifact-frame')) {
        document.querySelectorAll('.artifact-menu:popover-open').forEach(menu => menu.hidePopover());
      }
    }, 0);
  });
  let focusLastMenuItem = false;
  titleMenu.addEventListener('beforetoggle', event => {
    if (event.newState !== 'open') return;
    const bounds = titleTrigger.getBoundingClientRect();
    titleMenu.style.left = Math.max(8, Math.min(bounds.left, innerWidth - 188)) + 'px';
    titleMenu.style.top = bounds.bottom + 6 + 'px';
  });
  titleMenu.addEventListener('toggle', event => {
    const open = event.newState === 'open';
    titleTrigger.setAttribute('aria-expanded', String(open));
    if (open) {
      const items = titleMenu.querySelectorAll('[role="menuitem"]:not(:disabled)');
      (focusLastMenuItem ? items[items.length - 1] : items[0])?.focus();
      focusLastMenuItem = false;
    }
  });
  titleTrigger.addEventListener('keydown', event => {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
    event.preventDefault();
    focusLastMenuItem = event.key === 'ArrowUp';
    titleMenu.showPopover();
  });
  titleMenu.addEventListener('keydown', event => {
    const items = Array.from(titleMenu.querySelectorAll('[role="menuitem"]:not(:disabled)'));
    const index = items.indexOf(document.activeElement);
    let next;
    if (event.key === 'ArrowDown') next = (index + 1) % items.length;
    else if (event.key === 'ArrowUp') next = (index - 1 + items.length) % items.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = items.length - 1;
    else if (event.key === 'Tab') titleMenu.hidePopover();
    if (next !== undefined) {
      event.preventDefault();
      items[next]?.focus();
    }
  });
  $('menu-share').addEventListener('click', () => {
    titleMenu.hidePopover();
    $('share').click();
  });
  $('menu-reload').addEventListener('click', () => location.reload());

  function closeArtifacts(restoreFocus = true) {
    artifactsRequest += 1;
    $('artifact-library').hidden = true;
    $('artifacts-toggle').setAttribute('aria-expanded', 'false');
    document.body.classList.remove('artifacts-open');
    if (restoreFocus) $('artifacts-toggle').focus();
  }

  function artifactLink(artifact) {
    const item = document.createElement('li');
    const link = document.createElement('a');
    link.href = '/p/' + encodeURIComponent(artifact.id);
    link.dataset.testid = $('artifact-library').dataset.linkTestid;
    link.dataset.artifactId = artifact.id;
    const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    icon.setAttribute('aria-hidden', 'true');
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    use.setAttribute('href', artifact.format === 'markdown' ? '#icon-markdown' : '#icon-html');
    icon.append(use);
    const meta = document.createElement('div');
    meta.className = 'artifact-library-meta';
    const name = document.createElement('span');
    name.className = 'artifact-library-name';
    name.textContent = artifact.title;
    const date = document.createElement('time');
    date.dateTime = artifact.createdAt;
    date.textContent = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(new Date(artifact.createdAt));
    meta.append(name, date);
    link.append(icon, meta);
    item.append(link);
    return item;
  }

  async function loadArtifacts() {
    const request = ++artifactsRequest;
    $('artifacts-status').textContent = 'Loading artifacts...';
    $('artifacts-retry').hidden = true;
    try {
      const response = await fetch('/account/artifacts?excludeId=' + encodeURIComponent(id), {
        credentials: 'same-origin', cache: 'no-store'
      });
      if (!response.ok) throw new Error([401, 403].includes(response.status)
        ? 'Sign in again to view your artifacts.' : 'Could not load your artifacts. Try again.');
      const artifacts = await response.json();
      if (request !== artifactsRequest || !canManage) return;
      $('artifact-library-list').replaceChildren(...artifacts.map(artifactLink));
      $('artifacts-status').textContent = artifacts.length ? '' : 'No other artifacts yet.';
    } catch (error) {
      if (request !== artifactsRequest) return;
      $('artifacts-status').textContent = error.message;
      $('artifacts-retry').hidden = false;
    }
  }

  $('artifacts-toggle').addEventListener('click', () => {
    if (!canManage) return;
    if (!$('artifact-library').hidden) return closeArtifacts();
    $('artifact-library').hidden = false;
    $('artifacts-toggle').setAttribute('aria-expanded', 'true');
    document.body.classList.add('artifacts-open');
    $('artifacts-close').focus();
    loadArtifacts();
  });
  $('artifacts-close').addEventListener('click', () => closeArtifacts());
  $('artifacts-retry').addEventListener('click', loadArtifacts);
  $('artifact-library').addEventListener('keydown', event => {
    if (event.key === 'Escape') {
      event.preventDefault();
      closeArtifacts();
    }
  });

  function closeComments(restoreFocus = true) {
    commentsRequest += 1;
    $('comments-panel').hidden = true;
    $('comments-toggle').setAttribute('aria-expanded', 'false');
    document.body.classList.remove('comments-open');
    interactions.setActive(false);
    setElementSelection(false);
    $('attached-composer').hidden = true;
    if (!$('attached-comment-composer').submitting) $('attached-comment-composer').complete();
    if (restoreFocus) $('comments-toggle').focus();
  }

  function commentElement(comment, firstInThread) {
    const item = document.createElement('li');
    item.className = 'comment';
    item.dataset.commentId = comment.id;
    item.dataset.testid = initial.commentTestid;
    item.dataset.threadId = comment.threadId;
    const meta = document.createElement('div');
    meta.className = 'comment-meta';
    const author = document.createElement('span');
    author.className = 'comment-author';
    author.textContent = comment.author;
    const authorGroup = document.createElement('div');
    authorGroup.className = 'comment-author-group';
    const threadNumber = interactions.threadNumber(comment.threadId);
    if (firstInThread && threadNumber !== undefined) {
      const badge = document.createElement('span');
      badge.className = 'comment-thread-number';
      badge.textContent = threadNumber;
      badge.setAttribute('aria-label', 'Thread ' + threadNumber);
      authorGroup.append(badge);
    }
    authorGroup.append(author);
    const time = document.createElement('time');
    time.dateTime = comment.createdAt;
    time.textContent = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(comment.createdAt));
    const body = document.createElement('p');
    body.className = 'comment-body';
    body.textContent = comment.body;
    meta.append(authorGroup, time);
    item.append(meta, body);
    const edited = document.createElement('span');
    edited.className = 'comment-edited';
    edited.textContent = 'Edited';
    const showEdited = value => {
      edited.hidden = !value;
      edited.title = value ? 'Edited ' + new Date(value).toLocaleString() : '';
    };
    showEdited(comment.editedAt);
    const trailing = document.createElement('div');
    trailing.className = 'comment-meta-trailing';
    trailing.append(time, edited);
    meta.append(trailing);
    if (comment.canEdit) addCommentActions(item, trailing, comment, body, showEdited, firstInThread);
    if (comment.xpath) item.dataset.xpath = comment.xpath;
    item.tabIndex = 0;
    item.setAttribute('role', 'button');
    item.addEventListener('click', event => {
      if (event.target.closest('button, comment-composer, [role="menu"]')) return;
      selectThread(comment.threadId);
      if (comment.xpath) interactions.scrollTo(comment.xpath);
    });
    item.addEventListener('keydown', event => {
      if (event.target !== item) return;
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        item.click();
      }
    });
    return item;
  }

  function addCommentActions(item, trailing, comment, body, showEdited, firstInThread) {
    const trigger = document.createElement('button');
    trigger.type = 'button';
    trigger.className = 'icon-button comment-menu-trigger';
    trigger.dataset.testid = initial.commentMenuTestid;
    trigger.setAttribute('aria-label', 'Comment options');
    trigger.setAttribute('aria-haspopup', 'menu');
    trigger.setAttribute('aria-expanded', 'false');
    trigger.innerHTML = '<svg aria-hidden="true"><use href="#icon-more"/></svg>';
    const menu = document.createElement('div');
    menu.id = 'comment-menu-' + comment.id;
    menu.className = 'artifact-menu comment-menu';
    menu.popover = 'auto';
    menu.setAttribute('role', 'menu');
    menu.setAttribute('aria-label', 'Comment options');
    trigger.setAttribute('popovertarget', menu.id);
    trigger.setAttribute('aria-controls', menu.id);
    const edit = document.createElement('button');
    edit.type = 'button';
    edit.setAttribute('role', 'menuitem');
    edit.dataset.testid = initial.commentEditTestid;
    edit.innerHTML = '<svg aria-hidden="true"><use href="#icon-edit"/></svg><span>Edit</span>';
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.setAttribute('role', 'menuitem');
    remove.dataset.testid = $('comment-delete-dialog').dataset.actionTestid;
    remove.innerHTML = '<svg aria-hidden="true"><use href="#icon-delete"/></svg><span>Delete</span>';
    remove.addEventListener('click', () => {
      menu.hidePopover();
      confirmCommentDelete(comment, item, firstInThread, trigger);
    });
    menu.append(edit, remove);
    menu.addEventListener('beforetoggle', event => {
      if (event.newState !== 'open') return;
      const bounds = trigger.getBoundingClientRect();
      menu.style.left = Math.max(8, Math.min(bounds.right - 140, innerWidth - 148)) + 'px';
      menu.style.top = Math.max(8, Math.min(bounds.bottom + 4, innerHeight - 100)) + 'px';
    });
    menu.addEventListener('toggle', event => {
      const open = event.newState === 'open';
      trigger.setAttribute('aria-expanded', String(open));
      if (open) edit.focus();
    });
    trigger.addEventListener('keydown', event => {
      if (!['ArrowDown', 'ArrowUp'].includes(event.key)) return;
      event.preventDefault();
      menu.showPopover();
    });
    menu.addEventListener('keydown', event => {
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        menu.hidePopover();
        trigger.focus();
      } else if (event.key === 'Tab') menu.hidePopover();
      else if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
        event.preventDefault();
        const items = [edit, remove];
        const index = items.indexOf(document.activeElement);
        const next = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1
          : (index + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length;
        items[next].focus();
      }
    });
    const composer = document.createElement('comment-composer');
    composer.hidden = true;
    composer.className = 'comment-edit-composer';
    composer.setAttribute('label', 'Edit comment');
    composer.setAttribute('submit-label', 'Save');
    composer.setAttribute('pending-label', 'Saving...');
    composer.dataset.bodyTestid = initial.commentEditBodyTestid;
    composer.dataset.postTestid = initial.commentEditSaveTestid;
    composer.dataset.cancelTestid = initial.commentEditCancelTestid;
    edit.addEventListener('click', () => {
      menu.hidePopover();
      body.hidden = true;
      trigger.disabled = true;
      composer.input.value = comment.body;
      composer.setSubmitting(false);
      composer.show();
    });
    const finish = () => {
      composer.complete();
      body.hidden = false;
      trigger.disabled = false;
      trigger.focus();
    };
    composer.addEventListener('comment-cancel', finish);
    composer.addEventListener('keydown', event => {
      if (event.key === 'Escape') {
        event.stopPropagation();
        if (!composer.submitting) finish();
      }
    });
    composer.addEventListener('comment-submit', async event => {
      if (!canView) return composer.fail('Your access may have changed. Reload the page before editing.');
      try {
        const response = await fetch('/p/' + id + '/comments/' + comment.id, {
          method: 'PATCH', credentials: 'same-origin',
          headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': initial.csrf },
          body: JSON.stringify({ body: event.detail.body })
        });
        if (!response.ok) throw new Error([401, 403].includes(response.status)
          ? 'Your access may have changed. Reload the page before editing.' : 'Could not save your comment. Try again.');
        const updated = await response.json();
        comment.body = updated.body;
        body.textContent = updated.body;
        showEdited(updated.editedAt);
        finish();
      } catch (error) {
        composer.fail(error.message === 'Failed to fetch'
          ? 'Could not save your comment. Check your connection and try again.' : error.message);
      }
    });
    trailing.append(trigger);
    item.append(menu, composer);
  }

  const deleteDialog = $('comment-delete-dialog');
  let pendingDelete;
  let deletingComment = false;

  function confirmCommentDelete(comment, item, root, trigger) {
    pendingDelete = { comment, root, trigger };
    const bounds = item.closest('.comment-thread').getBoundingClientRect();
    const height = Math.min(Math.max(bounds.height, 200), innerHeight - 16);
    Object.assign(deleteDialog.style, {
      left: Math.max(8, bounds.left) + 'px',
      top: Math.max(8, Math.min(bounds.top, innerHeight - height - 8)) + 'px',
      width: Math.min(bounds.width, innerWidth - 16) + 'px', height: height + 'px'
    });
    $('comment-delete-title').textContent = root ? 'Delete this comment thread?' : 'Delete this comment?';
    $('comment-delete-detail').textContent = root ? 'All replies will also be deleted.' : '';
    $('comment-delete-error').textContent = '';
    deleteDialog.showModal();
  }

  deleteDialog.addEventListener('cancel', event => {
    if (deletingComment) event.preventDefault();
  });
  deleteDialog.addEventListener('close', () => {
    pendingDelete?.trigger?.focus();
    pendingDelete = null;
  });
  $('comment-delete-cancel').addEventListener('click', () => deleteDialog.close());
  $('comment-delete-confirm').addEventListener('click', async () => {
    if (deletingComment || !pendingDelete) return;
    const { comment, root } = pendingDelete;
    deletingComment = true;
    $('comment-delete-confirm').disabled = true;
    $('comment-delete-cancel').disabled = true;
    $('comment-delete-confirm').textContent = 'Deleting...';
    $('comment-delete-error').textContent = '';
    try {
      const response = await fetch('/p/' + id + '/comments/' + comment.id, {
        method: 'DELETE', credentials: 'same-origin', headers: { 'X-CSRF-TOKEN': initial.csrf }
      });
      if (!response.ok) throw new Error([401, 403].includes(response.status)
        ? 'Your access may have changed. Reload the page before deleting.' : 'Could not delete the comment. Try again.');
      if (root && selectedThreadId === comment.threadId) selectedThreadId = null;
      deleteDialog.close();
      await loadComments();
      $('comment-add').focus();
    } catch (error) {
      $('comment-delete-error').textContent = error.message === 'Failed to fetch'
        ? 'Could not delete the comment. Check your connection and try again.' : error.message;
    } finally {
      deletingComment = false;
      $('comment-delete-confirm').disabled = false;
      $('comment-delete-cancel').disabled = false;
      $('comment-delete-confirm').textContent = 'Delete';
    }
  });

  function selectThread(threadId) {
    selectedThreadId = threadId;
    for (const item of $('comments-list').querySelectorAll('.comment')) {
      item.classList.toggle('is-selected', item.dataset.threadId === String(threadId));
    }
    for (const thread of $('comments-list').children) {
      const selected = thread.dataset.threadId === String(threadId);
      thread.classList.toggle('is-selected', selected);
      thread.querySelector('.thread-reply').hidden = !selected;
    }
  }

  function updateAttachmentState() {
    for (const item of $('comments-list').querySelectorAll('.comment')) {
      if (!item.dataset.xpath) continue;
      const found = interactions.isResolved(item.dataset.xpath);
      item.classList.toggle('is-attached', found);
    }
  }

  interactions.addEventListener('layout', updateAttachmentState);
  interactions.addEventListener('thread-select', event => {
    selectThread(event.detail.threadId);
    const item = Array.from($('comments-list').querySelectorAll('.comment')).find(item => item.dataset.threadId === String(event.detail.threadId));
    item?.scrollIntoView({ block: 'nearest' });
    item?.focus({ preventScroll: true });
  });
  interactions.addEventListener('element-select', event => {
    const composer = $('attached-comment-composer');
    if (composer.submitting) return;
    attachedXpath = event.detail.xpath;
    attachedRelativeX = event.detail.relativeX;
    attachedRelativeY = event.detail.relativeY;
    attachedVersion = version;
    $('attached-composer').hidden = false;
    composer.show();
    interactions.scheduleLayout();
  });
  function setElementSelection(selecting) {
    interactions.setSelecting(selecting);
    $('element-selection').setAttribute('aria-pressed', String(interactions.selecting));
    $('element-selection').title = interactions.selecting
      ? 'Stop selecting elements (Escape)' : 'Select elements for comments';
  }

  function escapeComments() {
    if (interactions.selecting) setElementSelection(false);
    else closeComments();
  }

  $('element-selection').addEventListener('click', () => setElementSelection(!interactions.selecting));
  interactions.addEventListener('escape', escapeComments);
  interactions.addEventListener('attachment-lost', () => {
    if ($('attached-composer').hidden) return;
    $('attached-composer').hidden = true;
    notify('The selected element is no longer present. Select an element again.');
  });

  function renderThreads(comments) {
    const threads = new Map();
    for (const comment of comments) {
      if (!threads.has(comment.threadId)) {
        const thread = document.createElement('li');
        thread.className = 'comment-thread';
        thread.dataset.threadId = comment.threadId;
        thread.dataset.testid = initial.threadTestid;
        const messages = document.createElement('ol');
        messages.className = 'thread-comments';
        messages.setAttribute('aria-label', 'Thread comments, oldest first');
        thread.append(messages);
        threads.set(comment.threadId, thread);
      }
      const messages = threads.get(comment.threadId).firstElementChild;
      messages.append(commentElement(comment, messages.children.length === 0));
    }
    for (const [threadId, thread] of threads) {
      thread.append(replyControls(threadId));
    }
    $('comments-list').replaceChildren(...threads.values());
    selectThread(selectedThreadId);
  }

  function replyControls(threadId) {
    const footer = document.createElement('div');
    footer.className = 'thread-reply';
    footer.hidden = true;
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'thread-reply-button';
    button.dataset.testid = initial.replyTestid;
    button.textContent = 'Reply...';
    const composer = document.createElement('comment-composer');
    composer.hidden = true;
    composer.setAttribute('compact', '');
    composer.setAttribute('label', 'Reply');
    composer.setAttribute('placeholder', 'Write a reply...');
    composer.setAttribute('submit-label', 'Reply');
    composer.dataset.bodyTestid = initial.commentBodyTestid;
    composer.dataset.postTestid = initial.commentPostTestid;
    composer.dataset.cancelTestid = initial.commentCancelTestid;
    button.addEventListener('click', () => {
      composer.dataset.version = version;
      button.hidden = true;
      composer.show();
    });
    const cancel = () => {
      composer.complete();
      button.hidden = false;
      button.focus();
    };
    composer.addEventListener('comment-cancel', cancel);
    composer.addEventListener('keydown', event => {
      if (event.key === 'Escape' && !composer.submitting) {
        event.stopPropagation();
        cancel();
      }
    });
    composer.addEventListener('comment-submit', async event => {
      if (!canView) return composer.fail('Your access may have changed. Reload the page before replying.');
      commentsRequest += 1;
      try {
        const response = await fetch('/p/' + id + '/threads/' + threadId + '/comments', {
          method: 'POST', credentials: 'same-origin',
          headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': initial.csrf },
          body: JSON.stringify({ body: event.detail.body, version: Number(composer.dataset.version) })
        });
        if (!response.ok) throw new Error([401, 403].includes(response.status)
          ? 'Your access may have changed. Reload the page before replying.'
          : 'Could not post your reply. Try again.');
        const reply = await response.json();
        composer.complete();
        button.hidden = false;
        if (canView && !$('comments-panel').hidden) {
          await loadComments();
          const currentThread = Array.from($('comments-list').children).find(thread => thread.dataset.threadId === String(threadId));
          const message = Array.from(currentThread?.querySelectorAll('.comment') || []).find(item => item.dataset.commentId === String(reply.id));
          if (String(selectedThreadId) === String(threadId)) {
            message?.scrollIntoView({ block: 'nearest' });
            currentThread?.querySelector('.thread-reply-button')?.focus({ preventScroll: true });
          }
        }
      } catch (error) {
        composer.fail(error.message === 'Failed to fetch'
          ? 'Could not post your reply. Check your connection and try again.' : error.message);
      }
    });
    footer.append(button, composer);
    return footer;
  }

  async function loadComments() {
    const request = ++commentsRequest;
    $('comments-status').textContent = 'Loading comments...';
    $('discussion-empty').hidden = true;
    $('comments-retry').hidden = true;
    try {
      const response = await fetch('/p/' + id + '/comments', { credentials: 'same-origin', cache: 'no-store' });
      if (!response.ok) throw new Error('Could not load comments. Try again.');
      const comments = await response.json();
      if (request !== commentsRequest || !canView) return;
      interactions.setThreads(comments);
      renderThreads(comments);
      updateAttachmentState();
      $('comments-status').textContent = '';
      discussionEmpty = comments.length === 0;
      $('discussion-empty').hidden = !discussionEmpty || !$('comment-composer').hidden;
    } catch (error) {
      if (request !== commentsRequest) return;
      $('comments-status').textContent = error.message;
      $('comments-retry').hidden = false;
    }
  }

  $('comments-toggle').addEventListener('click', () => {
    if (!$('comments-panel').hidden) return closeComments();
    if (!canView) return;
    $('comments-panel').hidden = false;
    $('comments-toggle').setAttribute('aria-expanded', 'true');
    document.body.classList.add('comments-open');
    interactions.setActive(initial.format === 'html');
    $('comments-close').focus();
    loadComments();
  });
  $('comments-close').addEventListener('click', () => closeComments());
  $('comments-retry').addEventListener('click', loadComments);
  $('comments-panel').addEventListener('keydown', event => {
    if (event.key === 'Escape') {
      event.preventDefault();
      escapeComments();
    }
  });
  $('comment-add').addEventListener('click', () => {
    if (!$('attached-comment-composer').submitting) {
      $('attached-composer').hidden = true;
      $('attached-comment-composer').complete();
      interactions.clearSelection();
    }
    if ($('comment-composer').hidden) commentVersion = version;
    $('comment-composer').show();
    $('discussion-empty').hidden = true;
  });
  $('discussion-start').addEventListener('click', () => $('comment-add').click());
  $('comment-composer').addEventListener('comment-cancel', () => {
    $('discussion-empty').hidden = !discussionEmpty;
    $('comment-add').focus();
  });
  $('attached-comment-composer').addEventListener('comment-cancel', () => {
    $('attached-composer').hidden = true;
    interactions.clearSelection();
    $('comments-toggle').focus();
  });
  $('attached-composer').addEventListener('keydown', event => {
    if (event.key === 'Escape' && !$('attached-comment-composer').submitting) {
      setElementSelection(false);
      $('attached-comment-composer').complete();
      $('attached-composer').hidden = true;
      interactions.clearSelection();
      $('comments-toggle').focus();
    }
  });
  async function postComment(event) {
    const composer = event.currentTarget;
    if (!canView) return composer.fail('Your access may have changed. Reload the page before posting.');
    commentsRequest += 1;
    try {
      const response = await fetch('/p/' + id + '/comments', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': initial.csrf },
        body: JSON.stringify({
          body: event.detail.body,
          version: composer === $('attached-comment-composer') ? attachedVersion : commentVersion,
          xpath: composer === $('attached-comment-composer') ? attachedXpath : null,
          relativeX: composer === $('attached-comment-composer') ? attachedRelativeX : null,
          relativeY: composer === $('attached-comment-composer') ? attachedRelativeY : null
        })
      });
      if (!response.ok) throw new Error([401, 403].includes(response.status)
        ? 'Your access may have changed. Reload the page before posting.'
        : 'Could not post your comment. Try again.');
      const comment = await response.json();
      selectedThreadId = comment.threadId;
      composer.complete();
      if (composer === $('attached-comment-composer')) {
        $('attached-composer').hidden = true;
        interactions.clearSelection();
      }
      $('comment-add').focus();
      if (canView && !$('comments-panel').hidden) await loadComments();
    } catch (error) {
      composer.fail(error.message === 'Failed to fetch'
        ? 'Could not post your comment. Check your connection and try again.' : error.message);
    }
  }
  $('comment-composer').addEventListener('comment-submit', postComment);
  $('attached-comment-composer').addEventListener('comment-submit', postComment);

  function selectionChanged() {
    const selected = $('public').checked ? 'public' : 'internal';
    $('save').disabled = saving || selected === visibility;
    $('notice').hidden = selected === 'internal' && visibility === 'internal';
    if (selected === 'public') {
      $('notice-text').textContent = visibility === 'public'
        ? 'Public access ends in ' + daysLeft() + ' days.\nReturns to Internal on ' + dateText() + '.'
        : 'Returns to Internal after 30 days. Viewing, editing, or copying the link does not extend access.';
    } else {
      $('notice-text').textContent = 'Public access ends as soon as you save. The link stays the same.';
    }
    $('link-hint').textContent = visibility === 'internal'
      ? 'This link currently requires team sign-in. Copying it does not make it public.'
      : 'Anyone with this link can view and download.';
  }

  $('share').addEventListener('click', () => {
    if (!canManage) return;
    $('internal').checked = visibility === 'internal';
    $('public').checked = visibility === 'public';
    $('link').value = currentUrl();
    $('error').textContent = '';
    $('feedback').textContent = '';
    $('copy-label').textContent = 'Copy link';
    selectionChanged();
    $('share-dialog').showModal();
  });
  for (const button of ['close', 'cancel']) $(button).addEventListener('click', () => $('share-dialog').close());
  $('share-dialog').addEventListener('click', event => {
    if (event.target !== $('share-dialog')) return;
    const bounds = event.target.getBoundingClientRect();
    if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) event.target.close();
  });
  $('internal').addEventListener('change', selectionChanged);
  $('public').addEventListener('change', selectionChanged);
  $('copy').addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(currentUrl());
      $('copy-label').textContent = 'Copied';
      $('feedback').textContent = 'Link copied';
    } catch {
      $('link').focus();
      $('link').select();
      $('feedback').textContent = 'Select the link and copy it.';
    }
  });

  $('share-form').addEventListener('submit', async event => {
    event.preventDefault();
    if (!canManage || saving) return;
    saving = true;
    revision += 1;
    $('error').textContent = '';
    $('save').textContent = 'Saving…';
    selectionChanged();
    const selected = $('public').checked ? 'public' : 'internal';
    try {
      const response = await fetch('/p/' + id + '/visibility', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': initial.csrf },
        body: JSON.stringify({ visibility: selected })
      });
      if (!response.ok) throw new Error(response.status === 403
        ? 'Your session may have expired. Reload the page and sign in through your team login.'
        : 'Could not save sharing settings. Try again.');
      const artifact = await response.json();
      visibility = artifact.visibility;
      publicUntil = artifact.publicUntil;
      render();
      $('share-dialog').close();
      notify(visibility === 'public' ? 'Public access enabled for 30 days.' : 'Public access removed. Only the team can view.');
    } catch (error) {
      $('error').textContent = error.message === 'Failed to fetch'
        ? 'Could not save sharing settings. Check your connection and try again.' : error.message;
    } finally {
      saving = false;
      $('save').textContent = 'Save';
      selectionChanged();
    }
  });

  async function refresh() {
    if (!id || !canView || saving) return;
    try {
      const requestRevision = revision;
      const response = await fetch('/p/' + id + '/status', { credentials: 'same-origin', cache: 'no-store' });
      if (saving || requestRevision !== revision) return;
      if (response.status === 401) {
        // A private artifact requires login again. Let the page's normal redirect preserve its URL.
        location.reload();
        return;
      }
      if ([403, 404].includes(response.status)) {
        canManage = false;
        canView = false;
        visibility = 'internal';
        publicUntil = null;
        render();
        return;
      }
      if (!response.ok) return;
      const status = await response.json();
      if (status.canManage !== canManage) {
        // Refresh the avatar and CSRF token when another tab changes the login session.
        location.reload();
        return;
      }
      const artifact = status.artifact;
      visibility = artifact.visibility;
      publicUntil = artifact.publicUntil;
      canManage = status.canManage;
      if (artifact.live && artifact.version !== version) {
        $('artifact-frame').src = '/p/' + id + '/wrapper?v=' + artifact.version;
        version = artifact.version;
      }
      render();
    } catch {
      // The local expiry timer still removes public content when offline.
    }
  }

  $('avatar-image').addEventListener('error', () => {
    $('avatar-image').hidden = true;
    $('avatar-initials').hidden = false;
  });
  document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });
  window.addEventListener('pageshow', refresh);
  render();
  setInterval(refresh, 10000);
})();
