/*
 * 机器人端 H5 控制台（ES5，兼容 Android 5.1 的旧 WebView）
 *
 * 与原生唯一的接口是 window.NativeBridge（见 web/JsBridge.java）。
 * 在 PC 浏览器里打开时 NativeBridge 不存在，会自动进入演示模式，
 * 方便在没有机器人时调界面。
 */
(function () {
    'use strict';

    var Native = window.NativeBridge || null;
    var DEMO = !Native;

    var state = null;
    var mapInfo = null;
    var mapImage = new Image();
    var mapReady = false;
    var scan = null;
    var activeTagId = -1;

    var mapCanvas = document.getElementById('map-canvas');
    var mapCtx = mapCanvas.getContext('2d');
    var radarCanvas = document.getElementById('radar-canvas');
    var radarCtx = radarCanvas.getContext('2d');

    // ------------------------------------------------------------ 原生调用

    function parse(raw, dflt) {
        try {
            return raw ? JSON.parse(raw) : dflt;
        } catch (e) {
            return dflt;
        }
    }

    function callState() {
        if (DEMO) {
            return demoState();
        }
        return parse(Native.state(), {});
    }

    function callScan() {
        if (DEMO) {
            return demoScan();
        }
        return parse(Native.scan(), {});
    }

    function callMap() {
        if (DEMO) {
            return demoMap();
        }
        return parse(Native.map(), {});
    }

    // ------------------------------------------------------------ 演示数据

    var demoTagId = 0;

    function demoState() {
        var t = Date.now() / 1000;
        return {
            nav: { ready: true, ctrl: 2, ctrlText: '导航已开启', navi: 4, naviText: '导航中', navigating: true },
            pose: [120 + Math.sin(t) * 40, 150 + Math.cos(t) * 30, Math.sin(t / 3)],
            map: { loaded: true, name: 'demo-map', w: 400, h: 300 },
            tags: 3,
            lidar: {
                running: true, hz: 8.7, points: 300,
                front: 1500, left: 2200, right: 900,
                safety: 0, safetyText: '通畅'
            },
            chassis: '底盘:仿真', speech: true, battery: 86,
            bridgePort: 9001, bridgeClients: 1, backend: 'http://192.168.1.10:8080',
            pending: '会议室', message: '演示模式（无 NativeBridge）'
        };
    }

    function demoScan() {
        var r = [];
        for (var i = 0; i < 360; i++) {
            var rad = i * Math.PI / 180;
            var wall = 300 + 120 * Math.sin(rad * 3) + 60 * Math.cos(rad * 5);
            r.push(i > 40 && i < 70 ? -1 : Math.round(wall));
        }
        return { hz: 8.7, n: 340, ts: Date.now(), r: r };
    }

    function demoMap() {
        if (!mapInfo) {
            var c = document.createElement('canvas');
            c.width = 400;
            c.height = 300;
            var g = c.getContext('2d');
            g.fillStyle = '#f2f4f6';
            g.fillRect(0, 0, 400, 300);
            g.strokeStyle = '#1b1b1b';
            g.lineWidth = 4;
            g.strokeRect(20, 20, 360, 260);
            g.fillStyle = '#2b2b2b';
            g.fillRect(260, 60, 60, 40);
            g.fillRect(80, 180, 40, 40);
            mapInfo = {
                loaded: true, name: 'demo-map', w: 400, h: 300, scale: 1,
                png: c.toDataURL('image/png'),
                tags: [
                    { id: 1, name: '前台', x: 70, y: 70, rad: 0 },
                    { id: 2, name: '会议室', x: 320, y: 120, rad: 1.57 },
                    { id: 3, name: '充电点', x: 200, y: 250, rad: 3.14 }
                ]
            };
        }
        return mapInfo;
    }

    // ------------------------------------------------------------ 地图绘制

    function loadMap(force) {
        if (mapReady && !force) {
            return;
        }
        var m = callMap();
        if (!m || !m.loaded || !m.png) {
            document.getElementById('map-empty').className = 'empty show';
            return;
        }
        document.getElementById('map-empty').className = 'empty';
        mapInfo = m;
        mapImage.onload = function () {
            mapReady = true;
            document.getElementById('map-meta').innerHTML =
                m.name + ' · ' + m.w + '×' + m.h;
            renderTags();
        };
        mapImage.src = m.png;
    }

    function fitRect() {
        if (!mapReady) {
            return null;
        }
        var maxW = mapCanvas.parentNode.clientWidth - 8;
        var maxH = mapCanvas.parentNode.clientHeight - 8;
        var ratio = Math.min(maxW / mapImage.width, maxH / mapImage.height);
        var w = Math.floor(mapImage.width * ratio);
        var h = Math.floor(mapImage.height * ratio);
        mapCanvas.width = w;
        mapCanvas.height = h;
        return { x: 0, y: 0, w: w, h: h, k: ratio };
    }

    function drawMap() {
        if (!mapReady) {
            return;
        }
        var r = fitRect();
        mapCanvas.style.width = r.w + 'px';
        mapCanvas.style.height = r.h + 'px';
        mapCtx.clearRect(0, 0, r.w, r.h);
        mapCtx.drawImage(mapImage, 0, 0, r.w, r.h);

        var scale = (mapInfo.scale || 1) * r.k;
        var tags = mapInfo.tags || [];

        for (var i = 0; i < tags.length; i++) {
            var t = tags[i];
            var cx = t.x * scale;
            var cy = t.y * scale;
            mapCtx.beginPath();
            mapCtx.arc(cx, cy, t.id === activeTagId ? 7 : 5, 0, Math.PI * 2);
            mapCtx.fillStyle = t.id === activeTagId ? '#12b5a5' : '#2979ff';
            mapCtx.fill();
            mapCtx.strokeStyle = '#ffffff';
            mapCtx.lineWidth = 1.5;
            mapCtx.stroke();
            mapCtx.fillStyle = '#0b1013';
            mapCtx.font = '11px sans-serif';
            mapCtx.fillText(t.name, cx + 8, cy + 4);
        }

        if (state && state.pose) {
            var px = state.pose[0] * scale;
            var py = state.pose[1] * scale;
            var heading = -state.pose[2];
            mapCtx.save();
            mapCtx.translate(px, py);
            mapCtx.rotate(heading);
            mapCtx.beginPath();
            mapCtx.moveTo(0, -9);
            mapCtx.lineTo(6, 7);
            mapCtx.lineTo(0, 4);
            mapCtx.lineTo(-6, 7);
            mapCtx.closePath();
            mapCtx.fillStyle = '#ff4d4f';
            mapCtx.fill();
            mapCtx.restore();

            mapCtx.beginPath();
            mapCtx.arc(px, py, 14, 0, Math.PI * 2);
            mapCtx.strokeStyle = 'rgba(255,77,79,.45)';
            mapCtx.lineWidth = 1;
            mapCtx.stroke();
        }
    }

    // ------------------------------------------------------------ 雷达绘制

    function drawRadar() {
        var w = radarCanvas.width;
        var h = radarCanvas.height;
        var cx = w / 2;
        var cy = h / 2;
        var maxMm = 4000;
        var R = Math.min(w, h) / 2 - 6;

        radarCtx.clearRect(0, 0, w, h);

        radarCtx.strokeStyle = 'rgba(255,255,255,.07)';
        radarCtx.lineWidth = 1;
        for (var ring = 1; ring <= 4; ring++) {
            radarCtx.beginPath();
            radarCtx.arc(cx, cy, R * ring / 4, 0, Math.PI * 2);
            radarCtx.stroke();
        }
        radarCtx.beginPath();
        radarCtx.moveTo(cx - R, cy);
        radarCtx.lineTo(cx + R, cy);
        radarCtx.moveTo(cx, cy - R);
        radarCtx.lineTo(cx, cy + R);
        radarCtx.stroke();

        var safety = state && state.lidar ? state.lidar.safety : 0;
        var color = safety === 2 ? '#ff4d4f' : (safety === 1 ? '#ffb020' : '#3ddc97');

        if (scan && scan.r) {
            for (var d = 0; d < 360; d++) {
                var cm = scan.r[d];
                if (cm < 0) {
                    continue;
                }
                var mm = cm * 10;
                if (mm > maxMm) {
                    continue;
                }
                var rad = d * Math.PI / 180;
                var rr = R * mm / maxMm;
                var x = cx + Math.sin(rad) * rr;
                var y = cy - Math.cos(rad) * rr;
                radarCtx.fillStyle = d < 30 || d > 330 ? color : 'rgba(120,200,255,.65)';
                radarCtx.fillRect(x - 1.2, y - 1.2, 2.4, 2.4);
            }
        }

        radarCtx.beginPath();
        radarCtx.moveTo(cx, cy - 8);
        radarCtx.lineTo(cx + 6, cy + 7);
        radarCtx.lineTo(cx - 6, cy + 7);
        radarCtx.closePath();
        radarCtx.fillStyle = '#ffffff';
        radarCtx.fill();

        document.getElementById('radar-meta').innerHTML =
            (scan && scan.hz ? scan.hz.toFixed(1) : '--') + ' Hz · ' +
            (scan && scan.n ? scan.n : 0) + ' 点';
    }

    // ------------------------------------------------------------ 面板刷新

    function renderTags() {
        var list = document.getElementById('tag-list');
        var tags = (mapInfo && mapInfo.tags) || [];
        document.getElementById('tag-count').innerHTML = tags.length + ' 个';
        if (!tags.length) {
            list.innerHTML = '<li style="justify-content:center;color:#7f97a4">获取不到位置点</li>';
            return;
        }
        var html = '';
        for (var i = 0; i < tags.length; i++) {
            var t = tags[i];
            html += '<li data-id="' + t.id + '" class="' + (t.id === activeTagId ? 'active' : '') + '">' +
                '<span>' + t.name + '</span><span class="go">带路 ›</span></li>';
        }
        list.innerHTML = html;
        var items = list.getElementsByTagName('li');
        for (var j = 0; j < items.length; j++) {
            items[j].onclick = function () {
                var id = parseInt(this.getAttribute('data-id'), 10);
                goToTag(id);
            };
        }
    }

    function goToTag(id) {
        activeTagId = id;
        var res = DEMO
            ? { ok: true, message: '演示模式' }
            : parse(Native.navigateTo(id), { ok: false, message: '调用失败' });
        log((res.ok ? '导航已下发 · ' : '失败 · ') + res.message);
        renderTags();
    }

    function setChip(id, text, level) {
        var el = document.getElementById(id);
        el.innerHTML = text;
        el.className = 'chip' + (level ? ' ' + level : '');
    }

    function refreshChips() {
        if (!state) {
            return;
        }
        var lv = state.lidar && state.lidar.safety === 2 ? 'danger'
            : (state.lidar && state.lidar.safety === 1 ? 'warn' : 'ok');
        setChip('chip-nav', '导航：' + (state.nav ? state.nav.naviText : '--'),
            state.nav && state.nav.ready ? 'ok' : 'danger');
        setChip('chip-lidar', '雷达：' + (state.lidar ? state.lidar.hz + ' Hz' : '--'),
            state.lidar && state.lidar.running ? 'ok' : 'warn');
        setChip('chip-safety', '避障：' + (state.lidar ? state.lidar.safetyText : '--'), lv);
        setChip('chip-batt', '电量：' + (state.battery >= 0 ? state.battery + '%' : '--'),
            state.battery >= 0 && state.battery < 20 ? 'danger' : 'ok');

        document.getElementById('bridge-info').innerHTML =
            '桥接 :' + (state.bridgePort || '--') + ' · 客户端 ' + (state.bridgeClients || 0) +
            ' · ' + (state.chassis || '');
    }

    function log(text) {
        var el = document.getElementById('log-line');
        var d = new Date();
        var hh = ('0' + d.getHours()).slice(-2);
        var mm = ('0' + d.getMinutes()).slice(-2);
        var ss = ('0' + d.getSeconds()).slice(-2);
        el.innerHTML = '[' + hh + ':' + mm + ':' + ss + '] ' + text;
    }

    // ------------------------------------------------------------ 事件循环

    var lastMessage = '';

    function tickState() {
        state = callState();
        if (state) {
            if (state.map && state.map.loaded && !mapReady) {
                loadMap(true);
            }
            if (state.message && state.message !== lastMessage) {
                lastMessage = state.message;
                log(state.message);
            }
            refreshChips();
            drawMap();
        }
        setTimeout(tickState, 600);
    }

    function tickScan() {
        if (!DEMO) {
            scan = callScan();
        } else {
            scan = demoScan();
        }
        drawRadar();
        setTimeout(tickScan, 300);
    }

    // 原生主动推送：{type:"error"|"navi"|"safety"|"arrived"|"status", text:"..."}
    window.Bot = {
        onEvent: function (evt) {
            if (!evt) {
                return;
            }
            var prefix = evt.type === 'error' ? '⚠ ' : '';
            log(prefix + (evt.text || ''));
            if (evt.type === 'map' || evt.type === 'mapChanged') {
                loadMap(true);
            }
            if (evt.type === 'arrived') {
                activeTagId = -1;
                renderTags();
            }
        }
    };

    // ------------------------------------------------------------ 按钮

    document.getElementById('btn-cancel').onclick = function () {
        if (!DEMO) {
            Native.cancel();
        }
        activeTagId = -1;
        renderTags();
        log('已取消导航');
    };

    document.getElementById('btn-estop').onclick = function () {
        if (!DEMO) {
            Native.emergencyStop();
        }
        log('紧急停止已触发');
    };

    document.getElementById('btn-mapapp').onclick = function () {
        if (DEMO) {
            log('演示模式：建图 App 只在机器人上可用');
            return;
        }
        log(parse(Native.openMapApp(), {}).message || '正在打开建图 App');
    };

    document.getElementById('btn-sim').onclick = function () {
        if (DEMO) {
            log('当前已是演示模式');
            return;
        }
        log(parse(Native.useSimulator(), {}).message || '已切换仿真底盘');
    };

    // ------------------------------------------------------------ 启动

    if (DEMO) {
        log('演示模式：未检测到 NativeBridge，使用内置模拟数据');
    }
    loadMap(true);
    tickState();
    tickScan();
})();
