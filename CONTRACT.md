# Interface contract of the plugin

Describes everything the plugin exposes, so that API clients and scripts can rely on it **without** reading the
plugin code. On every change: increase the version and record the change in the log below.

- Contract version: 0.17.0
- Plugin version: 0.22.0 (plugin API 1.4.1)
- Minimum Morpheus version: 9.1.0

## Endpoints / providers
| Method | Path | Purpose | Request | Response |
|---|---|---|---|---|
| – | (provider `infra-explorer.overlay`, GlobalUIComponentProvider) | Button at the bottom left and overlay on every page for signed-in users; UI only, no data | – | HTML in the page footer |
| GET | `/assets/plugin/infra-explorer/infra-explorer-messages.js`, `…/infra-explorer.js`, `…/infra-explorer.css` | Texts per language, script and styles of the overlay (internal, not for API clients); included with `?v=<checksum of the content>` because Morpheus lets browsers cache them for a year | – | JS/CSS |
| GET | `/plugin/infra-explorer/clusters` | Clusters of all supported types (v1: HVM, `mvm-cluster`), sorted by name | – | `{"clusters":[node]}` |
| GET | `/plugin/infra-explorer/hosts?clusterId=<id>` | Hosts of a cluster, sorted by name | `clusterId` (number, required) | `{"clusterId":<id>,"hosts":[node]}` |
| GET | `/plugin/infra-explorer/vms?hostId=<id>` | VMs of a host (including discovered VMs without an instance), sorted by name | `hostId` (number, required) | `{"hostId":<id>,"vms":[node]}` |
| GET | `/plugin/infra-explorer/datastores?clusterId=<id>` | Datastores of a cluster, sorted by name; for users who may also see servers (`admin-servers` at least `read`) each datastore carries `vms` (VMs of the cluster with a disk there, sorted by name, each with only its disks on that datastore in `disks` and their total in `storage`, without `cores` and `memory`, plus `hostName` and `actions` as in `vms`) and `vmCount` | `clusterId` (number, required) | `{"clusterId":<id>,"datastores":[node]}` |
| GET | `/plugin/infra-explorer/networks?clusterId=<id>` | Networks of a cluster with the VMs of the cluster connected to them, sorted by name. A network belongs to the cluster if a host of the cluster carries it (network location) or a VM of the cluster has an interface in it. A VM with several interfaces appears once below each of its networks. VMs without interface, with an interface without network or in a network that is not visible appear once in `withoutNetwork` | `clusterId` (number, required) | `{"clusterId":<id>,"networks":[network node with vms],"withoutNetwork":[VM node]}` (`withoutNetwork` only if not empty) |
| GET | `/plugin/infra-explorer/vm-folders` | All visible VMs below the supported clusters with host and folder path, sorted by name | – | `{"levels":["Folder-1",…],"vms":[node with hostName, tenantId, tenant, folders],"create":{"tenantId":<id>}}` (`create` only with the permission "Instances: Add" `provisioning-add` `full`: tenant of the session in which new instances are created; `levelsEditable: true` only with Plugins and Compute `full`) |
| POST | `/plugin/infra-explorer/vm-folder` | Set the folder path of a VM: one tag per level on the server, deeper levels removed, other tags kept | Form: `serverId` (number, required), `path` (repeated, outermost level first; missing = no folder; each name 1–100 characters, at most as many as there are levels). Only POST with CSRF header (`X-XSRF-TOKEN` from `<meta name="_csrf">`), otherwise 405 or Morpheus redirects to `/error/invalid-csrf` | `{"levels":[…],"vm":node with folders}` |
| POST | `/plugin/infra-explorer/folder-rename` | Rename a folder: for all visible VMs of the tenant whose folder path starts with `path`, only the tag of the folder's level is set to `name`; deeper tags stay unchanged. A sibling folder with the same name → both are merged. Each server is reloaded and skipped if it no longer belongs to host and tenant or its tags no longer start with `path`. Not atomic, about 0.5 s per VM, at most 100 VMs per call (otherwise 400) | Form: `tenantId` (number, required), `path` (repeated, required, outermost level first), `name` (required, same rules as for `vm-folder`, different from the current name). Permission and CSRF as for `vm-folder`; 404 if no VM is below or none could be written | `{"levels":[…],"path":[new path],"changed":<number of VMs>}` |
| POST | `/plugin/infra-explorer/level-rename` | Rename the tag names of levels on all visible VMs (value is kept) before the overlay writes the new names into the setting `folderTags`. VMs that already carry the new name or changed in the meantime are skipped; at most 100 VMs per call | Form: `from`, `to` (repeated, same count; `from` = configured level, `to` = new name without comma, no other level, 1–100 characters). Permission: Plugins (`admin-plugins`) `full` and Compute `full`; only POST with CSRF | `{"renames":{old:new},"changed":<n>,"skipped":<n>}` |

Paths are under `/plugin/`, the response is the JSON object itself (not wrapped in `data`),
`Content-Type: application/json;charset=UTF-8`. Without a UI session Morpheus redirects with 302 to `/login/auth`, even
when called with an API token. The data endpoints are meant for the overlay and only work with the user's **UI session** (cookie).

Permissions: `clusters` requires the feature permission `infrastructure-cluster` at least `read`. `hosts`, `vms` and `vm-folders`
additionally require `admin-servers` (Compute) at least `read`, `datastores` additionally `infrastructure-storage` (Storage)
at least `read`, `networks` additionally `admin-servers` and `infrastructure-networks` (Networks) at least `read`. Only objects that Morpheus returns for the user appear (queries in the context of the user).
On top of that the plugin checks the tenant: users of the master tenant see all tenants, all others only objects of
their own tenant (clusters, datastores and networks: `owner`, servers: `account`) and networks shared publicly; inactive
networks are left out. Hosts and VMs only appear below a visible cluster.

Actions per VM (field `actions` in `vms` and `vm-folders`, the plugin executes none itself): `console` with Remote Console
(`terminal`) `full`, `power` with Power Control (`provisioning-power`) `full`, `reconfigure` with Reconfigure
(`provisioning-reconfigure`) `full`, `folder` with Compute (`admin-servers`) `full` (also applies to `POST vm-folder` and
`POST folder-rename`), `convert` (convert to managed) for unmanaged VMs (`managed: false`) and Compute `full`; the levels for
own workloads (`provisioned` or `user`) only if the user created the server (`createdBy`) or its instance. The overlay
carries out the actions via the routes of the Morpheus UI, which check the permissions again: console
`/terminal/server/<serverId>` (pop-up), power start/restart `POST /infrastructure/servers/<id>/start|restart` with form
`_method=PUT`, stop `PUT /infrastructure/servers/stop` with JSON `{"ids":[<id>],"force":<bool>}`, each with the CSRF header
from `<meta name="_csrf">`, reconfigure as dialog of the server page (`/infrastructure/servers/<id>/resize`, opened via
`/infrastructure/servers/<id>#ie-reconfigure`), convert as dialog "Convert to Managed" of the server page
(`/infrastructure/servers/<id>/install-agent-prompt`, opened via `…#ie-convert`).

Actions on clusters and hosts (fields `actions`): cluster `refresh` and `edit` with Clusters (`infrastructure-cluster`)
`full`; hosts `maintenance` and `edit` with Compute (`admin-servers`) `full`, `restart` additionally with Power Control `full`.
Carried out via the Morpheus UI: refresh `GET /infrastructure/clusters/<id>/refresh` or `?mode=daily`
(`{"success":true}`), edit cluster as dialog of the cluster page (`…/clusters/<id>#ie-edit`), enter maintenance mode
as dialog `…/servers/<id>/maintenance-prompt` (via `#ie-maintenance`), exit `POST /infrastructure/servers/bulk-leave-maintenance`
with `ids=<id>`, restart host as for VMs, edit host as dialog of the server page (`#ie-edit`).

Errors: `{"error":"<text>"}` with HTTP 400 (parameter missing or not a number), 403 (permission missing), 404 (unknown
or not visible, the two cannot be told apart) or 500 (internal error, details only in the log). Error texts are
English and meant for display, not for parsing.

## Options / settings
| Name | Type | Default | Meaning |
|---|---|---|---|
| `folderTags` ("Folder tags per level") | Text, comma-separated tag names (outermost level first, at most 10) | `Folder-1,Folder-2,Folder-3` | Tag names whose values form the folder levels of a VM. Empty or unreadable → default. Set via `PUT /api/plugins/<id>` with `{"plugin":{"config":{"folderTags":"…"}}}` (takes effect immediately); the overlay writes it this way with the UI session and CSRF header ("Edit levels …"; Morpheus checks `admin-plugins`); intended to be edited in the UI under *Administration → Integrations → Plugins* |

## Data formats
| What | Format |
|---|---|
| Node (cluster/host/VM) | Object with `id` (number), `name`, optional `status` (Morpheus status, e.g. `ok`, `provisioned`), `powerState` (`on`/`off`/`paused`/`unknown`, host/VM only), `type` (cluster: type name; host/VM: server type code), `clusterId` (host), `hostId` (VM), `instanceId` (VM with instance), `managed` (VM only: `false` for discovered/unmanaged VMs, derived from the server type), `url` (path of the Morpheus page of the object, e.g. `/infrastructure/clusters/<id>`, `/infrastructure/servers/<id>`, `/infrastructure/storage/data-stores/<id>`). Clusters only (`clusters`): `actions` (`refresh`, `edit`). Hosts only (`hosts`): `actions` (`maintenance`, `edit`, `restart`), `maintenance` (boolean, maintenance mode). VMs only (`vms`, `vm-folders`): `actions` (list of `console`, `power`, `reconfigure`, `folder`, `convert`; empty = none), `instanceUrl` (instance page `/provisioning/instances/<id>`, only with an instance), `cores` (vCPUs), `memory` (bytes), `storage` (total size of the disks in bytes) and `disks` (list of `{name, size in bytes, used, storage, storageId}` – `used` is the space the disk really occupies in bytes as reported by Morpheus (thin provisioning), only when known;  – `storage` and `storageId` are name and id of the datastore, only when known –, in the order of the disk ids): the configured values, not the usage; each only when known. Instances view only (`vm-folders`): `hostName`, `tenantId` and `tenant` (tenant of the server, `account`; name, otherwise `Tenant <id>`), `folders` (list of folder names, outermost first; empty = no folder). Datastores only: `storageSize` and `freeSpace` (bytes), `online` (boolean); `status` is `inactive` for disabled datastores, `type` the display name of the datastore type. Networks only (`networks`): `type` (display name of the network type), `clusterId`, `vlanId` (number), `cidr` and `cidrIPv6` (IP networks of the network, e.g. `10.0.0.0/24`), `subnets` (list of `{id, name, cidr}`, sorted by name), `vmCount` (number of VMs below the network), `vms` (VM nodes as in `vms` without `folders`, `cores`, `memory`, `storage` and `disks`, plus `hostName`, `interfaces` (list of `{name, macAddress, type, primary, dhcp, addresses}` for the interfaces in this network, each field only when known, in the order of the interface ids) and `ipAddresses`: addresses of the VM's interfaces in this network, IPv4 and IPv6, sorted; in `withoutNetwork` the addresses of interfaces outside the shown networks), `url` (`/infrastructure/networks/<id>`). Fields without a value are omitted. |
| Folder of a VM | Morpheus tags on the **server** of the VM (`/api/servers/<id>`, field `tags`), one tag `<tag name of the level>=<folder name>` per level. Readable/writable without the plugin as well: API `PUT /api/servers/<id>` with `addTags`/`removeTags` (remove deeper levels yourself). The plugin sets folders via `POST vm-folder`; reading and writing are not atomic there, a concurrent tag change on the same VM may be lost; for managed VMs a tag on the instance (`/api/instances/<id>`) works as well, Morpheus copies it to the server. Tag names are compared exactly (case-sensitive), values without surrounding whitespace. The path ends at the first level without a value: `Folder-1=Prod`, `Folder-3=X` without `Folder-2` → folder `Prod`. Without a `Folder-1` value the VM counts as "no folder". The overlay shows the tenant as a level before the first folder level; VMs without a folder sit directly below their tenant. `levels` only contains the tag levels, not the tenant. Folders only exist as long as at least one VM carries them. |

## Change log
| Contract version | Date | Change | Breaking |
|---|---|---|---|
| 0.1.0 | 2026-10-24 | created | – |
| 0.2.0 | 2026-10-24 | overlay provider and asset paths; no data endpoints yet | no |
| 0.3.0 | 2026-10-24 | data endpoints `clusters`, `hosts`, `vms` and node format | no |
| 0.3.1 | 2026-10-24 | confirmed: paths under `/plugin/`, response unwrapped, UI session only (API token → 302) | no |
| 0.4.0 | 2026-10-24 | node with `managed` and `url` | no |
| 0.5.0 | 2026-10-24 | endpoint `datastores` and datastore fields in the node | no |
| 0.6.0 | 2026-10-25 | endpoint `vm-folders`, setting `folderTags`, folder format defined (tags on the server) | no |
| 0.7.0 | 2026-10-25 | `vm-folders`: node with `tenantId` and `tenant` | no |
| 0.8.0 | 2026-10-25 | VM node with `actions` and `instanceUrl`; action routes and permissions described | no |
| 0.9.0 | 2026-10-25 | endpoint `POST vm-folder` (assign folder), action `folder` | no |
| 0.10.0 | 2026-10-25 | action `convert` for unmanaged VMs | no |
| 0.11.0 | 2026-10-25 | endpoint `POST folder-rename` (rename a folder for all VMs below it) | no |
| 0.12.0 | 2026-10-25 | cluster node with `actions` (`refresh`, `edit`), host node with `actions` (`maintenance`, `edit`, `restart`) and `maintenance` | no |
| 0.13.0 | 2026-10-25 | `vm-folders`: field `create` (tenant for new instances) | no |
| 0.14.0 | 2026-10-25 | endpoint `POST level-rename`, `vm-folders`: `levelsEditable`; setting `folderTags` can be changed from the overlay | no |
| 0.15.0 | 2026-10-31 | English texts: fallback tenant name `Tenant <id>` (was German), error texts and setting label in English | no (only display texts) |
| 0.16.0 | 2026-10-31 | endpoint `networks` (networks of a cluster with their VMs), network node fields, permission `infrastructure-networks`; asset `infra-explorer-messages.js` (overlay texts per language) | no |
| 0.17.0 | 2026-11-12 | VM node (`vms`, `vm-folders`): configured resources `cores`, `memory`, `storage`, `disks` (`name`, `size`, `used`, `storage`, `storageId`); VMs of `networks`: `interfaces`; `datastores`: `vms` and `vmCount` per datastore (only with server permission, each VM with only its disks there) | no (new optional fields) |
