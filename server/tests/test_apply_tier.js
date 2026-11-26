// The level picker of the admin page rewrites the client command like the Android app does. Run: node server/tests/test_apply_tier.js
const fs = require('fs'), path = require('path'), assert = require('assert');
for (const which of ['hub', 'exit']) {
  const src = fs.readFileSync(path.join(__dirname, '..', which, 'static', 'app.js'), 'utf8');
  const a = src.indexOf('  function setLong('), b = src.indexOf('  function showConfig(');
  assert(a > 0 && b > a, which + ': helper not found');
  const applyTier = new Function(src.slice(a, b) + '\nreturn applyTier;')();
  const max = { n: 'Maximum', l: 68, p: 62944, u: '1:2,2:2,4:3,8:4,16:6,32:9', t: 10, i: 0, d: 12000, s: 4096 };
  const cmd = 'speederv2 -c -l127.0.0.1:51821 -r203.0.113.5:62932 -k abcdefgh12 -f1:1,2:1,4:1 --timeout 15 -i 5 --mode 0 --mtu 1250 --decode-buf 2000 --sock-buf 1024 --report 60';
  const want = 'speederv2 -c -l127.0.0.1:51821 -r203.0.113.5:62944 -k abcdefgh12 -f1:2,2:2,4:3,8:4,16:6,32:9 --timeout 10 -i 0 --mode 0 --mtu 1250 --decode-buf 12000 --sock-buf 4096 --report 60';
  assert.strictEqual(applyTier(cmd, max), want, which + ': full command');
  const old = 'speederv2 -c -l127.0.0.1:51821 -r203.0.113.5:62932 -k abcdefgh12 -f1:1 --timeout 15 -i 5 --mode 0 --report 60';
  assert.strictEqual(applyTier(old, max),
    'speederv2 -c -l127.0.0.1:51821 -r203.0.113.5:62944 -k abcdefgh12 -f1:2,2:2,4:3,8:4,16:6,32:9 --timeout 10 -i 0 --mode 0 --decode-buf 12000 --sock-buf 4096 --report 60', which + ': buffers added before --report');
  assert.strictEqual(applyTier(cmd, null), cmd, which + ': no level leaves the command alone');
  assert.strictEqual(applyTier(applyTier(cmd, max), max), want, which + ': applying twice gives the same command');
  console.log('PASS ' + which);
}
console.log('ALL OK');
