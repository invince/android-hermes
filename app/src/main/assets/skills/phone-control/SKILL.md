---
name: phone-control
description: Control the Android phone (tap/swipe/type/screenshot/apps/settings) from inside the Hermes rootfs via the local control API.
---

# Phone control (Android)

Hermes runs inside a proot rootfs on the phone. The Android side exposes a
loopback control API at `http://127.0.0.1:8788`, protected by a token file that
is bind-mounted into the rootfs.

## Auth

Every request must carry the token:

```sh
TOKEN=$(cat /root/.hermes/control-token)
curl -s -H "X-Hermes-Token: $TOKEN" "http://127.0.0.1:8788/health"
```

Use `terminal` to run these. Prefer the JSON result over guessing.

## Endpoints

| Method | Path | Notes |
|---|---|---|
| GET | `/screen` | returns `{width,height}` |
| GET | `/tap?x=&y=` | tap |
| GET | `/longpress?x=&y=&ms=` | long press |
| GET | `/swipe?x1=&y1=&x2=&y2=&ms=` | swipe |
| GET | `/text?text=` | type text (`%20`/`+` decode to space) |
| GET | `/key?code=` | key event, e.g. `KEYCODE_HOME`, `KEYCODE_BACK` |
| GET | `/screenshot` | returns `{host,guest}`; `guest` is readable here, e.g. `/root/.hermes/shots/shot-*.png` |
| GET | `/launch?component=com.android.settings/.Settings` | start activity |
| GET | `/open?url=https://...` | open URL |
| GET | `/settings?ns=global&k=&v=` | `settings put` |
| GET | `/logcat?n=50` | recent logcat |
| GET | `/battery` | battery dump |

## Loop

1. `GET /screenshot`, then read the returned `guest` path with your image tool.
2. Decide coordinates from the screenshot.
3. `GET /tap?x=..&y=..` (or `/swipe`).
4. Screenshot again to confirm.

Example: open Settings then tap the search box.

```sh
TOKEN=$(cat /root/.hermes/control-token)
H="-H X-Hermes-Token:\ $TOKEN"
curl -s $H "http://127.0.0.1:8788/launch?component=com.android.settings/.Settings"
curl -s $H "http://127.0.0.1:8788/screenshot"
```
