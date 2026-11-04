'use strict';

/**
 * 后台冒烟测试：不依赖任何测试框架，直接 node test/smoke.js。
 * 覆盖：健康检查、点位 CRUD、任务上报、知识库检索、xlsx 导出结构。
 */

const http = require('http');
const fs = require('fs');
const os = require('os');
const path = require('path');
const assert = require('assert');

// 用临时数据文件，避免污染 data/db.json
const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'robot-backend-'));
process.env.DATA_FILE = path.join(tmpDir, 'db.json');
fs.copyFileSync(path.join(__dirname, '..', 'data', 'db.json'), process.env.DATA_FILE);

const { server } = require('../server');

function request(port, method, urlPath, body) {
  return new Promise((resolve, reject) => {
    const payload = body ? Buffer.from(JSON.stringify(body)) : null;
    const req = http.request({
      port,
      method,
      path: urlPath,
      headers: payload
        ? { 'Content-Type': 'application/json', 'Content-Length': payload.length }
        : {},
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({
        status: res.statusCode,
        headers: res.headers,
        buffer: Buffer.concat(chunks),
      }));
    });
    req.on('error', reject);
    if (payload) req.write(payload);
    req.end();
  });
}

async function run() {
  await new Promise((resolve) => server.listen(0, resolve));
  const port = server.address().port;
  let failed = 0;

  const check = (name, fn) => {
    try {
      fn();
      console.log('  PASS  ' + name);
    } catch (err) {
      failed++;
      console.log('  FAIL  ' + name + ' -> ' + err.message);
    }
  };

  const health = await request(port, 'GET', '/api/health');
  check('GET /api/health 返回 200', () => assert.strictEqual(health.status, 200));
  check('health.ok === true', () => assert.strictEqual(JSON.parse(health.buffer).ok, true));

  const pointsBefore = JSON.parse((await request(port, 'GET', '/api/points')).buffer).length;
  const created = await request(port, 'POST', '/api/points', { name: '测试点', x: 1, y: 2, yaw: 0 });
  check('POST /api/points 返回 201', () => assert.strictEqual(created.status, 201));
  const newPoint = JSON.parse(created.buffer);
  check('点位写入了 id 与时间戳', () => {
    assert.ok(newPoint.id > 0);
    assert.ok(newPoint.createdAt);
  });

  const pointsAfter = JSON.parse((await request(port, 'GET', '/api/points')).buffer).length;
  check('点位数量 +1', () => assert.strictEqual(pointsAfter, pointsBefore + 1));

  const del = await request(port, 'DELETE', '/api/points/' + newPoint.id);
  check('DELETE /api/points/:id 成功', () => assert.strictEqual(JSON.parse(del.buffer).ok, true));

  const task = await request(port, 'POST', '/api/tasks', { target: '会议室', source: 'remote', ts: Date.now() });
  check('任务上报返回 201', () => assert.strictEqual(task.status, 201));

  const answer = JSON.parse((await request(port, 'GET', '/api/qa/answer?q=' + encodeURIComponent('请问租金多少'))).buffer);
  check('知识库命中“租金”', () => assert.ok(String(answer.answer).includes('租金')));

  const xlsx = await request(port, 'GET', '/api/export/visitors.xlsx');
  check('xlsx 返回 200', () => assert.strictEqual(xlsx.status, 200));
  check('xlsx 是 zip 魔数 PK\\x03\\x04', () => {
    assert.strictEqual(xlsx.buffer[0], 0x50);
    assert.strictEqual(xlsx.buffer[1], 0x4b);
    assert.strictEqual(xlsx.buffer[2], 0x03);
    assert.strictEqual(xlsx.buffer[3], 0x04);
  });
  check('xlsx 内含 sheet1.xml 与 workbook.xml', () => {
    const text = xlsx.buffer.toString('utf8');
    assert.ok(text.includes('xl/worksheets/sheet1.xml'));
    assert.ok(text.includes('xl/workbook.xml'));
  });
  check('xlsx 表头含“姓名”', () => {
    assert.ok(xlsx.buffer.toString('utf8').includes('姓名'));
  });

  fs.writeFileSync(path.join(tmpDir, 'visitors.xlsx'), xlsx.buffer);

  const stats = JSON.parse((await request(port, 'GET', '/api/stats')).buffer);
  check('stats 返回各计数', () => {
    assert.ok(typeof stats.points === 'number');
    assert.ok(Array.isArray(stats.topTargets));
  });

  server.close();
  console.log('\n结果: ' + (failed === 0 ? '全部通过' : failed + ' 项失败'));
  console.log('导出的样例文件: ' + path.join(tmpDir, 'visitors.xlsx'));
  process.exit(failed === 0 ? 0 : 1);
}

run().catch((err) => {
  console.error(err);
  server.close();
  process.exit(1);
});
