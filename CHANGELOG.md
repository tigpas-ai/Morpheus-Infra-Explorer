# Changelog

All notable changes to this plugin. The interface (endpoints, options, data formats) is versioned separately in
[`CONTRACT.md`](CONTRACT.md).

## 0.26.0 – 2026-11-12
- Storage tab: datastores expand and show the VMs that have a disk on them (for users who may see servers); the resources
  of such a VM row and its tooltip cover only its disks on that datastore. Interface 0.20.0: `vms` and `vmCount` per
  datastore, `storageId` in the `disks` entries.

## 0.25.0 – 2026-11-12
- Networks tab: each interface label has its own tooltip with the details of that interface.
- The tooltip of the resources shows the storage of each disk: `(Storage) Name: Size`. Interface 0.19.0: new optional
  `storage` in the `disks` entries.

## 0.24.0 – 2026-11-12
- The resources (vCPUs, memory, disks) are shown only in the Clusters and Instances tabs. In the Networks tab the VM
  rows show the labels of the VM's interfaces in that network instead; the tooltip has the details per interface
  (type, MAC address, addresses, DHCP, primary). Interface 0.18.0: new optional `interfaces` on VMs of networks.

## 0.23.0 – 2026-11-12
- VM rows show the configured resources (not the usage) in a compact form: vCPUs, memory in rounded GB and the total
  disk size (GB, from 1 TB with one decimal), each with a small symbol. The tooltip has the details: memory with up to
  two decimals and one line per disk. Interface 0.17.0: new optional VM fields `cores`, `memory`, `storage`, `disks`.

## 0.22.0 – 2026-11-11
- The panel stays open across page changes: if it was open, it opens again right away on the next page (without
  taking the focus). Morpheus dialogs hide it while they are shown, then it opens again.
- The panel keeps its state in the browser tab (sessionStorage, per user): the shown view, the expanded nodes of every
  view and the scroll position per view, also after closing and opening it.
- The green power dot of a running VM opens its console (with the Remote Console permission).
- Dragging a VM near the top or bottom edge of the list scrolls it, faster closer to the edge.
- Two seconds after the pointer has left the overlay, the panel fades (over 0.3 s, also with "reduce motion") to half transparent; it
  is opaque again as soon as the pointer is back. A panel that opens again on a new page starts half transparent.
- Fixed: the reopened panel was hidden again right after loading a page, because a Morpheus component triggers a
  dialog event on an element that never appears; only dialogs that are really displayed hide the panel now.

## 0.21.0 – 2026-11-08
- Storage view: the host tooltip of the path state names the reason when a host is down despite active paths
  ("not mounted", "withdrawn", "pool inactive") or its mount check failed. Cluster-level `orphanMounts` of the Storage Path Monitor
  are not shown in the overview (they belong to no datastore).

## 0.20.2 – 2026-11-02
- Storage view: the node state link keeps a readable color on hover and focus (the link colors of the Morpheus
  stylesheets turned it dark on the dark overlay); OK states brighten to white, warning states keep their color.

## 0.20.1 – 2026-11-01
- Plugin metadata: website, source code and issue tracker point to the repository
  (https://github.com/tigpas-ai/Morpheus-Infra-Explorer), shown in the plugin list of Morpheus.
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
