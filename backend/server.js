'use strict';

/**
 * 迎宾机器人运营后台（零第三方依赖）。
 *
 * 对应 PPT：「后端服务 · Node.js 零依赖 / REST API + 手写 xlsx 导出，
 * 访客台账 / 问答 / 任务」。
 *
 * 启动：node server.js        默认 http://0.0.0.0:8080
 * 环境变量：PORT、DATA_FILE
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const { URL } = require('url');

const { Store } = require('./lib/store');
const { buildXlsx } = require('./lib/xlsx');

const PORT = Number(process.env.PORT || 8080);
const DATA_FILE = process.env.DATA_FILE || path.join(__dirname, 'data', 'db.json');
const PUBLIC_DIR = path.join(__dirname, 'public');

const store = new Store(DATA_FILE);

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
};

function sendJson(res, code, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Content-Type',
    'Access-Control-Allow-Methods': 'GET,POST,PUT,DELETE,OPTIONS',
  });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let raw = '';
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > 2 * 1024 * 1024) {
        reject(new Error('payload too large'));
        req.destroy();
        return;
      }
      raw += chunk;
    });
    req.on('end', () => {
      if (!raw) {
        resolve({});
        return;
      }
      try {
        resolve(JSON.parse(raw));
      } catch (err) {
        reject(new Error('invalid json'));
      }
    });
    req.on('error', reject);
  });
}

function serveStatic(res, pathname) {
  const rel = pathname === '/' ? 'index.html' : pathname.replace(/^\/+/, '');
  const file = path.join(PUBLIC_DIR, rel);
  if (!file.startsWith(PUBLIC_DIR)) {
    sendJson(res, 403, { error: 'forbidden' });
    return;
  }
  fs.readFile(file, (err, data) => {
    if (err) {
      sendJson(res, 404, { error: 'not found' });
      return;
    }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' });
    res.end(data);
  });
}

async function handleApi(req, res, url) {
  const parts = url.pathname.split('/').filter(Boolean); // ['api', 'points', '3']
  const resource = parts[1];
  const id = parts[2];

  if (resource === 'health') {
    sendJson(res, 200, { ok: true, ts: Date.now(), version: '1.0.0' });
    return;
  }

  if (resource === 'stats') {
    sendJson(res, 200, store.stats());
    return;
  }

  if (resource === 'qa' && req.method === 'GET' && parts[2] === 'answer') {
    const question = url.searchParams.get('q') || '';
    const hit = store.answer(question);
    sendJson(res, 200, hit || { answer: '这个问题我还没学会，可以到后台补充知识库。', matched: false });
    return;
  }

  if (resource === 'export' && id === 'visitors.xlsx' && req.method === 'GET') {
    const rows = [['姓名', '电话', '拜访企业', '事由', '登记时间']];
    for (const v of store.list('visitors')) {
      rows.push([v.name || '', v.phone || '', v.company || '', v.reason || '', v.createdAt || '']);
    }
    const buf = buildXlsx(rows, '访客台账');
    res.writeHead(200, {
      'Content-Type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      'Content-Disposition': 'attachment; filename="visitors.xlsx"',
      'Content-Length': buf.length,
    });
    res.end(buf);
    return;
  }

  const collection = resource;
  if (!collection) {
    sendJson(res, 404, { error: 'not found' });
    return;
  }

  const allowed = ['points', 'tasks', 'visitors', 'qa', 'enterprises'];
  if (!allowed.includes(collection)) {
    sendJson(res, 404, { error: 'unknown resource: ' + collection });
    return;
  }

  if (req.method === 'GET') {
    if (id) {
      const row = store.list(collection).find((r) => String(r.id) === String(id));
      sendJson(res, row ? 200 : 404, row || { error: 'not found' });
      return;
    }
    sendJson(res, 200, store.list(collection));
    return;
  }

  if (req.method === 'POST') {
    const body = await readBody(req);
    sendJson(res, 201, store.add(collection, body));
    return;
  }

  if (req.method === 'PUT' && id) {
    const body = await readBody(req);
    const row = store.update(collection, id, body);
    sendJson(res, row ? 200 : 404, row || { error: 'not found' });
    return;
  }

  if (req.method === 'DELETE' && id) {
    const ok = store.remove(collection, id);
    sendJson(res, ok ? 200 : 404, { ok });
    return;
  }

  sendJson(res, 405, { error: 'method not allowed' });
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://' + (req.headers.host || 'localhost'));

  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Headers': 'Content-Type',
      'Access-Control-Allow-Methods': 'GET,POST,PUT,DELETE,OPTIONS',
    });
    res.end();
    return;
  }

  if (url.pathname.startsWith('/api/')) {
    handleApi(req, res, url).catch((err) => {
      sendJson(res, 400, { error: err.message });
    });
    return;
  }

  serveStatic(res, url.pathname);
});

if (require.main === module) {
  server.listen(PORT, () => {
    console.log(`[backend] http://0.0.0.0:${PORT}  数据文件: ${DATA_FILE}`);
  });
}

module.exports = { server, store };
