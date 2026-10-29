// Isolated browser-test fixture: production templates/assets, in-memory HTTP responses, no real credentials or data.
// Run: node src/test/js/browser-server.cjs, then open http://localhost:7335 and evaluate discussions.browser.js.
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const resources = path.resolve(__dirname, '../../main/resources');
const viewerSource = fs.readFileSync(path.resolve(__dirname, '../../main/kotlin/dev/uncomplex/dumphere/adapters/api/ArtifactViewer.kt'), 'utf8');
const constants = Object.fromEntries([...viewerSource.matchAll(/const val (\w+) = "([^"]+)"/g)].map(match => [match[1], match[2]]));
const values = Object.fromEntries([...viewerSource.matchAll(/"(\w+)" to ArtifactViewerIds\.(\w+)/g)].map(match => [match[1], constants[match[2]]]));
Object.assign(values, {
  TITLE: 'Discussion browser regression', ID: 'browser-fixture', FORMAT: 'html', FORMAT_LABEL: 'HTML',
  VISIBILITY: 'internal', PUBLIC_UNTIL: '', VERSION: 1, CAN_VIEW: true, CAN_MANAGE: true,
  NAME: 'Browser tester', EMAIL: 'browser@example.test', INITIALS: 'BT', PICTURE: '', HAS_PICTURE: false,
  WRAPPER_URL: '/p/browser-fixture/wrapper', CONTENT_URL: '/p/browser-fixture/content', CSRF: 'browser-fixture'
});
const escape = value => String(value ?? '').replaceAll('&', '&amp;').replaceAll('"', '&quot;').replaceAll('<', '&lt;');
function template(name) {
  return fs.readFileSync(path.join(resources, 'templates', name), 'utf8')
    .replace(/th:attr="([^"]*)"/g, (_, attributes) => [...attributes.matchAll(/([\w-]+)=\$\{(\w+)\}/g)]
      .map(match => match[1] + '="' + escape(values[match[2]]) + '"').join(' '))
    .replace(/th:(src|href)="\$\{(\w+)\}"/g, (_, attribute, key) => attribute + '="' + escape(values[key]) + '"')
    .replace(/th:hidden="\$\{(\w+)\}"/g, (_, key) => values[key] ? 'hidden' : '')
    .replace(/\s+th:\w+="[^"]*"/g, '');
}
let nextComment = 1, nextThread = 1;
let comments = [];
const server = http.createServer(async (request, response) => {
  const url = new URL(request.url, 'http://localhost');
  const send = (value, status = 200) => { response.writeHead(status, { 'Content-Type': 'application/json' }); response.end(JSON.stringify(value)); };
  if (url.pathname.startsWith('/viewer/')) {
    const file = path.resolve(resources, 'static', '.' + url.pathname);
    if (!file.startsWith(path.join(resources, 'static', 'viewer') + path.sep) || !fs.existsSync(file)) return send({}, 404);
    response.writeHead(200, { 'Content-Type': file.endsWith('.css') ? 'text/css' : 'application/javascript' });
    return response.end(fs.readFileSync(file));
  }
  if (url.pathname === '/' || url.pathname.endsWith('/wrapper') || url.pathname.endsWith('/content')) {
    response.writeHead(200, { 'Content-Type': 'text/html' });
    return response.end(url.pathname.endsWith('/content') ? '<h1>Browser fixture</h1><p>Artifact content for anchored comments.</p>'
      : template(url.pathname.endsWith('/wrapper') ? 'artifact-wrapper.html' : 'artifact-viewer.html'));
  }
  if (url.pathname.endsWith('/status')) return send({ artifact: { visibility: { visibility: 'internal', publicUntil: null }, version: 1, live: false }, canManage: true });
  if (url.pathname === '/account/artifacts') return send([]);
  let raw = '';
  for await (const chunk of request) raw += chunk;
  const body = raw ? JSON.parse(raw) : {};
  if (request.method === 'GET' && url.pathname.endsWith('/comments')) return send(comments);
  const reply = url.pathname.match(/\/threads\/(\d+)\/comments$/);
  if (request.method === 'POST' && url.pathname.endsWith('/comments')) {
    const parent = reply ? comments.find(comment => comment.threadId === Number(reply[1])) : null;
    if (reply && !parent) return send({}, 404);
    const threadId = parent?.threadId || nextThread++;
    const comment = {
      id: nextComment++, threadId, threadNumber: threadId, body: body.body, author: 'Browser tester',
      createdAt: new Date().toISOString(), editedAt: null, resolvedAt: parent?.resolvedAt || null, canEdit: true,
      xpath: parent?.xpath || body.xpath || null, relativeX: parent?.relativeX ?? body.relativeX ?? null, relativeY: parent?.relativeY ?? body.relativeY ?? null
    };
    comments.push(comment);
    return send(comment, 201);
  }
  const resolution = url.pathname.match(/\/threads\/(\d+)\/resolution$/);
  if (resolution) {
    const threadId = Number(resolution[1]);
    const resolvedAt = body.resolved ? new Date().toISOString() : null;
    comments = comments.map(comment => comment.threadId === threadId ? { ...comment, resolvedAt } : comment);
    return send({ threadId, threadNumber: threadId, resolvedAt });
  }
  const target = Number(url.pathname.match(/\/comments\/(\d+)$/)?.[1]);
  const comment = comments.find(item => item.id === target);
  if (!comment) return send({}, 404);
  if (request.method === 'PATCH') {
    comment.body = body.body;
    comment.editedAt = new Date().toISOString();
    return send(comment);
  }
  if (request.method === 'DELETE') {
    const root = comments.find(item => item.threadId === comment.threadId);
    comments = comments.filter(item => item.id !== target && !(root.id === target && item.threadId === comment.threadId));
    return send(null, 204);
  }
  send({}, 404);
});
server.listen(7335, '127.0.0.1', () => console.log('Discussion browser fixture: http://localhost:7335'));
