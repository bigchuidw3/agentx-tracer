/**
 * 落地页动态背景 — 世界地图点阵 + 攻击态势弧线（浅色为主，跟随明暗主题）
 * - 手工大陆轮廓（经纬度多边形）栅格化为点阵，离屏 canvas 预渲染
 * - 攻击弧线：随机起止、二次贝塞尔、拖尾渐亮 + 头部光点 + 落点波纹
 * - 常驻热点节点呼吸脉冲；配色统一走品牌蓝（--primary 色系）
 * - prefers-reduced-motion 时仅静态点阵
 */
(function () {
  'use strict';

  var canvas = document.getElementById('attackMap');
  if (!canvas || !canvas.getContext) return;

  var ctx = canvas.getContext('2d');
  var reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  // ============ 大陆轮廓（[lon,lat,...]） ============
  var CONTINENTS = [
    // 北美大陆
    [-168,66,-164,67,-161,70,-156,71,-148,70,-141,70,-134,69,-128,70,-121,69,-115,69,-108,68,-102,68,-96,67,-95,64,-94,60,-92,57,-88,56,-85,55,-82,55,-79,55,-77,58,-78,61,-77,62,-73,62,-70,61,-65,60,-60,55,-56,54,-60,50,-64,48,-66,44,-70,43,-74,40,-76,36,-80,32,-81,30,-80,26,-82,27,-84,30,-88,30,-91,29,-95,29,-97,27,-97,23,-94,18,-91,19,-90,21,-87,21,-88,17,-84,15,-83,11,-80,9,-83,9,-85,11,-88,13,-92,15,-95,16,-97,17,-101,18,-105,20,-106,23,-110,24,-109,26,-112,28,-114,30,-117,33,-121,35,-124,39,-124,44,-124,48,-127,50,-128,52,-132,54,-136,58,-140,60,-146,61,-150,60,-154,59,-158,57,-162,56,-166,60,-164,63],
    // 南美
    [-77,8,-72,12,-64,11,-60,9,-52,5,-50,0,-44,-3,-38,-5,-35,-8,-38,-13,-39,-18,-41,-23,-47,-25,-52,-32,-57,-35,-62,-39,-65,-42,-66,-45,-69,-50,-68,-53,-72,-54,-74,-50,-73,-45,-72,-40,-71,-33,-70,-25,-70,-18,-76,-14,-80,-6,-81,-4,-80,0,-78,2,-77,5],
    // 格陵兰
    [-45,60,-50,63,-53,67,-55,70,-60,73,-66,75,-72,77,-66,80,-58,82,-45,83,-32,82,-22,80,-20,76,-22,72,-26,70,-32,68,-38,66,-42,63],
    // 冰岛 / 古巴
    [-22,64,-18,63,-14,64,-14,66,-18,66,-22,65],
    [-85,22,-80,23,-75,20,-78,21,-83,22],
    // 欧亚大陆
    [-9,37,-9,42,-4,44,-1,46,-4,48,-1,49,3,51,7,53,9,55,8,57,11,56,12,54,14,54,18,55,21,56,24,58,27,60,30,60,25,60,21,62,21,63,25,65,29,66,33,67,37,66,41,66,44,67,45,68,52,69,60,70,66,71,73,72,78,72,85,74,95,76,100,77,105,77,113,74,120,73,127,73,135,72,143,72,150,70,157,70,163,70,170,69,178,68,178,66,172,64,165,62,162,60,163,57,158,52,155,54,156,58,150,59,143,59,138,54,135,49,134,45,131,43,129,40,128,39,129,36,127,34,126,37,125,39,122,40,118,39,121,37,120,34,122,31,120,28,117,24,113,22,110,21,108,18,107,12,106,9,103,11,100,13,100,9,103,5,103,1,100,4,98,8,98,13,95,16,91,22,87,21,83,18,80,14,77,8,74,12,73,16,70,21,68,23,63,25,57,25,52,29,48,30,50,27,54,25,56,25,58,25,59,23,58,19,55,17,52,16,47,14,44,12,43,15,41,18,39,21,38,24,35,28,34,30,35,33,36,36,33,36,30,36,27,37,26,39,26,41,29,41,33,42,39,41,41,43,40,45,38,46,36,46,33,46,30,46,28,44,28,42,23,40,23,37,20,40,19,42,16,41,18,40,16,38,12,42,10,44,7,44,4,43,3,43,0,42,-1,38,-5,36],
    // 斯堪的纳维亚
    [5,58,4,61,8,63,12,66,16,68,21,70,27,71,31,70,28,68,24,66,21,64,19,63,18,61,17,59,12,58,10,59,8,58],
    // 英国 / 爱尔兰
    [-5,50,-6,53,-5,56,-6,58,-3,58,-2,57,-1,54,1,52,0,51,-4,50],
    [-10,52,-8,52,-6,53,-6,55,-8,55,-10,54],
    // 非洲
    [-6,35,-10,31,-15,27,-17,21,-17,15,-15,11,-12,8,-8,5,-3,5,3,6,8,4,9,3,9,0,11,-3,13,-8,12,-13,13,-18,15,-22,16,-27,18,-33,20,-35,25,-34,30,-31,33,-27,35,-22,36,-18,38,-15,40,-11,40,-6,42,-2,46,3,51,11,48,12,44,11,42,13,40,16,38,19,37,22,35,25,33,28,32,30,30,31,27,32,22,32,20,31,16,32,12,33,10,37,8,37,5,37,1,36,-3,35],
    // 马达加斯加
    [49,-13,50,-16,48,-22,45,-25,44,-22,46,-16,48,-14],
    // 日本（本州 / 北海道）
    [131,34,132,33,135,34,137,35,140,35,141,38,141,41,139,40,137,37,134,36,132,35],
    [140,43,143,42,145,43,145,45,142,45,140,44],
    // 斯里兰卡 / 菲律宾 / 印尼群岛 / 新几内亚
    [80,6,82,7,81,9,80,8],
    [120,14,121,13,122,15,122,18,120,17,120,15],
    [122,7,125,7,126,9,124,10,122,9],
    [95,5,97,4,100,1,103,-2,106,-5,104,-6,101,-3,98,1,95,3],
    [105,-6,108,-7,112,-7,114,-8,111,-8,107,-7],
    [109,1,111,4,114,5,117,4,118,1,116,-2,113,-4,110,-2,109,0],
    [119,0,121,1,123,0,122,-2,124,-3,122,-4,120,-3,120,-1],
    [131,-1,134,-1,137,-2,141,-3,145,-5,148,-8,146,-9,142,-9,138,-7,134,-4,131,-2],
    // 澳大利亚 / 塔斯马尼亚 / 新西兰
    [114,-22,113,-26,115,-33,118,-35,124,-33,129,-32,132,-32,135,-35,138,-35,140,-38,146,-39,150,-37,153,-32,153,-27,151,-24,148,-20,146,-18,143,-14,142,-11,141,-15,139,-17,136,-15,136,-12,132,-11,130,-13,129,-15,126,-14,122,-17,118,-20],
    [145,-41,148,-41,148,-43,145,-43],
    [173,-35,175,-37,176,-38,178,-38,175,-40,173,-40,172,-43,168,-46,166,-46,170,-43,172,-41]
  ];

  // 内陆海（从陆地中挖掉）
  var SEAS = [
    [28,41,33,42,39,41,41,43,38,46,34,46,30,46,28,44], // 黑海
    [50,37,53,38,53,42,51,46,48,45,47,41,49,38]        // 里海
  ];

  var LAT_TOP = 83, LAT_BOTTOM = -56, LAT_SPAN = LAT_TOP - LAT_BOTTOM;
  var STEP = 1.5; // 点阵间隔（度）

  // ============ 配色：浅色为主，统一品牌蓝，暗色主题自动切换 ============
  function palette() {
    var dark = document.documentElement.getAttribute('data-theme') === 'dark';
    return {
      dot: dark ? '148,163,184' : '101,124,170',      // 点阵：蓝灰
      accent: dark ? '96,165,250' : '79,110,247'       // 弧线/热点：品牌蓝
    };
  }

  // ============ 几何工具 ============
  function pointInPoly(lon, lat, poly) {
    var inside = false;
    for (var i = 0, j = poly.length - 2; i < poly.length; j = i, i += 2) {
      var xi = poly[i], yi = poly[i + 1], xj = poly[j], yj = poly[j + 1];
      if ((yi > lat) !== (yj > lat) &&
          lon < ((xj - xi) * (lat - yi)) / (yj - yi) + xi) {
        inside = !inside;
      }
    }
    return inside;
  }

  function isLand(lon, lat) {
    for (var s = 0; s < SEAS.length; s++) {
      if (pointInPoly(lon, lat, SEAS[s])) return false;
    }
    for (var c = 0; c < CONTINENTS.length; c++) {
      if (pointInPoly(lon, lat, CONTINENTS[c])) return true;
    }
    return false;
  }

  // ============ 布局 ============
  var W = 0, H = 0, mapW = 0, mapH = 0, offX = 0, offY = 0, cell = 0;
  var dots = [];
  var hubs = [];
  var dotsCanvas = null;

  function layout() {
    var rect = canvas.getBoundingClientRect();
    var dpr = Math.min(window.devicePixelRatio || 1, 2);
    W = Math.max(rect.width, 1);
    H = Math.max(rect.height, 1);
    canvas.width = Math.round(W * dpr);
    canvas.height = Math.round(H * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

    mapW = Math.min(W * 0.96, W - 24);
    mapH = (mapW * LAT_SPAN) / 360;
    cell = mapW / (360 / STEP);
    offX = (W - mapW) / 2;
    offY = H * 0.52 - mapH / 2;

    dots = [];
    for (var lat = LAT_TOP - STEP / 2; lat > LAT_BOTTOM; lat -= STEP) {
      for (var lon = -180 + STEP / 2; lon < 180; lon += STEP) {
        if (!isLand(lon, lat)) continue;
        dots.push({
          x: offX + ((lon + 180) / 360) * mapW,
          y: offY + ((LAT_TOP - lat) / LAT_SPAN) * mapH,
          a: 0.3 + Math.random() * 0.25
        });
      }
    }

    hubs = [];
    for (var i = 0; i < 16; i++) {
      var d = dots[Math.floor(Math.random() * dots.length)];
      hubs.push({ x: d.x, y: d.y, phase: Math.random() * Math.PI * 2, speed: 0.8 + Math.random() * 0.8 });
    }
  }

  function renderDots() {
    var p = palette();
    var dpr = Math.min(window.devicePixelRatio || 1, 2);
    dotsCanvas = document.createElement('canvas');
    dotsCanvas.width = Math.round(W * dpr);
    dotsCanvas.height = Math.round(H * dpr);
    var c2 = dotsCanvas.getContext('2d');
    c2.setTransform(dpr, 0, 0, dpr, 0, 0);

    var radius = Math.max(Math.min(cell * 0.26, 2.4), 1.1);
    c2.fillStyle = 'rgba(' + p.dot + ',1)';
    for (var i = 0; i < dots.length; i++) {
      c2.globalAlpha = dots[i].a;
      c2.beginPath();
      c2.arc(dots[i].x, dots[i].y, radius, 0, Math.PI * 2);
      c2.fill();
    }
    c2.globalAlpha = 1;
  }

  // ============ 攻击弧线 ============
  var arcs = [];
  var ripples = [];
  var impacts = [];
  var lastSpawn = 0;
  var rafPaused = false;

  function bezier(a, t) {
    var u = 1 - t;
    return {
      x: u * u * a.x0 + 2 * u * t * a.cx + t * t * a.x1,
      y: u * u * a.y0 + 2 * u * t * a.cy + t * t * a.y1
    };
  }

  function spawnArc(now) {
    var p = palette();
    var p0 = dots[Math.floor(Math.random() * dots.length)];
    // 目的地 70% 倾向热点节点（攻击瞄准核心节点的观感）
    var p1 = Math.random() < 0.7
      ? hubs[Math.floor(Math.random() * hubs.length)]
      : dots[Math.floor(Math.random() * dots.length)];
    var tries = 0;
    while (Math.hypot(p1.x - p0.x, p1.y - p0.y) < mapW * 0.22 && tries++ < 10) {
      p1 = dots[Math.floor(Math.random() * dots.length)];
    }

    var dist = Math.hypot(p1.x - p0.x, p1.y - p0.y);
    var nx = -(p1.y - p0.y) / dist;
    var ny = (p1.x - p0.x) / dist;
    if (ny > 0) { nx = -nx; ny = -ny; } // 弓向地图上方
    var lift = dist * (0.18 + Math.random() * 0.15);

    arcs.push({
      x0: p0.x, y0: p0.y, x1: p1.x, y1: p1.y,
      cx: (p0.x + p1.x) / 2 + nx * lift,
      cy: (p0.y + p1.y) / 2 + ny * lift,
      rgb: p.accent,
      t0: now,
      dur: 1600 + Math.random() * 1200
    });
  }

  function drawArc(a, now) {
    var t = (now - a.t0) / a.dur;
    if (t >= 1) return false;

    var SEG = 42;
    var tailFrom = Math.max(0, t - 0.5);
    ctx.lineCap = 'round';
    for (var i = 0; i < SEG; i++) {
      var t0 = tailFrom + (t - tailFrom) * (i / SEG);
      var t1 = tailFrom + (t - tailFrom) * ((i + 1) / SEG);
      var s0 = bezier(a, Math.min(t0, 1));
      var s1 = bezier(a, Math.min(t1, 1));
      var k = i / SEG;
      ctx.strokeStyle = 'rgba(' + a.rgb + ',' + (Math.pow(k, 1.7) * 0.8).toFixed(3) + ')';
      ctx.lineWidth = 0.8 + 1.8 * k;
      ctx.beginPath();
      ctx.moveTo(s0.x, s0.y);
      ctx.lineTo(s1.x, s1.y);
      ctx.stroke();
    }

    // 头部光点
    var head = bezier(a, t);
    ctx.save();
    ctx.shadowColor = 'rgba(' + a.rgb + ',1)';
    ctx.shadowBlur = 10;
    ctx.fillStyle = 'rgba(' + a.rgb + ',0.95)';
    ctx.beginPath();
    ctx.arc(head.x, head.y, 2.3, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();

    // 攻击源标记
    ctx.fillStyle = 'rgba(' + a.rgb + ',0.5)';
    ctx.beginPath();
    ctx.arc(a.x0, a.y0, 1.8, 0, Math.PI * 2);
    ctx.fill();
    return true;
  }

  function drawRipple(rp, now) {
    var t = (now - rp.t0) / 950;
    if (t >= 1) return false;
    ctx.strokeStyle = 'rgba(' + rp.rgb + ',' + (0.55 * (1 - t)).toFixed(3) + ')';
    ctx.lineWidth = 1.4;
    ctx.beginPath();
    ctx.arc(rp.x, rp.y, 4 + 30 * t, 0, Math.PI * 2);
    ctx.stroke();
    if (t > 0.28) {
      var t2 = (t - 0.28) / 0.72;
      ctx.strokeStyle = 'rgba(' + rp.rgb + ',' + (0.3 * (1 - t2)).toFixed(3) + ')';
      ctx.beginPath();
      ctx.arc(rp.x, rp.y, 3 + 16 * t2, 0, Math.PI * 2);
      ctx.stroke();
    }
    return true;
  }

  function drawImpact(im, now) {
    var t = (now - im.t0) / 320;
    if (t >= 1) return false;
    ctx.save();
    ctx.shadowColor = 'rgba(' + im.rgb + ',1)';
    ctx.shadowBlur = 9;
    ctx.fillStyle = 'rgba(' + im.rgb + ',' + (0.9 * (1 - t)).toFixed(3) + ')';
    ctx.beginPath();
    ctx.arc(im.x, im.y, 3 * (1 - t * 0.5), 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();
    return true;
  }

  function drawHubs(now) {
    var p = palette();
    for (var i = 0; i < hubs.length; i++) {
      var h = hubs[i];
      var pulse = 0.5 + 0.4 * Math.sin((now / 1000) * h.speed + h.phase);
      ctx.fillStyle = 'rgba(' + p.accent + ',' + (0.35 + 0.45 * pulse).toFixed(3) + ')';
      ctx.beginPath();
      ctx.arc(h.x, h.y, 1.6 + pulse * 1.2, 0, Math.PI * 2);
      ctx.fill();
    }
  }

  function frame(now) {
    ctx.clearRect(0, 0, W, H);
    if (dotsCanvas) ctx.drawImage(dotsCanvas, 0, 0, W, H);
    drawHubs(now);

    if (now - lastSpawn > 550 + Math.random() * 400) {
      var maxArcs = Math.max(3, Math.min(8, Math.round(W / 320)));
      if (arcs.length < maxArcs) {
        spawnArc(now);
        lastSpawn = now;
      }
    }

    arcs = arcs.filter(function (a) {
      var alive = drawArc(a, now);
      if (!alive) {
        ripples.push({ x: a.x1, y: a.y1, t0: now, rgb: a.rgb });
        impacts.push({ x: a.x1, y: a.y1, t0: now, rgb: a.rgb });
      }
      return alive;
    });
    ripples = ripples.filter(function (rp) { return drawRipple(rp, now); });
    impacts = impacts.filter(function (im) { return drawImpact(im, now); });

    if (document.hidden) rafPaused = true;
    else requestAnimationFrame(frame);
  }

  function drawStatic() {
    ctx.clearRect(0, 0, W, H);
    if (dotsCanvas) ctx.drawImage(dotsCanvas, 0, 0, W, H);
    var p = palette();
    for (var i = 0; i < hubs.length; i++) {
      ctx.fillStyle = 'rgba(' + p.accent + ',0.55)';
      ctx.beginPath();
      ctx.arc(hubs[i].x, hubs[i].y, 2.2, 0, Math.PI * 2);
      ctx.fill();
    }
  }

  function rebuild() {
    layout();
    renderDots();
    if (reduceMotion) drawStatic();
  }

  rebuild();

  if (!reduceMotion) {
    requestAnimationFrame(frame);
    document.addEventListener('visibilitychange', function () {
      if (!document.hidden && rafPaused) {
        rafPaused = false;
        arcs = [];
        ripples = [];
        impacts = [];
        lastSpawn = performance.now();
        requestAnimationFrame(frame);
      }
    });
  }

  // 主题切换时重绘点阵（弧线配色下一帧自动生效）
  new MutationObserver(function () {
    renderDots();
    if (reduceMotion) drawStatic();
  }).observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });

  var resizeTimer = null;
  window.addEventListener('resize', function () {
    window.clearTimeout(resizeTimer);
    resizeTimer = window.setTimeout(rebuild, 150);
  });
})();
