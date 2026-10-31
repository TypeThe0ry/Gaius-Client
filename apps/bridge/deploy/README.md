# Linux RelayNode deployment

This directory is a reproducible, key-free deployment template for one public
Gaius RelayNode. It runs the Node process on 127.0.0.1:8080 and puts Nginx in
front of it for TLS and the browser-facing API. It does not run a Minecraft
server, a registry management service, or a downloaded game client.

The public node accepts arbitrary public Java server targets by default.
Private, loopback, link-local, carrier-grade NAT, multicast, and reserved
targets remain blocked by GAIUS_ALLOW_PRIVATE_TARGETS=0. Keep that setting on
an Internet-facing node.

This release's public deployment uses `GAIUS_ALLOWED_ORIGINS=*`, an explicit
open-relay setting. It accepts every browser Origin, including HTTPS/HTTP
sites, `null`, and `file://`; it does not enable credentials. Host allow-list,
private-target blocking, rate limits, abuse reporting, and protocol attestation
remain enforced. Requests without an Origin are still accepted by the read-only
health/manifest endpoints for operational probes, rejected by `/tunnel`, and
rejected by `/proxy/*` before upstream access.

## Files

- gaius-relaynode.service: systemd unit with explicit working directory,
  executable, and environment file.
- env.example: public-safe defaults and bounded connection, frame, cache, and
  timeout settings. It contains no password or token.
- docker-single.env.example and docker-single-compose.example.yaml: single
  Docker container with container port 8080 bound only to host loopback port
  18080.
- docker-single-deploy.sh and docker-single-rollback.sh: mutually exclusive
  image deployment, preserved old/failed containers, health/manifest gate, and
  verified rollback.
- verify-dnat.sh: read-only IPv4/IPv6 NAT audit that rejects an unscoped
  PREROUTING DNAT rule.
- nginx-gaius-relay.conf: systemd TLS reverse proxy for only /tunnel,
  /relay-node/v1, /relay-node/v1.runtime, /health, and /proxy/.
- nginx-gaius-relay-docker.conf: the same proxy locations (including the
  runtime telemetry endpoint) pointing to the Docker loopback port 18080.
- verify-runtime.sh: read-only Linux audit for the listener, PID, cwd,
  running target-attestation code, health, and manifest. It supports both a
  systemd Node listener and a Docker-published listener whose docker-proxy cwd
  is `/`.
- verify-public-origin.sh: read-only public CORS check for browser origins.
- ellan-site.env.example: checked-in, non-secret values for the current
  `typethe0ry.github.io` + `ellan.site` deployment.

The registry lease write endpoint /relay-registry/v1/nodes/ is intentionally not
proxied by the Nginx example. If a registry is used, expose its read-only
manifest from a separate controlled service and keep management writes on a
private network.

## Prerequisites

Use a Linux host with systemd, Nginx, TLS certificates, `ss`, `curl`, and
Node.js 22 or newer. Docker deployment additionally requires Docker Engine,
`flock`, and `mktemp`. The service account and paths used by the templates are:

    user/group: gaius:gaius
    application: /opt/gaius/apps/bridge
    environment: /etc/gaius/relaynode.env
    state: /var/lib/gaius-relaynode
    listener: 127.0.0.1:8080

Create the account and directories once:

    sudo useradd --system --home-dir /opt/gaius --shell /usr/sbin/nologin gaius
    sudo install -d -o gaius -g gaius /opt/gaius/apps/bridge
    sudo install -d -o root -g gaius -m 0750 /etc/gaius

## Upload and install

Run the upload from a checked-out release on the operator machine. Replace the
SSH account and hostname with the deployment host; the commands below are
examples and do not contain credentials.

    rsync -a --delete --exclude node_modules/ --exclude '*.log' --exclude target/ apps/bridge/ deploy@relay.example.com:/opt/gaius/apps/bridge/
    scp apps/bridge/deploy/gaius-relaynode.service deploy@relay.example.com:/tmp/gaius-relaynode.service
    scp apps/bridge/deploy/env.example deploy@relay.example.com:/tmp/gaius-relaynode.env.example
    scp apps/bridge/deploy/nginx-gaius-relay.conf deploy@relay.example.com:/tmp/nginx-gaius-relay.conf
    scp apps/bridge/deploy/verify-runtime.sh deploy@relay.example.com:/opt/gaius/apps/bridge/verify-runtime.sh

On the Linux host, install the environment file as root, edit only the public
origin and node identity, and make it unreadable by other users:

    sudo install -o root -g gaius -m 0640 /tmp/gaius-relaynode.env.example /etc/gaius/relaynode.env
    sudoedit /etc/gaius/relaynode.env
    sudo chown -R gaius:gaius /opt/gaius/apps/bridge
    sudo chmod 0750 /opt/gaius/apps/bridge/verify-runtime.sh

Do not add GAIUS_BRIDGE_TOKEN to a public node. Browser clients cannot safely
receive a shared public secret. If registering with a private registry, add the
registry URL, node ID, public WSS URL, and a generated token only to the
root-owned environment file. Never commit those values.

## Install dependencies and validate

The RelayNode package is deployed with `package-lock.json` for reproducible
installation. Do not replace `npm ci` with an unlocked install on a production
public node.

    cd /opt/gaius/apps/bridge
    test -f package-lock.json
    npm ci --omit=dev
    node --version
    node --check dist/config.js
    node --check dist/policy.js
    node --check dist/main.js
    node --check dist/registry.js

Run the local protocol and routing checks from a repository checkout before a
release is uploaded (the smoke scripts also use `port/scripts`, which is not part
of the deployed bridge directory):

    cd apps/bridge
    npm ci
    npm run smoke

## Single-container Docker deployment

This is the deployment shape for a host that runs one RelayNode container and
keeps the public TLS proxy outside Docker. The process listens on `0.0.0.0:8080`
inside the container; Docker publishes it only as `127.0.0.1:18080` on the host.
The host must not publish container port 8080 directly to the Internet.

On the deployment host, from `apps/bridge`:

    cp deploy/docker-single.env.example deploy/docker-single.env
    # Edit only the public origin, node identity, and non-secret limits.
    chmod 0640 deploy/docker-single.env
    chmod 0750 deploy/docker-single-deploy.sh deploy/docker-single-rollback.sh
    GAIUS_DOCKER_HOST_PORT=18080 deploy/docker-single-deploy.sh

The script builds the image from the lockfile-backed Dockerfile and digest-pinned
multi-architecture Node base image. It holds a host deployment lock, renames the
existing container to `gaius-relaynode-previous`, starts the new container, and
requires `/health` to report the RelayNode kind and `/relay-node/v1` to report
protocol version 1 and the `target-attestation` capability. If either check
fails, it preserves the failed release under a timestamped container name and
restores and verifies the previous one. It never prints the environment file or
its values. Keep the previous container until the two-target connection
acceptance test has completed.

For a manual Compose start using the same loopback mapping:

    cp deploy/docker-single-compose.example.yaml deploy/docker-single-compose.yaml
    docker compose --env-file deploy/docker-single.env \
      -f deploy/docker-single-compose.yaml up -d --build

The deployment script is preferred for releases because it performs the
old-container backup and automatic rollback. The Compose file is a minimal
repeatable reference, not a replacement for the release gate.

Install `nginx-gaius-relay-docker.conf` for this mode and replace only the
example hostname and certificate paths. It proxies TLS/WSS and HTTP checks to
`127.0.0.1:18080`; do not use the systemd config unchanged, because that
config targets the systemd listener on port 8080.

To audit the Docker mode without exposing environment values:

    sudo GAIUS_VERIFY_RUNTIME=docker GAIUS_VERIFY_PORT=18080 \
      GAIUS_VERIFY_BASE_URL=http://127.0.0.1:18080 \
      bash deploy/verify-runtime.sh

The audit accepts a host `docker-proxy` listener with cwd `/`, then resolves
the single published container, checks its `/app` working directory and loaded
entrypoint, and checks health and manifest through the loopback port.

## NAT and Docker egress safety

Before accepting a public Docker RelayNode, inspect the host NAT table. Any
Minecraft inbound DNAT must match the public entry interface, for example
`-i eth0`; a DNAT rule without `-i` can also match the RelayNode's arbitrary
outbound connections during `PREROUTING` and redirect them to the local
Minecraft service. The check is read-only and does not assume a destination
address or a particular public interface:

    sudo bash deploy/verify-dnat.sh

The script is equivalent to reviewing the output of:

    sudo iptables-save -t nat
    sudo ip6tables-save -t nat

It scans every IPv4 and available IPv6 `PREROUTING` rule with `-j DNAT` and
fails if any lacks an explicit ingress-interface match. If IPv6 forwarding is
enabled but `ip6tables-save` is unavailable, it fails closed. A host with no
such rules passes this check. Do not add a broad PREROUTING DNAT rule to make
the RelayNode reachable; the public TLS proxy should remain the only
Internet-facing entry point.

The public smoke is a separate acceptance test because it requires a reachable
Java server and a deployed public URL. It must be run only against targets the
operator is authorized to test.

## Open browser-origin policy and TLS hostname

The v0.4.0 public RelayNode intentionally allows every browser origin:

    # Use deploy/ellan-site.env.example as the source for this live node.
    GAIUS_ALLOWED_ORIGINS=*

Do not add `GAIUS_ALLOW_NULL_ORIGIN`; wildcard mode accepts `null` and
`file://` without that legacy opt-in. Keep the wildcard as the only list entry.

After changing `/etc/gaius/relaynode.env`, restart the RelayNode and run the
read-only check from `apps/bridge` in a repository checkout. The script has no
built-in public target: supply an authorized Java server and a resource-pack URL
that the node allows.

    sudo systemctl restart gaius-relaynode.service
    GAIUS_VERIFY_BASE_URL=https://ellan.site \
    GAIUS_VERIFY_PAGES_ORIGIN=https://gaius.fun \
    GAIUS_VERIFY_FILE_ORIGIN=file:// \
      GAIUS_VERIFY_TARGET_HOST=server-a.example \
      GAIUS_VERIFY_TARGET_PORT=25565 \
      GAIUS_VERIFY_RESOURCE_PACK_URL=https://cdn.example/pack.zip \
      bash deploy/verify-public-origin.sh

The browser-facing endpoint is the DNS/TLS name `wss://ellan.site/tunnel`.
Do not put the origin IP in a client query, registry, README, or release
artifact. Keep the origin address only in the private DNS/provider configuration;
the browser must use the TLS DNS name from `GAIUS_RELAY_PUBLIC_URL`. An IP URL
also fails Chrome certificate validation before the RelayNode can receive the
request. If an old client contains an IP URL, replace it with the DNS URL and
rerun the Chrome/CDP gate.

## systemd and Nginx

Install the unit, reload systemd, and restart the process after every release:

    sudo install -m 0644 /tmp/gaius-relaynode.service /etc/systemd/system/gaius-relaynode.service
    sudo systemctl daemon-reload
    sudo systemctl enable gaius-relaynode.service
    sudo systemctl restart gaius-relaynode.service
    sudo systemctl --no-pager --full status gaius-relaynode.service

Replace relay.example.com and the certificate paths in the Nginx example, then
install and validate it:

    sudo install -m 0644 /tmp/nginx-gaius-relay.conf /etc/nginx/conf.d/gaius-relay.conf
    sudo nginx -t
    sudo systemctl reload nginx

The map and limit_*_zone declarations in the example are intentionally at Nginx
http scope. A standard /etc/nginx/conf.d/*.conf include has that scope. The
final catch-all returns 404; no registry management endpoint is published.

## Non-sensitive runtime audit

The audit does not print the service environment. It identifies the one PID
owning the 8080 listener, checks /proc/$PID/cwd, checks that the loaded
dist/main.js contains target attestation, and requests health and manifest JSON
without echoing either response.

    sudo GAIUS_VERIFY_RUNTIME=systemd GAIUS_VERIFY_BASE_URL=https://relay.example.com GAIUS_VERIFY_WORKDIR=/opt/gaius/apps/bridge bash /opt/gaius/apps/bridge/verify-runtime.sh

For a local-only check before Nginx is configured, omit GAIUS_VERIFY_BASE_URL;
it defaults to http://127.0.0.1:8080.

For a non-sensitive systemd audit, inspect only these fields:

    sudo systemctl show gaius-relaynode.service -p MainPID -p ActiveState -p SubState
    pid=$(sudo systemctl show -p MainPID --value gaius-relaynode.service)
    sudo readlink -f "/proc/$pid/cwd"
    sudo ss -ltnp 'sport = :8080'

Do not print /proc/$pid/environ wholesale. If an environment audit is needed,
allowlist non-secret keys such as GAIUS_BRIDGE_HOST, GAIUS_BRIDGE_PORT,
GAIUS_ALLOWED_HOSTS, GAIUS_ALLOWED_ORIGINS, GAIUS_ALLOW_PRIVATE_TARGETS,
GAIUS_MAXIMUM_CONNECTIONS, GAIUS_MAXIMUM_FRAME_BYTES,
GAIUS_CONNECT_TIMEOUT_MS, and GAIUS_IDLE_TIMEOUT_MS; omit every *_TOKEN,
*_PASSWORD, and credential value.

## Target-attestation acceptance

The RelayNode must prove that its TCP connection is to the target requested by
the browser. Test two different, authorized public Java servers, not only the
RelayNode host. The returned JSON must show target, attestedTarget, status
version, and a server description belonging to the same target on both runs.
Run these from `apps/bridge` in a repository checkout: `smoke:public` also reads
the root `relay-nodes.json`, which is not part of the deployed bridge directory.

    cd apps/bridge
    GAIUS_PUBLIC_RELAY_URL=wss://relay.example.com/tunnel GAIUS_PUBLIC_RELAY_TARGET=server-a.example:25565 npm run smoke:public
    GAIUS_PUBLIC_RELAY_URL=wss://relay.example.com/tunnel GAIUS_PUBLIC_RELAY_TARGET=server-b.example:25565 npm run smoke:public

For each run, record the actual `connected` fields and the status fingerprint
before adding the node to the curated registry. The minimum two-target gate is:

- Target A and target B are different authorized Java servers.
- For a direct target, `connected.candidateHost` and
  `connected.candidatePort` match the requested host and port. When Minecraft
  SRV discovery applies, they instead match the selected SRV host and port.
  `connected.remoteAddress` and `connected.remotePort` show the final socket
  peer and must be consistent with that candidate.
- The candidate fields and the status fingerprint (`version`, description, and
  SHA-256 of the status payload) differ between the two runs as expected for
  the two servers.
- `targetConnections.released` is true after each run.

Run the public smoke twice, capturing its machine-readable result without
printing credentials:

    GAIUS_PUBLIC_RELAY_URL=wss://relay.example.com/tunnel GAIUS_PUBLIC_RELAY_TARGET=server-a.example:25565 npm run smoke:public
    GAIUS_PUBLIC_RELAY_URL=wss://relay.example.com/tunnel GAIUS_PUBLIC_RELAY_TARGET=server-b.example:25565 npm run smoke:public

A node that reports the same candidate or status for both targets is misrouted
and must be removed from service until its running process and upstream proxy
are corrected.

Do not treat a `connected` message that merely repeats the requested host and
port as proof of a real TCP connection. The current release requirement is
that the `connected` control message includes the selected `candidateHost` and
`candidatePort`, plus the socket's actual `remoteAddress` and `remotePort`.
The candidate must match either the direct request or a valid SRV result for
that request, and the remote endpoint must be consistent with the candidate's
resolved address. If a deployed client or node does not publish these fields,
it is an older incompatible release and must be updated before it is accepted
into the public registry.

## v0.4.0 release bundle and smoke

A v0.4.0 RelayNode is `dist/` (8 modules, including `session-protocol.js` and
`protocol-schema.js`) **together with** `protocols/` (`774.json`, `776.json`,
`777.json`), `package.json`, `package-lock.json` and the `Dockerfile`. The
relay reads `protocols/` once at startup and exits when the directory is
missing, so the two directories must always come from the same commit. No new
environment variable is required; an existing v0.3.3 environment file is reused
unchanged. `GAIUS_BRIDGE_PROTOCOL_DIRECTORY` is optional and normally unset.

Build a reproducible bundle from committed files only:

    bash deploy/make-bundle.sh <git-ref> <output-dir>
    sha256sum -c <output-dir>/gaius-relay-*.tar.gz.sha256

The archive root is the former `apps/bridge` directory and carries `SHA256SUMS`
and `BUNDLE-INFO` (commit and package version). It is also the Docker build
context (`docker build <extracted-dir>`); for systemd extract it into a new
release directory and run `npm ci --omit=dev --ignore-scripts` there.

Values the public environment must satisfy:

- `GAIUS_ALLOWED_ORIGINS=*` enables the intentionally open browser-origin
  policy. Keep `*` as the only list entry. It accepts `null` and `file://`
  without `GAIUS_ALLOW_NULL_ORIGIN` and reflects each request Origin for CORS.
- `GAIUS_ALLOWED_HOSTS` is `*` or, when restricted, also lists `*.gaius-local`
  (LAN invitations query `/relay-node/v1?host=lan-server-<id>.gaius-local`).
- `GAIUS_ALLOW_PRIVATE_TARGETS=0` on a public node. LAN tunnels are paired
  inside the process and never dial a TCP target, so they work with the guard on.
- No `GAIUS_BRIDGE_TOKEN` on a public node.

`relay-deploy-smoke.mjs` verifies all of the above from any machine and needs no
Minecraft server (`ws` must be installed: `npm ci --omit=dev`):

    node relay-deploy-smoke.mjs <https://relay-host | wss://relay-host/tunnel> [--target host:port] [--skip-receipt]

It checks `/health` (capabilities `session-protocol-table-v1`,
`target-attestation`, `runtime-telemetry`; the runtime counters
`profilesSelected774`, `profilesSelected776` and `profilesSelected777`, which
exist only for loaded numeric tables), CORS for the Pages and `null` origins and
refusal of an unlisted origin, the `*.gaius-local` host policy, a tampered
declaration (must close 1007), a LAN tunnel pair, a legacy `connect` without a
declaration (no receipt), one declaration per profile (the `connected` receipt
must repeat format, SHA-256 and protocol number), loopback refusal when private
targets are disabled, and lease release. The receipt checks need one successful
outbound TCP dial; by default the relay dials its own public host and port, so
pass `--target` if that is not reachable. Exit status 1 means at least one
check failed; the last stdout line is a JSON summary without the relay address.
`npm run smoke:deploy-selftest` runs the same checks against throw-away local
relays, including the negative cases (set `GAIUS_SMOKE_LEGACY_BRIDGE_DIR` to an
extracted v0.3.3 `apps/bridge` with `node_modules` to prove an old relay is
rejected).

Client compatibility: a v0.3.3 client sends no `protocolTable`; the v0.4.0 relay
then behaves as before (startup tables select the packet layout, unknown
protocols stay opaque) and `connected` carries no receipt. A v0.4.0 client that
declares a table against a v0.3.3 relay is also served because that relay
ignores the extra field, but gets no receipt and cannot use a table the relay
does not have.

## Rollback

Keep the previous dist/, protocols/, package-lock.json, and environment file outside
the active release directory. Keep protocol tables paired with their relay code.
To roll back, upload the previous application bundle,
restore the previous environment file if it changed, reinstall its locked
dependencies, validate syntax, then restart the same unit:

    cd /opt/gaius/apps/bridge
    npm ci --omit=dev
    node --check dist/main.js
    sudo systemctl restart gaius-relaynode.service
    sudo nginx -t
    sudo systemctl reload nginx

To return to v0.3.3, build `deploy/make-bundle.sh v0.3.3 <dir>` (it has no
`protocols/`) or restart the previous image or container that was kept. The v0.3.3
environment file is identical, so no value changes. Run `relay-deploy-smoke.mjs`
afterwards: v0.3.3 is expected to fail exactly the v0.4.0 checks
(`health.capabilities`, `manifest.v1`, `ws.tampered-declaration`, the receipts)
and pass the rest.

Run verify-runtime.sh and the two-target attestation smoke after rollback. If
the old release lacks target-attestation, do not return it to a public registry;
use it only behind a restricted private deployment while the current release is
repaired.
