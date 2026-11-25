# The app string

The admin page's **Android** tab produces one line that carries everything the app needs to build a profile.

```
wgfec://1/<base64url(JSON)>          plain
wgfec://1z/<base64url(zlib(JSON))>   zlib-compressed (browsers that support CompressionStream use this)
```

`base64url` is the URL-safe alphabet, without padding. The JSON:

| Field | Meaning |
|---|---|
| `v` | Format version, currently `1`. The app refuses other versions. |
| `name` | Suggested profile name. The app strips characters outside letters, digits, space, `. _ @ -`. |
| `wg` | The WireGuard client config (the `[Interface]` and `[Peer]` sections). |
| `sp` | Optional. The `speederv2 -c ...` client command. Absent for a direct tunnel: the app then runs plain WireGuard without FEC. |
| `tiers` | Optional, only with `sp`. The server's FEC levels, weakest first, so the app can switch between them: `n` name, `l` the link loss it is meant for (%), `p` server UDP port, `u` / `t` / `i` uplink FEC table, max wait and interleave, `d` / `s` decode buffer and socket buffer. |

The app adds `ExcludedApplications = <its own package>` to the config so the helper process bypasses the tunnel, runs
`speederv2` with the arguments from `sp` (the binary name is replaced by the bundled one), then starts WireGuard.

The string contains the device's **private key**. A `wgfec://` link opened from another app, or text pasted in the
app, only ever asks for confirmation before it adds a profile; it never connects by itself.

The decoder limits the inflated size to 64 KiB.
