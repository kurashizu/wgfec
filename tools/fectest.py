#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
fectest.py - 家庭宽带 <-> 服务器 UDP 丢包 / 限速 / FEC 参数 一次性全面测试与调优
纯标准库, Python 3.8+ (Windows / Linux / macOS 通用). 只用 UDP, 不改任何系统设置.

用法 (朋友这边, Windows):
    python fectest.py client <服务器IP> --key <口令> --isp 电信 --city 上海
    (建议晚上 20:00-23:00 运行, 那是最差的时段; 全程约 20 分钟, 可加 --quick 缩到约 8 分钟)

用法 (服务器这边):
    python3 fectest.py server --key <口令> --port 5201

自测 (本机, 不需要服务器):
    python3 fectest.py selftest

测完会在当前目录生成:  fectest_<时间>_report.txt / .json / _traces.json.gz / _tracert.txt
把这 4 个文件发回来即可. 原始丢包轨迹都在里面, 之后换参数可离线重算, 不用重测.
"""
import argparse, base64, collections, datetime, gzip, hashlib, hmac, json, math, os, platform
import queue, random, re, socket, struct, subprocess, sys, threading, time, zlib

VER = "1.0"
HDR = struct.Struct('!2sIHIIHHHd')   # magic, sid, tid, seq, group, idx, k, y, ts  (30 字节)
MD, ME, MC = b'FD', b'FE', b'FC'
T_ECHO = 1

if os.name == 'nt':
    try:
        import ctypes
        ctypes.windll.winmm.timeBeginPeriod(1)
    except Exception:
        pass
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass


def say(*a):
    print(*a, flush=True)


def pct(a, p):
    if not a:
        return None
    a = sorted(a)
    return a[min(len(a) - 1, int(len(a) * p / 100.0))]


def r1(x):
    return None if x is None else round(x, 2)


# ---------------------------------------------------------------- 控制包 (HMAC)
def pack_ctrl(key, obj):
    obj['t'] = time.time()
    body = json.dumps(obj, separators=(',', ':')).encode()
    return MC + hmac.new(key, body, hashlib.sha256).digest()[:16] + body


def unpack_ctrl(key, data):
    if len(data) < 20 or data[:2] != MC:
        return None
    mac, body = data[2:18], data[18:]
    if not hmac.compare_digest(mac, hmac.new(key, body, hashlib.sha256).digest()[:16]):
        return None
    try:
        return json.loads(body)
    except Exception:
        return None


# ---------------------------------------------------------------- 发送计划
def make_schedule(rate, dur, k, y, spread_ms):
    """[(t, seq, g, i)]; rate=数据包/秒; 每组 k 个数据包 + y 个冗余包(冗余在组结束后 spread_ms 内匀速发出)"""
    ev = []
    ng = int(dur * rate / k)
    for g in range(ng):
        t0 = g * k / rate
        for i in range(k):
            ev.append((t0 + i / rate, g, i))
        te = t0 + k / rate
        for j in range(y):
            ev.append((te + (j + 0.5) / y * spread_ms / 1000.0, g, k + j))
    ev.sort()
    return [(t, s, g, i) for s, (t, g, i) in enumerate(ev)], ng


def make_burst_schedule(B, count, gap):
    ev = []
    for b in range(count):
        for p in range(B):
            ev.append((b * gap, b, p))
    return [(t, s, g, i) for s, (t, g, i) in enumerate(ev)], count


def sched_off(i, k, y, rate, spread_ms):
    if i < k:
        return i / rate
    return k / rate + ((i - k) + 0.5) / max(1, y) * spread_ms / 1000.0


def paced_send(sock, addr, sid, tid, sched, size, k=1, y=0, drop=None):
    pad = b'\0' * max(0, size - HDR.size)
    pc = time.perf_counter
    st = pc() + 0.05
    n = 0
    for t, seq, g, i in sched:
        while True:
            d = st + t - pc()
            if d <= 0:
                break
            if d > 0.004:
                time.sleep(d - 0.003)
        if drop is not None and drop(time.time()):
            continue
        try:
            sock.sendto(HDR.pack(MD, sid, tid, seq, g, i, k, y, time.time()) + pad, addr)
        except OSError:
            pass
        n += 1
    return n


# ---------------------------------------------------------------- 分析
def flags_from_seqs(seqs, n):
    f = bytearray(n)
    for s in seqs:
        if 0 <= s < n:
            f[s] = 1
    return f


def loss_stats(flags, rate):
    n = len(flags)
    if n == 0:
        return {}
    got = sum(flags)
    P = 1 - got / n
    lr, gr = [], []
    cur, c = None, 0
    for v in flags:
        if v == cur:
            c += 1
        else:
            if cur is not None:
                (gr if cur else lr).append(c)
            cur, c = v, 1
    (gr if cur else lr).append(c)
    hist = collections.OrderedDict((k, 0) for k in ('1', '2', '3-5', '6-10', '11-30', '31-100', '>100'))
    for L in lr:
        if L == 1: hist['1'] += 1
        elif L == 2: hist['2'] += 1
        elif L <= 5: hist['3-5'] += 1
        elif L <= 10: hist['6-10'] += 1
        elif L <= 30: hist['11-30'] += 1
        elif L <= 100: hist['31-100'] += 1
        else: hist['>100'] += 1
    w = max(1, int(rate))
    ws = []
    for a in range(0, n, w):
        seg = flags[a:a + w]
        if len(seg) >= max(1, w // 2):
            ws.append(1 - sum(seg) / len(seg))
    q = max(1, n // 4)
    head = 1 - sum(flags[:q]) / q
    tail = 1 - sum(flags[q:]) / max(1, n - q)
    mean_run = sum(lr) / len(lr) if lr else 0
    return {
        'n': n, 'got': got, 'loss_pct': r1(100 * P),
        'loss_head25_pct': r1(100 * head), 'loss_tail75_pct': r1(100 * tail),
        'loss_runs': len(lr), 'mean_run': r1(mean_run), 'max_run': max(lr) if lr else 0,
        'max_run_ms': r1(max(lr) * 1000.0 / rate) if lr else 0,
        'mean_good_run': r1(sum(gr) / len(gr)) if gr else 0,
        'burst_idx': r1(mean_run * (1 - P)) if lr else 0,   # 1=纯随机, 越大越"成片丢"
        'run_hist': dict(hist),
        'win1s_p50': r1(100 * pct(ws, 50)) if ws else None,
        'win1s_p90': r1(100 * pct(ws, 90)) if ws else None,
        'win1s_max': r1(100 * max(ws)) if ws else None,
        'win1s_frac_ge30': r1(100 * sum(1 for x in ws if x >= .3) / len(ws)) if ws else None,
    }


def owd_stats(rows):
    o = [r[4] - r[3] for r in rows]
    if not o:
        return {}
    m = min(o)
    j = [(x - m) * 1000 for x in o]
    return {'owd_jit_p50_ms': r1(pct(j, 50)), 'owd_jit_p95_ms': r1(pct(j, 95)),
            'owd_jit_p99_ms': r1(pct(j, 99)), 'owd_jit_max_ms': r1(max(j))}


def analyze_plain(rows, n, rate):
    fl = flags_from_seqs([r[0] for r in rows], n)
    st = loss_stats(fl, rate)
    st.update(owd_stats(rows))
    if len(rows) > 2:
        span = max(r[4] for r in rows) - min(r[4] for r in rows)
        st['rx_pps'] = r1(len(rows) / span) if span > 0 else None
    return st, fl


def eval_fec(rows, ng, k, y, rate, spread, dmax_ms, wsec=10.0):
    """按 "任意收到 k 个就能还原整组" 判定. rows=(seq,g,i,ts,tr)."""
    owd = sorted(r[4] - r[3] for r in rows if r[2] < k)
    base = owd[len(owd) // 2] if owd else 0.0
    G = collections.defaultdict(list)
    for r in rows:
        G[r[1]].append(r)
    tot = ng * k
    lost = late = 0
    rec = []
    nwin = max(1, int(math.ceil(ng * k / rate / wsec)))
    wl = [[0, 0] for _ in range(nwin)]       # [丢+迟到, 总]
    nd = np_ = 0
    for r in rows:
        if r[2] < k: nd += 1
        else: np_ += 1
    for g in range(ng):
        w = min(nwin - 1, int(g * k / rate / wsec))
        wl[w][1] += k
        sh = G.get(g, [])
        got = {r[2] for r in sh if r[2] < k}
        miss = k - len(got)
        if miss == 0:
            continue
        if len(sh) >= k:
            ready = sorted(r[4] for r in sh)[k - 1]
            r0 = sh[0]
            T0 = r0[3] - sched_off(r0[2], k, y, rate, spread)
            for j in range(k):
                if j in got:
                    continue
                d = max(0.0, (ready - (T0 + j / rate + base)) * 1000)
                rec.append(d)
                if d > dmax_ms:
                    late += 1
                    wl[w][0] += 1
        else:
            lost += miss
            wl[w][0] += miss
    ws = [a / b for a, b in wl if b]
    return {
        'tot': tot, 'resid_pct': r1(100.0 * lost / tot) if tot else None,
        'resid_eff_pct': r1(100.0 * (lost + late) / tot) if tot else None,
        'rec_n': len(rec), 'rec_p50_ms': r1(pct(rec, 50)), 'rec_p95_ms': r1(pct(rec, 95)),
        'rec_p99_ms': r1(pct(rec, 99)),
        'shard_loss_data_pct': r1(100.0 * (1 - nd / tot)) if tot else None,
        'shard_loss_par_pct': r1(100.0 * (1 - np_ / (ng * y))) if ng * y else None,
        'win_p90_pct': r1(100 * pct(ws, 90)) if ws else None,
        'win_max_pct': r1(100 * max(ws)) if ws else None,
    }


def replay(flags, rate, k, y, spread, dmax_ms, wsec=10.0):
    """用测得的丢包轨迹(按时间)离线回放一个 FEC 参数."""
    n = len(flags)
    dur = n / float(rate)
    sched, ng = make_schedule(rate, dur, k, y, spread)
    rows = []
    for t, seq, g, i in sched:
        idx = min(n - 1, int(t * rate))
        if flags[idx]:
            rows.append((seq, g, i, t, t + 0.05))
    return eval_fec(rows, ng, k, y, rate, spread, dmax_ms, wsec)


def rle_enc(flags):
    out = bytearray([1 if flags and flags[0] else 0])
    cur, c = (flags[0] if flags else 0), 0
    def vi(x):
        b = bytearray()
        while True:
            q = x & 0x7f
            x >>= 7
            if x:
                b.append(q | 0x80)
            else:
                b.append(q)
                return b
    for v in flags:
        if v == cur:
            c += 1
        else:
            out += vi(c)
            cur, c = v, 1
    out += vi(c)
    return zlib.compress(bytes(out), 9)


def rle_dec(blob):
    d = zlib.decompress(blob)
    st = d[0]
    i = 1
    runs = []
    while i < len(d):
        x = sh = 0
        while True:
            b = d[i]
            i += 1
            x |= (b & 0x7f) << sh
            sh += 7
            if not b & 0x80:
                break
        runs.append(x)
    f = bytearray()
    v = st
    for r in runs:
        f += bytes([v]) * r
        v ^= 1
    return f


# ---------------------------------------------------------------- 开发自测用丢包模拟 (仅 selftest)
class Sim:
    def __init__(s, P, D, cap):
        s.P, s.D, s.cap = P, D / 1000.0, cap
        s.bad, s.last, s.sec, s.cnt = False, None, 0, 0
        s.rng = random.Random(7)

    def drop(s, t):
        if s.cap:
            sec = int(t)
            if sec != s.sec:
                s.sec, s.cnt = sec, 0
            s.cnt += 1
            if s.cnt > s.cap:
                return True
        if s.P <= 0:
            return False
        if s.D <= 0:
            return s.rng.random() < s.P
        if s.last is not None:
            dt = t - s.last
            a = 1.0 / (s.D * (1 - s.P) / s.P)
            b = 1.0 / s.D
            e = math.exp(-(a + b) * dt)
            pb = (s.P + (1 - s.P) * e) if s.bad else s.P * (1 - e)
            s.bad = s.rng.random() < pb
        else:
            s.bad = s.rng.random() < s.P
        s.last = t
        return s.bad


# ---------------------------------------------------------------- 服务器
class Server:
    def __init__(s, key, port, sim=None, bind='0.0.0.0', logdir=None):
        s.key, s.port, s.bind = key, port, bind
        s.sim_in = Sim(*sim) if sim else None
        s.sim_out = Sim(*sim) if sim else None
        s.tests, s.echo, s.cache, s.blobs = {}, collections.defaultdict(set), {}, {}
        s.active = collections.defaultdict(int)
        s.bytes = collections.defaultdict(int)
        s.hour = int(time.time() // 3600)
        s.logdir = logdir
        s.sock = None
        s.ready = threading.Event()

    def reply(s, addr, obj, rid):
        obj['rid'] = rid
        s.sock.sendto(pack_ctrl(s.key, obj), addr)

    def run(s):
        s.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 8 << 20)
        except Exception:
            pass
        s.sock.bind((s.bind, s.port))
        s.port = s.sock.getsockname()[1]
        s.ready.set()
        say("服务器已启动 udp/%d  (Ctrl-C 退出)" % s.port)
        last_gc = time.time()
        while True:
            try:
                data, addr = s.sock.recvfrom(4096)
            except OSError:
                continue
            tr = time.time()
            try:
                s.handle(data, addr, tr)
            except Exception as e:
                say("handle error", repr(e))
            if tr - last_gc > 60:
                last_gc = tr
                for k_ in [k_ for k_, v in s.tests.items() if tr - v['t'] > 900]:
                    s.tests.pop(k_, None)
                    s.blobs.pop(k_, None)
                for k_ in [k_ for k_, v in s.cache.items() if tr - v[0] > 900]:
                    s.cache.pop(k_, None)

    def handle(s, data, addr, tr):
        m = data[:2]
        if m == MD and len(data) >= HDR.size:
            _, sid, tid, seq, g, i, k, y, ts = HDR.unpack(data[:HDR.size])
            t = s.tests.get((sid, tid))
            if t is not None and len(t['rows']) < 400000:
                if s.sim_in and s.sim_in.drop(tr):
                    return
                t['rows'].append((seq, g, i, ts, tr))
        elif m == ME and len(data) >= HDR.size:
            _, sid, tid, seq, g, i, k, y, ts = HDR.unpack(data[:HDR.size])
            if s.sim_in and s.sim_in.drop(tr):
                return
            s.echo[(sid, tid)].add(seq)
            if not (s.sim_out and s.sim_out.drop(tr)):
                s.sock.sendto(data, addr)
        elif m == MC:
            msg = unpack_ctrl(s.key, data)
            if msg is None or abs(time.time() - msg.get('t', 0)) > 300:
                return
            ck = (msg.get('sid'), msg.get('rid'))
            if ck in s.cache:
                s.sock.sendto(s.cache[ck][1], addr)
                return
            resp = s.ctrl(msg, addr)
            resp['rid'] = msg.get('rid')
            pkt = pack_ctrl(s.key, resp)
            s.cache[ck] = (time.time(), pkt)
            s.sock.sendto(pkt, addr)

    def ctrl(s, m, addr):
        op, sid, tid = m.get('op'), m.get('sid'), m.get('tid')
        ip = addr[0]
        h = int(time.time() // 3600)
        if h != s.hour:
            s.hour = h
            s.bytes.clear()
        if op == 'hello':
            return {'ok': 1, 'ver': VER, 'you': "%s:%d" % addr, 'server_time': time.time()}
        if op == 'up_open':
            s.tests[(sid, tid)] = {'rows': [], 't': time.time(), 'm': m}
            return {'ok': 1}
        if op == 'up_close':
            t = s.tests.get((sid, tid))
            if not t:
                return {'ok': 0, 'err': 'no test'}
            mm = t['m']
            rows = t['rows']
            if mm['mode'] == 'plain':
                st, fl = analyze_plain(rows, mm['n'], mm['rate'])
                s.blobs[(sid, tid)] = rle_enc(fl)
            else:
                st = eval_fec(rows, mm['ng'], mm['k'], mm['y'], mm['rate'], mm['spread'], mm['dmax'], mm.get('wsec', 10.0))
                st.update(owd_stats(rows))
            if s.logdir:
                try:
                    os.makedirs(s.logdir, exist_ok=True)
                    with gzip.open(os.path.join(s.logdir, "%d_%d.json.gz" % (sid, tid)), 'wt') as f:
                        json.dump({'m': mm, 'st': st}, f)
                except Exception:
                    pass
            return {'ok': 1, 'st': st}
        if op == 'fetch':
            b = s.blobs.get((sid, tid))
            if b is None:
                return {'ok': 0}
            CH = 700
            tot = (len(b) + CH - 1) // CH
            n = m.get('n', 0)
            return {'ok': 1, 'n': n, 'tot': tot, 'd': base64.b64encode(b[n * CH:(n + 1) * CH]).decode()}
        if op == 'echo_stat':
            return {'ok': 1, 'cnt': len(s.echo[(sid, tid)])}
        if op == 'down_start':
            rate, dur, size = float(m['rate']), float(m['dur']), int(m['size'])
            k, y, sp = int(m['k']), int(m['y']), float(m['spread'])
            burst = m.get('burst')
            if rate > 4000 or size > 1400 or dur > 60 or k + y > 400:
                return {'ok': 0, 'err': 'limit'}
            if s.active[ip] >= 1:
                return {'ok': 0, 'err': 'busy'}
            est = rate * (k + y) / k * dur * size if not burst else burst[0] * burst[1] * size
            if s.bytes[ip] + est > 2.5e9:
                return {'ok': 0, 'err': 'quota'}
            s.bytes[ip] += est
            s.active[ip] += 1
            if burst:
                sched, ng = make_burst_schedule(burst[0], burst[1], burst[2])
            else:
                sched, ng = make_schedule(rate, dur, k, y, sp)

            def th():
                try:
                    paced_send(s.sock, addr, sid, tid, sched, size, k, y,
                               drop=(s.sim_out.drop if s.sim_out else None))
                finally:
                    s.active[ip] -= 1
            threading.Thread(target=th, daemon=True).start()
            return {'ok': 1, 'n': len(sched)}
        return {'ok': 0, 'err': 'op'}


# ---------------------------------------------------------------- 客户端
class Client:
    def __init__(s, host, port, key):
        s.addr = (host, port)
        s.key = key
        s.sid = random.getrandbits(32)
        s.rid = 0
        s.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 8 << 20)
        except Exception:
            pass
        s.sock.bind(('0.0.0.0', 0))
        s.q = queue.Queue()
        s.collect = {}
        s.echo = {}
        s.lk = threading.Lock()
        threading.Thread(target=s.rx, daemon=True).start()

    def rx(s):
        while True:
            try:
                data, a = s.sock.recvfrom(4096)
            except OSError:
                continue
            tr = time.time()
            m = data[:2]
            if m == MD and len(data) >= HDR.size:
                _, sid, tid, seq, g, i, k, y, ts = HDR.unpack(data[:HDR.size])
                c = s.collect.get(tid)
                if c is not None:
                    c.append((seq, g, i, ts, tr))
            elif m == ME and len(data) >= HDR.size:
                _, sid, tid, seq, g, i, k, y, ts = HDR.unpack(data[:HDR.size])
                s.echo[seq] = (ts, tr)
            elif m == MC:
                o = unpack_ctrl(s.key, data)
                if o:
                    s.q.put(o)

    def rpc(s, obj, tries=80, wait=0.4):
        s.rid += 1
        rid = s.rid
        obj['sid'], obj['rid'] = s.sid, rid
        for _ in range(tries):
            s.sock.sendto(pack_ctrl(s.key, dict(obj)), s.addr)
            end = time.time() + wait
            while True:
                rem = end - time.time()
                if rem <= 0:
                    break
                try:
                    o = s.q.get(timeout=rem)
                except queue.Empty:
                    break
                if o.get('rid') == rid:
                    return o
        raise RuntimeError("控制通道无响应(服务器不可达、口令错误或丢包过于严重): %s" % obj.get('op'))


def get_flags(cl, tid):
    parts, tot, n = {}, None, 0
    while tot is None or n < tot:
        o = cl.rpc({'op': 'fetch', 'tid': tid, 'n': n})
        if not o.get('ok'):
            return None
        tot = o['tot']
        parts[n] = base64.b64decode(o['d'])
        n += 1
    return rle_dec(b''.join(parts[i] for i in range(tot)))


def run_up(cl, tid, mode, rate, dur, size, k=1, y=0, spread=20, dmax=100, burst=None, wsec=10.0, want_flags=False):
    if burst:
        sched, ng = make_burst_schedule(*burst)
    else:
        sched, ng = make_schedule(rate, dur, k, y, spread)
    cl.rpc({'op': 'up_open', 'tid': tid, 'mode': mode, 'n': len(sched), 'rate': rate, 'k': k, 'y': y,
            'spread': spread, 'ng': ng, 'dmax': dmax, 'wsec': wsec})
    paced_send(cl.sock, cl.addr, cl.sid, tid, sched, size, k, y)
    time.sleep(1.8)
    o = cl.rpc({'op': 'up_close', 'tid': tid})
    st = o.get('st', {})
    st['sent'] = len(sched)
    fl = get_flags(cl, tid) if (want_flags and mode == 'plain') else None
    return st, fl


def run_down(cl, tid, mode, rate, dur, size, k=1, y=0, spread=20, dmax=100, burst=None, wsec=10.0):
    if burst:
        sched, ng = make_burst_schedule(*burst)
    else:
        sched, ng = make_schedule(rate, dur, k, y, spread)
    rows = []
    cl.collect[tid] = rows
    t0 = time.time()
    o = cl.rpc({'op': 'down_start', 'tid': tid, 'rate': rate, 'dur': dur, 'size': size, 'k': k, 'y': y,
                'spread': spread, 'burst': burst})
    if not o.get('ok'):
        cl.collect.pop(tid, None)
        raise RuntimeError("服务器拒绝下行测试: %s" % o.get('err'))
    sd = sched[-1][0] if sched else 0
    end = t0 + sd + 3.2
    while time.time() < end:
        time.sleep(0.1)
    cl.collect.pop(tid, None)
    if mode == 'plain':
        st, fl = analyze_plain(rows, len(sched), rate)
    else:
        st = eval_fec(rows, ng, k, y, rate, spread, dmax, wsec)
        st.update(owd_stats(rows))
        fl = None
    st['sent'] = len(sched)
    return st, fl


# ---------------------------------------------------------------- 系统命令
def sh(cmd, timeout):
    try:
        p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
        b = p.stdout
    except subprocess.TimeoutExpired as e:
        b = e.stdout or b''
    except Exception as e:
        return "ERR %r" % e
    for enc in ('utf-8', 'gbk', 'cp936'):
        try:
            return b.decode(enc)
        except Exception:
            pass
    return b.decode('utf-8', 'replace')


def sys_ping(host, n):
    if os.name == 'nt':
        out = sh(['ping', '-n', str(n), host], n * 3 + 10)
    else:
        out = sh(['ping', '-c', str(n), '-i', '0.3', host], n * 3 + 10)
    loss = re.findall(r'(\d+(?:\.\d+)?)\s*%', out)
    ms = re.findall(r'(\d+(?:\.\d+)?)\s*ms', out)
    return {'loss_pct': float(loss[-1]) if loss else None, 'avg_ms': float(ms[-1]) if ms else None, 'raw': out[-600:]}


# ---------------------------------------------------------------- 调参
def gen_candidates(rate, dmax, size, cap_pps, max_mbps, ext_windows=()):
    """候选 (k,y,spread). ext=True 的候选超出延迟预算, 用来回答"要多少延迟才能逼近 0 丢包"."""
    spread = max(5, min(20, int((dmax - 10) / 3)))
    wmax = max(15, dmax - spread - 10)
    wins = sorted({max(10, int(wmax / 8)), max(10, int(wmax / 4)), max(10, int(wmax / 2)), int(wmax)})
    ratios = [0.2, 0.35, 0.5, 0.75, 1, 1.5, 2, 3, 4, 6, 8, 12, 16]
    out = {}
    for w in wins:
        k = max(2, int(round(rate * w / 1000.0)))
        for m in ratios:
            out[(k, max(1, int(math.ceil(k * m))), spread)] = (w, False)
    for y in (1, 2, 3, 4, 6, 8):
        out[(1, y, spread)] = (0, False)
    for w in ext_windows:
        sp = min(200, w)
        k = max(2, int(round(rate * w / 1000.0)))
        for m in ratios:
            out[(k, max(1, int(math.ceil(k * m))), sp)] = (w, True)
        for kk in (1, 2):                      # 时间分集: 把重复副本拉开到 w 毫秒, 专治突发丢包
            for y in (1, 2, 3, 4, 6, 8):
                out[(kk, y, w)] = (w, True)
    res = []
    for (k, y, sp), (w, ext) in out.items():
        if k + y > 255 or k > 200:
            continue
        pps = rate * (k + y) / float(k)
        if cap_pps and pps > cap_pps * 0.85:
            continue
        if pps * size * 8 / 1e6 > max_mbps:
            continue
        delay = (w if k > 1 else 0) + sp
        if not ext and delay > dmax:
            continue
        res.append({'k': k, 'y': y, 'spread': sp, 'window_ms': w, 'ext': ext, 'delay_ms': delay,
                    'overhead': round((k + y) / float(k), 2), 'pps': round(pps)})
    return res


def objective(st):
    """越小越好: 以"最差 10s 窗口"为主(最坏情况), 兼顾整体与恢复延迟超限."""
    w90 = st.get('win_p90_pct')
    eff = st.get('resid_eff_pct')
    if eff is None:
        return 1e9
    w90 = eff if w90 is None else w90
    return 0.6 * w90 + 0.4 * eff


def main_client(a):
    key = a.key.encode()
    host = a.host
    ts0 = datetime.datetime.now()
    tag = ts0.strftime("%Y%m%d_%H%M%S")
    outdir = a.out
    os.makedirs(outdir, exist_ok=True)
    base = os.path.join(outdir, "fectest_%s" % tag)
    rep = {'version': VER, 'start': ts0.isoformat(timespec='seconds'), 'args': {
        k: v for k, v in vars(a).items() if k not in ('key',)}, 'host_os': platform.platform(),
        'python': platform.python_version()}
    traces = {}
    lines = []

    def out(*x):
        t = " ".join(str(i) for i in x)
        lines.append(t)
        say(t)

    quick = a.quick
    rates = [int(x) for x in a.rates.split(',')] if a.rates else ([300] if quick else [100, 300, 800])
    rates = [r for r in rates if r * a.size * 8 / 1e6 <= a.max_mbps]
    soak = a.soak if a.soak else (25 if quick else 45)
    vdur = 8 if quick else 10
    ntop = 3 if quick else 6
    topn_est = len(rates) * ntop * 2 * (vdur + 2.5) + len(rates) * 2 * (soak + 2.5) + (150 if not quick else 80)
    say("=" * 64)
    say(" fectest %s   目标 %s:%d   预计耗时约 %d 分钟" % (VER, host, a.port, int(topn_est / 60) + 1))
    say(" 将向服务器收发 UDP 测试包, 总流量约 %d MB, 不会修改你电脑任何设置" % (topn_est * 0.6 * a.max_mbps / 8 / 3 + 50))
    say(" 现在是 %s. 建议晚上 20:00-23:00 测(最差时段), 测试期间请不要看视频/下载." % ts0.strftime("%H:%M"))
    say("=" * 64)
    if not a.yes:
        try:
            input("按回车开始 (Ctrl-C 取消) ... ")
        except (KeyboardInterrupt, EOFError):
            return
    isp = a.isp or input("你的运营商(电信/联通/移动/其它): ").strip()
    city = a.city or input("你的省市(例如 上海): ").strip()
    rep['isp'], rep['city'] = isp, city

    cl = Client(host, a.port, key)
    try:
        h = cl.rpc({'op': 'hello'}, tries=30)
    except Exception as e:
        say("连不上服务器: %s" % e)
        return
    rep['hello'] = h
    out("\n[0/5] 环境: 你的公网出口(服务器看到的) = %s  服务器版本 %s" % (h.get('you'), h.get('ver')))
    out("      运营商/地区(自报): %s %s" % (isp, city))
    pg = sys_ping(host, 15)
    rep['sys_ping'] = pg
    out("      系统 ICMP ping 15 次: 丢包 %s%%, 平均 %s ms" % (pg['loss_pct'], pg['avg_ms']))
    if not a.no_trace:
        out("      路由追踪(最多 60 秒)...")
        cmd = ['tracert', '-d', '-h', '24', '-w', '800', host] if os.name == 'nt' else ['traceroute', '-n', '-m', '24', '-w', '1', host]
        tr = sh(cmd, 70)
        with open(base + "_tracert.txt", 'w', encoding='utf-8') as f:
            f.write(tr)
        rep['tracert_hops'] = len([l for l in tr.splitlines() if re.match(r'\s*\d+\s', l)])

    tid = [100]

    def nt():
        tid[0] += 1
        return tid[0]

    # ---- 1 echo 延迟/抖动
    out("\n[1/5] UDP 往返延迟与抖动 (20 pps, %d 秒)" % (10 if quick else 20))
    dur = 10 if quick else 20
    pad = b'\0' * 70
    N = int(dur * 20)
    st = time.perf_counter() + 0.05
    for i in range(N):
        while time.perf_counter() < st + i / 20.0:
            time.sleep(0.001)
        cl.sock.sendto(HDR.pack(ME, cl.sid, T_ECHO, i, 0, 0, 0, 0, time.time()) + pad, cl.addr)
    time.sleep(1.5)
    es = cl.rpc({'op': 'echo_stat', 'tid': T_ECHO})
    rt = [(tr_ - ts) * 1000 for ts, tr_ in cl.echo.values()]
    srv = es.get('cnt', 0)
    up_l = 100.0 * (1 - srv / N)
    dn_l = 100.0 * (1 - len(rt) / max(1, srv))
    rep['echo'] = {'sent': N, 'srv_got': srv, 'echo_back': len(rt), 'up_loss_pct': r1(up_l), 'down_loss_pct': r1(dn_l),
                   'rtt_min': r1(min(rt)) if rt else None, 'rtt_p50': r1(pct(rt, 50)), 'rtt_p95': r1(pct(rt, 95)),
                   'rtt_p99': r1(pct(rt, 99)), 'rtt_max': r1(max(rt)) if rt else None}
    out("      RTT 最小/中位/p95/p99/最大 = %s / %s / %s / %s / %s ms" % (
        rep['echo']['rtt_min'], rep['echo']['rtt_p50'], rep['echo']['rtt_p95'], rep['echo']['rtt_p99'], rep['echo']['rtt_max']))
    out("      上行丢包 %.1f%%   下行丢包 %.1f%%" % (up_l, dn_l))

    # ---- 2 速率阶梯 + 突发
    sweep = {'up': [], 'down': []}
    if not a.skip_sweep:
        steps = [50, 100, 200, 400, 600, 800, 1000, 1250, 1600, 2000, 2500, 3200]
        steps = [r for r in steps if r * a.size * 8 / 1e6 <= a.max_mbps]
        if quick:
            steps = [r for r in steps if r in (100, 400, 800, 1250, 2000)]
        out("\n[2/5] UDP 速率阶梯 (找限速点; 每档 3 秒, 包长 %d)" % a.size)
        for dname, fn in (('up', run_up), ('down', run_down)):
            bad = 0
            base0 = None
            for r in steps:
                try:
                    st_, _ = fn(cl, nt(), 'plain', r, 3, a.size)
                except Exception as e:
                    out("      %s %d pps 失败: %s" % (dname, r, e))
                    break
                st_['rate'] = r
                sweep[dname].append(st_)
                out("      %s %5d pps (%5.1f Mbit/s): 收到 %6s pps  丢包 %5s%%  (前25%% %s%% / 后75%% %s%%)" % (
                    '上行' if dname == 'up' else '下行', r, r * a.size * 8 / 1e6, st_.get('rx_pps'),
                    st_.get('loss_pct'), st_.get('loss_head25_pct'), st_.get('loss_tail75_pct')))
                lp = st_.get('loss_pct') or 0
                if base0 is None:
                    base0 = lp
                # 只有"丢包随速率明显上升"才算触顶; 若低速时就已高丢包(随机丢包), 继续测更高速率
                bad = bad + 1 if (lp >= 40 and lp > base0 + 20) else 0
                if bad >= 2:
                    break
        rep['sweep'] = sweep
        out("      突发容忍测试 (一次性连发 N 个包, 重复 12 次):")
        rep['burst'] = {'up': {}, 'down': {}}
        for B in ((20, 60) if quick else (10, 30, 100)):
            for dname, fn in (('up', run_up), ('down', run_down)):
                try:
                    st_, fl = fn(cl, nt(), 'plain', 1000, 0, a.size, burst=(B, 12, 0.4), want_flags=True) if dname == 'up' \
                        else fn(cl, nt(), 'plain', 1000, 0, a.size, burst=(B, 12, 0.4))
                except Exception as e:
                    out("      burst %d %s 失败: %s" % (B, dname, e))
                    continue
                rep['burst'][dname][B] = {'loss_pct': st_.get('loss_pct'), 'rx_pps': st_.get('rx_pps')}
                out("        连发 %3d 个: %s 丢 %s%%" % (B, '上行' if dname == 'up' else '下行', st_.get('loss_pct')))

    def cap_of(d):
        L = sweep.get(d) or []
        if not L:
            return None
        mx = max((s_.get('rx_pps') or 0) for s_ in L)
        b0 = L[0].get('loss_pct') or 0
        w0 = L[-1].get('loss_pct') or 0
        if b0 < 8 and w0 >= 15 and w0 > b0 + 10 and mx:
            return mx       # 低速干净、高速明显丢包 => 限速
        return None         # 低速就高丢包 => 随机丢包/策略性丢包, 不是限速, 不限制冗余倍数
    caps = {'up': cap_of('up'), 'down': cap_of('down')}
    rep['caps_pps'] = caps
    out("      估计 UDP 速率上限: 上行 %s pps, 下行 %s pps (None=本次未触顶)" % (caps['up'], caps['down']))

    # ---- 3 丢包形态 (长轨迹, 每档流量)
    out("\n[3/5] 丢包形态长测 (每档流量 每方向 %d 秒): 流量档 %s pps" % (soak, rates))
    soakres = {}
    for r in rates:
        for dname, fn in (('up', run_up), ('down', run_down)):
            try:
                if dname == 'up':
                    st_, fl = fn(cl, nt(), 'plain', r, soak, a.size, want_flags=True)
                else:
                    st_, fl = fn(cl, nt(), 'plain', r, soak, a.size)
                    # 下行 flags 需要再取: run_down 内部不返回 rows, 这里重新用 analyze 的 flags
            except Exception as e:
                out("      %d pps %s 失败: %s" % (r, dname, e))
                continue
            soakres[(r, dname)] = (st_, fl)
            rep.setdefault('soak', {})["%d_%s" % (r, dname)] = st_
            traces["soak_%d_%s" % (r, dname)] = base64.b64encode(rle_enc(fl)).decode() if fl is not None else None
            out("      %4d pps %s: 丢包 %s%%  平均连丢 %s 个  最长连丢 %s 个(%s ms)  突发系数 %s  最差1秒丢 %s%%  >=30%%的秒占 %s%%" % (
                r, '上行' if dname == 'up' else '下行', st_.get('loss_pct'), st_.get('mean_run'), st_.get('max_run'),
                st_.get('max_run_ms'), st_.get('burst_idx'), st_.get('win1s_max'), st_.get('win1s_frac_ge30')))
            out("           连丢长度分布(次数): %s" % json.dumps(st_.get('run_hist'), ensure_ascii=False))

    # ---- 4 FEC 调参 (丢包优先)
    ext = [int(x) for x in a.ext_windows.split(',') if x.strip()]
    if quick:
        ext = ext[:2]
    out("\n[4/5] FEC 参数调优: 目标 = 残余丢包压到 <= %.2f%%  (丢包优先, 带宽/延迟其次)" % a.target_loss)
    out("      先在延迟预算 %d ms 内找最优; 同时探索更大窗口 %s ms, 看要多少延迟才能逼近 0 丢包" % (a.dmax, ext))
    tune = {}

    def bucket(v):
        return int(round(v / 0.05))
    for r in rates:
        for dname, fn in (('up', run_up), ('down', run_down)):
            sr = soakres.get((r, dname))
            if not sr or sr[1] is None:
                continue
            st_, fl = sr
            cands = gen_candidates(r, a.dmax, a.size, caps[dname], a.max_mbps, ext)
            scored = []
            for c in cands:
                dm = a.dmax if not c['ext'] else c['delay_ms'] + 40
                m = replay(fl, r, c['k'], c['y'], c['spread'], dm)
                scored.append((objective(m), c, m))
            inb = sorted([x for x in scored if not x[1]['ext']], key=lambda x: (bucket(x[0]), x[1]['overhead']))
            exs = sorted([x for x in scored if x[1]['ext']], key=lambda x: (bucket(x[0]), x[1]['delay_ms'], x[1]['overhead']))
            pick = inb[:ntop]
            low = [x for x in inb if x[1]['overhead'] <= 1.5]
            if low and pick and not any(c['overhead'] <= 1.5 for _, c, _ in pick):
                pick = pick[:-1] + [low[0]]
            pick += exs[:(2 if quick else 4)]
            real = []
            for o_, c, m in pick:
                vd = min(30, max(vdur, int(15 * c['delay_ms'] / 1000.0) + 5)) if c['ext'] else vdur
                dm = a.dmax if not c['ext'] else c['delay_ms'] + 40
                try:
                    if dname == 'up':
                        s2, _ = run_up(cl, nt(), 'fec', r, vd, a.size, c['k'], c['y'], c['spread'], dm, wsec=3.0)
                    else:
                        s2, _ = run_down(cl, nt(), 'fec', r, vd, a.size, c['k'], c['y'], c['spread'], dm, wsec=3.0)
                except Exception as e:
                    out("      验证失败 %s" % e)
                    continue
                real.append({'cand': c, 'replay': m, 'real': s2, 'obj_real': objective(s2)})
                out("      %s %4dpps %s%3d:%-3d 开销%.2fx 延迟≈%dms | 回放预测残余 %s%% | 真实 残余 %s%% (最差窗口 %s%%) 冗余包自然丢 %s%%" % (
                    '上行' if dname == 'up' else '下行', r, '[超预算]' if c['ext'] else '        ', c['k'], c['y'],
                    c['overhead'], c['delay_ms'], m.get('resid_eff_pct'), s2.get('resid_eff_pct'),
                    s2.get('win_max_pct'), s2.get('shard_loss_par_pct')))
            tune["%d_%s" % (r, dname)] = {'baseline_loss_pct': st_.get('loss_pct'), 'top_replay': [
                {'cand': c, 'replay': m} for _, c, m in (inb[:10] + exs[:10])], 'validated': real}

    rep['tune'] = tune

    # ---- 5 结论
    out("\n[5/5] 结论 / 推荐参数  (丢包优先; 目标残余 <= %.2f%%)" % a.target_loss)
    reco = {}
    for key_, T in tune.items():
        V = T['validated']
        if not V:
            continue
        base_loss = T['baseline_loss_pct']
        A = sorted([v for v in V if not v['cand']['ext']], key=lambda v: (bucket(v['obj_real']), v['cand']['overhead']))
        goal = [v for v in V if (v['real'].get('resid_eff_pct') is not None and v['real']['resid_eff_pct'] <= a.target_loss)]
        goal.sort(key=lambda v: (v['cand']['delay_ms'], v['cand']['overhead']))
        Bc = goal[0] if goal else sorted(V, key=lambda v: (bucket(v['obj_real']), v['cand']['delay_ms'], v['cand']['overhead']))[0]
        out("      %s  (基线丢包 %s%%)" % (key_, base_loss))
        R = {}
        if A:
            c = A[0]['cand']
            out("        A) 延迟预算 %d ms 内最优: -f %d:%d  带宽 %.2fx  真实残余 %s%%  最差窗口 %s%%" % (
                a.dmax, c['k'], c['y'], c['overhead'], A[0]['real'].get('resid_eff_pct'), A[0]['real'].get('win_max_pct')))
            R['within_budget'] = {'f': "%d:%d" % (c['k'], c['y']), 'overhead': c['overhead'], 'delay_ms': c['delay_ms'],
                                  'residual_pct': A[0]['real'].get('resid_eff_pct')}
        c = Bc['cand']
        out("        B) %s: -f %d:%d  带宽 %.2fx  额外延迟≈%d ms  真实残余 %s%%  最差窗口 %s%%%s" % (
            "达到目标的最小延迟档" if goal else "未达目标, 已测到的最低丢包档", c['k'], c['y'], c['overhead'], c['delay_ms'],
            Bc['real'].get('resid_eff_pct'), Bc['real'].get('win_max_pct'),
            "" if goal else "   <-- 需要更大窗口或更多冗余, 受限于上行/下行限速或延迟"))
        R['min_loss'] = {'f': "%d:%d" % (c['k'], c['y']), 'overhead': c['overhead'], 'delay_ms': c['delay_ms'],
                         'spread_ms': c['spread'], 'residual_pct': Bc['real'].get('resid_eff_pct'), 'reached_target': bool(goal)}
        reco[key_] = R
    rep['recommend'] = reco
    out("\n      说明: 上行(你->服务器)由你本机的 speeder 发送, 下行(服务器->你)由服务器端 speeder 发送, 两个方向可以用不同的 -f.")
    out("      '延迟'≈组窗口+冗余包展开时间, 约等于 --timeout(毫秒, 单位以 speederv2 --help 为准).")
    out("      注意: 真实残余是单次短时采样, 仅供参考; 完整原始轨迹已保存, 可离线继续精调.")

    rep['end'] = datetime.datetime.now().isoformat(timespec='seconds')
    with open(base + "_report.txt", 'w', encoding='utf-8') as f:
        f.write("\n".join(lines))
    with open(base + "_report.json", 'w', encoding='utf-8') as f:
        json.dump(rep, f, ensure_ascii=False, indent=1, default=str)
    with gzip.open(base + "_traces.json.gz", 'wt') as f:
        json.dump(traces, f)
    say("\n完成. 请把下面这几个文件发回来:")
    for suf in ("_report.txt", "_report.json", "_traces.json.gz", "_tracert.txt"):
        if os.path.exists(base + suf):
            say("   " + os.path.abspath(base + suf))


# ---------------------------------------------------------------- 入口
def main():
    ap = argparse.ArgumentParser(description="UDP/FEC 全面测试")
    sp = ap.add_subparsers(dest='cmd')
    s = sp.add_parser('server')
    s.add_argument('--key', required=True)
    s.add_argument('--port', type=int, default=5201)
    s.add_argument('--logdir')
    c = sp.add_parser('client')
    c.add_argument('host')
    c.add_argument('--port', type=int, default=5201)
    c.add_argument('--key', required=True)
    c.add_argument('--isp', default='')
    c.add_argument('--city', default='')
    c.add_argument('--rates', default='', help="数据流量档(包/秒), 逗号分隔, 默认 100,300,800")
    c.add_argument('--size', type=int, default=1000)
    c.add_argument('--dmax', type=int, default=100, help="优先考虑的额外延迟预算(ms); 同时会探索更大窗口")
    c.add_argument('--target-loss', type=float, default=0.1, dest='target_loss', help="目标残余丢包率(%%)")
    c.add_argument('--ext-windows', default='150,300,600,1000', dest='ext_windows', help="超出预算的大窗口(ms)")
    c.add_argument('--soak', type=int, default=0, help="每档每方向长测秒数")
    c.add_argument('--max-mbps', type=float, default=10.0, dest='max_mbps')
    c.add_argument('--quick', action='store_true')
    c.add_argument('--skip-sweep', action='store_true')
    c.add_argument('--no-trace', action='store_true')
    c.add_argument('--out', default='.')
    c.add_argument('--yes', action='store_true')
    t = sp.add_parser('selftest')
    a = ap.parse_args()
    if a.cmd == 'server':
        Server(a.key.encode(), a.port, logdir=a.logdir).run()
    elif a.cmd == 'client':
        try:
            main_client(a)
        except KeyboardInterrupt:
            say("\n已中断.")
    elif a.cmd == 'selftest':
        srv = Server(b'selftest', 0, sim=(0.25, 80, 700), bind='127.0.0.1')
        threading.Thread(target=srv.run, daemon=True).start()
        srv.ready.wait()
        ns = argparse.Namespace(host='127.0.0.1', port=srv.port, key='selftest', isp='自测', city='本机', rates='200,500',
                                size=500, dmax=100, target_loss=0.1, ext_windows='150,300', soak=12, max_mbps=10.0, quick=True, skip_sweep=False,
                                no_trace=True, out='/tmp/fectest_selftest', yes=True)
        main_client(ns)
    else:
        ap.print_help()


if __name__ == '__main__':
    main()
