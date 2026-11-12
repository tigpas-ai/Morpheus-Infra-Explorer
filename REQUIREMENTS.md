# Requirements: Infra Explorer – hierarchical infrastructure navigation as an overlay

> Status: agreed, technically clarified for the most part · target Morpheus 9.1 · plugin API 1.4.1

## Goal
A Morpheus plugin that provides hierarchical infrastructure navigation: clusters → hosts → VMs as a tree,
VMs in folders, storage per cluster. It complements the Morpheus UI and does not replace it.

## Interaction concept: overlay
- A small button is visible at the bottom left of **every** Morpheus page.
- Clicking it opens a menu or panel above the page (overlay); the page itself stays unchanged.
- Content (trees, lists) is loaded **on demand** (when a tab is opened or a node is expanded), not when any
  Morpheus page loads – except when the panel was left open on the previous page (see Panel state).
- No specific template to follow; implemented according to the plugin API and documentation.

## Views
| View | Content |
|---|---|
| **Clusters** | All clusters → their hosts → the VMs of each host |
| **Instances** | VMs in a folder hierarchy: the first level is the tenant, below it the folders (see Folders); VMs without a folder sit directly below their tenant |
| **Storage** | Per cluster the related datastores (name, type, capacity/usage as far as the API provides them) |
| **Networks** | Per cluster its networks with their IP networks (CIDR IPv4/IPv6, subnets), VLAN and number of VMs → the VMs connected to them with their addresses there; a VM with several interfaces appears below each of its networks (requested 2026-10-31, implemented in 0.18.0) |

Each VM shows at least: name, power state, host. The state should be refreshable without reloading the whole
Morpheus page (reloading the node is enough).

## Actions (first version)
From the tree, per VM:
1. **Go to the VM page** (details page in Morpheus)
2. **Open the console** (Morpheus console of the VM)
3. **Start / stop**
4. **Open reconfigure** (open the existing Morpheus dialog or page, do not rebuild it)
5. **Assign a folder** – via the context menu of the VM (input/selection per level, implemented in 0.8.0) and via drag and
   drop of a VM onto a folder or its tenant (= no folder) in the instances view (implemented in 0.13.0; only within the
   same tenant, saved via `POST vm-folder`; closed folders open while hovering over them)
6. **Convert to managed** – for unmanaged VMs; opens the Morpheus dialog "Convert to Managed" (requested 2026-10-25)
7. **Rename a folder** – right-click on a folder in the instances view; applies to all VMs in it and in its
   subfolders (requested 2026-10-25, implemented in 0.10.0)
8. **New instance** – right-click on a tenant or folder in the instances view; opens the Morpheus "Add" wizard on
   `/provisioning/instances` and enters the folder tags into its tag rows (requested 2026-10-25, implemented in 0.12.0).
   Only in the tenant of the session (Morpheus creates the instance there) and with "Instances: Add" (`provisioning-add`) `full`.
9. **Name the levels** – "Edit levels …" below the instances view: change the name of each level (setting `folderTags`),
   optionally renaming the existing tags on all visible VMs (requested 2026-10-25, implemented in 0.14.0).
   Permission: Plugins and Compute `full`.

Utilization (requested 2026-10-25, implemented in 0.15.0): in the "Clusters" tab, clusters and hosts show CPU and RAM as
bars (same style as the datastore usage), red from 90 %.

Window size (requested 2026-10-26, implemented in 0.16.0): the overlay can be enlarged by dragging its top and right edge
(and the top right corner), resized with the arrow keys on the focused edge and maximized with a button in the header;
double-clicking the edge restores the default size. The size (only width, height, maximized) is stored per browser and
user in localStorage under a pseudonymous key (truncated SHA-256 of user ID and name, independent of tenant and
permissions). The entry survives logging out; renaming the user discards it.
It therefore applies across tabs and sessions in this browser, but not on other devices. The plugin API offers no
per-user storage on the server. The window limits the stored size so the panel never extends past the edge.

Panel state (requested 2026-11-11, implemented in 0.22.0): the panel stays open across page changes – if it was open,
it opens again directly when the next page loads (without taking the focus from the page, and then prefetches like a
normal opening). It keeps the shown view, the expanded nodes of every view and the scroll position per view in the
sessionStorage of the browser tab (key `infra-explorer.state`), under the same opaque user/tenant/permission key as the
cached answers, so another user or changed permissions start with the default state. Expanded nodes are identified by
their path in the view (e.g. `cluster:3/host:7`, `tenant:1/folder:Prod`); collapsing a node keeps the state below it, at
most 300 nodes per view are remembered (not the folders opened by hovering during drag and drop); renaming a folder
forgets its state. Remembered nodes expand again level by level as their data arrives; the scroll position is applied
again while the view fills in (up to 5 s, until the user scrolls or acts in the list). Because every expansion is
remembered, a newer answer that differs from the shown one rebuilds its list and the nodes expand again (before 0.22.0
it was skipped while a node was expanded, which kept added objects hidden); scroll position and keyboard focus are
kept, and while a VM is dragged the rebuild waits. The panel lies above Morpheus dialogs (`z-index`), so any Bootstrap
dialog of the page (`shown.bs.modal`, only for a really displayed `.modal` – Morpheus components also trigger `show.bs.modal` on elements that never appear, e.g. the date picker on page load) hides it and closing the last dialog (`hidden.bs.modal`) shows it again; this does
not count as closing it.

Configured resources of a VM (requested 2026-11-12, implemented in 0.23.0, limited to the Clusters and Instances tabs in 0.24.0): every VM row there shows, before the power badge,
the configured – not the used – vCPUs, memory and total disk size, each as a small symbol with the value: memory as
rounded GB without unit, disks with unit (GB; from 1 TB with one decimal). Tooltip: vCPUs, memory with up to two
decimals, total of the disks and one line per disk (name, size). The data comes from the plugin (`cores`, `memory`,
`storage`, `disks` of the VM node, one extra query for the disks); the total comes from the disks, otherwise from the
server's `maxStorage`. Values that are 0 or unknown are not shown; without any value the row has no resource part.
Storage tab (requested 2026-11-12, 0.26.0): a datastore expands and lists the VMs with a disk on it (resources and
tooltip limited to the disks there); shown only with server permission, otherwise the datastore stays a leaf.
In the Networks tab the VM rows instead show the labels (`name`) of the VM's interfaces in that network; the tooltip lists
per interface type, MAC address, addresses, DHCP and primary flag (requested 2026-11-12, 0.24.0; `interfaces` of the VM
node, read with the interface query, columns `interfaces.name/macAddress/type.name/primaryInterface/dhcp` – verified on the
appliance, 2026-11-12).

Console from the power state (requested 2026-11-11, implemented in 0.22.0): for running VMs with the `console` action the
green power dot is a link to `/terminal/server/<id>` and opens the console like the menu entry (with a modifier key the
browser opens the link itself).

Drag and drop scrolling (requested 2026-11-11, implemented in 0.22.0): while a VM is dragged in the instances view, the
list scrolls when the pointer is within 60 px of its top or bottom edge, faster the closer it is to the edge (quadratic,
up to 24 px per frame); above or below the list (header, status line, outside the panel within its width) at full
speed. Scrolling stops when the drag ends or no `dragover` arrives for 400 ms.

Fading (requested 2026-11-11, implemented in 0.22.0, timing changed 2026-11-12): 2 s after the pointer has left the
overlay (panel, its menus and dialogs), the panel fades within 0.3 s to half transparent (opacity 0.5) and turns opaque
again within 0.3 s when the pointer is back; the transition is also set inline with `!important`, because with the
system setting "reduce motion" the Morpheus stylesheets force `transition: none`; not while a VM is dragged or the
keyboard focus is visibly in the panel; touch input does not fade it. It stays usable while faded.
A panel opened again on a new page (or after a Morpheus dialog) is half transparent right away, without a delay, until
the pointer is over it (requested 2026-11-12).

Path state of shared storage (requested 2026-10-31, implemented in 0.19.0): in the storage view each shared datastore shows
per node whether all storage paths are up (✅, muted), some are down (⚠️) or none is up (❌), e.g. `Nodes: 1 ✅, 2 ⚠️`.
Morpheus itself keeps no path state (verified: the host inventory lists one path per LUN although the hosts have two,
all active; the datastore only has one status per host). Collecting it means running a command on every host, so it
is a separate plugin with its own contract ("one plugin, one task"): Storage Path Monitor, code `storage-path-monitor`,
route `GET /plugin/storage-path-monitor/paths?clusterId=<id>` (UI session; counters per datastore and state per host).
The overlay reads it in the background per expanded cluster (cached like the CPU/RAM values); a non-JSON answer
(plugin not installed) or 403 hides the line and is asked again after 5 minutes. Unknown states count as unknown (❔).
Minimum versions of Storage Path Monitor for the reasons in the host tooltip: `mounted`/`withdrawn` from 0.4.0,
`poolActive` from 0.5.0. The overlay only names a reason if the field is present with the exact value
(`mounted === false`, `withdrawn === true`, `poolActive === false`), so older versions show no reason.

Languages (requested 2026-10-31, implemented in 0.18.0): English by default, German as the first further language, more
to follow. The overlay takes the language from the `lang` attribute of the Morpheus page (verified: `en-US`, with
`Accept-Language: de` → `de-DE`); Morpheus sets it from the user locale, the appliance default locale or the browser
(product documentation 9.1.0, "Supported Locales", p. 59–60). The plugin API offers no locale on `User` (verified in
the Javadoc of 1.4.1), so the browser script translates. Texts per language in `infra-explorer-messages.js`; plural
forms via `Intl.PluralRules`; missing languages and keys fall back to English. Route error texts and the setting label
stay English.

About (requested 2026-10-31, implemented in 0.18.0): an info button in the overlay header shows name, version (from the
plugin manifest), author (Pascal Liehne) and license (MIT); author and description are also set on the plugin
(`Plugin.setAuthor`/`setDescription`), so `GET /api/plugins` and the plugin list of Morpheus show them.

Per cluster (requested 2026-10-25, implemented in 0.11.0): details page, refresh short/daily, edit.
Per host: details page, enter/exit maintenance mode, restart, edit.

Interaction: right-click on the row of a cluster, host, VM or folder, or Shift+F10 or the context menu key on the name
(no separate button).
Clicking an expandable node (cluster, host, folder, tenant) only expands it; its details page is in the
context menu. Clicking a VM or a datastore opens its details page (requested 2026-10-25, implemented in 0.11.3).

Actions use existing Morpheus functions (links, API); no custom logic for console or reconfigure.
Permissions: the permissions of the signed-in user apply – nothing is shown or executed that they could not do in
Morpheus themselves. Multi-tenant capable.

## Folders
- HVM has no VM folders. Folders are stored as **Morpheus tags** (key=value) on the VM:
  one tag per level, the value is the folder name.
- Tag names per level are **configurable** (plugin setting); default `Folder-1` (1st level), `Folder-2` (2nd level)
  and so on. Example: `Folder-1=Prod`, `Folder-2=Web` → folder `Prod / Web`.
- Consequence: folders only exist as long as at least one VM carries them (no empty folders) – accepted.
- Advantage: visible in the API and in scripts, survives uninstalling the plugin.

## Scope and extensibility
- First version: **HVM clusters only**.
- Structured so that further cluster/cloud types can be added later as a separate "source"
  (an interface per type for: list clusters, hosts of a cluster, VMs of a host, datastores
  of a cluster); the UI is independent of the type.

## Technical clarification
Sources: Developer Portal https://developer.morpheusdata.com (docs, Javadoc `/api/`, generator `/start/`),
source code of `morpheus-plugin-core` (tag `rel-1.4.1`; GitHub `HewlettPackard/…`, formerly `gomorpheus/…`),
`morpheus-plugin-samples`, API reference https://apidocs.morpheusdata.com (9.1.0), product documentation 9.1.0
([sd00008772en_us](https://support.hpe.com/hpesc/public/docDisplay?docId=sd00008772en_us), pages = PDF),
a 9.1 test appliance. "Verified" = checked on the test appliance, otherwise taken from documentation/source code.

### 1. Plugin API, JDK, Gradle
- **Plugin API 1.4.1.** The product documentation names 1.4.1 for 9.1.0 (table "v9.1.0 Service Versions", p. 113).
  The generator still enters `morpheus-plugin-api:1.3.0` for 9.1.x → set it to `1.4.1` in `build.gradle` after
  generating (the only deviation from the generator output).
- **JDK:** the manager runs Java 17.0.14 (p. 113); the generator compiles with `source/targetCompatibility 1.11`.
  → Build with **JDK 17**, the target stays 11.
- Generator output for 9.1.x: Gradle wrapper 8.3, Groovy 3.0.21, Spock 2.3-groovy-3.0, Shadow 8.1.1,
  `morpheus-plugin-gradle` 1.3.1, asset-pipeline 4.4.0. Manifest attributes (`Plugin-Class`, `Plugin-Version`,
  `Morpheus-Name/-Code/-Organization/-Description/-Labels`, `Morpheus-Min-Appliance-Version`) in `jar { manifest }`.
  Set `Morpheus-Min-Appliance-Version` (generator: `6.3.0`) to `9.1.0`.
- Generator via curl: `GET https://developer.morpheusdata.com/generate?pluginName=…&pluginCode=…&morpheusVersion=9.1.x&language=groovy&basePackage=…&providers=<p>`
  returns a ZIP; `providers` is required. The generator does **not** offer a type for global UI components
  → generate with `providers=generic` and add the provider class.

### 2. Overlay
- Provider: **`com.morpheusdata.core.providers.GlobalUIComponentProvider`** (base class
  `AbstractGlobalUIComponentProvider`). According to the Javadoc meant for overlay code on all pages, "typically rendered into
  the footer of the main layout". Methods: `show(User, Account)`, `renderTemplate(User, Account)`,
  `getContentSecurityPolicy()`. Example: `morpheus-plugin-samples/morpheus-global-ui-plugin`.
- Template: Handlebars in `src/main/resources/renderer/hbs/<name>.hbs`; helpers `asset`, `nonce`, `i18n`.
- JS/CSS: in `src/assets/…`, at runtime `/assets/plugin/<plugin-name-lowercase-with-hyphens>/<file>`
  (`{{asset "/x.js"}}`). Checked in the build: the first subfolder is dropped, `src/assets/javascripts/x.js` ends up in
  the JAR as `assets/x.js`.
- **CSP (verified, `/login/auth`):** `script-src 'self' 'nonce-…' 'unsafe-inline' 'strict-dynamic' https://www.gstatic.com`,
  `style-src 'self' 'unsafe-inline' …`, `object-src 'none'`, plus `X-Frame-Options: SAMEORIGIN`.
  Because of `strict-dynamic`, script tags need the page nonce: `<script src="{{asset '/…js'}}" nonce="{{nonce ''}}">`.
  Scripts loaded dynamically from there are allowed. No access to other hosts is needed, so the CSP need not be extended.
- This makes the concept feasible: the template only renders the button and an empty container, the JS loads the data
  when the overlay is opened.

### 3. Data access and permissions
- According to the API documentation, `/api/…` requires a bearer token (p. 1112); access with the cookie session is not documented.
  → **Decision: plugin controller** (`PluginController`, `Route.build(url, method, permissions)`,
  response `JsonResponse`). According to the sample, the route is reachable in the browser under `/plugin/<route>` and uses
  the UI session; POST with the CSRF token from `<meta name="_csrf">`. The controller returns JSON for trees/lists and
  carries out the actions.
- **Verified:** routes under `/plugin/<route>` work only with a UI session (API token → 302 to the login).
  The filters `serverGroup.id`, `parentServer.id` and `server.id` (with `in`) work in `DataQuery`.
- **Required with controllers:** the plugin needs its own renderer (`setRenderer(new HandlebarsRenderer('renderer',
  classLoader))` in `initialize()`). Otherwise Morpheus 9.1 attaches the templates to the shared renderer, which ends in
  an `UnsupportedOperationException` (`DynamicTemplateLoader.addTemplateLoader`), and the upload only reports
  "Failed to parse plugin file". Rejected JARs stay in the plugin folder of the manager and are loaded again on the next
  restart (and fail there again).
- **Character set:** without an explicit setting, Morpheus sends `JsonResponse` as ISO-8859-1. Characters outside Latin-1
  become `?`, and `fetch().json()` misreads non-ASCII characters because it always assumes UTF-8. → The controller sets
  `response.setCharacterEncoding('UTF-8')`, the overlay decodes according to the charset in `Content-Type` (verified).
- Troubleshooting: application log of the manager `/var/log/morpheus/morpheus-ui/current`.
- **Run time (measured):** `list`/`find` return complete model objects. Converting them costs about
  0.5 s **per server** (3 hosts: 2–5.6 s; `listById` of one host: 2.5 s). `listOptions` with `query.propertyList`
  (property paths such as `parentServer.id`, `account.name`, `metadata.name`) returns maps with exactly these keys
  in 6–20 ms. Associations with value `null` are kept (left join), `metadata.*` yields one row per tag.
  `list` with `propertyList`, on the other hand, fails in the marshaller (`MissingMethodException` in `domainToModel`).
  `listOptions` without `propertyList` returns nothing for servers. → The plugin only queries via `listOptions` with
  `propertyList` and sets `max` explicitly. Routes now take 0.08–0.23 s instead of 2–5 s.
- **Load test (103 VMs, 88 of them on one host):** `clusters`, `hosts`, `vms` 0.10–0.15 s, `vm-folders` 0.18–0.20 s.
- **Browser cache of the overlay:** responses are kept for up to 10 min in the sessionStorage of the tab, separated by an
  opaque key of user, tenant and permissions (hash, rendered into the markup by the overlay provider). A different key
  clears the storage. Stored responses are shown immediately and reloaded in the background after 15 s. On 401,
  403 or an expired session the overlay discards all responses and views. Residual risk: if group or cloud access of a
  role changes without the permissions changing, the old state is visible briefly (until the next reload).
  Prefetching: at most 4 requests at the same time, requests of the user first, at most 60 background requests per opening.
- **The plugin must enforce permissions itself.** Morpheus services (`morpheus.async.computeServer`, `.cluster`,
  `.instance`, `.cloud.datastore`, `.metadataTag`) only filter with `new DataQuery(user)`, and even then according to the
  Javadoc "not guaranteed" by resource permissions. Route permissions only check whether the permission code exists, not
  its level. Approach: always query with `DataQuery(user)`, additionally match the user's tenant/groups/cloud
  (`MorpheusResourcePermissionService`) and check role permissions from `user.permissions` (infrastructure, console
  "Remote Console" p. 1451, power, reconfigure). Encapsulated in a separate `AccessPolicy` class with tests.

### 4. Object model
- **Cluster** = `ComputeServerGroup` (API `/api/clusters`; type code `mvm-cluster`).
- **Host** = `ComputeServer` with `serverGroup` = cluster, `parentServer = null`, type `mvmHost` (verified).
- **VM** = `ComputeServer` with `parentServer` = host, `computeServerType.guestVm`; `serverGroup` is empty for VMs (verified).
  Managed VMs additionally have an `Instance` (via `Workload.server`). Discovered/unmanaged VMs
  appear as servers **without** an instance (verified: type `kvmUnmanaged`, `instance=null`).
  → **The base object in the tree is the ComputeServer**, the instance is an optional reference.
- API queries (verified): hosts `GET /api/servers?clusterId=<id>`, VMs of a host `?parentServerId=<id>`.
  Unknown filters are silently ignored and return **everything**. So only use verified filters.
  `/api/clusters/:id/servers|hosts|vms` → 400 for `mvm-cluster`.
- **Tags:** `ComputeServer` and `Instance` both have `metadata` (List<MetadataTag> with `name`/`value`). Via the API
  `PUT /api/servers/:id` or `/api/instances/:id` with `tags` (replaces all), `addTags`, `removeTags`.
  → Store folder tags on the **server** (possible for VMs without an instance as well); set them only with
  `addTags`/`removeTags`, never with `tags`, so that other tags are kept.

### 5. Endpoints and direct links
| Purpose | Route | Evidence |
|---|---|---|
| VM/host details page | `/infrastructure/servers/<serverId>` | docs p. 219 |
| Instance details page | `/provisioning/instances/<id>` | verified |
| Console | no API endpoint (404). The UI opens a pop-up `/terminal/server/<serverId>` (instance: `/terminal/<instanceId>`), window name `server_terminal_<id>`. Permission "Remote Console" (`terminal`: none/provisioned/full) | docs p. 1451; Morpheus UI script |
| Start/stop | API: `PUT /api/servers/:id/start|stop` (token only). UI (session + CSRF header `X-XSRF-TOKEN` from `<meta name="_csrf">`), verified on a test VM: start/restart `POST /infrastructure/servers/<id>/start|restart` with form `_method=PUT` → 200 "OK"; stop `PUT /infrastructure/servers/stop` with JSON `{"ids":[<id>],"force":true|false}` → `{"success":true,…}` (as sent by the stop dialog of the UI). `force:false` waits synchronously for the ACPI shutdown (guest without ACPI reaction: about 3 min, then aborted). `PUT /infrastructure/servers/<id>/stop` → 405; without CSRF token 302 to `/error/invalid-csrf`. Permission "Power Control" (`provisioning-power`: none/user/full) | verified; docs p. 1251 |
| Reconfigure | Bootstrap dialog of the server page: link `/infrastructure/servers/<id>/resize` with `data-target="#resize-server-modal"` (instance: `/provisioning/instances/<id>/resize`). Permission "Reconfigure" (`provisioning-reconfigure`: none/user/full) | server page (verified); docs p. 1252 |
| Refresh cluster | `GET /infrastructure/clusters/<id>/refresh` (Ajax, like the "Refresh" link), daily run with `?mode=daily`; response `{"success":true}`, also without CSRF token. Edit cluster: dialog `#edit-server-group-modal` of the cluster page (`data-page="serverGroups/show"`) | verified |
| Host maintenance mode | Enter: dialog of the server page (`/infrastructure/servers/<id>/maintenance-prompt`, `data-target="#form-modal"`), Morpheus asks for the options itself. Exit: `POST /infrastructure/servers/bulk-leave-maintenance` with form `ids=<id>` (as the server list does). State: field `maintenanceMode` (API; readable via `listOptions`, not present in the plugin model). Edit host: dialog `#server-edit-modal`. **Not executed** on the test appliance (hosts of its only cluster) | Morpheus UI script, server page (verified) |
| New instance | Wizard `#new-instance-modal` on `/provisioning/instances` (`data-page="instances/index"`, button `.add-instance`). Tags in the "Configure" step: `Morpheus.AppComponentMetadata` renders `.instance-metadata-wrapper .metadata-row` with `metadata.name`/`metadata.value`, takes over values on `change`, `.add-row` adds a row. Prefilling these fields is an assumption about the UI; not yet checked in the browser | Morpheus UI script |
| Convert to managed | Bootstrap dialog "Convert to Managed" of the server page of an unmanaged VM: link `/infrastructure/servers/<id>/install-agent-prompt` (class `server-agent-button`, `data-target="#agent-server-modal"`). Permission in the plugin: Compute `full` | server page (verified) |
| CPU/RAM of hosts and clusters | Values of the REST API: `GET /api/servers?clusterId=<id>` returns the hosts of a cluster with `stats.usedMemory`, `stats.maxMemory`, `stats.cpuUsage` (as the host pages show them); `/api/…` accepts the UI session. The plugin services return different numbers: `listOptions` only knows `stats.usedMemory`/`stats.usedCpu`/`stats.usedStorage` (other `stats.*` empty the whole query), the full model `computeStats` has the same values – without the memory of the VMs (1.2 GB instead of 12 GiB on a host running the manager VM). → The overlay reads the API per cluster (background, cached for 15 s; about 2.2 s per query with 3 hosts); cluster = sum of RAM, CPU weighted by cores | verified |
| Set folder (tags) | Plugin route `POST /plugin/infra-explorer/vm-folder`: loads the server (`computeServer.find` with `DataQuery(user)`), replaces only the folder tags in `metadata` and saves (`computeServer.save`). Verified on a test VM: tags set or removed on the server, other tags kept, remaining server fields unchanged (comparison of the API response), Morpheus checks CSRF for plugin routes as well. Permission: Compute (`admin-servers`) `full`. **Not suitable:** UI dialog "Apply Tags" (`POST /infrastructure/servers/apply-tags`, form `ids`, `mode` add/replace/remove, `tags`) sets **labels**, not name/value tags; the edit form (`/infrastructure/servers/<id>/edit`) submits the whole server including credentials | verified |
| Datastores of a cluster | `GET /api/clusters/:id/datastores`: `name`, `type`, `datastoreType.code`, `storageSize`, `freeSpace` (bytes), `online`, `status` | verified |

The controller uses the Morpheus services instead of the REST API; see open point 3 for how datastores are
assigned to the cluster.

### 5a. Networks (verified, 0.18.0)
- **Network** = `Network` (service `morpheus.services.network`, page `/infrastructure/networks/<id>`, role permission
  `infrastructure-networks` "Networks"). HVM networks reference the cloud (`refType ComputeZone`); the cluster is not
  stored on the network, and `/api/clusters/:id/networks` → 400 for `mvm-cluster`. The hosts carrying a network are its
  `locations` (`refType ComputeServer`, `refId` = host id, one per host). → A network belongs to a cluster if a host of the
  cluster carries it or a VM of the cluster is connected to it.
- **Interfaces of a VM:** `listOptions` on `computeServer` with `interfaces.id`, `interfaces.network.id`,
  `interfaces.addresses.address` returns one row per address of each interface (left join). `interfaces.ipAddress` and
  `interfaces.ipv6Address` are not columns (model getters derived from `addresses`) and empty the **whole** query;
  `interfaces.network` alone returns a Hibernate proxy that cannot be read outside the session.
- Network columns readable via `listOptions`: `name`, `status`, `active`, `vlanId`, `cidr`, `cidrIPv6`, `type.name`,
  `owner.id`, `visibility` (`private`/`public`), `locations.refType`/`refId`, `subnets.id`/`name`/`cidr` (one row per
  subnet; the test appliance has no subnets, so the values were only seen as `null`).
- Tenant: networks of the own tenant, plus networks of other tenants with `visibility = public` (shared, as in Morpheus).
- The plugin services were checked with a test build on the appliance; the routes of the Morpheus UI behind the network
  page were not opened in a browser.

### 6. Plugin settings
- Defined via `Plugin.setSettings(List<OptionType>)`, read with `morpheus.getSettings(plugin)` → JSON string.
  Stored in Morpheus itself (not in the plugin JAR), so they survive updates.
- According to the developer documentation, edited under *Administration → Integrations → Plugins*. The product
  documentation 9.1.0 calls the modal "mostly read-only" (p. 1304).
- One text option "Folder tags per level" (comma-separated, default `Folder-1,Folder-2,Folder-3`).

### 7. Upload/deploy
- Upload the JAR via the UI (*Administration → Integrations → Plugins → Upload File*) or via the API
  `POST /api/plugins/upload` as multipart with the field `file` (API documentation 9.1.0, `uploadPlugin`). Installation
  runs asynchronously (`installing` → `loaded` or `error`) → after the upload, query `GET /api/plugins` until the
  status is final.
- Verified on 9.1 (2026-11-01): an upload replaces any installed version, **including an older one** (downgrade
  0.20.0 → 0.19.1 accepted, `loaded` within seconds). Plugin ID, settings (`config`) and the enabled state are kept;
  only the uploaded jar remains in the plugin directory of the manager. `lastUpdated` of the plugin changes with every
  upload (also of the same version) and every `PUT`. `PUT /api/plugins/<id>` with `config` replaces the whole config:
  unknown keys are dropped, `{}` leaves the defined settings as `null` (verified). `DELETE /api/plugins/<id>` removes a plugin
  (used by the integration test when the plugin was not installed before; verified against an appliance: the test removed the plugin again, no jar was left in the plugin folder; a later upload creates a new plugin ID).
- Route permissions: a user without the permission of a route gets an answer from Morpheus itself (404 JSON
  "Unable to find page") before the plugin is called; with `infrastructure-cluster` `read` only, `clusters` answers 200
  without actions and `hosts`, `datastores`, `networks`, `vm-folders` answer 403 from the plugin (verified, 2026-11-01).
- Integration test against an appliance with full restore of the previous state: `docs/integration-test.md`.

### Open points
1. ~~Direct URLs for instance details page, console and reconfigure~~ – clarified (verified), see section 5.
2. Permissions of restricted users. Clarified (verified, role "User Admin"): feature codes `infrastructure-cluster`
   (Clusters), `admin-servers` (Compute), `provisioning-power`, `provisioning-reconfigure`, `terminal` (Remote Console).
   Implemented (v0.2.0): feature permissions and tenant (`AccessPolicy`), queries with `DataQuery(user)`.
   **Open:** group/cloud access of the role (`sites`, `zones`, `clusterTypePermissions`). The plugin API offers no
   query for it; whether `DataQuery(user)` filters by it is not established. Check with a test user whose role has no
   access to the group of the cluster. Until then, users of the same tenant may see clusters whose group they are not
   allowed to see in Morpheus, if Morpheus does not filter itself.
3. ~~Datastores of a cluster via the plugin services~~ – clarified (verified): `Datastore.refType = ComputeServerGroup`,
   `refId` = cluster ID; service `morpheus.services.cloud.datastore`; page `/infrastructure/storage/data-stores/<id>`;
   role permission `infrastructure-storage`. Implemented in v0.4.0.
4. Plugin settings: clarified via the API (verified): `PUT /api/plugins/:id` with `{"plugin":{"config":{"folderTags":"…"}}}`
   takes effect immediately; `GET /api/plugins/:id` shows the setting as `editable`. `{"plugin":{"enabled":false}}` disables
   the plugin (emergency). The Morpheus UI itself writes with `PUT /api/plugins/<id>` and the session (CSRF header),
   `/api/…` accepts the UI session (verified); the overlay uses this route. **Open:** whether the field is editable in the
   UI under *Administration → Integrations → Plugins*.
5. ~~Instance tags on the server?~~ – clarified (verified): a tag set on the instance via `addTags` also appears as a
   separate tag on the server of the VM and therefore as a folder; removing it on the instance removes it on the server too.
   Tags set directly on the server work as well.
6. Where the global component lands in the DOM and whether the bottom-left position works without conflicts with the
   Morpheus UI. To check: stacking order relative to Morpheus dialogs (`z-index: 10000`), influence of the Morpheus styles
   on the overlay and whether Morpheus sets its own theme class for dark mode. Since 0.9.1 the overlay uses the colors of
   the Morpheus main navigation (theme CSS: background `#263040` as `header#header`, white text, hover/selection `#17d0a6`,
   active entry underlined 3 px green, focus `#435470`) and is dark in every mode.
   Some pages additionally load the theme stylesheet (`/assets/themes/hpe/app-*.css`, e.g. `/infrastructure/groups`, not
   `/admin/accounts`); it turns **every** `button` into an oval green button (`border-radius: 32px; height: 32px; padding: 0 18px;
   background: #008567`, hover/focus `#006750`, narrow windows `min-height: 44px !important`) and draws focus rings on links.
   The overlay resets this: base shape with `:where(#ie-root) button` (specificity of a bare `button`, so the class rules of
   the overlay win), hover/focus/disabled with `#ie-root` (0.11.1 only hover/focus – not enough, 0.11.2 complete).

## Prerequisites
| What | Note |
|---|---|
| JDK | 17 for building (see README) |
| Gradle wrapper | from the generator (8.3) |
| Test data | an HVM cluster with hosts, managed and unmanaged VMs, and a test user with a restricted role |

Risk: plugins run inside the Morpheus manager. A faulty plugin affects the whole appliance – build and test locally
before uploading, and disable or remove the plugin if problems occur.

## Acceptance (first version)
- Button at the bottom left on any Morpheus page; the overlay opens/closes, the page stays usable.
- The cluster tree shows all HVM clusters with hosts and their VMs; the storage view shows the datastores per cluster.
- The instances view groups by the configured tags; a tag change in Morpheus is visible after reloading.
- The VM actions work for a test VM; a user without permissions neither sees nor can execute them.
