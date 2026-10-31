// Infra Explorer overlay: open/close the panel, switch views and load the cluster tree level by level.
// Nothing is fetched on page load. Opening the panel prefetches all views and the levels below in the background,
// so switching views and expanding nodes usually shows data at once. Answers are kept per user across page loads
// (sessionStorage), shown immediately and refreshed in the background when older than FRESH_MS.
(function () {
	'use strict';

	var root = document.getElementById('ie-root');
	if (!root || root.dataset.ieReady) {
		return;
	}
	root.dataset.ieReady = 'true';

	var API = '/plugin/infra-explorer';
	var POWER_STATES = ['on', 'off', 'paused', 'unknown'];
	// Responses younger than this are shown without refreshing them again
	var FRESH_MS = 15000;
	// Upper bound for background requests started by one level
	var PREFETCH_LIMIT = 20;
	// Requests to the manager at the same time; requests of the user go before background requests
	var MAX_PARALLEL = 4;
	// Background requests per opening of the panel (plus PREFETCH_LIMIT per expanded node, up to this value)
	var PREFETCH_TOTAL = 60;
	// Answers kept across page loads are dropped after this time
	var STORE_MAX_AGE_MS = 10 * 60 * 1000;
	var STORE_KEY = 'infra-explorer.cache';
	// Separates the stored answers of different users in the same browser tab
	var cacheKey = root.dataset.ieCacheKey || '';
	// Panel size per user in this browser (localStorage); empty key: the size is kept only until the page changes
	var LAYOUT_KEY_PREFIX = 'infra-explorer.layout.';
	var layoutKey = root.dataset.ieLayoutKey || '';
	// Panel size limits in pixels; the window (minus the margins in the stylesheet) caps the size as well
	var MIN_WIDTH = 300;
	var MIN_HEIGHT = 240;
	var MAX_WIDTH = 2000;
	var MAX_HEIGHT = 1400;
	// Step of the arrow keys on a focused resize grip
	var RESIZE_STEP = 20;

	var CONSOLE_WINDOW = 'menubar=no,location=no,status=no,titlebar=yes,height=825,width=1024';
	// Server page opened with one of these hashes opens the matching Morpheus dialog by clicking its own link
	var PAGE_DIALOGS = {
		'servers/show': {
			'#ie-reconfigure': 'a[data-target="#resize-server-modal"]',
			'#ie-convert': 'a.server-agent-button[data-target="#agent-server-modal"]',
			'#ie-edit': 'a[data-target="#server-edit-modal"]',
			'#ie-maintenance': 'a[href$="/maintenance-prompt"][data-target="#form-modal"]'
		},
		'serverGroups/show': {
			'#ie-edit': 'a[data-target="#edit-server-group-modal"]'
		}
	};
	// Reload power states after an action (milliseconds after the request succeeded)
	var POWER_REFRESH_MS = [3000, 10000, 30000];

	// --- Texts ---------------------------------------------------------------------------------------------------
	// The language follows the Morpheus page (user or appliance locale, otherwise the browser), see lang of <html>.
	// Texts come from infra-explorer-messages.js; English is the fallback for languages and keys that are missing.

	var MESSAGES = window.InfraExplorerMessages || { en: {} };
	var LOCALE = validLocale(document.documentElement.lang);
	var LANG = pickLanguage(LOCALE);
	var plurals = new Intl.PluralRules(LANG);

	// An invalid tag (e.g. de_DE) would make Intl throw and stop the whole script; then the browser default applies
	function validLocale(tag) {
		try {
			return tag ? Intl.getCanonicalLocales(tag)[0] : undefined;
		} catch (invalid) {
			return undefined;
		}
	}

	function pickLanguage(locale) {
		var tag = String(locale || '').toLowerCase();
		if (MESSAGES[tag]) {
			return tag;
		}
		var primary = tag.split(/[-_]/)[0];
		return MESSAGES[primary] ? primary : 'en';
	}

	function lookup(key) {
		var text = (MESSAGES[LANG] || {})[key];
		return text !== undefined ? text : (MESSAGES.en || {})[key];
	}

	// Text of a key with {name} placeholders replaced; unknown keys show the key itself
	function t(key, params) {
		var text = lookup(key);
		if (text === undefined) {
			return key;
		}
		return text.replace(/\{(\w+)\}/g, function (match, name) {
			return params && params[name] !== undefined ? String(params[name]) : match;
		});
	}

	// Text with a number: key.one, key.other, … after the plural rules of the language; {count} is the number
	function tn(key, count, params) {
		var form = key + '.' + plurals.select(count);
		var values = { count: formatNumber(count) };
		Object.keys(params || {}).forEach(function (name) { values[name] = params[name]; });
		var own = (MESSAGES[LANG] || {})[form] !== undefined || (MESSAGES.en || {})[form] !== undefined;
		return t(own ? form : key + '.other', values);
	}

	function formatNumber(value, options) {
		return Number(value).toLocaleString(LOCALE, options);
	}

	// Static markup of the template: data-ie-i18n (text), data-ie-i18n-label (aria-label), data-ie-i18n-title (title).
	// Without a text (e.g. the text file failed to load) the English text of the template stays.
	function translateMarkup(scope) {
		[['ieI18n', 'data-ie-i18n', function (node, text) { node.textContent = text; }],
			['ieI18nLabel', 'data-ie-i18n-label', function (node, text) { node.setAttribute('aria-label', text); }],
			['ieI18nTitle', 'data-ie-i18n-title', function (node, text) { node.title = text; }]].forEach(function (kind) {
			Array.prototype.forEach.call(scope.querySelectorAll('[' + kind[1] + ']'), function (node) {
				var text = lookup(node.dataset[kind[0]]);
				if (text !== undefined) {
					kind[2](node, text);
				}
			});
		});
	}

	translateMarkup(root);

	var launcher = document.getElementById('ie-launcher');
	var panel = document.getElementById('ie-panel');
	var body = panel.querySelector('.ie-body');
	var tabs = Array.prototype.slice.call(panel.querySelectorAll('.ie-tab'));
	var closeButton = panel.querySelector('.ie-close');
	var reloadButton = panel.querySelector('.ie-reload');
	var aboutButton = panel.querySelector('.ie-about');
	var maximizeButton = panel.querySelector('.ie-maximize');
	var resizeGrips = Array.prototype.slice.call(panel.querySelectorAll('.ie-resize'));
	var statusLine = panel.querySelector('.ie-status');
	// Power state per VM id and maintenance mode per host id as last reported by any answer; menus and tags use it
	var vmPower = {};
	var hostMaintenance = {};
	var loadedViews = {};
	var currentView = 'clusters';

	function el(tag, className, text) {
		var node = document.createElement(tag);
		if (className) {
			node.className = className;
		}
		if (text !== undefined) {
			node.textContent = text;
		}
		return node;
	}

	function message(text, className) {
		return el('p', className || 'ie-empty', text);
	}

	// Morpheus sends plugin JSON as ISO-8859-1 unless the route sets UTF-8 (ours do), while response.json() always
	// assumes UTF-8; so the body is decoded with the charset of the Content-Type header.
	function readJson(response) {
		var match = /charset=([^;]+)/i.exec(response.headers.get('Content-Type') || '');
		var charset = match ? match[1].trim().replace(/"/g, '') : 'utf-8';
		return response.arrayBuffer().then(function (buffer) {
			var decoder;
			try {
				decoder = new TextDecoder(charset);
			} catch (unknownCharset) {
				decoder = new TextDecoder('utf-8');
			}
			return JSON.parse(decoder.decode(buffer));
		});
	}

	// Controller responses may arrive as the body itself or wrapped in "data". Anything that is not JSON (e.g. the
	// login page after the session expired) is an error, never an empty result.
	function fetchJson(path) {
		return fetch(API + path, { credentials: 'same-origin', headers: { Accept: 'application/json' } })
			.then(function (response) {
				var contentType = response.headers.get('Content-Type') || '';
				if (response.redirected || contentType.indexOf('json') === -1) {
					var notJson = new Error(t('error.session'));
					notJson.status = response.status;
					notJson.session = true;
					throw notJson;
				}
				return readJson(response).then(function (json) {
					var data = json && json.data !== undefined && !Array.isArray(json) ? json.data : json;
					if (!response.ok || (data && data.error)) {
						var error = new Error((data && data.error) || ('HTTP ' + response.status));
						error.status = response.status;
						throw error;
					}
					return data;
				});
			});
	}

	var running = 0;
	var queue = [];

	function schedule(task, background) {
		return new Promise(function (resolve, reject) {
			var job = { task: task, resolve: resolve, reject: reject };
			if (background) {
				queue.push(job);
			} else {
				queue.unshift(job);
			}
			runQueue();
		});
	}

	function runQueue() {
		while (running < MAX_PARALLEL && queue.length) {
			var job = queue.shift();
			running++;
			job.task().then(job.resolve, job.reject).then(function () {
				running--;
				runQueue();
			});
		}
	}

	// path -> { time, promise, data }; failed requests are not kept
	var cache = restoreCache();
	var saveTimer = null;
	var prefetchBudget = 0;

	// Lost session or permission: nothing loaded so far may be shown any longer
	function revokes(error) {
		return error.session || error.status === 401 || error.status === 403;
	}

	// Drops all answers and built views; the open view is built again and shows the error. Only once per loss:
	// the rebuilt views have no answers yet, so their failing requests do not rebuild again.
	function discardAll() {
		var hadData = Object.keys(cache).some(function (path) { return cache[path].data !== undefined; });
		cache = {};
		clearStore();
		loadedViews = {};
		if (hadData && !panel.hidden) {
			showView(currentView);
		}
	}

	function clearStore() {
		try {
			window.sessionStorage.removeItem(STORE_KEY);
		} catch (ignored) {
			// Storage unavailable: nothing stored
		}
	}

	function restoreCache() {
		var entries = {};
		try {
			var stored = JSON.parse(window.sessionStorage.getItem(STORE_KEY) || 'null');
			if (!cacheKey || !stored || stored.key !== cacheKey) {
				// Answers of another user (or of nobody known) are removed, not only ignored
				clearStore();
				return entries;
			}
			Object.keys(stored.entries || {}).forEach(function (path) {
				var item = stored.entries[path];
				if (item && Date.now() - item.time < STORE_MAX_AGE_MS) {
					entries[path] = { time: item.time, data: item.data, promise: Promise.resolve(item.data) };
				}
			});
		} catch (ignored) {
			// Storage unavailable or unreadable: start empty
		}
		return entries;
	}

	function saveCache() {
		clearTimeout(saveTimer);
		saveTimer = setTimeout(function () {
			if (!cacheKey) {
				return;
			}
			var entries = {};
			Object.keys(cache).forEach(function (path) {
				if (cache[path].data !== undefined) {
					entries[path] = { time: cache[path].time, data: cache[path].data };
				}
			});
			try {
				window.sessionStorage.setItem(STORE_KEY, JSON.stringify({ key: cacheKey, entries: entries }));
			} catch (ignored) {
				// Storage full or blocked: the in-page cache still works
			}
		}, 300);
	}

	function load(path, background) {
		var entry = { time: Date.now(), promise: schedule(function () { return fetchJson(path); }, background) };
		cache[path] = entry;
		entry.promise.then(function (data) {
			entry.data = data;
			saveCache();
			updatePower(data);
			updateHosts(data);
		}, function (error) {
			if (revokes(error)) {
				discardAll();
			} else if (cache[path] === entry) {
				delete cache[path];
			}
			saveCache();
		});
		return entry.promise;
	}

	function cached(path) {
		return cache[path];
	}

	function isFresh(entry) {
		return !!entry && Date.now() - entry.time < FRESH_MS;
	}

	// Background request: answer from the cache if fresh, otherwise a new request while the budget lasts
	function prefetchLoad(path) {
		var entry = cached(path);
		if (isFresh(entry)) {
			return entry.promise;
		}
		if (prefetchBudget <= 0) {
			return Promise.reject(new Error('prefetch budget used up'));
		}
		prefetchBudget--;
		return load(path, true);
	}

	// Shows cached data at once; loads again if it is not fresh. render is called for every answer.
	function withData(path, render, fail) {
		var entry = cached(path);
		if (entry) {
			entry.promise.then(render, function () { /* loaded again below */ });
			if (isFresh(entry)) {
				// A fresh entry may still be a pending request that fails; then load once more for this view
				entry.promise.catch(function () { load(path).then(render, fail); });
				return;
			}
		}
		load(path).then(render, fail);
	}

	// Loads the children of the nodes and the levels below in the background (bounded per level)
	function prefetchTree(kind, nodes) {
		var level = LEVELS[kind];
		if (!level) {
			return;
		}
		nodes.slice(0, PREFETCH_LIMIT).forEach(function (node) {
			prefetchLoad(level.path(node)).then(function (data) {
				prefetchTree(level.childKind, level.items(data, node) || []);
			}, function () { /* shown when the node is expanded */ });
		});
	}

	// Everything the views need, started when the panel opens
	function prefetchViews() {
		prefetchBudget = PREFETCH_TOTAL;
		prefetchLoad('/clusters').then(function (data) {
			var clusters = data.clusters || [];
			prefetchTree('cluster', clusters);
			prefetchTree('storageCluster', clusters);
			prefetchTree('networkCluster', clusters);
		}, function () { /* shown in the view */ });
		prefetchLoad('/vm-folders').catch(function () { /* shown in the view */ });
	}

	// Ids of all listed objects of an answer, e.g. "vms:16"
	function idsOf(data) {
		var ids = [];
		Object.keys(data || {}).forEach(function (key) {
			if (Array.isArray(data[key])) {
				data[key].forEach(function (item) {
					if (item && item.id !== undefined) {
						ids.push(key + ':' + item.id);
					}
				});
			}
		});
		return ids;
	}

	// Renders the first answer and later answers that differ. If the user has meanwhile expanded a node in the
	// container, a newer answer is only rendered when objects were removed (replacing would collapse the node).
	// Lost session or permission replaces whatever is shown; other errors only show while nothing is shown.
	function updater(container, show, alive) {
		var shownJson = null;
		var shownIds = [];
		return {
			render: function (data) {
				var json = JSON.stringify(data);
				if ((alive && !alive()) || json === shownJson) {
					return;
				}
				var ids = idsOf(data);
				var removed = shownIds.some(function (id) { return ids.indexOf(id) === -1; });
				if (shownJson !== null && !removed && container.querySelector('.ie-toggle[aria-expanded="true"]')) {
					return;
				}
				shownJson = json;
				shownIds = ids;
				show(data);
			},
			fail: function (error) {
				if ((alive && !alive()) || (shownJson !== null && !revokes(error))) {
					return;
				}
				shownJson = null;
				shownIds = [];
				container.replaceChildren(message(errorText(error), 'ie-error'));
			}
		};
	}

	// --- Actions per VM ------------------------------------------------------------------------------------------

	var openMenu = null;

	function menuEntries(node) {
		var actions = node.actions || [];
		var power = vmPower[node.id] || node.powerState;
		var entries = [];
		if (isLocalPath(node.url)) {
			entries.push({ label: t('menu.details'), run: function () { window.location.href = node.url; } });
		}
		if (isLocalPath(node.instanceUrl)) {
			entries.push({ label: t('menu.instance'), run: function () { window.location.href = node.instanceUrl; } });
		}
		if (actions.indexOf('console') !== -1) {
			entries.push({ label: t('menu.console'), run: function () { openConsole(node); } });
		}
		if (actions.indexOf('power') !== -1) {
			if (power !== 'on') {
				entries.push({ label: t('menu.start'), run: function () { powerAction(node, POWER.start); } });
			}
			if (power !== 'off') {
				entries.push({ label: t('menu.shutdown'), run: function () { powerAction(node, POWER.shutdown); } });
				entries.push({ label: t('menu.powerOff'), run: function () { powerAction(node, POWER.powerOff); } });
			}
			if (power === 'on') {
				entries.push({ label: t('menu.restart'), run: function () { powerAction(node, POWER.restart); } });
			}
		}
		if (actions.indexOf('folder') !== -1) {
			entries.push({ label: t('menu.moveToFolder'), run: function () { openFolderDialog(node); } });
		}
		if (actions.indexOf('reconfigure') !== -1 && isLocalPath(node.url)) {
			entries.push({ label: t('menu.reconfigure'), run: function () { window.location.href = node.url + '#ie-reconfigure'; } });
		}
		if (actions.indexOf('convert') !== -1 && isLocalPath(node.url)) {
			entries.push({ label: t('menu.convert'), run: function () { window.location.href = node.url + '#ie-convert'; } });
		}
		return entries;
	}

	function isLocalPath(url) {
		return typeof url === 'string' && url.charAt(0) === '/' && url.charAt(1) !== '/';
	}

	// Right click opens the menu at the mouse; Shift+F10 and the context menu key fire the same event without mouse
	// position, then the menu is placed at the element
	function menuAnchor(event, element) {
		if (event.clientX || event.clientY) {
			return { left: event.clientX, right: event.clientX, top: event.clientY, bottom: event.clientY };
		}
		var box = element.getBoundingClientRect();
		return { left: box.left, right: box.left + 180, top: box.top, bottom: box.bottom };
	}

	// entries is called when the menu opens, so it sees the current power and maintenance state
	function addActions(row, title, entries) {
		// Expandable rows: the toggle gets the focus back; its aria-expanded is the open state, not the menu
		var trigger = row.querySelector('.ie-toggle') || row.querySelector('.ie-name');
		if (!trigger.matches('.ie-toggle')) {
			if (!trigger.matches('a, button')) {
				trigger.tabIndex = 0;
			}
			trigger.setAttribute('aria-haspopup', 'menu');
			trigger.setAttribute('aria-expanded', 'false');
		}
		row.addEventListener('contextmenu', function (event) {
			event.preventDefault();
			showMenu(title, entries(), trigger, menuAnchor(event, trigger));
		});
	}

	// Context menu for VMs and folders; anchor is a rectangle (mouse position or element)
	function showMenu(title, entries, trigger, anchor) {
		closeMenu(false);
		var menu = el('div', 'ie-menu');
		menu.setAttribute('role', 'menu');
		menu.setAttribute('aria-label', title);
		entries.forEach(function (entry) {
			var item = el('button', 'ie-menu-item', entry.label);
			item.type = 'button';
			item.setAttribute('role', 'menuitem');
			item.tabIndex = -1;
			item.addEventListener('click', function () {
				closeMenu(true);
				entry.run();
			});
			menu.appendChild(item);
		});
		menu.addEventListener('keydown', function (event) {
			var items = Array.prototype.slice.call(menu.querySelectorAll('.ie-menu-item'));
			var index = items.indexOf(document.activeElement);
			if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
				event.preventDefault();
				var step = event.key === 'ArrowDown' ? 1 : -1;
				items[(index + step + items.length) % items.length].focus();
			} else if (event.key === 'Home' || event.key === 'End') {
				event.preventDefault();
				items[event.key === 'Home' ? 0 : items.length - 1].focus();
			} else if (event.key === 'Escape') {
				// Closes only the menu, not the panel
				event.preventDefault();
				event.stopPropagation();
				closeMenu(true);
			} else if (event.key === 'Tab') {
				closeMenu(false);
			}
		});
		root.appendChild(menu);
		// Below the anchor, kept inside the window
		var width = menu.offsetWidth;
		var height = menu.offsetHeight;
		var left = Math.min(anchor.right - width, window.innerWidth - width - 8);
		var top = anchor.bottom + 2;
		if (top + height > window.innerHeight - 8) {
			top = Math.max(8, anchor.top - height - 2);
		}
		menu.style.left = Math.max(8, left) + 'px';
		menu.style.top = top + 'px';
		// Folder toggles use aria-expanded for their own open state; only real menu triggers report the menu
		var reportsMenu = trigger.getAttribute('aria-haspopup') === 'menu';
		if (reportsMenu) {
			trigger.setAttribute('aria-expanded', 'true');
		}
		openMenu = { menu: menu, trigger: trigger, reportsMenu: reportsMenu };
		var first = menu.querySelector('.ie-menu-item');
		if (first) {
			first.focus();
		}
	}

	function closeMenu(restoreFocus) {
		if (!openMenu) {
			return;
		}
		var current = openMenu;
		openMenu = null;
		current.menu.remove();
		if (current.reportsMenu) {
			current.trigger.setAttribute('aria-expanded', 'false');
		}
		if (restoreFocus && current.trigger.isConnected) {
			current.trigger.focus();
		}
	}

	document.addEventListener('mousedown', function (event) {
		if (openMenu && !openMenu.menu.contains(event.target) && event.target !== openMenu.trigger) {
			closeMenu(false);
		}
	});

	function openConsole(node) {
		var win = window.open('/terminal/server/' + encodeURIComponent(node.id), 'server_terminal_' + node.id, CONSOLE_WINDOW);
		if (win) {
			win.focus();
		} else {
			showStatus(t('status.consoleBlocked'), true);
		}
	}

	// Power endpoints of the Morpheus UI (verified against Morpheus 9.1): start and restart as form POST with _method=PUT and
	// "OK" as answer; stop as JSON PUT with force (graceful waits for the guest's ACPI shutdown, up to a few minutes).
	var POWER = {
		start: { task: 'task.start', request: function (id) { return formPut('/infrastructure/servers/' + id + '/start'); } },
		restart: { task: 'task.restart', request: function (id) { return formPut('/infrastructure/servers/' + id + '/restart'); } },
		shutdown: { task: 'task.shutdown', request: function (id) { return stopRequest(id, false); } },
		powerOff: {
			task: 'task.powerOff',
			warning: 'warning.powerOff',
			request: function (id) { return stopRequest(id, true); }
		}
	};

	function csrfHeaders(contentType) {
		var token = document.querySelector('meta[name="_csrf"]');
		var header = document.querySelector('meta[name="_csrf_header"]');
		var headers = { Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest', 'Content-Type': contentType };
		if (token && token.content) {
			headers[(header && header.content) || 'X-XSRF-TOKEN'] = token.content;
		}
		return headers;
	}

	function formPut(url) {
		return fetch(url, {
			method: 'POST', credentials: 'same-origin', body: '_method=PUT',
			headers: csrfHeaders('application/x-www-form-urlencoded; charset=UTF-8')
		});
	}

	function stopRequest(id, force) {
		return fetch('/infrastructure/servers/stop', {
			method: 'PUT', credentials: 'same-origin', body: JSON.stringify({ ids: [id], force: force }),
			headers: csrfHeaders('application/json')
		});
	}

	// Uses the power endpoints of the Morpheus UI, so Morpheus checks the permissions again
	function powerAction(node, operation) {
		uiAction(t(operation.task, { name: node.name }), operation.warning && t(operation.warning),
			function () { return operation.request(node.id); }, function () { refreshPower(node); });
	}

	// Runs a request of the Morpheus UI after a confirmation (skipped with skipConfirm); answers are "OK" or JSON
	// with success. task is the whole action as one text, e.g. "Start vm-1".
	function uiAction(task, warning, request, done, skipConfirm) {
		if (!skipConfirm && !window.confirm(t('action.confirm', { task: task }) + (warning ? '\n' + warning : ''))) {
			return;
		}
		showStatus(t('action.running', { task: task }), false);
		request().then(function (response) {
			var contentType = response.headers.get('Content-Type') || '';
			if (response.redirected) {
				throw new Error(response.url.indexOf('invalid-csrf') !== -1 ? t('error.csrf') : t('error.session'));
			}
			var body = contentType.indexOf('json') !== -1 ? readJson(response) : Promise.resolve(null);
			return body.then(function (json) {
				if (!response.ok || (json && json.success === false)) {
					throw new Error((json && (json.msg || json.message)) ||
						(response.status === 403 ? t('error.forbidden') : 'HTTP ' + response.status));
				}
			});
		}).then(function () {
			showStatus(t('action.accepted', { task: task }), false);
			if (done) {
				done();
			}
		}).catch(function (error) {
			showStatus(t('action.failed', { task: task, error: error.message }), true);
		});
	}

	function hostEntries(node) {
		var actions = node.actions || [];
		var entries = [];
		if (isLocalPath(node.url)) {
			entries.push({ label: t('menu.details'), run: function () { window.location.href = node.url; } });
		}
		if (actions.indexOf('maintenance') !== -1 && isLocalPath(node.url)) {
			var inMaintenance = hostMaintenance.hasOwnProperty(node.id) ? hostMaintenance[node.id] : node.maintenance;
			if (inMaintenance) {
				entries.push({ label: t('menu.exitMaintenance'), run: function () { leaveMaintenance(node); } });
			} else {
				// Morpheus asks for the options (e.g. moving the VMs) in its own dialog
				entries.push({ label: t('menu.enterMaintenance'), run: function () { window.location.href = node.url + '#ie-maintenance'; } });
			}
		}
		if (actions.indexOf('restart') !== -1) {
			entries.push({ label: t('menu.restart'), run: function () {
				uiAction(t('task.restartHost', { name: node.name }), t('warning.restartHost'),
					function () { return formPut('/infrastructure/servers/' + encodeURIComponent(node.id) + '/restart'); },
					function () { refreshHosts(node); });
			} });
		}
		if (actions.indexOf('edit') !== -1 && isLocalPath(node.url)) {
			entries.push({ label: t('menu.edit'), run: function () { window.location.href = node.url + '#ie-edit'; } });
		}
		return entries;
	}

	// Same request as the server list of the Morpheus UI
	function leaveMaintenance(node) {
		uiAction(t('task.exitMaintenance', { name: node.name }), t('warning.exitMaintenance'), function () {
			return fetch('/infrastructure/servers/bulk-leave-maintenance', {
				method: 'POST', credentials: 'same-origin', body: 'ids=' + encodeURIComponent(node.id),
				headers: csrfHeaders('application/x-www-form-urlencoded; charset=UTF-8')
			});
		}, function () { refreshHosts(node); });
	}

	function refreshHosts(node) {
		POWER_REFRESH_MS.forEach(function (delay) {
			setTimeout(function () {
				if (node.clusterId != null) {
					load('/hosts?clusterId=' + encodeURIComponent(node.clusterId), true).catch(function () { /* next try */ });
				}
			}, delay);
		});
	}

	function clusterEntries(node) {
		var actions = node.actions || [];
		var entries = [];
		if (isLocalPath(node.url)) {
			entries.push({ label: t('menu.details'), run: function () { window.location.href = node.url; } });
		}
		if (actions.indexOf('refresh') !== -1) {
			entries.push({ label: t('menu.refreshShort'), run: function () { refreshCluster(node, false); } });
			entries.push({ label: t('menu.refreshDaily'), run: function () { refreshCluster(node, true); } });
		}
		if (actions.indexOf('edit') !== -1 && isLocalPath(node.url)) {
			entries.push({ label: t('menu.edit'), run: function () { window.location.href = node.url + '#ie-edit'; } });
		}
		return entries;
	}

	// Refresh of the Morpheus UI (GET, like its "Refresh" and "Daily" links); no confirmation, nothing is changed
	function refreshCluster(node, daily) {
		uiAction(t(daily ? 'task.refreshDaily' : 'task.refresh', { name: node.name }), null, function () {
			return fetch('/infrastructure/clusters/' + encodeURIComponent(node.id) + '/refresh' + (daily ? '?mode=daily' : ''), {
				credentials: 'same-origin', headers: { Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest' }
			});
		}, null, true);
	}

	function refreshPower(node) {
		POWER_REFRESH_MS.forEach(function (delay) {
			setTimeout(function () {
				if (node.hostId != null) {
					load('/vms?hostId=' + encodeURIComponent(node.hostId), true).catch(function () { /* next try */ });
				}
				if (cached('/vm-folders')) {
					load('/vm-folders', true).catch(function () { /* next try */ });
				}
			}, delay);
		});
	}

	// --- New instance in a folder ------------------------------------------------------------------------------

	var NEW_INSTANCE_HASH = '#ie-new-instance=';
	var MAX_PREFILL_TAGS = 10;

	// Opens the instance list with the wizard of Morpheus; the folder path travels as tags in the hash
	function newInstance(folder) {
		var tags = (folder.path || []).slice(0, folderLevels.length).map(function (name, index) {
			return { name: folderLevels[index], value: name };
		});
		window.location.href = '/provisioning/instances' + NEW_INSTANCE_HASH + encodeURIComponent(JSON.stringify(tags));
	}

	// Tags from the hash: only name/value strings, bounded, otherwise nothing is prefilled
	function tagsFromHash(hash) {
		try {
			var tags = JSON.parse(decodeURIComponent(hash.slice(NEW_INSTANCE_HASH.length)));
			if (!Array.isArray(tags) || tags.length > MAX_PREFILL_TAGS) {
				return [];
			}
			return tags.filter(function (tag) {
				return tag && typeof tag.name === 'string' && typeof tag.value === 'string' &&
					tag.name.length > 0 && tag.name.length <= 100 && tag.value.length <= 100;
			});
		} catch (invalid) {
			return [];
		}
	}

	// On the instance list opened from the menu: open "Add" and enter the tags in the wizard's tag rows as soon as
	// Morpheus renders them (it reads the rows on change; "+" adds a row and renders them again)
	function openNewInstanceFromHash() {
		if (window.location.hash.indexOf(NEW_INSTANCE_HASH) !== 0 || document.body.dataset.page !== 'instances/index') {
			return;
		}
		var tags = tagsFromHash(window.location.hash);
		history.replaceState(null, '', window.location.pathname + window.location.search);
		closePanelForDialog();
		var open = function () {
			var add = document.querySelector('.add-instance[data-target="#new-instance-modal"]');
			var modal = document.getElementById('new-instance-modal');
			if (!add || !modal) {
				return;
			}
			if (tags.length) {
				prefillWizardTags(modal, tags);
			}
			add.click();
		};
		if (document.readyState === 'complete') {
			setTimeout(open, 300);
		} else {
			window.addEventListener('load', function () { setTimeout(open, 300); });
		}
	}

	function prefillWizardTags(modal, tags) {
		var done = false;
		var observer = new MutationObserver(function () {
			var wrapper = modal.querySelector('.instance-metadata-wrapper');
			if (done || !wrapper || !wrapper.querySelector('.metadata-row')) {
				return;
			}
			done = true;
			observer.disconnect();
			tags.forEach(function (tag) { fillTagRow(wrapper, tag); });
			var note = document.createElement('p');
			note.className = 'help-block';
			note.textContent = t('wizard.prefilled', { tags: tags.map(function (tag) {
				return tag.name + '=' + tag.value;
			}).join(', ') });
			wrapper.parentNode.insertBefore(note, wrapper.nextSibling);
		});
		observer.observe(modal, { childList: true, subtree: true });
		// The wizard is left open by the user; stop watching when it closes
		$jq(modal).one('hidden.bs.modal', function () { observer.disconnect(); });
	}

	// jQuery of the Morpheus page, if any (only for its modal events)
	function $jq(element) {
		var jq = window.jQuery;
		return jq ? jq(element) : { one: function () { /* no jQuery: observer ends with the page */ } };
	}

	function fillTagRow(wrapper, tag) {
		var row = emptyTagRow(wrapper);
		if (!row) {
			var add = wrapper.querySelectorAll('.add-row');
			if (!add.length) {
				return;
			}
			add[add.length - 1].click();
			row = emptyTagRow(wrapper);
			if (!row) {
				return;
			}
		}
		setInput(row.querySelector('[name="metadata.name"]'), tag.name);
		setInput(row.querySelector('[name="metadata.value"]'), tag.value);
	}

	function emptyTagRow(wrapper) {
		var rows = wrapper.querySelectorAll('.metadata-row');
		for (var i = 0; i < rows.length; i++) {
			var name = rows[i].querySelector('[name="metadata.name"]');
			if (name && !name.value && !name.readOnly) {
				return rows[i];
			}
		}
		return null;
	}

	function setInput(input, value) {
		if (!input) {
			return;
		}
		input.value = value;
		input.dispatchEvent(new Event('change', { bubbles: true }));
	}

	// --- Folder assignment -------------------------------------------------------------------------------------

	var dialogCounter = 0;
	// From the last answer of vm-folders: tag names per level, tenant in which the user may create instances
	var folderLevels = [];
	var instanceCreate = null;

	// Folder names already used on a level within the tenant of the VM, as suggestions
	function folderSuggestions(vms, vm, level) {
		var names = {};
		(vms || []).forEach(function (other) {
			var sameTenant = other.tenantId === vm.tenantId;
			if (sameTenant && other.folders && other.folders[level]) {
				names[other.folders[level]] = true;
			}
		});
		return Object.keys(names).sort(collator.compare);
	}

	function openFolderDialog(node) {
		closeFolderDialog();
		showStatus(t('status.loadingFolders'), false);
		fresh('/vm-folders').then(function (data) {
			statusLine.hidden = true;
			var levels = data.levels || [];
			var vm = (data.vms || []).filter(function (item) { return item.id === node.id; })[0] || node;
			buildFolderDialog(node, vm, levels, data.vms || []);
		}, function (error) {
			showStatus(t('status.foldersFailed', { error: errorText(error) }), true);
		});
	}

	function fresh(path) {
		var entry = cached(path);
		return isFresh(entry) ? entry.promise : load(path);
	}

	// Modal dialog inside the panel with title, optional sub line, fields of the caller, error line, hint and buttons;
	// Escape, the focus trap and returning the focus are handled here
	function createDialog(titleText, subText, hintText) {
		closeFolderDialog();
		var trigger = document.activeElement;
		var id = 'ie-dialog-' + (++dialogCounter);
		var dialog = el('div', 'ie-dialog');
		dialog.setAttribute('role', 'dialog');
		dialog.setAttribute('aria-modal', 'true');
		dialog.setAttribute('aria-labelledby', id + '-title');
		var form = el('form', 'ie-dialog-box');
		// Holds the focus while the buttons are disabled during saving
		form.tabIndex = -1;
		var title = el('h2', 'ie-dialog-title', titleText);
		title.id = id + '-title';
		form.appendChild(title);
		if (subText) {
			form.appendChild(el('p', 'ie-sub', subText));
		}
		var fields = el('div');
		form.appendChild(fields);
		var error = el('p', 'ie-error');
		error.hidden = true;
		error.setAttribute('role', 'alert');
		form.appendChild(error);
		var hint = el('p', 'ie-hint', hintText);
		hint.id = id + '-hint';
		form.appendChild(hint);
		dialog.setAttribute('aria-describedby', hint.id);
		var buttons = el('div', 'ie-dialog-buttons');
		form.appendChild(buttons);
		dialog.appendChild(form);
		var inputCount = 0;

		var api = {
			form: form,
			hint: hint,
			addInput: function (labelText, value, suggestions) {
				var row = el('div', 'ie-field');
				var label = el('label', null, labelText);
				var input = el('input', 'ie-input');
				input.type = 'text';
				input.id = id + '-input-' + (inputCount++);
				input.maxLength = 100;
				input.autocomplete = 'off';
				input.value = value || '';
				label.htmlFor = input.id;
				row.appendChild(label);
				row.appendChild(input);
				if (suggestions && suggestions.length) {
					var list = el('datalist');
					list.id = input.id + '-list';
					suggestions.forEach(function (name) {
						var option = el('option');
						option.value = name;
						list.appendChild(option);
					});
					input.setAttribute('list', list.id);
					row.appendChild(list);
				}
				fields.appendChild(row);
				return input;
			},
			addCheckbox: function (labelText, checked) {
				var row = el('div', 'ie-field ie-field-check');
				var input = el('input', 'ie-check');
				input.type = 'checkbox';
				input.id = id + '-input-' + (inputCount++);
				input.checked = !!checked;
				var label = el('label', null, labelText);
				label.htmlFor = input.id;
				row.appendChild(input);
				row.appendChild(label);
				fields.appendChild(row);
				return input;
			},
			// Read-only line: label above, value below
			addInfo: function (labelText, value) {
				var row = el('div', 'ie-field ie-info');
				row.appendChild(el('span', 'ie-info-label', labelText));
				row.appendChild(el('span', 'ie-info-value', value));
				fields.appendChild(row);
			},
			addButton: function (labelText, primary) {
				var button = el('button', 'ie-button' + (primary ? ' ie-button-primary' : ''), labelText);
				button.type = primary ? 'submit' : 'button';
				buttons.appendChild(button);
				return button;
			},
			showError: function (text, focus) {
				error.textContent = text;
				error.hidden = false;
				if (focus) {
					focus.focus();
				}
			},
			// Disables the buttons while a request runs; the form keeps the focus
			busy: function (isBusy) {
				if (isBusy) {
					form.focus();
				}
				buttons.querySelectorAll('button').forEach(function (button) { button.disabled = isBusy; });
			},
			// Lost session or permission closes the dialog and drops all data; other errors stay in the dialog
			fail: function (failure, focus) {
				if (revokes(failure)) {
					closeFolderDialog();
					discardAll();
					showStatus(t('dialog.saveFailed', { error: saveErrorText(failure) }), true);
					focusOrFallback(null);
					return;
				}
				api.busy(false);
				api.showError(t('dialog.saveFailed', { error: saveErrorText(failure) }), focus);
			},
			close: function () {
				closeFolderDialog();
				focusOrFallback(trigger);
			},
			show: function (focus) {
				panel.appendChild(dialog);
				focus.focus();
			}
		};

		dialog.addEventListener('keydown', function (event) {
			if (event.key === 'Escape') {
				event.preventDefault();
				event.stopPropagation();
				api.close();
			} else if (event.key === 'Tab') {
				// Keeps the focus inside the dialog
				var focusable = Array.prototype.slice.call(form.querySelectorAll('input, button:not([disabled])'));
				var first = focusable[0];
				var last = focusable[focusable.length - 1];
				if (event.shiftKey && document.activeElement === first) {
					event.preventDefault();
					last.focus();
				} else if (!event.shiftKey && document.activeElement === last) {
					event.preventDefault();
					first.focus();
				}
			}
		});
		return api;
	}

	function buildFolderDialog(node, vm, levels, vms) {
		var dialog = createDialog(t('folder.title', { name: node.name }), vm.tenant ? t('folder.tenant', { tenant: vm.tenant }) : null,
			t('folder.hint'));
		var inputs = levels.map(function (level, index) {
			return dialog.addInput(t('folder.level', { number: index + 1, tag: level }), vm.folders && vm.folders[index],
				folderSuggestions(vms, vm, index));
		});
		var save = dialog.addButton(t('button.save'), true);
		var clear = dialog.addButton(t('button.noFolder'));
		var cancel = dialog.addButton(t('button.cancel'));

		function submit(path) {
			var gap = path.indexOf('');
			if (gap !== -1 && path.slice(gap).some(function (name) { return name !== ''; })) {
				dialog.showError(t('folder.gap', { number: gap + 1 }), inputs[gap]);
				return;
			}
			dialog.busy(true);
			saveFolders(node, path.filter(function (name) { return name !== ''; })).then(function (result) {
				dialog.close();
				showStatus(folderResult(node.name, result.vm.folders), false);
				if (result.shown) {
					revealPath(treeLabels(result.shown), result.shown.id);
				}
			}, function (failure) {
				dialog.fail(failure, save);
			});
		}

		dialog.form.addEventListener('submit', function (event) {
			event.preventDefault();
			submit(inputs.map(function (input) { return input.value.trim(); }));
		});
		clear.addEventListener('click', function () { submit([]); });
		cancel.addEventListener('click', dialog.close);
		dialog.show(inputs[0] || save);
	}

	// Renames the levels (tag names, plugin setting folderTags); optionally the tags of the VMs as well
	function openLevelDialog(levels) {
		var dialog = createDialog(t('levels.title'), t('levels.sub'), t('levels.hint'));
		var inputs = levels.map(function (level, index) {
			return dialog.addInput(t('levels.level', { number: index + 1 }), level);
		});
		var migrate = dialog.addCheckbox(t('levels.migrate'), true);
		var save = dialog.addButton(t('button.save'), true);
		var cancel = dialog.addButton(t('button.cancel'));

		dialog.form.addEventListener('submit', function (event) {
			event.preventDefault();
			var names = inputs.map(function (input) { return input.value.trim(); });
			var problem = levelProblem(names);
			if (problem) {
				dialog.showError(problem.text, inputs[problem.index]);
				return;
			}
			var renames = levels.map(function (old, index) { return [old, names[index]]; })
				.filter(function (pair) { return pair[0] !== pair[1]; });
			if (!renames.length) {
				dialog.close();
				return;
			}
			if (migrate.checked && renames.some(function (pair) { return levels.indexOf(pair[1]) !== -1; })) {
				dialog.showError(t('levels.swap'), save);
				return;
			}
			dialog.busy(true);
			saveLevels(names, migrate.checked ? renames : []).then(function (result) {
				dialog.close();
				showStatus(!result ? t('levels.saved') : result.skipped ?
					tn('levels.savedSkipped', result.changed, { skipped: formatNumber(result.skipped) }) :
					tn('levels.savedRenamed', result.changed), false);
			}, function (failure) {
				dialog.fail(failure, save);
			});
		});
		cancel.addEventListener('click', dialog.close);
		dialog.show(inputs[0] || save);
	}

	// Name, version, author and license of the plugin (rendered into the markup by the overlay provider)
	function openAboutDialog() {
		var dialog = createDialog(t('about.title'), t('about.description'), t('about.hint', { license: root.dataset.ieLicense || 'MIT' }));
		dialog.addInfo(t('about.version'), root.dataset.ieVersion || '–');
		dialog.addInfo(t('about.author'), root.dataset.ieAuthor || '–');
		dialog.addInfo(t('about.license'), root.dataset.ieLicense || '–');
		var close = dialog.addButton(t('button.close'), true);
		dialog.form.addEventListener('submit', function (event) {
			event.preventDefault();
			dialog.close();
		});
		dialog.show(close);
	}

	// Same rules as the route: not empty, at most 100 characters, no comma, no duplicates
	function levelProblem(names) {
		for (var i = 0; i < names.length; i++) {
			if (!names[i]) {
				return { index: i, text: t('levels.empty', { number: i + 1 }) };
			}
			if (names[i].indexOf(',') !== -1) {
				return { index: i, text: t('levels.comma') };
			}
			if (names.indexOf(names[i]) !== i) {
				return { index: i, text: t('levels.twice', { name: names[i] }) };
			}
		}
		return null;
	}

	// Tags first (repeatable: a second run finds nothing left to rename), then the setting through the plugin API
	// of Morpheus with the session (Morpheus checks the Plugins permission)
	function saveLevels(names, renames) {
		var tags = renames.length ? postForm('/level-rename', renames.map(function (pair) { return ['from', pair[0]]; })
			.concat(renames.map(function (pair) { return ['to', pair[1]]; }))) : Promise.resolve(null);
		return tags.then(function (result) {
			return savePluginSetting(names).then(function () {
				return rebuildInstances().then(function () { return result; });
			}, function (failure) {
				if (result) {
					failure.message = t('levels.settingFailed', { error: failure.message });
				}
				throw failure;
			});
		});
	}

	function apiJson(response) {
		var contentType = response.headers.get('Content-Type') || '';
		if (response.redirected || contentType.indexOf('json') === -1) {
			var invalid = new Error(t('error.session'));
			invalid.session = true;
			throw invalid;
		}
		return readJson(response).then(function (json) {
			if (!response.ok || (json && json.success === false)) {
				var failed = new Error((json && (json.msg || json.error)) || ('HTTP ' + response.status));
				failed.status = response.status;
				throw failed;
			}
			return json;
		});
	}

	function savePluginSetting(names) {
		return fetch('/api/plugins?max=200', {
			credentials: 'same-origin', headers: { Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest' }
		}).then(apiJson).then(function (data) {
			var plugin = (data.plugins || []).filter(function (item) { return item.code === 'infra-explorer'; })[0];
			if (!plugin) {
				throw new Error(t('error.pluginMissing'));
			}
			var config = {};
			Object.keys(plugin.config || {}).forEach(function (key) { config[key] = plugin.config[key]; });
			config.folderTags = names.join(',');
			return fetch('/api/plugins/' + encodeURIComponent(plugin.id), {
				method: 'PUT', credentials: 'same-origin', body: JSON.stringify({ plugin: { config: config } }),
				headers: csrfHeaders('application/json')
			}).then(apiJson);
		});
	}

	// Renames a folder (folder node of the instances view) for all VMs below it
	function openRenameDialog(folder, siblings) {
		var where = [folder.tenant].concat(folder.path.slice(0, -1)).filter(Boolean).join(' / ');
		var dialog = createDialog(t('rename.title'), where ? t('rename.where', { path: where }) : null,
			tn('rename.hint', folder.count));
		var input = dialog.addInput(t('rename.name'), folder.name);
		var save = dialog.addButton(t('button.rename'), true);
		var cancel = dialog.addButton(t('button.cancel'));
		var baseHint = dialog.hint.textContent;

		// Renaming to the name of a sibling merges both folders; the hint says so before saving
		input.addEventListener('input', function () {
			var name = input.value.trim();
			// Tag values are compared exactly, like the tree groups them
			var merges = name !== folder.name && siblings.indexOf(name) !== -1;
			dialog.hint.textContent = baseHint + (merges ? ' ' + t('rename.merge', { name: name }) : '');
		});

		dialog.form.addEventListener('submit', function (event) {
			event.preventDefault();
			var name = input.value.trim();
			if (!name) {
				dialog.showError(t('rename.empty'), input);
				return;
			}
			if (name === folder.name) {
				dialog.close();
				return;
			}
			dialog.busy(true);
			renameFolder(folder, name).then(function (result) {
				dialog.close();
				showStatus(tn('rename.done', result.changed, { name: name }), false);
				revealPath([folder.tenant].concat(result.path || []).filter(Boolean), null);
			}, function (failure) {
				dialog.fail(failure, input);
			});
		});
		cancel.addEventListener('click', dialog.close);
		dialog.show(input);
		input.select();
	}

	// Messages of the route (400) are meant for the user; other errors get the general texts
	function saveErrorText(error) {
		if (error.status === 403 || error.status === 404) {
			return errorText(error);
		}
		return error.message + '.';
	}

	// The element that had the focus may have been replaced by a rebuilt view; then the active tab gets it
	function focusOrFallback(element) {
		if (element && element.isConnected) {
			element.focus();
		} else if (!panel.hidden) {
			var tab = panel.querySelector('.ie-tab[aria-pressed="true"]');
			if (tab) {
				tab.focus();
			}
		}
	}

	function closeFolderDialog() {
		var open = panel.querySelector('.ie-dialog');
		if (open) {
			open.remove();
		}
	}

	// POST to a plugin route with the CSRF token of the page; answers JSON or throws like fetchJson
	function postForm(path, params) {
		var body = params.map(function (pair) {
			return encodeURIComponent(pair[0]) + '=' + encodeURIComponent(pair[1]);
		}).join('&');
		return fetch(API + path, {
			method: 'POST', credentials: 'same-origin', body: body,
			headers: csrfHeaders('application/x-www-form-urlencoded; charset=UTF-8')
		}).then(function (response) {
			var contentType = response.headers.get('Content-Type') || '';
			if (response.redirected || contentType.indexOf('json') === -1) {
				var invalid = new Error(response.url.indexOf('invalid-csrf') !== -1 ? t('error.csrf') : t('error.session'));
				invalid.session = response.url.indexOf('invalid-csrf') === -1;
				throw invalid;
			}
			return readJson(response).then(function (json) {
				if (!response.ok || json.error) {
					var failed = new Error(json.error || ('HTTP ' + response.status));
					failed.status = response.status;
					throw failed;
				}
				return json;
			});
		});
	}

	// After a folder change the instances view is built again from a fresh answer (the tree structure changed)
	function rebuildInstances() {
		return load('/vm-folders').then(function (data) {
			delete loadedViews.vms;
			if (currentView === 'vms' && !panel.hidden) {
				showView('vms');
			}
			return data;
		}, function () {
			return null;
		});
	}

	function saveFolders(node, path) {
		var params = [['serverId', node.id]].concat(path.map(function (name) { return ['path', name]; }));
		return postForm('/vm-folder', params).then(function (result) {
			return rebuildInstances().then(function (data) {
				// The node of the fresh answer carries the tenant, the first level of the tree
				if (currentView === 'vms') {
					result.shown = (data && data.vms || []).filter(function (vm) { return vm.id === result.vm.id; })[0] ||
						result.vm;
				}
				return result;
			});
		});
	}

	function renameFolder(folder, name) {
		var params = [['tenantId', folder.tenantId]].concat(folder.path.map(function (part) { return ['path', part]; }),
			[['name', name]]);
		return postForm('/folder-rename', params).then(function (result) {
			return rebuildInstances().then(function () { return result; });
		});
	}

	// Labels from the top of the instances tree down to the VM: tenant, then folders
	function treeLabels(vm) {
		return (vm.tenant ? [vm.tenant] : []).concat(vm.folders || []);
	}

	// Expands the folders along the labels in the instances view and focuses the VM, or else the last folder. Runs
	// after the rebuilt view has rendered its cached answer (promise callbacks first, then this timeout).
	function revealPath(labels, vmId) {
		setTimeout(function () { revealNow(labels, vmId); }, 0);
	}

	function revealNow(labels, vmId) {
		var container = loadedViews.vms;
		if (!container) {
			focusOrFallback(null);
			return;
		}
		var scope = container;
		var toggle = null;
		for (var i = 0; i < labels.length && scope; i++) {
			toggle = null;
			Array.prototype.forEach.call(scope.querySelectorAll(':scope > .ie-tree > .ie-node > .ie-row > .ie-toggle'), function (candidate) {
				if (!toggle && candidate.textContent === labels[i]) {
					toggle = candidate;
				}
			});
			if (!toggle) {
				focusOrFallback(null);
				return;
			}
			if (toggle.getAttribute('aria-expanded') !== 'true') {
				toggle.click();
			}
			scope = toggle.closest('.ie-node').querySelector(':scope > .ie-children');
		}
		var target = vmId == null ? toggle :
			container.querySelector('.ie-node-vm[data-ie-vm-id="' + vmId + '"] > .ie-row > .ie-name');
		if (target) {
			target.focus();
			target.scrollIntoView({ block: 'nearest' });
		} else {
			focusOrFallback(null);
		}
	}

	var statusTimer = null;

	function showStatus(text, isError) {
		clearTimeout(statusTimer);
		statusLine.textContent = text;
		statusLine.classList.toggle('ie-status-error', !!isError);
		statusLine.hidden = false;
		statusTimer = setTimeout(function () { statusLine.hidden = true; }, isError ? 15000 : 6000);
	}

	// The panel lies above Morpheus dialogs (z-index); it closes when one of them is opened from the menu
	function closePanelForDialog() {
		if (!panel.hidden) {
			setOpen(false);
		}
	}

	// On a server page opened from the menu: open the dialog of Morpheus itself (only if Morpheus shows its link)
	function openPageDialogFromHash() {
		var dialogs = PAGE_DIALOGS[document.body.dataset.page];
		var selector = dialogs && dialogs[window.location.hash];
		if (!selector) {
			return;
		}
		history.replaceState(null, '', window.location.pathname + window.location.search);
		closePanelForDialog();
		var open = function () {
			var link = document.querySelector(selector);
			if (link) {
				link.click();
			}
		};
		if (document.readyState === 'complete') {
			setTimeout(open, 300);
		} else {
			window.addEventListener('load', function () { setTimeout(open, 300); });
		}
	}

	function errorText(error) {
		if (error.status === 403) {
			return t('error.forbiddenView');
		}
		if (error.status === 404) {
			return t('error.notFound');
		}
		return t('error.loading', { error: error.message });
	}

	function powerBadge(state) {
		var known = POWER_STATES.indexOf(state) !== -1;
		var label = known ? t('power.' + state) : state;
		var badge = el('span', 'ie-power ie-power-' + (known ? state : 'unknown'));
		badge.title = t('power.title', { state: label });
		badge.appendChild(el('span', 'ie-sr', t('power.title', { state: label })));
		return badge;
	}

	// How to load and show the children of each node kind
	var LEVELS = {
		cluster: {
			path: function (node) { return '/hosts?clusterId=' + encodeURIComponent(node.id); },
			items: function (data) { return data.hosts; },
			childKind: 'host',
			empty: 'empty.hosts'
		},
		host: {
			path: function (node) { return '/vms?hostId=' + encodeURIComponent(node.id); },
			items: function (data) { return data.vms; },
			childKind: 'vm',
			empty: 'empty.vms'
		},
		storageCluster: {
			path: function (node) { return '/datastores?clusterId=' + encodeURIComponent(node.id); },
			items: function (data) { return data.datastores; },
			childKind: 'datastore',
			empty: 'empty.datastores'
		},
		networkCluster: {
			path: function (node) { return '/networks?clusterId=' + encodeURIComponent(node.id); },
			items: function (data) { return networkItems(data); },
			childKind: 'network',
			empty: 'empty.networks'
		},
		// The VMs of a network come with the networks of its cluster (same answer, no extra request)
		network: {
			path: function (node) { return '/networks?clusterId=' + encodeURIComponent(node.clusterId); },
			items: function (data, node) { return networkVms(data, node); },
			childKind: 'networkVm',
			empty: 'empty.networkVms'
		}
	};

	var UNITS = ['B', 'KiB', 'MiB', 'GiB', 'TiB', 'PiB'];

	function formatBytes(bytes) {
		var value = bytes;
		var unit = 0;
		while (value >= 1024 && unit < UNITS.length - 1) {
			value /= 1024;
			unit++;
		}
		return formatNumber(value, { maximumFractionDigits: value < 10 && unit > 0 ? 1 : 0 }) + ' ' + UNITS[unit];
	}

	// Used space as bar and text; nothing if the capacity is unknown
	function usage(node) {
		if (typeof node.storageSize !== 'number' || node.storageSize <= 0 || typeof node.freeSpace !== 'number') {
			return null;
		}
		var used = Math.max(0, node.storageSize - node.freeSpace);
		var percent = Math.min(100, Math.round(used * 100 / node.storageSize));
		var box = el('span', 'ie-usage');
		var bar = el('span', 'ie-usage-bar' + (percent >= 90 ? ' ie-usage-high' : ''));
		bar.style.width = percent + '%';
		var track = el('span', 'ie-usage-track');
		track.appendChild(bar);
		box.appendChild(track);
		box.appendChild(el('span', 'ie-usage-text',
			formatBytes(used) + ' / ' + formatBytes(node.storageSize) + ' (' + formatNumber(percent) + ' %)'));
		box.title = t('storage.free', { size: formatBytes(node.freeSpace) });
		return box;
	}

	// --- CPU and RAM of hosts and clusters ----------------------------------------------------------------------
	// Values of the Morpheus API (server stats, as on its host pages), read with the session: one request per
	// cluster gives all its hosts. The plugin services only see the host's own usage without its VMs.

	var hostStatsCache = {};

	function clusterHostStats(clusterId) {
		var entry = hostStatsCache[clusterId];
		if (entry && Date.now() - entry.time < FRESH_MS) {
			return entry.promise;
		}
		var promise = schedule(function () {
			return fetch('/api/servers?clusterId=' + encodeURIComponent(clusterId) + '&max=500', {
				credentials: 'same-origin', headers: { Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest' }
			}).then(apiJson);
		}, true).then(function (data) {
			var stats = {};
			(data.servers || []).forEach(function (server) {
				if (server.parentServer) {
					return;
				}
				var values = server.stats || {};
				stats[server.id] = {
					usedMemory: values.usedMemory, maxMemory: values.maxMemory || server.maxMemory,
					cpu: values.cpuUsage, cores: server.maxCores || 1
				};
			});
			return stats;
		});
		hostStatsCache[clusterId] = { time: Date.now(), promise: promise };
		promise.catch(function () {
			if (hostStatsCache[clusterId] && hostStatsCache[clusterId].promise === promise) {
				delete hostStatsCache[clusterId];
			}
		});
		return promise;
	}

	// Cluster: RAM as sum of the hosts, CPU as mean weighted by the cores
	function clusterLoad(stats) {
		var used = 0, max = 0, cpu = 0, cores = 0;
		Object.keys(stats).forEach(function (id) {
			var host = stats[id];
			if (typeof host.usedMemory === 'number' && typeof host.maxMemory === 'number' && host.maxMemory > 0) {
				used += host.usedMemory;
				max += host.maxMemory;
			}
			if (typeof host.cpu === 'number') {
				cpu += host.cpu * host.cores;
				cores += host.cores;
			}
		});
		return { usedMemory: max ? used : null, maxMemory: max || null, cpu: cores ? cpu / cores : null };
	}

	function meterLine(label, percent, title) {
		var line = el('span', 'ie-load-line');
		line.appendChild(el('span', 'ie-load-label', label));
		var track = el('span', 'ie-usage-track');
		var bar = el('span', 'ie-usage-bar' + (percent >= 90 ? ' ie-usage-high' : ''));
		bar.style.width = Math.min(100, Math.max(0, percent)) + '%';
		track.appendChild(bar);
		line.appendChild(track);
		line.appendChild(el('span', 'ie-load-value', formatNumber(Math.round(percent)) + ' %'));
		line.title = title;
		return line;
	}

	function fillLoad(box, load) {
		if (!load) {
			box.remove();
			return;
		}
		box.replaceChildren();
		if (typeof load.cpu === 'number') {
			box.appendChild(meterLine('CPU', load.cpu, t('load.cpu', { value: formatNumber(load.cpu, { maximumFractionDigits: 1 }) })));
		}
		if (typeof load.usedMemory === 'number' && load.maxMemory > 0) {
			box.appendChild(meterLine('RAM', load.usedMemory * 100 / load.maxMemory,
				t('load.ram', { used: formatBytes(load.usedMemory), max: formatBytes(load.maxMemory) })));
		}
		if (!box.childNodes.length) {
			box.remove();
		}
	}

	// Placeholder before the power badge or status, filled when the values arrive; without values it disappears
	function attachLoad(row, request, pick) {
		var box = el('span', 'ie-load');
		var anchor = row.querySelector(':scope > .ie-power, :scope > .ie-meta');
		row.insertBefore(box, anchor);
		request.then(function (stats) {
			fillLoad(box, pick(stats));
		}, function () {
			box.remove();
		});
	}

	// Only relative paths of the Morpheus UI are linked
	function nodeLabel(node) {
		var label;
		if (typeof node.url === 'string' && node.url.charAt(0) === '/' && node.url.charAt(1) !== '/') {
			label = el('a', 'ie-name', node.name);
			label.href = node.url;
		} else {
			label = el('span', 'ie-name', node.name);
		}
		return label;
	}

	function treeItem(kind, node) {
		if (kind === 'datastore') {
			return datastoreItem(node);
		}
		// VMs below a network are VM rows (power updates, menu) with their addresses in that network
		var addresses = kind === 'networkVm' ? node.ipAddresses || [] : null;
		if (addresses) {
			kind = 'vm';
		}
		var item = el('li', 'ie-node ie-node-' + kind);
		var row = el('div', 'ie-row');
		var level = LEVELS[kind];

		// Expandable nodes only expand on click, like the folders of the instances view; their detail page is in the
		// context menu. Leaves (VMs) link to their page.
		if (level) {
			var toggle = el('button', 'ie-toggle');
			toggle.type = 'button';
			toggle.setAttribute('aria-expanded', 'false');
			toggle.setAttribute('aria-label', t('tree.expand', { name: node.name }));
			toggle.appendChild(el('span', 'ie-caret'));
			toggle.appendChild(el('span', 'ie-name', node.name));
			toggle.addEventListener('click', function () {
				toggleNode(item, toggle, level, node);
			});
			row.appendChild(toggle);
		} else {
			row.appendChild(el('span', 'ie-leaf'));
			row.appendChild(nodeLabel(node));
		}
		if (node.managed === false) {
			row.appendChild(el('span', 'ie-tag', t('tag.unmanaged')));
		}
		if (addresses && addresses.length) {
			var ips = el('span', 'ie-sub ie-ips', addresses.join(', '));
			ips.title = t('network.addresses', { addresses: addresses.join(', ') });
			row.appendChild(ips);
		}
		if (kind === 'network') {
			networkRow(row, node);
		}
		if (kind === 'host') {
			var maintenanceTag = el('span', 'ie-tag ie-maintenance', t('tag.maintenance'));
			maintenanceTag.hidden = !(hostMaintenance.hasOwnProperty(node.id) ? hostMaintenance[node.id] : node.maintenance);
			row.appendChild(maintenanceTag);
		}
		if (node.hostName) {
			var host = el('span', 'ie-host', node.hostName);
			host.title = t('tree.host', { name: node.hostName });
			row.appendChild(host);
		}
		if (node.powerState) {
			row.appendChild(powerBadge(vmPower[node.id] || node.powerState));
		} else if (node.status && kind !== 'network') {
			row.appendChild(el('span', 'ie-meta', node.status));
		}
		if (kind === 'vm') {
			item.dataset.ieVmId = String(node.id);
			addActions(row, t('menu.actionsVm', { name: node.name }), function () { return menuEntries(node); });
		} else if (kind === 'network') {
			if (isLocalPath(node.url)) {
				addActions(row, t('menu.actionsNetwork', { name: node.name }), function () { return networkEntries(node); });
			}
		} else if (kind === 'host') {
			item.dataset.ieHostId = String(node.id);
			addActions(row, t('menu.actionsHost', { name: node.name }), function () { return hostEntries(node); });
			if (node.clusterId != null) {
				attachLoad(row, clusterHostStats(node.clusterId), function (stats) { return stats[node.id]; });
			}
		} else if (kind === 'cluster' || kind === 'storageCluster' || kind === 'networkCluster') {
			addActions(row, t('menu.actionsCluster', { name: node.name }), function () { return clusterEntries(node); });
			if (kind === 'cluster') {
				attachLoad(row, clusterHostStats(node.id), clusterLoad);
			}
		}
		item.appendChild(row);
		return item;
	}

	// Shows or hides the maintenance tag of all shown rows of the hosts in an answer
	function updateHosts(data) {
		(data && Array.isArray(data.hosts) ? data.hosts : []).forEach(function (host) {
			var maintenance = host.maintenance === true;
			hostMaintenance[host.id] = maintenance;
			root.querySelectorAll('.ie-node-host[data-ie-host-id="' + host.id + '"] > .ie-row > .ie-maintenance').forEach(function (tag) {
				tag.hidden = !maintenance;
			});
		});
	}

	// Replaces the power badges of all shown rows of the VMs in an answer
	function updatePower(data) {
		(data && Array.isArray(data.vms) ? data.vms : []).forEach(function (vm) {
			if (!vm.powerState || vmPower[vm.id] === vm.powerState) {
				return;
			}
			vmPower[vm.id] = vm.powerState;
			root.querySelectorAll('.ie-node-vm[data-ie-vm-id="' + vm.id + '"] > .ie-row > .ie-power').forEach(function (badge) {
				badge.replaceWith(powerBadge(vm.powerState));
			});
		});
	}

	function datastoreItem(node) {
		var item = el('li', 'ie-node ie-node-datastore');
		var row = el('div', 'ie-row');
		row.appendChild(el('span', 'ie-leaf'));
		var text = el('span', 'ie-stack');
		text.appendChild(nodeLabel(node));
		if (node.type) {
			text.appendChild(el('span', 'ie-sub', node.type));
		}
		row.appendChild(text);
		if (node.online === false) {
			row.appendChild(el('span', 'ie-tag ie-warn', t('tag.offline')));
		} else if (node.status === 'inactive') {
			row.appendChild(el('span', 'ie-tag', t('tag.inactive')));
		}
		var bar = usage(node);
		if (bar) {
			row.appendChild(bar);
		}
		item.appendChild(row);
		return item;
	}

	// --- Networks ----------------------------------------------------------------------------------------------

	// Networks of a cluster answer; VMs outside any shown network follow as one extra node
	function networkItems(data) {
		var nodes = (data.networks || []).slice();
		var without = data.withoutNetwork || [];
		if (without.length) {
			nodes.push({ id: 'without', name: t('network.without'), clusterId: data.clusterId, vmCount: without.length,
				without: true });
		}
		return nodes;
	}

	function networkVms(data, node) {
		if (node.without) {
			return data.withoutNetwork || [];
		}
		var network = (data.networks || []).filter(function (item) { return item.id === node.id; })[0];
		return network ? network.vms || [] : [];
	}

	// VLAN and IP networks next to the name, number of VMs at the end
	function networkRow(row, node) {
		var parts = [];
		if (node.vlanId != null) {
			parts.push(t('network.vlan', { id: node.vlanId }));
		}
		[node.cidr, node.cidrIPv6].forEach(function (cidr) {
			if (cidr) {
				parts.push(cidr);
			}
		});
		(node.subnets || []).forEach(function (subnet) {
			parts.push(subnet.cidr ? (subnet.name ? subnet.name + ' ' + subnet.cidr : subnet.cidr) : subnet.name);
		});
		if (parts.length) {
			var info = el('span', 'ie-sub ie-net', parts.join(' · '));
			info.title = parts.join('\n');
			row.appendChild(info);
		}
		var count = el('span', 'ie-meta', formatNumber(node.vmCount || 0));
		count.title = tn('network.vmCount', node.vmCount || 0);
		row.appendChild(count);
	}

	function networkEntries(node) {
		return [{ label: t('menu.details'), run: function () { window.location.href = node.url; } }];
	}

	function showChildren(children, level, data, node) {
		var nodes = level.items(data, node) || [];
		children.replaceChildren(list(level.childKind, nodes, level.empty));
		prefetchBudget = Math.min(PREFETCH_TOTAL, prefetchBudget + PREFETCH_LIMIT);
		prefetchTree(level.childKind, nodes);
	}

	function toggleNode(item, toggle, level, node) {
		var expanded = toggle.getAttribute('aria-expanded') === 'true';
		var children = item.querySelector(':scope > .ie-children');
		if (expanded) {
			toggle.setAttribute('aria-expanded', 'false');
			toggle.setAttribute('aria-label', t('tree.expand', { name: node.name }));
			if (children) {
				children.remove();
			}
			return;
		}
		toggle.setAttribute('aria-expanded', 'true');
		toggle.setAttribute('aria-label', t('tree.collapse', { name: node.name }));
		children = el('div', 'ie-children');
		// Replaced in the same frame when the data is already prefetched
		children.appendChild(message(t('tree.loading')));
		item.appendChild(children);

		var update = updater(children, function (data) {
			showChildren(children, level, data, node);
		}, function () {
			return children.isConnected;
		});
		withData(level.path(node), update.render, update.fail);
	}

	function list(kind, nodes, emptyKey) {
		if (!nodes.length) {
			return message(t(emptyKey));
		}
		var ul = el('ul', 'ie-tree');
		nodes.forEach(function (node) {
			ul.appendChild(treeItem(kind, node));
		});
		return ul;
	}

	// Cluster list as first level of a view; kind decides what expanding a cluster shows
	function clusterView(kind) {
		var container = el('div', 'ie-view');
		container.appendChild(message(t('tree.loading')));
		var update = updater(container, function (data) {
			var clusters = data.clusters || [];
			container.replaceChildren(list(kind, clusters, 'empty.clusters'));
			prefetchTree(kind, clusters);
		});
		withData('/clusters', update.render, update.fail);
		return container;
	}

	function loadClusters() {
		return clusterView('cluster');
	}

	function loadStorage() {
		return clusterView('storageCluster');
	}

	function loadNetworks() {
		return clusterView('networkCluster');
	}

	var collator = new Intl.Collator(LOCALE, { sensitivity: 'base', numeric: true });

	// Path of a VM: tenant first, then the folders from its tags. Tenants are keyed by id, folders by name.
	function folderPath(vm) {
		var path = [];
		if (vm.tenant || vm.tenantId != null) {
			path.push({ key: 'tenant:' + (vm.tenantId != null ? vm.tenantId : vm.tenant),
				name: vm.tenant || t('tenant.fallback', { id: vm.tenantId }), kind: 'tenant' });
		}
		(vm.folders || []).forEach(function (name) {
			path.push({ key: 'folder:' + name, name: name, kind: 'folder' });
		});
		return path;
	}

	// Folder tree from the paths of the VMs: { name, kind, folders: {key: folder}, vms: [], count }. Folder nodes also
	// carry tenant, tenantId and their path (folder names, outermost first); editable if the user may change folders.
	function buildFolderTree(vms) {
		var root = { folders: {}, vms: [], count: 0 };
		vms.forEach(function (vm) {
			var folder = root;
			var path = [];
			var tenantLabel = null;
			folder.count++;
			folderPath(vm).forEach(function (step) {
				if (step.kind === 'folder') {
					path = path.concat([step.name]);
				} else {
					tenantLabel = step.name;
				}
				if (!Object.prototype.hasOwnProperty.call(folder.folders, step.key)) {
					folder.folders[step.key] = { name: step.name, kind: step.kind, folders: {}, vms: [], count: 0,
						path: path, tenant: tenantLabel, tenantId: vm.tenantId, editable: false };
				}
				folder = folder.folders[step.key];
				folder.count++;
				if ((vm.actions || []).indexOf('folder') !== -1) {
					folder.editable = true;
				}
			});
			folder.vms.push(vm);
		});
		return root;
	}

	function sortedFolders(folder) {
		return Object.keys(folder.folders).map(function (key) {
			return folder.folders[key];
		}).sort(function (a, b) {
			return collator.compare(a.name, b.name);
		});
	}

	function folderItem(folder, parent) {
		var label = folder.name;
		var item = el('li', 'ie-node ie-node-' + (folder.kind || 'folder'));
		var row = el('div', 'ie-row');
		var toggle = el('button', 'ie-toggle');
		toggle.type = 'button';
		toggle.setAttribute('aria-expanded', 'false');
		toggle.setAttribute('aria-label', t('tree.expand', { name: label }));
		toggle.appendChild(el('span', 'ie-caret'));
		toggle.appendChild(el('span', 'ie-name', label));
		row.appendChild(toggle);
		row.appendChild(el('span', 'ie-meta', formatNumber(folder.count)));
		item.appendChild(row);
		makeDropTarget(row, folder, toggle);
		row.addEventListener('contextmenu', function (event) {
			var entries = [];
			// New instances are created in the tenant of the session; Morpheus' wizard gets the folder tags
			if (instanceCreate && folder.tenantId != null && folder.tenantId === instanceCreate.tenantId) {
				entries.push({ label: t('menu.newInstance'), run: function () { newInstance(folder); } });
			}
			if (folder.kind === 'folder' && folder.editable && folder.tenantId != null) {
				var siblings = sortedFolders(parent).filter(function (other) { return other !== folder; })
					.map(function (other) { return other.name; });
				entries.push({ label: t('menu.rename'), run: function () { openRenameDialog(folder, siblings); } });
			}
			if (!entries.length) {
				return;
			}
			event.preventDefault();
			showMenu(t(folder.kind === 'tenant' ? 'menu.actionsTenant' : 'menu.actionsFolder', { name: label }), entries, toggle,
				menuAnchor(event, toggle));
		});

		toggle.addEventListener('click', function () {
			var expanded = toggle.getAttribute('aria-expanded') === 'true';
			var children = item.querySelector(':scope > .ie-children');
			toggle.setAttribute('aria-expanded', String(!expanded));
			toggle.setAttribute('aria-label', t(expanded ? 'tree.expand' : 'tree.collapse', { name: label }));
			if (expanded) {
				if (children) {
					children.remove();
				}
				return;
			}
			children = el('div', 'ie-children');
			children.appendChild(folderContent(folder));
			item.appendChild(children);
		});
		return item;
	}

	// Subfolders first, then the VMs of the folder itself
	function folderContent(folder) {
		var ul = el('ul', 'ie-tree');
		sortedFolders(folder).forEach(function (sub) {
			ul.appendChild(folderItem(sub, folder));
		});
		folder.vms.forEach(function (vm) {
			ul.appendChild(draggableVm(treeItem('vm', vm), vm));
		});
		return ul;
	}

	// --- Drag and drop of VMs onto folders (instances view) -----------------------------------------------------

	var DRAG_TYPE = 'application/x-infra-explorer-vm';
	// Hovering a closed folder this long while dragging opens it
	var DRAG_EXPAND_MS = 700;
	var dragged = null;

	function draggableVm(item, vm) {
		if ((vm.actions || []).indexOf('folder') === -1) {
			return item;
		}
		item.draggable = true;
		item.addEventListener('dragstart', function (event) {
			dragged = vm;
			item.classList.add('ie-dragging');
			event.dataTransfer.effectAllowed = 'move';
			event.dataTransfer.setData(DRAG_TYPE, String(vm.id));
			closeMenu(false);
		});
		item.addEventListener('dragend', function () {
			dragged = null;
			item.classList.remove('ie-dragging');
			root.querySelectorAll('.ie-drop-target').forEach(function (row) { row.classList.remove('ie-drop-target'); });
		});
		return item;
	}

	function samePath(a, b) {
		return a.length === b.length && a.every(function (name, index) { return name === b[index]; });
	}

	// Folders and tenant nodes accept VMs of their tenant; dropping on the tenant means "no folder"
	function acceptsDrop(folder) {
		return dragged && folder.tenantId != null && dragged.tenantId === folder.tenantId &&
			!samePath(dragged.folders || [], folder.path || []);
	}

	function makeDropTarget(row, folder, toggle) {
		var expandTimer = null;
		function leave() {
			clearTimeout(expandTimer);
			expandTimer = null;
			row.classList.remove('ie-drop-target');
		}
		row.addEventListener('dragover', function (event) {
			if (!acceptsDrop(folder)) {
				return;
			}
			event.preventDefault();
			event.dataTransfer.dropEffect = 'move';
			row.classList.add('ie-drop-target');
			if (!expandTimer && toggle.getAttribute('aria-expanded') !== 'true') {
				expandTimer = setTimeout(function () { toggle.click(); }, DRAG_EXPAND_MS);
			}
		});
		row.addEventListener('dragleave', function (event) {
			if (!row.contains(event.relatedTarget)) {
				leave();
			}
		});
		row.addEventListener('drop', function (event) {
			leave();
			if (!acceptsDrop(folder)) {
				return;
			}
			event.preventDefault();
			moveVm(dragged, folder);
		});
	}

	function moveVm(vm, folder) {
		var path = folder.path || [];
		showStatus(path.length ? t('move.toFolder', { name: vm.name, path: path.join(' / ') }) :
			t('move.toNoFolder', { name: vm.name }), false);
		saveFolders(vm, path).then(function (result) {
			showStatus(folderResult(vm.name, result.vm.folders), false);
			if (result.shown) {
				revealPath(treeLabels(result.shown), result.shown.id);
			}
		}, function (failure) {
			if (revokes(failure)) {
				discardAll();
			}
			showStatus(t('move.failed', { name: vm.name, error: saveErrorText(failure) }), true);
		});
	}

	// Status after a folder change: "vm-1: folder Prod / Web."
	function folderResult(name, folders) {
		return folders && folders.length ? t('folder.resultFolder', { name: name, path: folders.join(' / ') }) :
			t('folder.resultNone', { name: name });
	}

	function loadVms() {
		var container = el('div', 'ie-view');
		container.appendChild(message(t('tree.loading')));
		var update = updater(container, function (data) {
			var vms = data.vms || [];
			if (!vms.length) {
				container.replaceChildren(message(t('empty.instances')));
				return;
			}
			folderLevels = data.levels || [];
			instanceCreate = data.create || null;
			container.replaceChildren(folderContent(buildFolderTree(vms)));
			var levels = [t('levels.tenant')].concat(data.levels || []);
			var hint = message(t('levels.list', { levels: levels.join(' / ') }), 'ie-hint');
			if (data.levelsEditable) {
				var edit = el('button', 'ie-link-button', t('levels.edit'));
				edit.type = 'button';
				edit.addEventListener('click', function () { openLevelDialog(data.levels || []); });
				hint.appendChild(document.createTextNode(' '));
				hint.appendChild(edit);
			}
			container.appendChild(hint);
		});
		withData('/vm-folders', update.render, update.fail);
		return container;
	}

	var VIEW_LOADERS = { clusters: loadClusters, vms: loadVms, storage: loadStorage, networks: loadNetworks };

	function showView(view, reload) {
		currentView = view;
		tabs.forEach(function (tab) {
			tab.setAttribute('aria-pressed', String(tab.dataset.ieView === view));
		});
		if (reload) {
			cache = {};
			saveCache();
		}
		if (reload || !loadedViews[view]) {
			loadedViews[view] = VIEW_LOADERS[view]();
		}
		body.replaceChildren(loadedViews[view]);
	}

	// --- Panel size ---
	// layout: { width, height } in pixels (null: stylesheet default) and maximized

	function clampSize(value, min, max) {
		return typeof value === 'number' && isFinite(value) ? Math.round(Math.min(Math.max(value, min), max)) : null;
	}

	function readLayout() {
		var empty = { width: null, height: null, maximized: false };
		if (!layoutKey) {
			return empty;
		}
		try {
			var stored = JSON.parse(window.localStorage.getItem(LAYOUT_KEY_PREFIX + layoutKey) || 'null');
			if (!stored || typeof stored !== 'object') {
				return empty;
			}
			return {
				width: clampSize(stored.width, MIN_WIDTH, MAX_WIDTH),
				height: clampSize(stored.height, MIN_HEIGHT, MAX_HEIGHT),
				maximized: stored.maximized === true
			};
		} catch (e) {
			return empty;
		}
	}

	var layout = readLayout();

	function saveLayout() {
		if (!layoutKey) {
			return;
		}
		try {
			if (layout.width === null && layout.height === null && !layout.maximized) {
				window.localStorage.removeItem(LAYOUT_KEY_PREFIX + layoutKey);
			} else {
				window.localStorage.setItem(LAYOUT_KEY_PREFIX + layoutKey, JSON.stringify(layout));
			}
		} catch (e) {
			// storage full or blocked: the size applies to this page only
		}
	}

	// The window caps a stored size (same margins as the stylesheet), so a smaller window never cuts off the panel
	// While maximized the stored size stays for restoring, but must not override the maximized size inline
	function applyLayout() {
		var custom = !layout.maximized;
		panel.style.width = custom && layout.width !== null ? 'min(' + layout.width + 'px, calc(100vw - 32px))' : '';
		panel.style.height = custom && layout.height !== null ? 'min(' + layout.height + 'px, calc(100vh - 100px))' : '';
		panel.classList.toggle('ie-maximized', layout.maximized);
		maximizeButton.setAttribute('aria-pressed', String(layout.maximized));
		maximizeButton.setAttribute('aria-label', t(layout.maximized ? 'panel.restoreLabel' : 'panel.maximizeLabel'));
		maximizeButton.title = t(layout.maximized ? 'panel.restore' : 'panel.maximize');
		updateGripValues();
	}

	function updateGripValues() {
		if (panel.hidden) {
			return;
		}
		var rect = panel.getBoundingClientRect();
		var values = {
			width: [MIN_WIDTH, Math.min(MAX_WIDTH, window.innerWidth - 32), rect.width],
			height: [MIN_HEIGHT, Math.min(MAX_HEIGHT, window.innerHeight - 100), rect.height]
		};
		resizeGrips.forEach(function (grip) {
			var value = values[grip.dataset.ieResize];
			if (!value) {
				return;
			}
			grip.setAttribute('aria-valuemin', String(value[0]));
			grip.setAttribute('aria-valuemax', String(Math.max(value[0], value[1])));
			grip.setAttribute('aria-valuenow', String(Math.round(value[2])));
		});
	}

	function resetLayout() {
		layout.width = null;
		layout.height = null;
		applyLayout();
		saveLayout();
	}

	// Sizes are taken from what is visible, so dragging starts where a capped panel actually ends
	function resizeTo(width, height) {
		if (width !== undefined) {
			layout.width = clampSize(Math.min(width, window.innerWidth - 32), MIN_WIDTH, MAX_WIDTH);
		}
		if (height !== undefined) {
			layout.height = clampSize(Math.min(height, window.innerHeight - 100), MIN_HEIGHT, MAX_HEIGHT);
		}
		applyLayout();
	}

	maximizeButton.addEventListener('click', function () {
		layout.maximized = !layout.maximized;
		applyLayout();
		saveLayout();
	});

	resizeGrips.forEach(function (grip) {
		var mode = grip.dataset.ieResize;
		var drag = null;

		grip.addEventListener('pointerdown', function (event) {
			if (event.button !== 0) {
				return;
			}
			event.preventDefault();
			var rect = panel.getBoundingClientRect();
			try {
				grip.setPointerCapture(event.pointerId);
			} catch (e) {
				return;
			}
			drag = { pointer: event.pointerId, x: event.clientX, y: event.clientY, width: rect.width, height: rect.height };
			panel.classList.add('ie-resizing', 'ie-resizing-' + mode);
		});

		grip.addEventListener('pointermove', function (event) {
			if (!drag || event.pointerId !== drag.pointer) {
				return;
			}
			// Anchored bottom left: moving right widens, moving up makes it taller
			resizeTo(mode === 'height' ? undefined : drag.width + event.clientX - drag.x,
				mode === 'width' ? undefined : drag.height - (event.clientY - drag.y));
		});

		function endDrag(event) {
			if (!drag || event.pointerId !== drag.pointer) {
				return;
			}
			drag = null;
			panel.classList.remove('ie-resizing', 'ie-resizing-' + mode);
			saveLayout();
		}

		grip.addEventListener('pointerup', endDrag);
		grip.addEventListener('pointercancel', endDrag);
		// Also when the panel closes during the drag (Escape) and pointerup never reaches the grip
		grip.addEventListener('lostpointercapture', endDrag);

		grip.addEventListener('dblclick', resetLayout);

		grip.addEventListener('keydown', function (event) {
			var step = { ArrowUp: RESIZE_STEP, ArrowRight: RESIZE_STEP, ArrowDown: -RESIZE_STEP, ArrowLeft: -RESIZE_STEP }[event.key];
			if (step === undefined) {
				return;
			}
			event.preventDefault();
			var rect = panel.getBoundingClientRect();
			if (mode === 'width') {
				resizeTo(rect.width + step);
			} else {
				resizeTo(undefined, rect.height + step);
			}
			saveLayout();
		});
	});

	applyLayout();
	window.addEventListener('resize', updateGripValues);

	function setOpen(open) {
		panel.hidden = !open;
		launcher.setAttribute('aria-expanded', String(open));
		launcher.setAttribute('aria-label', t(open ? 'launcher.close' : 'launcher.open'));
		if (open) {
			showView(currentView);
			updateGripValues();
			prefetchViews();
			panel.querySelector('.ie-tab[aria-pressed="true"]').focus();
		}
	}

	launcher.addEventListener('click', function () {
		setOpen(panel.hidden);
	});

	closeButton.addEventListener('click', function () {
		setOpen(false);
		launcher.focus();
	});

	reloadButton.addEventListener('click', function () {
		showView(currentView, true);
	});

	aboutButton.addEventListener('click', openAboutDialog);

	tabs.forEach(function (tab) {
		tab.addEventListener('click', function () {
			showView(tab.dataset.ieView);
		});
	});

	// On page load, and when the target page is already open (then only the hash changes and nothing reloads)
	function openFromHash() {
		openPageDialogFromHash();
		openNewInstanceFromHash();
	}

	openFromHash();
	window.addEventListener('hashchange', openFromHash);

	// Only react while focus is inside the overlay, so Escape still closes Morpheus dialogs normally.
	root.addEventListener('keydown', function (event) {
		if (event.key === 'Escape' && !panel.hidden && !event.defaultPrevented) {
			setOpen(false);
			launcher.focus();
		}
	});
})();
