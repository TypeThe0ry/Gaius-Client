# Public RelayNode registry

Gaius discovers public translator nodes from `relay-nodes.json`. The online
client reads the registry served beside `index.html`; the portable HTML embeds
the same node snapshot and also checks the curated registry on GitHub. Users
can add private registries with repeated `relayRegistry` URL parameters or the
`gaius.relayRegistries` local-storage entry.

The registry format is intentionally small:

```json
{
  "kind": "gaius-relay-registry",
  "protocolVersion": 1,
  "registries": [
    "https://registry.example/relay-nodes.json"
  ],
  "nodes": [
    {
      "name": "Example public node",
      "url": "wss://relay.example/tunnel",
      "priority": 0
    }
  ]
}
```

`registries` is optional. It lets the curated GitHub file bootstrap a live
lease registry without requiring a new client build. Discovery is bounded to
32 registry URLs and 64 unique RelayNodes, and cycles are ignored. A live
registry entry expires automatically when its RelayNode stops renewing its
lease.

Before adding a node, expose `GET /relay-node/v1` over HTTPS and `/tunnel` over
WSS. The manifest must report protocol version 1, positive available capacity,
and the `tcp-tunnel` capability. Public nodes must use destination policy,
origin policy, connection limits, rate limiting, traffic accounting, TLS, and
an abuse contact. Do not publish a bearer token in this registry. Traffic
accounting is built in: set `GAIUS_MAXIMUM_BANDWIDTH_PER_CONNECTION`,
`GAIUS_MAXIMUM_BANDWIDTH_TOTAL`, and `GAIUS_MAXIMUM_TRANSFER_PER_CONNECTION`
on the node and keep `GAIUS_TRAFFIC_STATS` enabled so the manifest reports
live totals under `traffic`. Registries may enforce this with
`GAIUS_REGISTRY_REQUIRE_TRAFFIC_LIMITS=1`. Set
`GAIUS_ABUSE_CONTACT` (a `mailto:` address or an `https:` URL) on the node to
publish it in the manifest at `abuseContact`; a registry started with
`GAIUS_REGISTRY_REQUIRE_ABUSE_CONTACT=1` rejects nodes that omit it.

Contributors add or remove nodes by changing the root `relay-nodes.json` and
opening a pull request. CI and the browser routing smoke validate the schema,
deduplicate endpoints, cap discovery at 64 nodes, probe capacity, and fail over
when a listed node is unavailable. Listing a node only selects a translator;
every status ping, login, and player session still receives a separate
WebSocket and TCP connection.

## Live registry deployment

The RelayNode package also contains the registry process. Run it behind HTTPS
with a management token that is never sent to a browser:

```sh
GAIUS_REGISTRY_HOST=0.0.0.0 \
GAIUS_REGISTRY_PORT=8083 \
GAIUS_REGISTRY_TOKEN='replace-with-a-long-random-secret' \
npm run start:registry
```

Configure each public RelayNode to renew a lease:

```sh
GAIUS_RELAY_NODE_ID='contributor-node-1' \
GAIUS_RELAY_NODE_NAME='Contributor RelayNode' \
GAIUS_RELAY_PUBLIC_URL='wss://relay.example/tunnel' \
GAIUS_RELAY_REGISTRY_URL='https://registry.example/relay-registry/v1/nodes/' \
GAIUS_RELAY_REGISTRY_TOKEN='replace-with-a-long-random-secret' \
npm start
```

The registry verifies `/relay-node/v1` before accepting a lease. By default it
rejects non-WSS, private-network, unreachable, token-protected, incompatible,
or private-target-enabled nodes. `GAIUS_REGISTRY_ALLOW_PRIVATE_NODES=1` and
`GAIUS_RELAY_ALLOW_INSECURE_REGISTRATION=1` exist only for loopback tests or a
trusted private container network. Set
`GAIUS_REGISTRY_REQUIRE_TRAFFIC_LIMITS=1` to reject nodes whose manifest does
not enable traffic accounting. Nodes can also export OpenTelemetry metrics
privately: `GAIUS_OTEL_PORT` serves OTLP/HTTP JSON at `GET /v1/metrics` on a
separate loopback-bound listener, and `GAIUS_OTEL_EXPORTER_URL` pushes the
same OTLP payloads to a collector on an interval. Public RelayNodes should bind publicly with
`GAIUS_ALLOW_PRIVATE_TARGETS=0`; wildcard destination policy then reaches
arbitrary public Minecraft servers without exposing private network services.
Normal RelayNode SIGINT/SIGTERM shutdown sends an authenticated `DELETE` for its
lease before closing. A crash cannot do that, so the registry still expires
unrenewed entries by TTL.

The registry does not make an offline or private Java server reachable. The
selected RelayNode must be able to resolve and connect to the entered host, and
the server's normal version, authentication, allow-list, proxy, and resource
pack rules still apply.

The browser first tries the optional same-host Gaius plugin. If that endpoint is
absent, it asks discovered public RelayNodes for target-specific affinity and
capacity, reuses a node that already serves the normalized `host:port`, or
selects an available node and opens the first temporary tunnel there. Reuse is
node affinity only: each player receives an isolated WebSocket and TCP stream.
The WebSocket is the tunnel lease, so leaving the server closes that player's
TCP connection; after the last player leaves, only expiring target-selection
metadata remains on the long-running RelayNode.

## Turnkey public deployment

`apps/bridge/compose.public.example.yaml` is the production-facing template.
It keeps the registry lease endpoint and both Node.js ports on the private
Compose network; `apps/bridge/Caddyfile.public.example` exposes only HTTPS/WSS
client endpoints. Start from `apps/bridge/public.env.example`, point both DNS
names at the node, and run:

```sh
docker compose --env-file .env \
  -f compose.public.example.yaml up -d --build
```

Verify `https://<registry-domain>/relay-nodes.json` and
`https://<relay-domain>/relay-node/v1` before adding the registry URL to the
root `relay-nodes.json`. The latter manifest must show positive available
capacity, `requiresToken: false`, and `allowsPrivateTargets: false`. Downloaded
clients use the `null` origin, so keep `null` in `GAIUS_ALLOWED_ORIGINS` when the
node is intended for the portable HTML.
