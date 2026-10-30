# Changelog

All notable changes to this plugin. The interface (endpoints, options, data formats) is versioned separately in
[`CONTRACT.md`](CONTRACT.md).

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
