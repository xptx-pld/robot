'use strict';

/**
 * 极简 JSON 文件存储。
 *
 * 现场部署在机器人的随身工控机/局域网 PC 上，没有数据库可用，
 * 所以用「一个 JSON 文件 + 原子写」足够：先写 .tmp 再 rename，
 * 断电最多丢最后一次写入，不会留下半个文件。
 */

const fs = require('fs');
const path = require('path');

const COLLECTIONS = ['points', 'tasks', 'visitors', 'qa', 'enterprises'];

class Store {
  constructor(file) {
    this.file = file;
    this.data = { seq: 1 };
    COLLECTIONS.forEach((name) => {
      this.data[name] = [];
    });
    this.load();
  }

  load() {
    try {
      if (fs.existsSync(this.file)) {
        const raw = fs.readFileSync(this.file, 'utf8');
        const parsed = JSON.parse(raw);
        COLLECTIONS.forEach((name) => {
          if (Array.isArray(parsed[name])) {
            this.data[name] = parsed[name];
          }
        });
        if (typeof parsed.seq === 'number') {
          this.data.seq = parsed.seq;
        }
        return;
      }
    } catch (err) {
      console.error('[store] 读取失败，按空库启动:', err.message);
    }
    this.save();
  }

  save() {
    const dir = path.dirname(this.file);
    fs.mkdirSync(dir, { recursive: true });
    const tmp = this.file + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(this.data, null, 2), 'utf8');
    fs.renameSync(tmp, this.file);
  }

  list(name) {
    this.assert(name);
    return this.data[name];
  }

  add(name, item) {
    this.assert(name);
    const row = Object.assign({ id: this.data.seq++, createdAt: new Date().toISOString() }, item);
    this.data[name].push(row);
    this.save();
    return row;
  }

  update(name, id, patch) {
    this.assert(name);
    const row = this.data[name].find((r) => String(r.id) === String(id));
    if (!row) {
      return null;
    }
    Object.assign(row, patch, { updatedAt: new Date().toISOString() });
    this.save();
    return row;
  }

  remove(name, id) {
    this.assert(name);
    const before = this.data[name].length;
    this.data[name] = this.data[name].filter((r) => String(r.id) !== String(id));
    const removed = before !== this.data[name].length;
    if (removed) {
      this.save();
    }
    return removed;
  }

  assert(name) {
    if (!COLLECTIONS.includes(name)) {
      throw new Error('unknown collection: ' + name);
    }
  }

  /** 知识库检索：关键词命中数排序，返回最高分答案。 */
  answer(question) {
    if (!question) {
      return null;
    }
    const q = question.trim();
    let best = null;
    let bestScore = 0;
    for (const item of this.data.qa) {
      let score = 0;
      if (item.question && q.includes(item.question)) {
        score += 5;
      }
      for (const key of item.keywords || []) {
        if (key && q.includes(key)) {
          score += 2;
        }
      }
      if (score > bestScore) {
        bestScore = score;
        best = item;
      }
    }
    return bestScore > 0 ? best : null;
  }

  stats() {
    const today = new Date().toISOString().slice(0, 10);
    const todayVisitors = this.data.visitors.filter((v) => (v.createdAt || '').startsWith(today));
    const counter = {};
    this.data.tasks.forEach((t) => {
      counter[t.target] = (counter[t.target] || 0) + 1;
    });
    const topTargets = Object.keys(counter)
      .map((k) => ({ target: k, count: counter[k] }))
      .sort((a, b) => b.count - a.count)
      .slice(0, 5);
    return {
      points: this.data.points.length,
      tasks: this.data.tasks.length,
      visitors: this.data.visitors.length,
      todayVisitors: todayVisitors.length,
      qa: this.data.qa.length,
      topTargets,
    };
  }
}

module.exports = { Store, COLLECTIONS };
