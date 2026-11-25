(function () {
  'use strict';

  var app = document.getElementById('app');
  var csrf = '';
  var timer = null;
  var tab = 'overview';
  var content;

  // ---------- helpers ----------
  function el(tag, attrs, kids) {
    var n = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) {
      var v = attrs[k];
      if (k === 'class') n.className = v;
      else if (k === 'text') n.textContent = v;
      else if (k.indexOf('on') === 0) n.addEventListener(k.slice(2), v);
      else if (v !== false && v != null) n.setAttribute(k, v === true ? '' : v);
    });
    (kids || []).forEach(function (c) {
      if (c == null) return;
      n.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
    });
    return n;
  }
  function clear(n) { while (n.firstChild) n.removeChild(n.firstChild); }
  function badge(kind, text) { return el('span', { class: 'badge ' + kind, text: text }); }
  function section(title) { return el('h2', { class: 'section-title', text: title }); }

  function fmtBytes(n) {
    var u = ['B', 'KiB', 'MiB', 'GiB', 'TiB'], i = 0;
    n = Number(n) || 0;
    while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
    return (i === 0 ? n : n.toFixed(n < 10 ? 2 : 1)) + ' ' + u[i];
  }
  function fmtAgo(s) {
    if (s == null) return 'never';
    if (s < 60) return s + 's ago';
    if (s < 3600) return Math.floor(s / 60) + 'm ago';
    if (s < 86400) return Math.floor(s / 3600) + 'h ' + Math.floor((s % 3600) / 60) + 'm ago';
    return Math.floor(s / 86400) + 'd ago';
  }

  function api(method, path, body) {
    var opt = { method: method, credentials: 'same-origin', headers: { 'X-CSRF-Token': csrf } };
    if (body !== undefined) {
      opt.headers['Content-Type'] = 'application/json';
      opt.body = JSON.stringify(body);
    }
    return fetch(path, opt).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (d) {
        if (r.status === 401 && path !== '/api/login') { showLogin(); }
        return { status: r.status, ok: r.ok, data: d };
      });
    }, function () {
      return { status: 0, ok: false, data: { error: 'Network error' } };
    });
  }

  function stopTimer() { if (timer) { clearInterval(timer); timer = null; } }
  function autoRefresh(fn, ms) { timer = setInterval(function () { if (!document.hidden) fn(); }, ms); }

  function copyText(text, btn) {
    var done = function () { var o = btn.textContent; btn.textContent = 'Copied'; setTimeout(function () { btn.textContent = o; }, 1500); };
    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard.writeText(text).then(done, function () { fallbackCopy(text); done(); });
    } else { fallbackCopy(text); done(); }
  }
  function fallbackCopy(text) {
    var t = el('textarea', { 'aria-hidden': 'true' });
    t.value = text; t.style.position = 'fixed'; t.style.opacity = '0';
    document.body.appendChild(t); t.select();
    try { document.execCommand('copy'); } catch (e) { /* ignore */ }
    document.body.removeChild(t);
  }

  // generic modal; returns {close, body}
  function openModal(title, bodyNodes, opts) {
    opts = opts || {};
    var bg = el('div', { class: 'modal-bg' });
    var close = function () { if (bg.parentNode) document.body.removeChild(bg); document.removeEventListener('keydown', onKey); };
    var onKey = function (e) { if (e.key === 'Escape') close(); };
    var modal = el('div', { class: 'modal' + (opts.danger ? ' danger' : ''), role: 'dialog', 'aria-modal': 'true', 'aria-label': title },
      [el('h2', { text: title })].concat(bodyNodes));
    bg.appendChild(modal);
    bg.addEventListener('click', function (e) { if (e.target === bg) close(); });
    document.addEventListener('keydown', onKey);
    document.body.appendChild(bg);
    return { close: close, modal: modal };
  }

  // ---------- login ----------
  function showLogin(message) {
    stopTimer(); csrf = ''; clear(app);
    var pw = el('input', { type: 'password', id: 'pw', autocomplete: 'current-password', placeholder: 'Password', required: true, autofocus: true });
    var err = el('div', { class: 'msg bad', hidden: true });
    var btn = el('button', { class: 'btn primary', type: 'submit', text: 'Sign in' });
    var form = el('form', { class: 'panel' }, [
      el('h1', { text: 'WireGuard Admin' }),
      el('div', { class: 'muted', text: 'Sign in to manage the VPN.' }),
      pw, err, btn
    ]);
    if (message) { err.textContent = message; err.hidden = false; }
    form.addEventListener('submit', function (e) {
      e.preventDefault(); btn.disabled = true; err.hidden = true;
      api('POST', '/api/login', { password: pw.value }).then(function (r) {
        btn.disabled = false;
        if (r.ok) { csrf = r.data.csrf; showMain(); }
        else { err.textContent = r.data.error || 'Sign-in failed'; err.hidden = false; pw.select(); }
      });
    });
    app.appendChild(el('div', { class: 'wrap' }, [el('div', { class: 'login' }, [form])]));
  }

  // ---------- shell ----------
  var TABS = [['overview', 'Overview'], ['devices', 'Devices'], ['service', 'Service'], ['settings', 'Settings']];
  var RENDER = {};

  function showMain() {
    clear(app);
    var nav = el('nav', { class: 'tabs', role: 'tablist' });
    TABS.forEach(function (t) {
      var b = el('button', { role: 'tab', 'aria-selected': t[0] === tab ? 'true' : 'false', text: t[1], onclick: function () { selectTab(t[0]); } });
      b.dataset.tab = t[0];
      if (t[0] === 'settings') b.classList.add('tab-danger');
      nav.appendChild(b);
    });
    content = el('main');
    var out = el('button', { class: 'btn', text: 'Sign out', onclick: function () {
      api('POST', '/api/logout', {}).then(function () { showLogin(); });
    } });
    app.appendChild(el('div', { class: 'wrap' }, [
      el('header', { class: 'top' }, [
        el('div', {}, [el('h1', { text: 'WireGuard Admin' }), el('div', { class: 'sub', text: 'Internet exit server (overlay 10.99.0.0/24)' })]),
        out
      ]), nav, content
    ]));
    selectTab(tab);
  }

  function selectTab(name) {
    tab = name; stopTimer();
    Array.prototype.forEach.call(document.querySelectorAll('nav.tabs button'), function (b) {
      b.setAttribute('aria-selected', b.dataset.tab === name ? 'true' : 'false');
    });
    clear(content);
    RENDER[name]();
  }

  function isRunning(s) { return s.service.active === 'active' && s.interface.up; }

  // ---------- overview (read-only) ----------
  RENDER.overview = function () {
    var banner = el('div');
    var network = el('div', { class: 'grid' });
    var activity = el('div', { class: 'grid' });
    var software = el('div', { class: 'grid' });
    content.appendChild(banner);
    content.appendChild(section('Network')); content.appendChild(network);
    content.appendChild(section('Activity')); content.appendChild(activity);
    content.appendChild(section('Software')); content.appendChild(software);

    function card(title, big, meta, extra) {
      return el('div', { class: 'card' }, [el('h3', { text: title }), big, meta ? el('div', { class: 'meta', text: meta }) : null, extra || null]);
    }

    function paint(s) {
      clear(banner); clear(network); clear(activity); clear(software);
      var running = isRunning(s), failed = s.service.active === 'failed';
      var bar = el('div', { class: 'banner ' + (running ? 'ok' : (failed ? 'bad' : 'warn')) }, [
        badge(running ? 'ok' : (failed ? 'bad' : 'warn'), running ? 'Running' : (failed ? 'Failed' : 'Stopped')),
        el('span', { text: running ? 'The VPN service is up' + (s.service.since ? ' since ' + s.service.since : '') + '.'
                                    : 'The VPN service is not running. No device can connect.' })
      ]);
      if (!running) bar.appendChild(el('button', { class: 'btn', text: 'Go to Settings', onclick: function () { selectTab('settings'); } }));
      banner.appendChild(bar);

      network.appendChild(card('Interface wg0', el('div', { class: 'big', text: s.interface.address || '—' }),
        (s.interface.up ? 'up' : 'down') + ' · MTU ' + (s.interface.mtu || '—')));
      network.appendChild(card('Public endpoint', el('div', { class: 'big small', text: s.endpoint }),
        'UDP ' + (s.interface.listen_port || '—') + ' · direct WireGuard connections'));
      var copyBtn = el('button', { class: 'btn slim', text: 'Copy' });
      copyBtn.addEventListener('click', function () { copyText(s.interface.public_key, copyBtn); });
      network.appendChild(card('Server public key', el('div', { class: 'mono', text: s.interface.public_key || '—' }), null,
        s.interface.public_key ? el('div', { class: 'cardfoot' }, [copyBtn]) : null));

      activity.appendChild(card('Devices connected',
        el('div', { class: 'big', text: s.peers.connected + ' / ' + s.peers.total }), 'handshake within the last 3 minutes'));
      activity.appendChild(card('Traffic since start',
        el('div', { class: 'big small', text: '↓ ' + fmtBytes(s.traffic.rx) + '   ↑ ' + fmtBytes(s.traffic.tx) }), 'as seen by this server'));
      var pct = Math.min(100, Math.round(100 * s.peers.managed / s.peers.pool_size));
      var barEl = el('div', { class: 'bar', role: 'progressbar', 'aria-valuenow': String(pct), 'aria-valuemin': '0', 'aria-valuemax': '100' });
      var fill = el('div', { class: 'fill' }); fill.style.width = pct + '%'; barEl.appendChild(fill);
      activity.appendChild(card('Auto-assign pool',
        el('div', { class: 'big', text: s.peers.managed + ' / ' + s.peers.pool_size }), '10.99.0.100 – 10.99.0.254', barEl));

      software.appendChild(card('Implementation', el('div', { class: 'big small', text: s.implementation }), 'runs inside the Linux kernel'));
      software.appendChild(card('Service unit', el('div', { class: 'big small', text: 'wg-quick@wg0' }),
        (s.service.enabled || 'unknown') + ' at boot · web UI ' + s.web.active));
    }

    function load() {
      api('GET', '/api/status').then(function (r) {
        if (r.ok) paint(r.data);
        else if (r.status !== 401) { clear(banner); banner.appendChild(el('div', { class: 'msg bad', text: r.data.error || 'Cannot load status' })); }
      });
    }
    load(); autoRefresh(load, 5000);
  };

  var VIA_LABEL = { direct: 'Direct (WireGuard)', speeder: 'Via UDPspeeder (FEC)', quic: 'FEC over QUIC' };

  // ---------- devices ----------
  RENDER.devices = function () {
    var msg = el('div');
    var name = el('input', { type: 'text', placeholder: 'Device name (e.g. laptop)', maxlength: '32', autocomplete: 'off', required: true, 'aria-label': 'Device name' });
    var addr = el('input', { type: 'text', placeholder: 'Address (optional)', maxlength: '13', autocomplete: 'off', inputmode: 'numeric', 'aria-label': 'Static address (optional)' });
    var addBtn = el('button', { class: 'btn primary', type: 'submit', text: 'Add device' });
    var hint = el('div', { class: 'muted hint' });
    var form = el('form', { class: 'row' }, [name, addr, addBtn]);
    var pool = el('span', { class: 'muted' });
    var tbody = el('tbody');
    var table = el('div', { class: 'tablewrap' }, [el('table', {}, [
      el('thead', {}, [el('tr', {}, ['Name', 'Address', 'Status', 'Last handshake', 'Transfer (↓ / ↑)', ''].map(function (h) { return el('th', { text: h }); }))]),
      tbody])]);
    var addPanel = el('div', { class: 'panel' }, [
      el('h2', { text: 'Add a device' }),
      el('div', { class: 'muted', text: 'Creates a key pair and a client config that sends all of the device\'s internet traffic through this server. The config is kept on this server, so you can open it again from the list below.' }),
      form, hint, msg]);
    addPanel.children[2].style.marginTop = '12px';
    content.appendChild(addPanel);
    content.appendChild(el('div', { class: 'panel' }, [el('div', { class: 'row between' }, [el('h2', { text: 'Devices' }), pool]), table]));

    form.addEventListener('submit', function (e) {
      e.preventDefault(); clear(msg);
      var body = { name: name.value.trim() };
      var a = addr.value.trim();
      if (a) {
        if (/^[0-9]{1,3}$/.test(a)) a = '10.99.0.' + a;            // allow just the last octet
        body.ip = a;
      }
      addBtn.disabled = true;
      api('POST', '/api/peers', body).then(function (r) {
        addBtn.disabled = false;
        if (r.ok) { name.value = ''; addr.value = ''; showConfig(r.data, true); load(); }
        else msg.appendChild(el('div', { class: 'msg bad', text: r.data.error || 'Failed to add device' }));
      });
    });

    function load() {
      api('GET', '/api/peers').then(function (r) {
        if (!r.ok) { if (r.status !== 401) { clear(tbody); msg.appendChild(el('div', { class: 'msg bad', text: r.data.error || 'Cannot load devices' })); } return; }
        pool.textContent = 'auto pool ' + r.data.pool;
        hint.textContent = 'Leave the address empty to use the next free one' + (r.data.next_free ? ' (' + r.data.next_free + ')' : '') +
          '. For a static address, enter any free 10.99.0.2 – 10.99.0.254 (a bare number like 20 works too).';
        clear(tbody);
        r.data.peers.forEach(function (p) {
          var st;
          if (!p.live) st = badge('bad', 'Not applied');
          else if (p.handshake_ago == null) st = badge('mute', 'Never connected');
          else if (p.handshake_ago < 180) st = badge('ok', 'Connected');
          else st = badge('warn', 'Idle');
          var act = el('td');
          if (p.managed) {
            if (p.has_config) act.appendChild(el('button', { class: 'btn slim', text: 'Config', onclick: function () { viewConfig(p.name, 'auto'); } }));
            else act.appendChild(el('span', { class: 'muted', title: 'Created before configs were kept on the server', text: 'no saved config' }));
            act.appendChild(document.createTextNode(' '));
            act.appendChild(el('button', { class: 'btn danger slim', text: 'Delete', onclick: function () { confirmDelete(p); } }));
          } else act.appendChild(badge('mute', 'system'));
          tbody.appendChild(el('tr', {}, [
            el('td', { text: p.name }), el('td', { class: 'num', text: p.ip || '—' }), el('td', {}, [st]),
            el('td', { text: fmtAgo(p.handshake_ago) }),
            el('td', { class: 'num', text: fmtBytes(p.rx) + ' / ' + fmtBytes(p.tx) }), act]));
        });
      });
    }

    function confirmDelete(p) {
      var go = el('button', { class: 'btn danger', text: 'Delete device' });
      var cancel = el('button', { class: 'btn', text: 'Cancel' });
      var m = openModal('Delete "' + p.name + '"?', [
        el('div', { class: 'msg warn', text: 'This device (' + p.ip + ') loses access immediately. Its config stops working and the saved copy is deleted.' }),
        el('div', { class: 'row end' }, [cancel, go])
      ], { danger: true });
      cancel.addEventListener('click', m.close);
      go.addEventListener('click', function () {
        go.disabled = true;
        api('DELETE', '/api/peers/' + encodeURIComponent(p.name)).then(function (d) {
          m.close(); clear(msg);
          if (!d.ok) msg.appendChild(el('div', { class: 'msg bad', text: d.data.error || 'Failed to delete' }));
          load();
        });
      });
    }
    load(); autoRefresh(load, 5000);
  };

  // ---------- per-OS setup guide for the client program (speederv2 for FEC; nothing to run for a direct tunnel) ----------
  // cmds = { speeder: 'speederv2 -c ...' | null }
  function setupGuide(cmds, conf, dev) {
    var ua = navigator.userAgent || '';
    if (!cmds) cmds = { speeder: null };
    if (typeof cmds === 'string') cmds = { speeder: cmds };
    var direct = !cmds.speeder;                    // plain WireGuard: nothing to run besides the tunnel itself
    var sArgs = cmds.speeder ? cmds.speeder.replace(/^speederv2\s+/, '') : null;
    // PowerShell breaks "-l127.0.0.1:51821" at the dot and "-f1:3,2:4" at the commas, so for Windows
    // every value gets its own word and double quotes (cmd.exe accepts that form too).
    function winQuote(args) {
      return args.replace(/(^|\s)-([lrf])(\S+)/g, function (m, sp, f, v) { return sp + '-' + f + ' "' + v + '"'; })
                 .replace(/(^|\s)-k (\S+)/, function (m, sp, v) { return sp + '-k "' + v + '"'; });
    }
    var win, lin, mac;
    if (direct) {
      win = [['text', 'Install WireGuard for Windows (wireguard.com/install), choose "Import tunnel(s) from file", pick ' + conf + ', then Activate.']];
      lin = [['text', 'Install WireGuard (the wireguard-tools package of your distribution), then start the tunnel (wg-quick down to stop):'],
             ['code', 'sudo wg-quick up ./' + conf]];
      mac = [['text', 'Install WireGuard from the App Store, choose "Import tunnel(s) from file", pick ' + conf + ', then Activate.']];
    } else {
      win = [['text', 'Install WireGuard for Windows (wireguard.com/install).'],
             ['text', 'Download and unpack speederv2_windows.zip (github.com/wangyu-/UDPspeeder/releases).'],
             ['text', 'In PowerShell, run it and keep it open (keep the quotes):'],
             ['code', '.\\speederv2.exe ' + winQuote(sArgs)],
             ['text', 'In WireGuard: Import tunnel(s) from file, pick ' + conf + ', Activate.']];
      // Linux: run the unpacked binary directly, nothing is installed
      lin = [['text', 'Install WireGuard:'], ['code', 'sudo apt install wireguard-tools resolvconf'],
             ['text', 'Download and unpack speederv2_binaries.tar.gz (github.com/wangyu-/UDPspeeder/releases), then in that folder run it and keep it open:'],
             ['code', './speederv2_amd64 ' + sArgs],
             ['text', 'Start the tunnel (wg-quick down to stop):'], ['code', 'sudo wg-quick up ./' + conf]];
      mac = [['text', 'Not tested here. Install WireGuard from the App Store.'],
             ['text', 'Build speederv2:'], ['code', 'git clone https://github.com/wangyu-/UDPspeeder && cd UDPspeeder && make mac'],
             ['text', 'In Terminal, run it and keep it open:'], ['code', './speederv2_mac ' + sArgs],
             ['text', 'In WireGuard: Import tunnel(s) from file, pick ' + conf + ', Activate.']];
    }
    // Android: the WG FEC app takes one pasted "app string" that holds the WireGuard config and the speederv2 command
    var appStep = ['code', 'Preparing…', 'wrap'];
    var droid = [['text', 'Install the WG FEC app on the phone.'],
                 ['text', 'Copy this app string (it contains the private key, so keep it private), then in the app tap Paste and confirm the import:'], appStep,
                 ['text', cmds.speeder ? 'Tap Connect and allow the VPN request. The app starts speederv2 itself, so there is nothing else to run.' : 'Tap Connect and allow the VPN request. This is a plain WireGuard tunnel without FEC. The official WireGuard app also works: scan the QR code above.']];
    var guides = { windows: { label: 'Windows', steps: win }, linux: { label: 'Linux', steps: lin }, macos: { label: 'macOS', steps: mac }, android: { label: 'Android', steps: droid } };
    var order = ['windows', 'linux', 'macos', 'android'];
    var cur = /Android/i.test(ua) ? 'android' : (/Windows/i.test(ua) ? 'windows' : (/Mac/i.test(ua) ? 'macos' : 'linux'));
    var wrap = el('div', { class: 'guide' });
    var tabs = el('div', { class: 'guide-tabs' });
    var body = el('div');
    wrap.appendChild(el('div', { class: 'section-title', text: 'Set up this device' }));
    wrap.appendChild(el('div', { class: 'muted', text: (direct ? 'Pick your system.' : 'Start speederv2 first, then the tunnel.') + ' On Android use the Android tab; iPhones cannot run these programs.' }));
    wrap.appendChild(tabs); wrap.appendChild(body);
    function paint() {
      clear(tabs); clear(body);
      order.forEach(function (k) {
        tabs.appendChild(el('button', { class: 'btn slim' + (k === cur ? ' on' : ''), type: 'button', text: guides[k].label, 'aria-pressed': k === cur ? 'true' : 'false', onclick: function () { cur = k; paint(); } }));
      });
      var ol = el('ol'), li = null;
      guides[cur].steps.forEach(function (st) {
        if (st[0] === 'text' || !li) {                       // a command belongs to the step above it
          li = el('li');
          ol.appendChild(li);
        }
        if (st[0] === 'text') li.appendChild(el('span', { text: st[1] }));
        else {
          li.appendChild(el('pre', { text: st[1], class: st[2] === 'wrap' ? 'wrapcode' : '' }));
          var b = el('button', { class: 'btn slim', type: 'button', text: 'Copy' });
          b.addEventListener('click', function () { copyText(st[1], b); });
          li.appendChild(b);
        }
      });
      body.appendChild(ol);
    }
    paint();
    if (appStep && dev) appLink(dev).then(function (l) { appStep[1] = l; paint(); });
    return wrap;
  }

  // ---------- show a device's config (right after creating it, or again later) ----------
  function viewConfig(name, via) {
    function go(v) {
      api('GET', '/api/peers/' + encodeURIComponent(name) + '/config' + (v ? '?via=' + (v === 'quic' ? 'speeder' : v) : '')).then(function (r) {
        if (r.ok) { if (v === 'quic') r.data.qd = true; return showConfig(r.data, false); }
        var m = openModal('Cannot show the config', [
          el('div', { class: 'msg bad', text: r.data.error || 'Failed to load the config' }),
          el('div', { class: 'row end' }, [el('button', { class: 'btn', text: 'Close', onclick: function () { m.close(); } })])
        ]);
      });
    }
    go(via === 'auto' ? null : via);                         // no choice given: the server opens the mode saved for this device
  }

  // ---------- app string for the Android app "WG FEC" ----------
  // Link format: wgfec://1/<base64url(JSON)>  or  wgfec://1z/<base64url(zlib(JSON))> (used when the browser can compress).
  // JSON: {"v":1,"name":"...","wg":"<WireGuard config>","sp":"<speederv2 client command>","tiers":[...],"probe":62933,"quic":{"p":443,"s":"name"}}; "sp" is absent for a direct tunnel.
  // "tiers" (optional): the FEC levels of the server, weakest first: {n: name, l: loss it is for (%), p: server UDP port, u/t/i: uplink FEC table, wait, interleave, d/s: decode / socket buffer}.
  function b64url(bytes) {
    var s = '';
    for (var i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }
  function appLink(d) {
    var o = { v: 1, name: d.name, wg: d.config };
    var c = d.cmds || {};
    var sp = c.speeder || d.speeder_cmd;
    if (typeof sp === 'string' && sp) o.sp = sp;
    if (typeof d.probe === 'number') o.probe = d.probe;                                // UDP port of the server's link-test probe
    if (d.quic && typeof d.quic.p === 'number' && typeof d.quic.s === 'string') { o.quic = d.quic; if (d.qd && sp) o.qd = 1; }   // the server accepts the FEC tunnel dressed up as QUIC: {p: port, s: name}; qd: the app starts with it on
    if (sp && Array.isArray(d.tiers) && d.tiers.length) o.tiers = d.tiers;       // FEC levels the app can switch between
    var raw = new TextEncoder().encode(JSON.stringify(o));
    var plain = 'wgfec://1/' + b64url(raw);
    if (typeof CompressionStream === 'undefined') return Promise.resolve(plain);
    return new Response(new Blob([raw]).stream().pipeThrough(new CompressionStream('deflate'))).arrayBuffer()
      .then(function (buf) { var z = 'wgfec://1z/' + b64url(new Uint8Array(buf)); return z.length < plain.length ? z : plain; }, function () { return plain; });
  }
  function showConfig(d, created) {
    var curVia = d.qd ? 'quic' : d.via || (d.speeder ? 'speeder' : 'direct');
    var img = el('img', { class: 'qr', alt: 'QR code of the client config' });
    try {
      var qr = qrcode(0, 'M'); qr.addData(d.config); qr.make();
      img.src = qr.createDataURL(4, 4);
    } catch (e) { img = el('div', { class: 'msg warn', text: 'Config too large for a QR code; use the file or copy it.' }); }
    var copyBtn = el('button', { class: 'btn', text: 'Copy' });
    copyBtn.addEventListener('click', function () { copyText(d.config, copyBtn); });
    var dl = el('button', { class: 'btn primary', text: 'Download .conf', onclick: function () {
      var url = URL.createObjectURL(new Blob([d.config], { type: 'text/plain' }));
      var a = el('a', { href: url, download: d.name + '.conf' });
      document.body.appendChild(a); a.click(); document.body.removeChild(a);
      setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
    } });
    var unsaved = created && d.stored === false;
    var head;
    if (unsaved) head = el('div', { class: 'msg bad', text: 'This config could not be saved on the server. This is the only time the private key is shown, so save it now. Address: ' + d.ip });
    else if (created) head = el('div', { class: 'msg ok', text: 'Device created. The config is kept on this server; open it again any time from the Devices list. Address: ' + d.ip });
    else head = el('div', { class: 'msg warn', text: 'This config contains the device\'s private key. Keep it private. Address: ' + d.ip });
    var qnote = d.qd ? el('div', { class: 'msg', text: 'FEC over QUIC: the Android app sends the FEC tunnel to UDP ' + (d.quic ? d.quic.p : '') + ' as QUIC-looking packets; use the app string below (it switches the disguise on). The config and command for a computer are the same as for FEC, because only the app can do the disguise.' }) : null;
    var sw = el('div', { class: 'row' });
    var m = openModal(created ? 'Device "' + d.name + '" created' : 'Device "' + d.name + '"', [head, sw].concat(qnote ? [qnote] : []).concat([img, el('pre', { text: d.config })]).concat([
      setupGuide(d.cmds || d.speeder_cmd || null, d.name + '.conf', d)
    ]).concat([
      el('div', { class: 'row end' }, [dl, copyBtn, el('button', { class: 'btn', text: 'Close', onclick: function () { m.close(); } })])
    ]));
    if (unsaved) return;
    api('GET', '/api/speeder').then(function (r) {            // offer the other connection type when this server has both
      if (!r.ok || !r.data.enabled || r.data.active !== 'active') return;
      var kinds = (r.data.required ? [] : ['direct']).concat(Object.keys(r.data.modes || {}));
      if (r.data.quic && r.data.quic.state === 'active' && kinds.indexOf('speeder') >= 0) kinds.push('quic');
      if (kinds.length < 2) return;
      sw.appendChild(el('span', { class: 'muted', text: 'Config for:' }));
      kinds.map(function (k) { return [k, VIA_LABEL[k]]; }).forEach(function (o) {
        var b = el('button', { class: 'btn slim' + (o[0] === curVia ? ' on' : ''), type: 'button', text: o[1] });
        if (o[0] !== curVia) b.addEventListener('click', function () {
          api('POST', '/api/peers/' + encodeURIComponent(d.name) + '/mode', { via: o[0] === 'quic' ? 'speeder' : o[0] }).then(function () { m.close(); viewConfig(d.name, o[0]); });   // remember the choice (the QUIC disguise is the FEC type, switched on in the app)
        });
        sw.appendChild(b);
      });
    });
  }

  // ---------- logs (a panel on the Service page) ----------
  function buildLogs() {
    var src = el('select', { 'aria-label': 'Log source' }, [['wg-service', 'WireGuard service (systemd)'], ['speeder', 'UDPspeeder (FEC)'], ['web-ui', 'This web UI']].map(function (o) { return el('option', { value: o[0], text: o[1] }); }));
    var lines = el('select', { 'aria-label': 'Line count' }, ['100', '200', '500'].map(function (n) { return el('option', { value: n, text: n + ' lines' }); }));
    var auto = el('input', { type: 'checkbox', id: 'auto' });
    var pre = el('pre', { class: 'logs', text: 'Loading…' });
    var msg = el('div');
    var panel = el('div', { class: 'panel' }, [
      el('h2', { text: 'Logs' }),
      el('div', { class: 'row' }, [src, lines, el('label', { for: 'auto', class: 'inline' }, [auto, ' Auto-refresh']),
        el('button', { class: 'btn', text: 'Refresh', onclick: function () { load(); } })]), msg, pre]);
    pre.style.marginTop = '12px';
    src.addEventListener('change', load); lines.addEventListener('change', load);
    function load() {
      api('GET', '/api/logs?source=' + encodeURIComponent(src.value) + '&lines=' + encodeURIComponent(lines.value)).then(function (r) {
        clear(msg);
        if (!r.ok) { if (r.status !== 401) msg.appendChild(el('div', { class: 'msg bad', text: r.data.error || 'Cannot load logs' })); return; }
        var atBottom = pre.scrollTop + pre.clientHeight >= pre.scrollHeight - 20;
        pre.textContent = r.data.lines.length ? r.data.lines.join('\n') : '(no entries)';
        if (atBottom || pre.scrollTop === 0) pre.scrollTop = pre.scrollHeight;
      });
    }
    load();
    return { panel: panel, load: load, auto: auto };
  }

  // ---------- FEC levels editor ----------
  // FEC_PRESETS: the defaults and notes of the five levels (the same values as PRESETS in wg-admin).
  var FEC_PRESETS = [
    { id: 'aggressive', name: 'Aggressive', loss: '3%',
      down: '1:1,2:1,5:1,10:1,20:2,40:3', td: 10, id_: 0, up: '1:1,2:1,4:1,8:1,16:1,32:1', tu: 10, iu: 0, db: 2000, sb: 1024,
      note: 'Least extra traffic. Repairs light random loss (about 3% or less); heavy loss gets through.' },
    { id: 'light', name: 'Light', loss: '10%',
      down: '1:2,2:2,5:3,10:4,20:7,40:10', td: 15, id_: 0, up: '1:1,2:1,4:1,8:1,16:2,32:3', tu: 10, iu: 0, db: 2000, sb: 1024,
      note: 'Good for ordinary lossy links (up to about 10%).' },
    { id: 'balanced', name: 'Balanced', loss: '25%',
      down: '1:3,2:5,4:6,8:9,16:14,32:22,48:30', td: 20, id_: 2, up: '1:1,2:1,4:1,8:2,16:3,32:5', tu: 10, iu: 0, db: 2000, sb: 1024,
      note: 'Handles bad links (up to about 25%) at moderate cost.' },
    { id: 'conservative', name: 'Conservative', loss: '40%',
      down: '1:6,2:8,3:10,5:13,8:16,12:21,16:25,24:33,32:41,48:55,64:69', td: 30, id_: 5, up: '1:1,2:2,4:2,8:3,16:5,32:8', tu: 10, iu: 0, db: 6000, sb: 2048,
      note: 'For very lossy links (up to about 40%). Roughly 2 to 7 times the traffic.' },
    { id: 'maximum', name: 'Maximum', loss: '55%',
      down: '1:10,2:13,3:16,5:21,8:28,12:36,16:43,24:57,32:70,48:96,64:121,80:145', td: 40, id_: 5, up: '1:2,2:2,4:3,8:4,16:6,32:9', tu: 10, iu: 0, db: 12000, sb: 4096,
      note: 'Worst case (up to about 55% loss). Most protection, 3 to 11 times the traffic, about 40 to 60 ms extra delay.' }
  ];
  var LOGF = [0];
  for (var li = 1; li <= 260; li++) LOGF.push(LOGF[li - 1] + Math.log(li));
  function groupFail(x, y, p) {                       // chance that fewer than x of the x+y packets arrive
    var n = x + y, sum = 0;
    if (p <= 0) return 0;
    if (p >= 1) return 1;
    for (var k = y + 1; k <= n; k++) sum += Math.exp(LOGF[n] - LOGF[k] - LOGF[n - k] + k * Math.log(p) + (n - k) * Math.log(1 - p));
    return Math.min(1, sum);
  }
  function parseFec(str) {
    if (!/^[0-9]+:[0-9]+(,[0-9]+:[0-9]+)*$/.test(str) || str.length > 200) return null;
    var t = str.split(',').map(function (g) { var a = g.split(':'); return [parseInt(a[0], 10), parseInt(a[1], 10)]; });
    if (t.length > 24) return null;
    for (var i = 0; i < t.length; i++) {
      if (t[i][0] < 1 || t[i][0] + t[i][1] > 255 || (i && t[i][0] <= t[i - 1][0])) return null;
    }
    return t;
  }
  function pctText(frac) {
    var v = frac * 100;
    if (v === 0) return '0 %';
    if (v >= 0.01) return v.toFixed(v >= 1 ? 1 : 2) + ' %';
    return v.toExponential(1) + ' %';
  }
  function fecPreview(str, lossPct, withNote) {
    var t = parseFec(str), box = el('div', { class: 'fec-prev' });
    if (!t) { box.appendChild(el('div', { class: 'msg bad', text: 'Not valid. Use data:parity pairs with increasing data, like 1:3,2:4,10:10 (data + parity at most 255, up to 24 pairs).' })); return box; }
    var p = Math.max(0, Math.min(99, lossPct)) / 100;
    var rows = t.map(function (g) {
      return el('tr', {}, [el('td', { text: g[0] + ':' + g[1] }), el('td', { class: 'num', text: '×' + ((g[0] + g[1]) / g[0]).toFixed(2) }),
        el('td', { class: 'num', text: pctText(groupFail(g[0], g[1], p) * p) })]);
    });
    box.appendChild(el('table', {}, [el('thead', {}, [el('tr', {}, ['Entry (data:parity)', 'Bandwidth', 'Residual loss at ' + (p * 100).toFixed(0) + '% link loss'].map(function (h) { return el('th', { text: h }); }))]), el('tbody', {}, rows)]));
    if (withNote) box.appendChild(el('div', { class: 'muted hint', text: 'Bandwidth: traffic sent compared with plain forwarding. Residual loss: share of packets still lost after repair, assuming independent random loss. Only the preview uses the assumed loss; it is not saved.' }));
    return box;
  }

  var pendingFlash = null;
  // FEC levels: each level is a set of FEC parameters meant for a certain amount of link loss, served on its own UDP port.
  // The default level is served on the original port and is what the client commands of the other platforms use.
  function buildFecEditor(d) {
    if (!d.levels) return el('div', { class: 'msg warn', text: 'FEC levels are not set up on this server yet. Run server/scripts/install-levels.sh on it, then reload this page.' });
    var draft = {}, saved = {}, byId = {}, current = d.default_level, defaultId = d.default_level, mtu = String(d.fec_mtu || 1250);
    var loss = { d: 0, u: 0 }, inp = {}, msg = el('div');
    d.levels.forEach(function (l) {
      byId[l.id] = l;
      draft[l.id] = { down: l.down.fec, td: String(l.down.timeout), id_: String(l.down.interval), up: l.up.fec, tu: String(l.up.timeout), iu: String(l.up.interval), db: String(l.db), sb: String(l.sb) };
      saved[l.id] = JSON.stringify(draft[l.id]);
    });
    var title = function (t) { return el('div', { class: 'section-title', text: t }); };
    var label = function (l) { return l.name + (l.loss ? ' (up to about ' + l.loss + '%)' : ''); };
    var presetOf = function (id) { var r = null; FEC_PRESETS.forEach(function (ps) { if (ps.id === id) r = ps; }); return r; };

    var defSel = el('select', { 'aria-label': 'Default level' }, d.levels.map(function (l) { return el('option', { value: l.id, text: label(l) }); }));
    defSel.value = defaultId;
    defSel.addEventListener('change', function () { defaultId = defSel.value; paintTabs(); });
    var mtuInp = el('input', { type: 'text', autocomplete: 'off', 'aria-label': 'Max packet size' });
    mtuInp.value = mtu;
    mtuInp.addEventListener('input', function () { mtu = mtuInp.value.trim(); });

    var tabs = el('div', { class: 'preset-row' }), noteEl = el('div', { class: 'muted preset-note' }), form = el('div'), prevDown = el('div'), prevUp = el('div');
    function dirty(id) { return JSON.stringify(draft[id]) !== saved[id]; }
    function paintTabs() {
      clear(tabs);
      d.levels.forEach(function (l) {
        var bits = (l.id === defaultId ? ' · default' : '') + (dirty(l.id) ? ' •' : '');
        tabs.appendChild(el('button', { class: 'btn slim' + (l.id === current ? ' on' : ''), type: 'button', text: l.name + bits, onclick: function () { current = l.id; paintLevel(); } }));
      });
    }
    function v(k) { return inp[k].value.trim(); }
    function paintPrev() {
      clear(prevDown); clear(prevUp);
      prevDown.appendChild(fecPreview(v('down'), parseFloat(v('lossd')) || 0, true));
      prevUp.appendChild(fecPreview(v('up'), parseFloat(v('lossu')) || 0, false));
    }
    function field(key, name, help, readonly) {
      var i = el('input', { type: 'text', autocomplete: 'off', 'aria-label': name });
      i.value = draft[current][key];
      inp[key] = i;
      i.addEventListener('input', function () { draft[current][key] = i.value.trim(); paintTabs(); paintPrev(); });
      return el('label', { class: 'fld' }, [el('span', { text: name }), i, help ? el('small', { class: 'muted', text: help }) : null]);
    }
    function previewField(key, name, val) {
      var i = el('input', { type: 'text', autocomplete: 'off', 'aria-label': name });
      i.value = String(val); inp[key] = i; i.addEventListener('input', paintPrev);
      return el('label', { class: 'fld' }, [el('span', { text: name }), i, el('small', { class: 'muted', text: 'Preview only, not saved.' })]);
    }
    function paintLevel() {
      var l = byId[current], ps = presetOf(current);
      clear(form); inp = {};
      noteEl.textContent = (ps ? ps.note + ' ' : 'The settings this server used before levels existed. It is not offered to the Android app for automatic switching. ') +
        (l.instance ? 'Runs on UDP port ' + l.port + ' (' + (l.state === 'active' ? 'running' : l.state) + ').' : 'Served on the original port ' + l.port + ' while it is the default level.');
      form.appendChild(title('Downlink: server to device'));
      form.appendChild(el('div', { class: 'muted', text: 'Used by this level\'s service as soon as you save.' }));
      form.appendChild(field('down', 'FEC table (data:parity, comma separated)',
        'Each entry means: for every group of data packets, send parity extra packets; any data of the data+parity packets rebuild the whole group. The busier the link, the later (larger) entry is used; between entries parity is interpolated.'));
      form.appendChild(el('div', { class: 'fld-row' }, [
        field('td', 'Max wait per group (ms)', 'A group is sent after this long even if not full. Longer: bigger groups, less overhead, more delay.'),
        field('id_', 'Interleave window (ms)', 'Packets of one group are spread over this time so a short loss burst cannot hit the whole group. 0: back to back.'),
        previewField('lossd', 'Assumed link loss (%)', l.loss || 25)]));
      form.appendChild(prevDown);
      form.appendChild(title('Uplink: device to server'));
      form.appendChild(el('div', { class: 'muted', text: 'Given to devices in their client command and in the Android app string. A device that is already running keeps its old uplink settings until it gets a new command or string.' }));
      form.appendChild(field('up', 'FEC table (data:parity, comma separated)'));
      form.appendChild(el('div', { class: 'fld-row' }, [field('tu', 'Max wait per group (ms)'), field('iu', 'Interleave window (ms)'), previewField('lossu', 'Assumed link loss (%)', Math.max(1, Math.round((l.loss || 25) / 5)))]));
      form.appendChild(prevUp);
      form.appendChild(title('Memory for this level'));
      form.appendChild(el('div', { class: 'fld-row' }, [
        field('db', 'Decode buffer (packets)', 'Packets the decoder keeps while waiting for the rest of a group (about 4 KB each, per connection). Large groups at high rates need more.'),
        field('sb', 'Socket buffer (KB)', 'Kernel buffer of the FEC socket; the system limit net.core.rmem_max caps it.')]));
      if (ps) form.appendChild(el('div', { class: 'row end' }, [el('button', { class: 'btn slim', type: 'button', text: 'Reset this level to its defaults', onclick: function () {
        draft[current] = { down: ps.down, td: String(ps.td), id_: String(ps.id_), up: ps.up, tu: String(ps.tu), iu: String(ps.iu), db: String(ps.db), sb: String(ps.sb) };
        paintLevel();
      } })]));
      paintTabs(); paintPrev();
    }

    var save = el('button', { class: 'btn primary', type: 'button', text: 'Save' });
    var wrap = el('div', {}, [
      el('div', { class: 'muted', text: 'The tunnel has several levels. A level is a set of FEC settings meant for a certain amount of link loss, and each runs on its own UDP port. ' +
        'The Android app measures the link and switches between the levels by itself; nothing has to be changed on the server for that.' }),
      title('Default level'),
      el('label', { class: 'fld' }, [el('span', { text: 'Level' }), defSel,
        el('small', { class: 'muted', text: 'What a device starts with, and what the commands for Linux, Windows, macOS and iOS use. It is served on the original UDP port ' + d.port + '.' })]),
      title('Levels'), tabs, noteEl, form,
      title('All levels'),
      el('label', { class: 'fld' }, [el('span', { text: 'Max packet size (bytes)' }), mtuInp,
        el('small', { class: 'muted', text: 'Upper limit for each FEC packet on the wire; larger data is split. Changing it restarts every level.' })]),
      el('div', { class: 'muted hint', text: 'Saving restarts only the levels you changed. Changing the default level restarts the main service, and a new packet size restarts all of them; ' +
        'devices on a restarted level reconnect within a few seconds. If a service cannot start with the new values, everything is put back.' }),
      msg,
      el('div', { class: 'row end' }, [el('button', { class: 'btn', type: 'button', text: 'Discard changes', onclick: function () { selectTab('settings'); } }), save])
    ]);
    paintLevel();

    save.addEventListener('click', function () {
      clear(msg);
      var body = { levels: {} }, bad = null;
      d.levels.forEach(function (l) {
        if (!dirty(l.id)) return;
        var f = draft[l.id];
        if (!parseFec(f.down) || !parseFec(f.up)) { bad = l.name + ': fix the FEC tables first (see the preview).'; return; }
        var n = {};
        ['td', 'id_', 'tu', 'iu', 'db', 'sb'].forEach(function (k) { if (!/^[0-9]{1,6}$/.test(f[k])) bad = l.name + ': numbers only in the time, size and buffer fields.'; else n[k] = parseInt(f[k], 10); });
        body.levels[l.id] = { down: { fec: f.down, timeout: n.td, interval: n.id_ }, up: { fec: f.up, timeout: n.tu, interval: n.iu }, db: n.db, sb: n.sb };
      });
      if (!bad && !/^[0-9]{3,4}$/.test(mtu)) bad = 'Max packet size: a number of bytes.';
      if (bad) { msg.appendChild(el('div', { class: 'msg bad', text: bad })); return; }
      if (defaultId !== d.default_level) body.default = defaultId;
      if (mtu !== String(d.fec_mtu || 1250)) body.mtu = parseInt(mtu, 10);
      if (!Object.keys(body.levels).length) delete body.levels;
      if (!Object.keys(body).length) { msg.appendChild(el('div', { class: 'msg warn', text: 'Nothing changed.' })); return; }
      save.disabled = true; save.textContent = 'Applying…';
      api('POST', '/api/speeder/levels', body).then(function (r) {
        save.disabled = false; save.textContent = 'Save';
        if (!r.ok) { msg.appendChild(el('div', { class: 'msg bad', text: (r.data && r.data.error) || 'Could not save' })); return; }
        pendingFlash = 'Saved. The changed FEC services restarted with the new settings.';
        selectTab('settings');
      });
    });
    return wrap;
  }

  // ---------- service (read-only status) ----------
  RENDER.service = function () {
    var info = el('div', { class: 'panel' });
    var cfgPre = el('pre', { text: 'Loading…' });
    cfgPre.style.marginTop = '12px';
    var cfg = el('div', { class: 'panel' }, [
      el('h2', { text: 'WireGuard configuration' }),
      el('div', { class: 'muted', text: '/etc/wireguard/wg0.conf, read-only. Private and preshared keys are hidden. Devices created here appear as "# BEGIN peer" blocks.' }),
      cfgPre]);
    var spd = el('div', { class: 'panel' });
    var logs = buildLogs();
    content.appendChild(info); content.appendChild(spd); content.appendChild(cfg); content.appendChild(logs.panel);
    api('GET', '/api/speeder').then(function (r) {
      clear(spd);
      spd.appendChild(el('h2', { text: 'FEC tunnel (UDPspeeder)' }));
      if (!r.ok || !r.data.enabled) { spd.appendChild(el('div', { class: 'muted', text: r.ok ? 'Not configured on this server.' : (r.data.error || 'Cannot load') })); return; }
      var d = r.data;
      spd.appendChild(el('div', { class: 'muted', text: d.required ? 'Every device uses this tunnel; the WireGuard port is closed from outside.' : 'Optional: pick it per device when adding one.' }));
      var kvx = function (k, v) { return el('div', { class: 'kv' }, [el('div', { class: 'k', text: k }), el('div', { class: 'v', text: v })]); };
      spd.appendChild(el('div', { class: 'kv' }, [el('div', { class: 'k', text: 'State' }), el('div', { class: 'v' }, [badge(d.active === 'active' ? 'ok' : 'bad', d.active === 'active' ? 'Running' : d.active)])]));
      spd.appendChild(kvx('Original UDP port', String(d.port)));
      if (d.levels) {
        spd.appendChild(kvx('Default level', (function () { var n = d.default_level; d.levels.forEach(function (l) { if (l.id === d.default_level) n = l.name; }); return n + ' (original port)'; })()));
        d.levels.forEach(function (l) {
          spd.appendChild(el('div', { class: 'kv' }, [el('div', { class: 'k', text: l.name + (l.loss ? ' · up to about ' + l.loss + '%' : '') }),
            el('div', { class: 'v' }, [l.instance ? badge(l.state === 'active' ? 'ok' : 'bad', l.state === 'active' ? 'Running' : l.state) : document.createTextNode('default only'),
              document.createTextNode('  ' + (l.instance ? 'UDP ' + l.port + ' · ' : '') + 'downlink ' + l.down.fec + ' · wait ' + l.down.timeout + ' ms')])]));
        });
      } else {
        spd.appendChild(kvx('Downlink (server to device)', d.fec + ' (data:parity) · max wait ' + d.timeout + ' ms · interleave ' + d.interval + ' ms'));
        spd.appendChild(kvx('Uplink (device to server)', (d.fec_up || d.fec) + ' · max wait ' + (d.timeout_up != null ? d.timeout_up : d.timeout) + ' ms · interleave ' + (d.interval_up != null ? d.interval_up : d.interval) + ' ms'));
      }
      if (d.fec_mtu) spd.appendChild(kvx('Max packet size', d.fec_mtu + ' B'));
      if (d.quic) spd.appendChild(el('div', { class: 'kv' }, [el('div', { class: 'k', text: 'QUIC disguise' }),
        el('div', { class: 'v' }, [badge(d.quic.state === 'active' ? 'ok' : 'bad', d.quic.state === 'active' ? 'Running' : d.quic.state), document.createTextNode('  UDP ' + d.quic.port + ' · devices can send the FEC tunnel as QUIC-looking packets (name ' + d.quic.sni + ')')])]));
      if (d.probe) spd.appendChild(el('div', { class: 'kv' }, [el('div', { class: 'k', text: 'Link-test probe' }),
        el('div', { class: 'v' }, [badge(d.probe.state === 'active' ? 'ok' : 'bad', d.probe.state === 'active' ? 'Running' : d.probe.state), document.createTextNode('  UDP ' + d.probe.port + ' · what the app\'s Link test measures')])]));
    });
    api('GET', '/api/config').then(function (r) {
      cfgPre.textContent = r.ok ? r.data.config : (r.data.error || 'Cannot load config');
    });

    function kv(k, v) { return el('div', { class: 'kv' }, [el('div', { class: 'k', text: k }), el('div', { class: 'v' }, [typeof v === 'string' ? document.createTextNode(v) : v])]); }

    function paint(s) {
      var running = s.service.active === 'active', failed = s.service.active === 'failed';
      clear(info);
      info.appendChild(el('h2', { text: 'WireGuard service' }));
      info.appendChild(el('div', { class: 'muted', text: 'Read-only. Starting, stopping and tuning are on the Settings page.' }));
      info.appendChild(kv('State', badge(running ? 'ok' : (failed ? 'bad' : 'warn'), running ? 'Running' : (failed ? 'Failed' : 'Stopped'))));
      info.appendChild(kv('Unit', 'wg-quick@wg0.service'));
      info.appendChild(kv('Since', s.service.since || '—'));
      info.appendChild(kv('Start at boot', s.service.enabled || 'unknown'));
      info.appendChild(kv('Implementation', s.implementation));
      info.appendChild(kv('Devices connected', s.peers.connected + ' of ' + s.peers.total));
    }

    function load() {
      api('GET', '/api/status').then(function (r) {
        if (r.ok) paint(r.data);
        else if (r.status !== 401) { clear(info); info.appendChild(el('div', { class: 'msg bad', text: r.data.error || 'Cannot load status' })); }
      });
    }
    load(); autoRefresh(function () { load(); if (logs.auto.checked) logs.load(); }, 5000);
  };

  // ---------- settings (everything that can be changed) ----------
  RENDER.settings = function () {
    var flash = el('div'), fecPanel = el('div', { class: 'panel' }), note = el('div'), zone = el('div', { class: 'panel danger-zone' });
    content.appendChild(flash); content.appendChild(fecPanel); content.appendChild(note); content.appendChild(zone);
    var last = null;
    if (pendingFlash) { flash.appendChild(el('div', { class: 'msg ok', text: pendingFlash })); pendingFlash = null; }
    fecPanel.appendChild(el('h2', { text: 'FEC tunnel (UDPspeeder)' }));
    api('GET', '/api/speeder').then(function (r) {
      if (!r.ok || !r.data.enabled) { fecPanel.appendChild(el('div', { class: 'muted', text: r.ok ? 'Not configured on this server.' : (r.data.error || 'Cannot load') })); return; }
      fecPanel.appendChild(buildFecEditor(r.data));
    });

    function paint(s) {
      last = s;
      var running = s.service.active === 'active';
      clear(zone);
      zone.appendChild(el('h2', { text: 'Service control' }));
      zone.appendChild(el('div', { class: 'muted', text: 'These actions affect every device. You can always start the service again from here; this page stays reachable.' }));
      [
        { a: 'restart', title: 'Restart service', desc: 'Drops all tunnels for a few seconds; devices reconnect on their own.', btn: 'Restart…', on: true, cls: 'danger' },
        { a: 'stop', title: 'Stop service', desc: 'Disconnects every device until you start it again.', btn: 'Stop…', on: running, cls: 'danger' },
        { a: 'start', title: 'Start service', desc: 'Brings the interface up and loads all saved devices.', btn: 'Start', on: !running, cls: 'primary' }
      ].forEach(function (x) {
        zone.appendChild(el('div', { class: 'zone-row' }, [
          el('div', {}, [el('div', { class: 'zone-title', text: x.title }), el('div', { class: 'muted', text: x.desc })]),
          el('button', { class: 'btn ' + x.cls, text: x.btn, disabled: !x.on, onclick: function () { ask(x.a); } })
        ]));
      });
    }

    function ask(action) {
      if (action === 'start') return run(action);
      var n = last ? last.peers.connected : 0, t = last ? last.peers.total : 0;
      var go = el('button', { class: 'btn danger', text: action === 'stop' ? 'Stop service' : 'Restart now' });
      var cancel = el('button', { class: 'btn', text: 'Cancel' });
      var nodes = [
        el('div', { class: 'msg warn', text: n + ' of ' + t + ' device(s) are connected right now and will lose the tunnel' +
          (action === 'stop' ? ' until the service is started again.' : ' for a few seconds.') })
      ];
      var typed = null;
      if (action === 'stop') {
        typed = el('input', { type: 'text', placeholder: 'Type stop to confirm', autocomplete: 'off', 'aria-label': 'Type stop to confirm' });
        nodes.push(el('div', {}, [typed]));
        go.disabled = true;
        typed.addEventListener('input', function () { go.disabled = typed.value.trim().toLowerCase() !== 'stop'; });
      }
      nodes.push(el('div', { class: 'row end' }, [cancel, go]));
      var m = openModal(action === 'stop' ? 'Stop the VPN service?' : 'Restart the VPN service?', nodes, { danger: true });
      cancel.addEventListener('click', m.close);
      go.addEventListener('click', function () { m.close(); run(action); });
      if (typed) typed.focus(); else cancel.focus();
    }

    function run(action) {
      Array.prototype.forEach.call(zone.querySelectorAll('button'), function (b) { b.disabled = true; });
      clear(note);
      note.appendChild(el('div', { class: 'msg warn', text: action.charAt(0).toUpperCase() + action.slice(1) + ' in progress…' }));
      api('POST', '/api/service', { action: action }).then(function (r) {
        clear(note);
        note.appendChild(el('div', { class: 'msg ' + (r.ok ? 'ok' : 'bad'), text: r.ok ? 'Done: ' + action + '.' : (r.data.error || 'Failed') }));
        load();
      });
    }

    function load() {
      api('GET', '/api/status').then(function (r) {
        if (r.ok) paint(r.data);
        else if (r.status !== 401) { clear(zone); zone.appendChild(el('div', { class: 'msg bad', text: r.data.error || 'Cannot load status' })); }
      });
    }
    load(); autoRefresh(load, 5000);
  };

  // ---------- boot ----------
  api('GET', '/api/me').then(function (r) {
    if (r.ok) { csrf = r.data.csrf; showMain(); } else showLogin();
  });
})();
