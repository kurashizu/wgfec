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

  var VIA_LABEL = { direct: 'Direct (WireGuard)', speeder: 'Via UDPspeeder (FEC)', faketcp: 'Via faketcp (udp2raw)',
                    'speeder-faketcp': 'FEC + faketcp (UDPspeeder + udp2raw)' };

  // ---------- devices ----------
  RENDER.devices = function () {
    var msg = el('div');
    var name = el('input', { type: 'text', placeholder: 'Device name (e.g. laptop)', maxlength: '32', autocomplete: 'off', required: true, 'aria-label': 'Device name' });
    var addr = el('input', { type: 'text', placeholder: 'Address (optional)', maxlength: '13', autocomplete: 'off', inputmode: 'numeric', 'aria-label': 'Static address (optional)' });
    var via = el('select', { 'aria-label': 'Connection type' }, [
      el('option', { value: 'direct', text: VIA_LABEL.direct })]);
    api('GET', '/api/speeder').then(function (r) {            // FEC-only servers: no choice to make
      if (!r.ok || !r.data.enabled) return;
      Object.keys(r.data.modes || {}).forEach(function (k) { via.appendChild(el('option', { value: k, text: VIA_LABEL[k] })); });
      if (r.data.required) { via.value = 'speeder'; via.style.display = 'none'; }
      else if (r.data.default_via === 'speeder') via.value = 'speeder';
    });
    var addBtn = el('button', { class: 'btn primary', type: 'submit', text: 'Add device' });
    var hint = el('div', { class: 'muted hint' });
    var form = el('form', { class: 'row' }, [name, addr, via, addBtn]);
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
      var body = { name: name.value.trim(), via: via.value };
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

  // ---------- per-OS setup guide for the client programs (udp2raw and/or speederv2) ----------
  // cmds = { u2r: 'udp2raw -c ...' | null, speeder: 'speederv2 -c ...' | null }, in the order they start.
  function setupGuide(cmds, conf, dev) {
    var ua = navigator.userAgent || '';
    if (!cmds) cmds = { speeder: null, u2r: null };
    if (typeof cmds === 'string') cmds = { speeder: cmds, u2r: null };
    var direct = !cmds.speeder && !cmds.u2r;       // plain WireGuard: nothing to run besides the tunnel itself
    var uArgs = cmds.u2r ? cmds.u2r.replace(/^udp2raw\s+/, '') : null;
    var sArgs = cmds.speeder ? cmds.speeder.replace(/^speederv2\s+/, '') : null;
    // PowerShell breaks "-l127.0.0.1:51821" at the dot and "-f1:3,2:4" at the commas, so for Windows
    // every value gets its own word and double quotes (cmd.exe accepts that form too).
    function winQuote(args) {
      return args.replace(/(^|\s)-([lrf])(\S+)/g, function (m, sp, f, v) { return sp + '-' + f + ' "' + v + '"'; })
                 .replace(/(^|\s)-k (\S+)/, function (m, sp, v) { return sp + '-k "' + v + '"'; });
    }
    // udp2raw_mp (Windows/macOS) has no -a; "easy-faketcp" talks to a faketcp server without firewall rules.
    function mpArgs(args) { return args.replace(/\s-a --keep-rule/, '').replace('--raw-mode faketcp', '--raw-mode easy-faketcp'); }
    var what = [cmds.u2r ? 'udp2raw' : null, cmds.speeder ? 'speederv2' : null].filter(Boolean).join(', then ');
    var both = !!(cmds.u2r && cmds.speeder);
    var order2 = both ? 'each in its own window, in this order, and keep them open' : 'it and keep it open';
    // Windows
    var wdl = [];
    if (cmds.u2r) wdl.push('udp2raw_mp_binaries.tar.gz (github.com/wangyu-/udp2raw-multiplatform/releases; untested here, try udp2raw_mp_wepoll.exe if it will not start)');
    if (cmds.speeder) wdl.push('speederv2_windows.zip (github.com/wangyu-/UDPspeeder/releases)');
    var win = [['text', 'Install WireGuard for Windows (wireguard.com/install)' + (cmds.u2r ? ' and Npcap (npcap.com).' : '.')]];
    if (wdl.length) win.push(['text', 'Download and unpack: ' + wdl.join(' and ') + '.']);
    win.push(['text', 'In PowerShell, run ' + order2 + ' (keep the quotes):']);
    if (cmds.u2r) win.push(['code', '.\\udp2raw_mp.exe ' + winQuote(mpArgs(uArgs))]);
    if (cmds.speeder) win.push(['code', '.\\speederv2.exe ' + winQuote(sArgs)]);
    win.push(['text', 'In WireGuard: Import tunnel(s) from file, pick ' + conf + ', Activate.']);
    // Linux: run the unpacked binaries directly, nothing is installed
    var ldl = [];
    if (cmds.u2r) ldl.push('udp2raw_binaries.tar.gz (github.com/wangyu-/udp2raw/releases)');
    if (cmds.speeder) ldl.push('speederv2_binaries.tar.gz (github.com/wangyu-/UDPspeeder/releases)');
    var lin = [['text', 'Install WireGuard:'], ['code', 'sudo apt install wireguard-tools resolvconf'],
               ['text', 'Download and unpack ' + ldl.join(' and ') + ' into one folder, then in that folder run ' + order2 + ':']];
    if (cmds.u2r) lin.push(['code', 'sudo ./udp2raw_amd64 ' + uArgs]);
    if (cmds.speeder) lin.push(['code', './speederv2_amd64 ' + sArgs]);
    lin.push(['text', 'Start the tunnel (wg-quick down to stop):']);
    lin.push(['code', 'sudo wg-quick up ./' + conf]);
    // macOS
    var mac = [['text', 'Not tested here. Install WireGuard from the App Store' + (cmds.u2r ? ' and libpcap (brew install libpcap).' : '.')]];
    if (cmds.u2r) mac.push(['text', 'Download udp2raw_mp_binaries.tar.gz (github.com/wangyu-/udp2raw-multiplatform/releases; the _m1 file on Apple silicon), then chmod +x it.']);
    if (cmds.speeder) { mac.push(['text', 'Build speederv2:']); mac.push(['code', 'git clone https://github.com/wangyu-/UDPspeeder && cd UDPspeeder && make mac']); }
    mac.push(['text', 'In Terminal, run ' + order2 + ':']);
    if (cmds.u2r) mac.push(['code', 'sudo ./udp2raw_mp_mac ' + mpArgs(uArgs)]);
    if (cmds.speeder) mac.push(['code', './speederv2_mac ' + sArgs]);
    mac.push(['text', 'In WireGuard: Import tunnel(s) from file, pick ' + conf + ', Activate.']);
    if (direct) {
      win = [['text', 'Install WireGuard for Windows (wireguard.com/install), choose "Import tunnel(s) from file", pick ' + conf + ', then Activate.']];
      lin = [['text', 'Install WireGuard (the wireguard-tools package of your distribution), then start the tunnel (wg-quick down to stop):'],
             ['code', 'sudo wg-quick up ./' + conf]];
      mac = [['text', 'Install WireGuard from the App Store, choose "Import tunnel(s) from file", pick ' + conf + ', then Activate.']];
    }
    // Android: the WG FEC app takes one pasted "app string" that holds the WireGuard config and the speederv2 command
    var droid, appStep = null;
    if (cmds.u2r) droid = [['text', 'Not available: fake-TCP (udp2raw) needs root on Android, and the app does not support it. Switch this device to Direct or FEC above.']];
    else {
      appStep = ['code', 'Preparing…', 'wrap'];
      droid = [['text', 'Install the WG FEC app on the phone.'],
               ['text', 'Copy this app string (it contains the private key, so keep it private), then in the app tap Paste and confirm the import:'], appStep,
               ['text', cmds.speeder ? 'Tap Connect and allow the VPN request. The app starts speederv2 itself, so there is nothing else to run.' : 'Tap Connect and allow the VPN request. This is a plain WireGuard tunnel without FEC. The official WireGuard app also works: scan the QR code above.']];
    }
    var guides = { windows: { label: 'Windows', steps: win }, linux: { label: 'Linux', steps: lin }, macos: { label: 'macOS', steps: mac }, android: { label: 'Android', steps: droid } };
    var order = ['windows', 'linux', 'macos', 'android'];
    var cur = /Android/i.test(ua) ? 'android' : (/Windows/i.test(ua) ? 'windows' : (/Mac/i.test(ua) ? 'macos' : 'linux'));
    var wrap = el('div', { class: 'guide' });
    var tabs = el('div', { class: 'guide-tabs' });
    var body = el('div');
    wrap.appendChild(el('div', { class: 'section-title', text: 'Set up this device' }));
    wrap.appendChild(el('div', { class: 'muted', text: (direct ? 'Pick your system.' : cmds.u2r && cmds.speeder ? 'Start udp2raw, then speederv2, then the tunnel.' : 'Start ' + what + ' first, then the tunnel.') + ' On Android use the Android tab; iPhones cannot run these programs.' }));
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
      api('GET', '/api/peers/' + encodeURIComponent(name) + '/config' + (v ? '?via=' + v : '')).then(function (r) {
        if (r.ok) return showConfig(r.data, false);
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
  // JSON: {"v":1,"name":"...","wg":"<WireGuard config>","sp":"<speederv2 client command>"}; "sp" is absent for a direct tunnel.
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
    var raw = new TextEncoder().encode(JSON.stringify(o));
    var plain = 'wgfec://1/' + b64url(raw);
    if (typeof CompressionStream === 'undefined') return Promise.resolve(plain);
    return new Response(new Blob([raw]).stream().pipeThrough(new CompressionStream('deflate'))).arrayBuffer()
      .then(function (buf) { var z = 'wgfec://1z/' + b64url(new Uint8Array(buf)); return z.length < plain.length ? z : plain; }, function () { return plain; });
  }
  function showConfig(d, created) {
    var curVia = d.via || (d.speeder ? 'speeder' : 'direct');
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
    var sw = el('div', { class: 'row' });
    var m = openModal(created ? 'Device "' + d.name + '" created' : 'Device "' + d.name + '"', [head, sw, img, el('pre', { text: d.config })
    ].concat([
      setupGuide(d.cmds || d.speeder_cmd || null, d.name + '.conf', d)
    ]).concat([
      el('div', { class: 'row end' }, [dl, copyBtn, el('button', { class: 'btn', text: 'Close', onclick: function () { m.close(); } })])
    ]));
    if (unsaved) return;
    api('GET', '/api/speeder').then(function (r) {            // offer the other connection type when this server has both
      if (!r.ok || !r.data.enabled || r.data.active !== 'active' || r.data.required) return;
      sw.appendChild(el('span', { class: 'muted', text: 'Config for:' }));
      ['direct'].concat(Object.keys(r.data.modes || {})).map(function (k) { return [k, VIA_LABEL[k]]; }).forEach(function (o) {
        var b = el('button', { class: 'btn slim' + (o[0] === curVia ? ' on' : ''), type: 'button', text: o[1] });
        if (o[0] !== curVia) b.addEventListener('click', function () {
          api('POST', '/api/peers/' + encodeURIComponent(d.name) + '/mode', { via: o[0] }).then(function () { m.close(); viewConfig(d.name, o[0]); });   // remember the choice
        });
        sw.appendChild(b);
      });
    });
  }

  // ---------- logs (a panel on the Service page) ----------
  function buildLogs() {
    var src = el('select', { 'aria-label': 'Log source' }, [['wg-service', 'WireGuard service (systemd)'], ['speeder', 'UDPspeeder (FEC)'], ['faketcp', 'udp2raw (faketcp)'], ['web-ui', 'This web UI']].map(function (o) { return el('option', { value: o[0], text: o[1] }); }));
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

  // ---------- FEC settings editor (presets from aggressive to conservative) ----------
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
  function buildFecEditor(d) {
    var inp = {}, msg = el('div'), noteEl = el('div', { class: 'muted preset-note' });
    function field(key, label, val, help) {
      var i = el('input', { type: 'text', autocomplete: 'off', 'aria-label': label });
      i.value = String(val); inp[key] = i; i.addEventListener('input', paint);
      return el('label', { class: 'fld' }, [el('span', { text: label }), i, help ? el('small', { class: 'muted', text: help }) : null]);
    }
    var cur = {
      down: d.fec, td: d.timeout, id_: d.interval, up: d.fec_up || d.fec, tu: d.timeout_up != null ? d.timeout_up : d.timeout,
      iu: d.interval_up != null ? d.interval_up : d.interval, mtu: d.fec_mtu || 1250, db: d.decode_buf || 2000, sb: d.sock_buf || 1024 };
    var prevDown = el('div'), prevUp = el('div'), presetRow = el('div', { class: 'preset-row' }), presetBtns = {};
    FEC_PRESETS.forEach(function (ps) {
      var b = el('button', { class: 'btn slim', type: 'button', text: ps.name + ' (' + ps.loss + ')', onclick: function () { apply(ps); } });
      presetBtns[ps.id] = b; presetRow.appendChild(b);
    });
    var title = function (t) { return el('div', { class: 'section-title', text: t }); };
    var form = el('div', {}, [
      title('Presets, from aggressive to conservative'), presetRow, noteEl,
      title('Downlink: server to device'),
      el('div', { class: 'muted', text: 'Applied by this server as soon as you save.' }),
      field('down', 'FEC table (data:parity, comma separated)', cur.down,
        'Each entry means: for every group of data packets, send parity extra packets; any data of the data+parity packets rebuild the whole group. The busier the link, the later (larger) entry is used; between entries parity is interpolated.'),
      el('div', { class: 'fld-row' }, [
        field('td', 'Max wait per group (ms)', cur.td, 'A group is sent after this long even if not full. Longer: bigger groups, less overhead, more delay.'),
        field('id_', 'Interleave window (ms)', cur.id_, 'Packets of one group are spread over this time so a short loss burst cannot hit the whole group. 0: back to back.'),
        field('lossd', 'Assumed link loss (%)', 45, 'Preview only.')]),
      prevDown,
      title('Uplink: device to server'),
      el('div', { class: 'muted', text: 'Stored here and used by the client command. It applies to client configs opened after you save; a device that is already running keeps its old command until you copy the new one.' }),
      field('up', 'FEC table (data:parity, comma separated)', cur.up),
      el('div', { class: 'fld-row' }, [field('tu', 'Max wait per group (ms)', cur.tu), field('iu', 'Interleave window (ms)', cur.iu), field('lossu', 'Assumed link loss (%)', 5)]),
      prevUp,
      title('Both directions'),
      el('div', { class: 'fld-row' }, [
        field('mtu', 'Max packet size (bytes)', cur.mtu, 'Upper limit for each FEC packet on the wire; larger data is split.'),
        field('db', 'Decode buffer (packets)', cur.db, 'Packets the decoder keeps while waiting for the rest of a group (about 4 KB each, per connection). Large groups at high rates need more.'),
        field('sb', 'Socket buffer (KB)', cur.sb, 'Kernel buffer of the FEC socket; the system limit net.core.rmem_max caps it.')]),
      el('div', { class: 'muted hint', text: 'Saving restarts the FEC service; devices using it reconnect within a few seconds. If the service cannot start with the new values, the previous ones are restored.' })
    ]);
    function v(k) { return inp[k].value.trim(); }
    function paint() {
      clear(prevDown); clear(prevUp);
      prevDown.appendChild(fecPreview(v('down'), parseFloat(v('lossd')) || 0, true));
      prevUp.appendChild(fecPreview(v('up'), parseFloat(v('lossu')) || 0, false));
      var hit = null;
      FEC_PRESETS.forEach(function (ps) {
        var same = v('down') === ps.down && v('up') === ps.up && v('td') === String(ps.td) && v('id_') === String(ps.id_) &&
                   v('tu') === String(ps.tu) && v('iu') === String(ps.iu) && v('db') === String(ps.db) && v('sb') === String(ps.sb);
        presetBtns[ps.id].className = 'btn slim' + (same ? ' on' : '');
        if (same) hit = ps;
      });
      noteEl.textContent = hit ? hit.note : 'Custom settings.';
    }
    function apply(ps) {
      inp.down.value = ps.down; inp.td.value = ps.td; inp.id_.value = ps.id_; inp.up.value = ps.up; inp.tu.value = ps.tu; inp.iu.value = ps.iu;
      inp.db.value = ps.db; inp.sb.value = ps.sb;
      inp.lossd.value = parseInt(ps.loss, 10); inp.lossu.value = Math.max(1, Math.round(parseInt(ps.loss, 10) / 5));
      paint();
    }
    var save = el('button', { class: 'btn primary', type: 'button', text: 'Save and restart the FEC service' });
    var wrap = el('div', {}, [form, msg, el('div', { class: 'row end' }, [el('button', { class: 'btn', type: 'button', text: 'Discard changes', onclick: function () { selectTab('settings'); } }), save])]);
    paint();
    save.addEventListener('click', function () {
      clear(msg);
      var nums = { td: 'timeout', id_: 'interval', tu: 'timeout_up', iu: 'interval_up', mtu: 'mtu', db: 'decode_buf', sb: 'sock_buf' };
      var body = { fec: v('down'), fec_up: v('up') };
      if (!parseFec(body.fec) || !parseFec(body.fec_up)) { msg.appendChild(el('div', { class: 'msg bad', text: 'Fix the FEC tables first (see the preview).' })); return; }
      for (var k in nums) {
        if (!/^[0-9]{1,6}$/.test(v(k))) { msg.appendChild(el('div', { class: 'msg bad', text: 'Numbers only in the time, size and buffer fields.' })); return; }
        body[nums[k]] = parseInt(v(k), 10);
      }
      save.disabled = true; save.textContent = 'Restarting…';
      api('POST', '/api/speeder/settings', body).then(function (r) {
        save.disabled = false; save.textContent = 'Save and restart the FEC service';
        if (!r.ok) { msg.appendChild(el('div', { class: 'msg bad', text: (r.data && r.data.error) || 'Could not save' })); return; }
        pendingFlash = 'Saved. The FEC service restarted with the new settings.';
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
      spd.appendChild(kvx('UDP port', String(d.port)));
      spd.appendChild(kvx('Downlink (server to device)', d.fec + ' (data:parity) · max wait ' + d.timeout + ' ms · interleave ' + d.interval + ' ms'));
      spd.appendChild(kvx('Uplink (device to server)', (d.fec_up || d.fec) + ' · max wait ' + (d.timeout_up != null ? d.timeout_up : d.timeout) + ' ms · interleave ' + (d.interval_up != null ? d.interval_up : d.interval) + ' ms'));
      if (d.fec_mtu) spd.appendChild(kvx('Packet size / buffers', 'max ' + d.fec_mtu + ' B · decode buffer ' + d.decode_buf + ' packets · socket buffer ' + d.sock_buf + ' KB'));
      var u = d.u2r;
      if (u) {
        spd.appendChild(el('h2', { text: 'faketcp (udp2raw)' }));
        spd.appendChild(el('div', { class: 'muted', text: 'UDP disguised as TCP, alone or in front of the FEC tunnel.' }));
        spd.appendChild(el('div', { class: 'kv' }, [el('div', { class: 'k', text: 'faketcp only' }),
          el('div', { class: 'v' }, [badge(u.active === 'active' ? 'ok' : 'bad', u.active === 'active' ? 'Running' : u.active), document.createTextNode('  TCP ' + u.port)])]));
        spd.appendChild(el('div', { class: 'kv' }, [el('div', { class: 'k', text: 'FEC + faketcp' }),
          el('div', { class: 'v' }, [badge(u.active_fec === 'active' ? 'ok' : 'bad', u.active_fec === 'active' ? 'Running' : u.active_fec), document.createTextNode('  TCP ' + u.port_fec)])]));
        spd.appendChild(kvx('Encryption / integrity', u.cipher + ' / ' + u.auth));
      }
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
