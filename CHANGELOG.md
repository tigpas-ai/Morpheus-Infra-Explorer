# Changelog

All notable changes to this plugin. The interface (endpoints, options, data formats) is versioned separately in
[`CONTRACT.md`](CONTRACT.md).

## 0.20.1 – 2026-11-01
- Plugin metadata: website, source code and issue tracker point to the repository
  (https://github.com/tigpas-ai/morpheus-infra-explorer), shown in the plugin list of Morpheus.
- Integration test `./gradlew integrationTest` against a Morpheus appliance: installs the built jar, checks overlay,
  assets, data routes, settings, error handling and permissions (with a temporary test user and role), optionally the
  Storage Path Monitor path state, and restores the previous state afterwards – previous version with its settings
  and enabled state, or no plugin. Stops before any change if that state cannot be restored. Connection data only
  from environment variables (test object prefix `TEST_PREFIX`); see `docs/integration-test.md`.
- `scripts/build-release.sh` builds the jar of a released version from its tag `v<version>`; releases 0.17.0–0.20.0
  are tagged.
- Repository: `SECURITY.md`, `.gitattributes`, CI workflow (unit tests and build), README restructured.

## 0.20.0 – 2026-10-31
- Storage view: the node state line ("Nodes: 2 ✅, 1 ⚠️") is a link to the detail view of the Storage Path Monitor
  plugin (cluster › Storage), opened at the datastore. Shown only while that plugin answers.

## 0.19.1 – 2026-10-31
- Storage path tooltip: hosts with fewer paths than the other hosts show the expected number, e.g. "1/1 paths
  active (expected 2)" (field `expectedPaths` of Storage Path Monitor 0.2.0).

## 0.19.0 – 2026-10-31
- Storage view: path state per node for shared datastores ("Nodes: 2 ✅, 1 ⚠️"; muted while all nodes are OK, details
  per host in the tooltip). Optional: read in the background from the separate Storage Path Monitor plugin
  (`storage-path-monitor`); without it, or without its permissions, nothing is shown.

## 0.18.0 – 2026-10-31
- New view **Networks**: per cluster its networks with VLAN, IP networks (CIDR IPv4/IPv6, subnets) and number of VMs,
  below them the VMs with their addresses in that network; VMs with several interfaces appear below each network,
  VMs outside any visible network under "Without network". New endpoint `networks` (contract 0.16.0), requires the
  permission Networks (`infrastructure-networks`).
- Multiple languages: English (default) and German. The overlay follows the language of the Morpheus UI; texts in
  `infra-explorer-messages.js`. Numbers and sorting follow that language as well.
- Info button in the overlay with version, author and license; author and description in the plugin list of Morpheus.

## 0.17.0 – 2026-10-31
First public release, under the MIT license.

- UI texts, error messages, setting label and documentation in English.
- Numbers and sorting follow the browser locale.
- Fallback tenant name is `Tenant <id>`.
- Jar is named `infra-explorer-<version>-all.jar`.

### Features (developed in 0.1–0.16)
- Overlay with a button at the bottom left of every Morpheus page; resizable and maximizable panel whose size is kept
  per browser and user.
- Views: clusters (hosts, VMs, CPU/RAM utilization), instances (tenants and tag-based folders), storage (datastores per cluster).
- Actions: details page, console, start/shut down/power off/restart, reconfigure, convert to managed, assign folder
  (context menu and drag and drop), rename folder, rename levels, new instance in a folder, cluster refresh,
  host maintenance mode, edit cluster/host.
- Permission checks for every view and action (feature permissions, tenant, own workloads).
- Short-lived per-tab cache with background refresh and limited prefetching.
