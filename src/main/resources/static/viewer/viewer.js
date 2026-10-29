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
  let artifactsRequest = 0;
  const interactions = new ArtifactInteractions($('artifact-frame'), $('artifact-annotations'));
  const discussions = new DiscussionPanel({ id, csrf: initial.csrf, interactions, notify, context: { canManage, canView, version } });
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
    discussions.updateAccess({ canView, canManage, version });
    if (!canView) $('artifact-frame').src = 'about:blank';
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
    setTimeout(() => {
      if (document.activeElement === $('artifact-frame') && titleMenu.matches(':popover-open')) titleMenu.hidePopover();
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
    if (next !== undefined) { event.preventDefault(); items[next]?.focus(); }
  });
  $('menu-share').addEventListener('click', () => { titleMenu.hidePopover(); $('share').click(); });
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
      const response = await fetch('/account/artifacts?excludeId=' + encodeURIComponent(id), { credentials: 'same-origin', cache: 'no-store' });
      if (!response.ok) throw new Error([401, 403].includes(response.status) ? 'Sign in again to view your artifacts.' : 'Could not load your artifacts. Try again.');
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
  $('artifact-library').addEventListener('keydown', event => { if (event.key === 'Escape') { event.preventDefault(); closeArtifacts(); } });

  function selectionChanged() {
    const selected = $('public').checked ? 'public' : 'internal';
    $('save').disabled = saving || selected === visibility;
    $('notice').hidden = selected === 'internal' && visibility === 'internal';
    if (selected === 'public') {
      $('notice-text').textContent = visibility === 'public'
        ? 'Public access ends in ' + daysLeft() + ' days.\nReturns to Internal on ' + dateText() + '.'
        : 'Returns to Internal after 30 days. Viewing, editing, or copying the link does not extend access.';
    } else $('notice-text').textContent = 'Public access ends as soon as you save. The link stays the same.';
    $('link-hint').textContent = visibility === 'internal' ? 'This link currently requires team sign-in. Copying it does not make it public.' : 'Anyone with this link can view and download.';
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
    try { await navigator.clipboard.writeText(currentUrl()); $('copy-label').textContent = 'Copied'; $('feedback').textContent = 'Link copied'; }
    catch { $('link').focus(); $('link').select(); $('feedback').textContent = 'Select the link and copy it.'; }
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
        method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': initial.csrf }, body: JSON.stringify({ visibility: selected })
      });
      if (!response.ok) throw new Error(response.status === 403 ? 'Your session may have expired. Reload the page and sign in through your team login.' : 'Could not save sharing settings. Try again.');
      const artifact = await response.json();
      visibility = artifact.visibility.visibility;
      publicUntil = artifact.visibility.publicUntil;
      render();
      $('share-dialog').close();
      notify(visibility === 'public' ? 'Public access enabled for 30 days.' : 'Public access removed. Only the team can view.');
    } catch (error) {
      $('error').textContent = error.message === 'Failed to fetch' ? 'Could not save sharing settings. Check your connection and try again.' : error.message;
    } finally { saving = false; $('save').textContent = 'Save'; selectionChanged(); }
  });

  async function refresh() {
    if (!id || !canView || saving) return;
    try {
      const requestRevision = revision;
      const response = await fetch('/p/' + id + '/status', { credentials: 'same-origin', cache: 'no-store' });
      if (saving || requestRevision !== revision) return;
      if (response.status === 401) { location.reload(); return; }
      if ([403, 404].includes(response.status)) {
        canManage = false; canView = false; visibility = 'internal'; publicUntil = null; render(); return;
      }
      if (!response.ok) return;
      const status = await response.json();
      if (status.canManage !== canManage) { location.reload(); return; }
      const artifact = status.artifact;
      visibility = artifact.visibility.visibility;
      publicUntil = artifact.visibility.publicUntil;
      canManage = status.canManage;
      if (artifact.live && artifact.version !== version) {
        $('artifact-frame').src = '/p/' + id + '/wrapper?v=' + artifact.version;
        version = artifact.version;
      }
      render();
    } catch { /* The local expiry timer still removes public content when offline. */ }
  }

  $('avatar-image').addEventListener('error', () => { $('avatar-image').hidden = true; $('avatar-initials').hidden = false; });
  document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });
  window.addEventListener('pageshow', refresh);
  render();
  setInterval(refresh, 10000);
})();
