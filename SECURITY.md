# Security

The plugin runs inside the Morpheus manager and serves data routes to every signed-in user. A flaw in it could show
users objects they are not allowed to see in Morpheus or let them trigger actions they could not perform there.

## Reporting a vulnerability
Please do **not** open a public issue. Use GitHub's private vulnerability reporting instead
(**Security** tab → **Report a vulnerability**) and include the affected release, the steps to reproduce and what an
attacker gains. Reports are answered as soon as possible.

## Supported versions
Only the latest release receives fixes.

## Design rules that limit the impact
- Every route checks the role permissions and the tenant of the signed-in user and reads data through the
  user-scoped queries of the plugin API; nothing should be returned or executed that the user could not see or do in
  Morpheus.
- Actions are not implemented by the plugin: the overlay links to the Morpheus pages or calls the Morpheus UI
  endpoints and API with the user's own session, so Morpheus enforces its own checks.
- The plugin's own routes change nothing but folder tags on servers, accept changes only by POST (covered by the
  CSRF check of Morpheus) and apply the same permission checks.
