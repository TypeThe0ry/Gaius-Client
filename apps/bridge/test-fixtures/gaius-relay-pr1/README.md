# Gaius-Relay PR #1 loopback fixture

The `dist/` files, `package.json`, and `LICENSE` are byte-for-byte copies from
`TypeThe0ry/Gaius-Relay` PR #1, commit `418621658435f766d872d817c85dbf789d490888`.
Run `node --test apps/bridge/url-connect-vendor.test.mjs` from the repository root.
The fixture uses the bridge package's `ws` dependency and listens on loopback only.

Race9g does not deploy this fixture. URL connect requires support on the deployed
relay, an explicit tokenless capability advertisement, and an eligible payload.
This upstream fixture accepts legacy connect requests and does not implement this
repository's session protocol table negotiation.
