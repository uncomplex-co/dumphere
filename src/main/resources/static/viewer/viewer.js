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
    $('avatar').hidden = !canManage;
    $('download').hidden = !canView;
    $('content').hidden = !canView;
    $('blocked').hidden = canView;
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
        $('artifact-frame').src = '/p/' + id + '/content?v=' + artifact.version;
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
