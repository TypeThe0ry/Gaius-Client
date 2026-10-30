# GitHub Pages Relay origin proxy

This Cloudflare Worker exposes `pages-relay.ellan.site` for the portable client.
It accepts only the downloaded-client `null` origin and the project's GitHub
Pages origin, then forwards the WebSocket upgrade and RelayNode HTTP endpoints
to `https://ellan.site` with the upstream's existing trusted origin.

The proxy is intentionally narrow: only `/tunnel`, the RelayNode manifests,
and the browser's exact `/proxy/resource-pack` endpoint are forwarded. The
legacy `/resource-pack/*` paths remain allowed. Unknown paths and origins are
rejected at the edge.

Run the local contract test with:

```sh
node apps/bridge/cloudflare-origin-proxy/worker.test.mjs
```

After deployment and DNS setup, verify the public Pages origin and a resource
pack request with `apps/bridge/deploy/verify-public-origin.sh` using
`GAIUS_VERIFY_BASE_URL=https://pages-relay.ellan.site`. The script also
requires `GAIUS_VERIFY_TARGET_HOST`, `GAIUS_VERIFY_TARGET_PORT` and
`GAIUS_VERIFY_RESOURCE_PACK_URL` (an authorized Java server and a pack URL
allowed by the node); it stores no public target defaults. The local test and
Wrangler dry-run do not prove that the public route has been deployed.
