'use strict';

const $ = (sel) => document.querySelector(sel);

async function api(path, options) {
    const res = await fetch(path, Object.assign({
        headers: { 'Content-Type': 'application/json' },
    }, options || {}));
    const type = res.headers.get('content-type') || '';
    if (!type.includes('json')) {
        return res;
    }
    return res.json();
}

function fmtTime(iso) {
    if (!iso) return '';
    const d = new Date(iso);
    if (isNaN(d.getTime())) return iso;
    const p = (n) => String(n).padStart(2, '0');
    return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) +
        ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
}

// ---------------------------------------------------------------- 看板

async function loadStats() {
    const s = await api('/api/stats');
    const cards = [
        ['点位数', s.points],
        ['导航任务', s.tasks],
        ['访客总数', s.visitors],
        ['今日访客', s.todayVisitors],
        ['知识条目', s.qa],
    ];
    $('#stat-cards').innerHTML = cards.map(([k, v]) =>
        `<div class="card"><div class="k">${k}</div><div class="v">${v}</div></div>`).join('');
    $('#top-targets').innerHTML = (s.topTargets || []).map((t) =>
        `<tr><td>${t.target}</td><td>${t.count}</td></tr>`).join('') ||
        '<tr><td colspan="2" class="hint">还没有导航记录</td></tr>';
}

// ---------------------------------------------------------------- 点位

async function loadPoints() {
    const rows = await api('/api/points');
    $('#points-body').innerHTML = rows.map((r) =>
        `<tr><td>${r.id}</td><td>${r.name}</td><td>${Number(r.x).toFixed(2)}</td>` +
        `<td>${Number(r.y).toFixed(2)}</td><td>${Number(r.yaw || 0).toFixed(2)}</td>` +
        `<td><span class="danger" data-del="points" data-id="${r.id}">删除</span></td></tr>`).join('');
}

// ---------------------------------------------------------------- 任务

async function loadTasks() {
    const rows = await api('/api/tasks');
    $('#tasks-body').innerHTML = rows.slice().reverse().map((r) =>
        `<tr><td>${r.id}</td><td>${r.target}</td><td>${r.source}</td>` +
        `<td>${fmtTime(r.createdAt)}</td></tr>`).join('') ||
        '<tr><td colspan="4" class="hint">暂无任务</td></tr>';
}

// ---------------------------------------------------------------- 访客

async function loadVisitors() {
    const rows = await api('/api/visitors');
    $('#visitors-body').innerHTML = rows.slice().reverse().map((r) =>
        `<tr><td>${r.id}</td><td>${r.name || ''}</td><td>${r.phone || ''}</td>` +
        `<td>${r.company || ''}</td><td>${r.reason || ''}</td><td>${fmtTime(r.createdAt)}</td></tr>`).join('') ||
        '<tr><td colspan="6" class="hint">暂无访客</td></tr>';
}

// ---------------------------------------------------------------- 知识库

async function loadQa() {
    const rows = await api('/api/qa');
    $('#qa-body').innerHTML = rows.map((r) =>
        `<tr><td>${r.question}</td><td>${(r.keywords || []).join('、')}</td>` +
        `<td>${r.answer}</td>` +
        `<td><span class="danger" data-del="qa" data-id="${r.id}">删除</span></td></tr>`).join('');
}

// ---------------------------------------------------------------- 交互

document.querySelectorAll('.tab').forEach((tab) => {
    tab.addEventListener('click', () => {
        document.querySelectorAll('.tab').forEach((t) => t.classList.remove('active'));
        document.querySelectorAll('.view').forEach((v) => v.classList.remove('active'));
        tab.classList.add('active');
        $('#view-' + tab.dataset.view).classList.add('active');
        refresh(tab.dataset.view);
    });
});

document.addEventListener('click', async (e) => {
    const del = e.target.closest('[data-del]');
    if (!del) return;
    const name = del.dataset.del;
    if (!confirm('确认删除？')) return;
    await api('/api/' + name + '/' + del.dataset.id, { method: 'DELETE' });
    refresh(name === 'qa' ? 'qa' : 'points');
    loadStats();
});

$('#form-point').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = new FormData(e.target);
    await api('/api/points', {
        method: 'POST',
        body: JSON.stringify({
            name: f.get('name'),
            x: Number(f.get('x')),
            y: Number(f.get('y')),
            yaw: Number(f.get('yaw') || 0),
        }),
    });
    e.target.reset();
    loadPoints();
    loadStats();
});

$('#form-task').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = new FormData(e.target);
    await api('/api/tasks', {
        method: 'POST',
        body: JSON.stringify({ target: f.get('target'), source: f.get('source'), ts: Date.now() }),
    });
    e.target.reset();
    loadTasks();
    loadStats();
});

$('#form-visitor').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = new FormData(e.target);
    await api('/api/visitors', {
        method: 'POST',
        body: JSON.stringify({
            name: f.get('name'),
            phone: f.get('phone'),
            company: f.get('company'),
            reason: f.get('reason'),
        }),
    });
    e.target.reset();
    loadVisitors();
    loadStats();
});

$('#form-qa').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = new FormData(e.target);
    const keywords = String(f.get('keywords') || '')
        .split(/[,，、\s]+/).filter(Boolean);
    await api('/api/qa', {
        method: 'POST',
        body: JSON.stringify({
            question: f.get('question'),
            keywords,
            answer: f.get('answer'),
        }),
    });
    e.target.reset();
    loadQa();
    loadStats();
});

$('#form-ask').addEventListener('submit', async (e) => {
    e.preventDefault();
    const q = new FormData(e.target).get('q');
    const hit = await api('/api/qa/answer?q=' + encodeURIComponent(q));
    $('#ask-result').textContent = hit.answer || '没有匹配';
});

// ---------------------------------------------------------------- 启动

function refresh(view) {
    if (view === 'stats') loadStats();
    if (view === 'points') loadPoints();
    if (view === 'tasks') loadTasks();
    if (view === 'visitors') loadVisitors();
    if (view === 'qa') loadQa();
}

async function boot() {
    try {
        const h = await api('/api/health');
        $('#health').textContent = h.ok ? '后台在线 v' + h.version : '后台异常';
        $('#health').className = 'pill ' + (h.ok ? 'ok' : 'bad');
    } catch (err) {
        $('#health').textContent = '后台未连接';
        $('#health').className = 'pill bad';
    }
    await Promise.all([loadStats(), loadPoints(), loadTasks(), loadVisitors(), loadQa()]);
}

boot();
