# Relay (Raspberry Pi)

Takes the place of Firebase in the PRD. The wearer's phone uploads events and a heartbeat every
5 minutes. Each caregiver's phone keeps a live Server-Sent Events stream open and rings the moment
a FALL or SOS arrives. Node 22 and its built-in SQLite, no packages.

| | |
|---|---|
| `server.mjs` | The relay. `PORT` (default 8787) and `DB_PATH` env vars |
| `test.mjs` | End-to-end test against a throwaway database |
| `fall-bracelet-relay.service` | systemd unit for the Pi |

## Run on the Pi

```bash
mkdir -p ~/fall-bracelet-relay && cp server.mjs test.mjs ~/fall-bracelet-relay/
node --disable-warning=ExperimentalWarning ~/fall-bracelet-relay/test.mjs
sudo cp fall-bracelet-relay.service /etc/systemd/system/
sudo systemctl enable --now fall-bracelet-relay
```

Debug builds of the app point at `http://192.168.0.104:8787` (`app/src/debug/res/values/relay.xml`)
and allow plain HTTP. Anything outside the home network needs HTTPS, e.g. a Cloudflare Tunnel.

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
