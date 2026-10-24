# QoE Probe (Android)

Field test app for video QoE on **YouTube, Facebook and Instagram**, plus HTTP throughput,
ICMP latency/packet loss and a 1 s coverage logger. Every test writes CSV with serving-cell
radio (LTE/NR/WCDMA/GSM) and GPS on each row.

Min Android 10 (API 29). No root needed.

## Build the APK

**Option A – GitHub (no local SDK):** push this folder to a GitHub repo → *Actions* tab →
`build-apk` runs automatically → download the `QoEProbe-apk` artifact → install `app-debug.apk`.

**Option B – Android Studio:** File › Open › this folder → wait for sync → Build › Build APK(s).

**Option C – Windows CMD** (Android Studio or SDK installed):
```
cd QoEProbe
echo sdk.dir=C:\\Users\\%USERNAME%\\AppData\\Local\\Android\\Sdk> local.properties
gradlew.bat assembleDebug
```
APK: `app\build\outputs\apk\debug\app-debug.apk` (JDK 17+ required).

## How video measurement works

The official YouTube/FB/IG apps are sandboxed, so no third-party app can read their
player stats. QoE Probe instead plays the **same content via the platforms' mobile-web
players** inside an instrumented WebView (Chromium). All three use MSE/DASH-style ABR on
the web, so a script injected at document start hooks the media pipeline:

| Metric | Source |
|---|---|
| Video / audio codec (vp09, av01, avc1, mp4a, opus…) and codec changes | `MediaSource.addSourceBuffer` / `SourceBuffer.changeType` |
| Media bitrate (ABR) | bytes passed to `SourceBuffer.appendBuffer` per track |
| Resolution & ABR switches | `videoWidth/videoHeight` changes |
| Startup (TTFF, join time), stalls, stall time, rebuffer ratio | playhead progress every 500 ms + media events |
| Buffer ahead, dropped/total frames | `buffered`, `getVideoPlaybackQuality()` |
| YouTube quality label (hd720, hd1080…) | `movie_player.getPlaybackQuality()` |
| App downlink throughput | `TrafficStats` for the app UID |

Results reflect the web player's ABR logic, which is close to — but not identical to — the native apps.

## Logins

| Platform | Login | Notes |
|---|---|---|
| YouTube | Not needed | Google blocks sign-in inside embedded browsers; public videos only (no age-restricted/members). |
| Facebook | **Login Facebook** button | Public videos partly work logged-out; log in for reliable playback. Cookie `c_user` = logged in. |
| Instagram | **Login Instagram** button | Effectively required. Cookie `sessionid` = logged in. |

Use dedicated test accounts — automated-looking repeated playback can trigger checkpoints.
The UA's WebView markers are stripped so the normal mobile site is served. "Clear all logins" wipes cookies/storage.

## Tests and CSV outputs

All files go to `Android/data/com.qoeprobe.app/files/csv/`; **Export** copies new/updated
files to `Download/QoEProbe/`.

| File | Content |
|---|---|
| `video_samples_<platform>_<id>.csv` | 500 ms rows: state, position, buffer, resolution, codecs, dropped frames, media kbps, app rx Mbps, stall count/time + radio + GPS |
| `video_events_<platform>_<id>.csv` | page load, first frame, stall start/end, res switch, codec changes, media errors |
| `video_summary.csv` | one row per test: TTFF, join time, stalls, rebuffer %, stalls/min, switches, avg/min/max height, codecs, dropped %, avg media kbps, avg/peak Mbps, PCIs, avg RSRP/RSRQ/SINR, NR SS-RSRP/SINR, logged_in |
| `throughput_DL|UL_<id>.csv` | per-second Mbps + radio |
| `ping_<id>.csv` | per-packet status (ok/lost), RTT + radio |
| `network_summary.csv` | one row per DL/UL/ping test: avg/peak/p10/p50/p90 Mbps, loss %, RTT min/avg/max/p95, jitter + radio |
| `coverage_<id>.csv` | 1 s serving cell + GPS + neighbours (`L<pci>:<earfcn>:<rsrp>;N…`) |

Radio columns: `rat` (LTE, LTE+NR for NSA, NR, WCDMA, GSM), `data_net`, `mcc/mnc/tac/cell_id`,
`pci`, `arfcn`, `band`, `bw_khz`, `rsrp`, `rsrq`, `sinr`, `cqi`, `ta`, `rssi`, `rscp`, `ecno`,
`nr_*`, `lat/lon/acc_m/speed_kmh`. Blank = not reported by the modem.

**Stall definition:** playback started, not paused/seeking/ended, playhead advanced < 25 % of
real time over a 500 ms sample. Rebuffer ratio = stall time / (play + stall time).

## Notes & limits

- Keep **Location ON** (Android requires it for cell info). Grant Location + Phone permissions.
- Cell info refresh rate is set by the modem/OEM (often 1–10 s); some OEMs report fewer fields.
- Dual-SIM: first registered cell is logged.
- Throughput defaults to Cloudflare's speed endpoints; point DL/UL URLs at your own HTTP server for controlled tests (UL needs a POST sink).
- Packet loss uses ICMP (`/system/bin/ping`); some targets rate-limit ICMP — prefer a server you control. UDP loss would need a dedicated server.
- If the WebView is very old (no document-start script support) the hook is injected at page start and the first codec event may be missed; update "Android System WebView" from Play.
