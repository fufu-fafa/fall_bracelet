# Relay (Raspberry Pi)

Takes the place of Firebase in the PRD. The wearer's phone uploads events and a heartbeat every
5 minutes. Each caregiver's phone keeps a live Server-Sent Events stream open and rings the moment
a FALL or SOS arrives. Node 22 and its built-in SQLite, no packages.

| | |
|---|---|
| `server.mjs` | The relay. `PORT` (default 8787), `DB_PATH` and `LAN_ONLY` (default on) env vars |
| `web/index.html` | Caregiver mode in a browser, served at `/` (see below) |
| `test.mjs` | End-to-end test against a throwaway database |
| `fall-bracelet-relay.service` | systemd unit for the Pi |

## Run on the Pi

```bash
mkdir -p ~/fall-bracelet-relay/web && cp server.mjs test.mjs ~/fall-bracelet-relay/ && cp web/index.html ~/fall-bracelet-relay/web/
node --disable-warning=ExperimentalWarning ~/fall-bracelet-relay/test.mjs
sed "s/YOUR_PI_USER/$USER/g" fall-bracelet-relay.service | sudo tee /etc/systemd/system/fall-bracelet-relay.service >/dev/null
sudo systemctl daemon-reload && sudo systemctl enable --now fall-bracelet-relay
```

For now the relay only answers phones on the home network: requests from public addresses get
`403`. Set `LAN_ONLY=0` once it sits behind HTTPS.

**Changing WiFi (e.g. a phone hotspot at a demo):** join the Pi to the new network. The app finds
the relay by itself: when its address stops answering, each phone asks every address on its own
WiFi for `/v1/health` and switches to the one that replies with this relay's `relayId`, keeping its
links. Settings and the caregiver link screen also have "Find relay on this WiFi".

Debug builds of the app point at `http://192.168.0.104:8787` (`app/src/debug/res/values/relay.xml`)
and allow plain HTTP. Anything outside the home network needs HTTPS, e.g. a Cloudflare Tunnel.

## Caregiver mode in a browser

Open `http://<pi address>:8787/` on any phone or laptop on the same WiFi. It does what the app's
Caregiver mode does: link with the wearer's 6-digit invite code, a live stream with a full-screen
alarm and Acknowledge, and a dashboard per wearer (online, bracelet, state, battery, last check-in,
history). The token stays in that browser's storage, so a reload keeps the link.

A browser can't wake a locked phone the way the app's full-screen alarm does. The page rings
(after one tap, which browsers require before playing sound), vibrates on Android, flashes and
keeps the screen on only while it is open in the foreground. Notifications and the screen wake
lock need HTTPS, so on plain `http://` on the LAN they're off; the page says what's missing.
The page is read on every request, so copying a new `web/index.html` needs no restart.

## API

Every phone gets a random bearer token; only its SHA-256 is stored.

| Call | Who | What |
|---|---|---|
| `POST /v1/wearers` | wearer phone | Register, returns `token` |
| `PUT /v1/wearers/me` | wearer | Rename (the wearer name caregivers see) |
| `POST /v1/invites` | wearer | 6-digit code, single use, 15 min |
| `POST /v1/link` | caregiver | `{code, name}`; returns a caregiver token (send an existing one to follow another wearer) |
| `GET /v1/caregivers`, `DELETE /v1/caregivers/:id` | wearer | List / remove linked caregivers |
| `POST /v1/events` | wearer | Idempotent on (wearer, deviceAddress, seq) |
| `POST /v1/heartbeat` | wearer | Latest status; reply carries acknowledgements and the caregiver count |
| `GET /v1/dashboard` | caregiver | Each followed wearer with status and the last 100 events |
| `POST /v1/events/:id/ack` | caregiver | "Seen by …" |
| `GET /v1/stream` | caregiver | SSE: `hello`, `event`, `status`, `ack`, `offline`, `online`, `unlinked`. `Last-Event-ID` replays the last 24 h |

Timers: a wearer with no heartbeat for 15 min is pushed to caregivers as `offline`. A FALL or SOS
nobody acknowledged is pushed again every 2 min, up to 5 times.
