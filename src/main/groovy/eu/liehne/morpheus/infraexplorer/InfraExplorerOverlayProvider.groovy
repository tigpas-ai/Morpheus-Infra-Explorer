package eu.liehne.morpheus.infraexplorer

import com.morpheusdata.core.AbstractGlobalUIComponentProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.ContentSecurityPolicy
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Renders the launcher button (bottom left) and the empty overlay container on every Morpheus page.
 * The template only contains static markup plus an opaque cache key; tree data is loaded by the browser script.
 */
class InfraExplorerOverlayProvider extends AbstractGlobalUIComponentProvider {

	public static final String PROVIDER_CODE = 'infra-explorer.overlay'
	public static final String PROVIDER_NAME = 'Infra Explorer Overlay'

	protected MorpheusContext context
	protected Plugin plugin
	private String assetVersion

	static final List<String> ASSETS = ['assets/infra-explorer-messages.js', 'assets/infra-explorer.js', 'assets/infra-explorer.css']

	InfraExplorerOverlayProvider(Plugin plugin, MorpheusContext context) {
		this.@plugin = plugin
		this.@context = context
	}

	/**
	 * Only signed-in users get the overlay. Role-based checks follow once the permission codes are settled
	 * (see REQUIREMENTS.md, "Open points" 2).
	 */
	@Override
	Boolean show(User user, Account account) {
		return user != null && account != null
	}

	@Override
	HTMLResponse renderTemplate(User user, Account account) {
		// No user data goes into the page, only a key that keeps the browser cache of one user apart from the next.
		ViewModel<Object> model = new ViewModel<>()
		model.object = [cacheKey: cacheKey(user, account), layoutKey: layoutKey(user), assetVersion: assetVersion(),
						version: plugin?.version ?: '', author: InfraExplorerPlugin.AUTHOR, license: InfraExplorerPlugin.LICENSE]
		getRenderer().renderTemplate('hbs/infraExplorerOverlay', model)
	}

	/**
	 * Checksum of script and stylesheet. Morpheus serves plugin assets under a fixed name with a cache lifetime of a
	 * year, so the URL has to change with the content, also when a version is deployed again.
	 */
	String assetVersion() {
		if (assetVersion == null) {
			MessageDigest digest = MessageDigest.getInstance('SHA-256')
			ASSETS.each { String path ->
				InputStream stream = getClass().classLoader.getResourceAsStream(path)
				if (stream != null) {
					stream.withCloseable { digest.update(it.bytes) }
				}
			}
			assetVersion = digest.digest().encodeHex().toString().take(12)
		}
		return assetVersion
	}

	/**
	 * Opaque, stable per user, tenant and permission set (changed roles start with an empty browser cache); not
	 * reversible to names. Empty without user, so the browser stores nothing.
	 */
	static String cacheKey(User user, Account account) {
		if (user?.id == null) {
			return ''
		}
		String permissions = (user.permissions ?: [:]).collect { k, v -> "${k}=${v}" }.sort().join(',')
		String source = "infra-explorer:${user.id}:${user.username}:${account?.id}:${permissions}"
		MessageDigest.getInstance('SHA-256').digest(source.getBytes(StandardCharsets.UTF_8)).encodeHex().toString().take(16)
	}

	/**
	 * Opaque and stable per user only (not per tenant or permission set), so the panel size the user chose survives
	 * role changes. Empty without user, so the browser stores nothing.
	 */
	static String layoutKey(User user) {
		if (user?.id == null) {
			return ''
		}
		String source = "infra-explorer-layout:${user.id}:${user.username}"
		MessageDigest.getInstance('SHA-256').digest(source.getBytes(StandardCharsets.UTF_8)).encodeHex().toString().take(16)
	}

	/**
	 * Scripts and styles are served from the Morpheus host itself; no external sources needed.
	 */
	@Override
	ContentSecurityPolicy getContentSecurityPolicy() {
		return null
	}

	@Override
	String getCode() {
		return PROVIDER_CODE
	}

	@Override
	String getName() {
		return PROVIDER_NAME
	}

	@Override
	MorpheusContext getMorpheus() {
		return this.@context
	}

	@Override
	Plugin getPlugin() {
		return this.@plugin
	}
}
