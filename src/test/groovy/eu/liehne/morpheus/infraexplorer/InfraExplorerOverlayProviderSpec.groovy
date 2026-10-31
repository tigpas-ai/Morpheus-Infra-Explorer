package eu.liehne.morpheus.infraexplorer

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.web.MorpheusWebRequestService
import com.morpheusdata.model.Account
import com.morpheusdata.model.User
import spock.lang.Specification
import spock.lang.Subject

class InfraExplorerOverlayProviderSpec extends Specification {

	MorpheusWebRequestService webRequest = Stub {
		getNonceToken() >> 'test-nonce'
	}
	MorpheusContext morpheus = Stub {
		getWebRequest() >> webRequest
	}
	InfraExplorerPlugin plugin = new InfraExplorerPlugin()

	@Subject
	InfraExplorerOverlayProvider provider

	def setup() {
		plugin.setName('Infra Explorer')
		plugin.setClassLoader(getClass().classLoader)
		provider = new InfraExplorerOverlayProvider(plugin, morpheus)
	}

	def "shows the overlay only for a signed-in user with an account"() {
		expect:
		provider.show(user, account) == expected

		where:
		user       | account       || expected
		new User() | new Account() || true
		null       | new Account() || false
		new User() | null          || false
	}

	def "renders launcher and closed, empty panel without user data"() {
		given:
		User user = new User(username: 'secret-user', email: 'test@example.invalid')

		when:
		String html = provider.renderTemplate(user, new Account()).html

		then:
		html.contains('id="ie-launcher"')
		html =~ /id="ie-panel"[^>]*hidden/
		html =~ /<div class="ie-body"[^>]*><\/div>/
		!html.contains('secret-')
	}

	def "renders an opaque cache key that differs per user"() {
		given:
		User a = new User(id: 1L, username: 'secret-a')
		User b = new User(id: 2L, username: 'secret-b')
		Account account = new Account(id: 1L)

		when:
		String html = provider.renderTemplate(a, account).html

		then:
		html.contains('data-ie-cache-key="' + InfraExplorerOverlayProvider.cacheKey(a, account) + '"')
		InfraExplorerOverlayProvider.cacheKey(a, account) ==~ /[0-9a-f]{16}/
		InfraExplorerOverlayProvider.cacheKey(a, account) == InfraExplorerOverlayProvider.cacheKey(a, account)
		InfraExplorerOverlayProvider.cacheKey(a, account) != InfraExplorerOverlayProvider.cacheKey(b, account)
		!html.contains('secret-')
	}

	def "changes the cache key with the permissions and has none without user"() {
		given:
		Account account = new Account(id: 1L)
		User reader = new User(id: 1L, username: 'secret-a', permissions: ['infrastructure-cluster': 'read'])
		User none = new User(id: 1L, username: 'secret-a', permissions: ['infrastructure-cluster': 'none'])

		expect:
		InfraExplorerOverlayProvider.cacheKey(reader, account) != InfraExplorerOverlayProvider.cacheKey(none, account)
		InfraExplorerOverlayProvider.cacheKey(null, account) == ''
		InfraExplorerOverlayProvider.cacheKey(new User(), account) == ''
	}

	def "renders an opaque layout key per user that ignores permissions"() {
		given:
		User a = new User(id: 1L, username: 'secret-a', permissions: ['infrastructure-cluster': 'read'])
		User aOtherRole = new User(id: 1L, username: 'secret-a', permissions: ['infrastructure-cluster': 'full'])
		User b = new User(id: 2L, username: 'secret-b')

		when:
		String html = provider.renderTemplate(a, new Account(id: 1L)).html

		then:
		html.contains('data-ie-layout-key="' + InfraExplorerOverlayProvider.layoutKey(a) + '"')
		InfraExplorerOverlayProvider.layoutKey(a) ==~ /[0-9a-f]{16}/
		InfraExplorerOverlayProvider.layoutKey(a) == InfraExplorerOverlayProvider.layoutKey(aOtherRole)
		InfraExplorerOverlayProvider.layoutKey(a) != InfraExplorerOverlayProvider.layoutKey(b)
		InfraExplorerOverlayProvider.layoutKey(a) != InfraExplorerOverlayProvider.cacheKey(a, new Account(id: 1L))
		InfraExplorerOverlayProvider.layoutKey(null) == ''
		InfraExplorerOverlayProvider.layoutKey(new User()) == ''
		!html.contains('secret-')
	}

	def "loads texts, script and stylesheet from the plugin asset path with the page nonce, texts first"() {
		when:
		String html = provider.renderTemplate(new User(), new Account()).html

		then:
		html.contains('<script src="/assets/plugin/infra-explorer/infra-explorer.js?v=' + provider.assetVersion() + '" nonce="test-nonce">')
		html.indexOf('/infra-explorer-messages.js?v=' + provider.assetVersion() + '" nonce="test-nonce">') != -1
		html.indexOf('/infra-explorer-messages.js') < html.indexOf('/infra-explorer.js')
		html.contains('href="/assets/plugin/infra-explorer/infra-explorer.css?v=' + provider.assetVersion() + '"')
		provider.assetVersion() ==~ /[0-9a-f]{12}/
	}

	def "renders version, author and license for the about dialog"() {
		given:
		plugin.setVersion('1.2.3')

		when:
		String html = provider.renderTemplate(new User(), new Account()).html

		then:
		html.contains('data-ie-version="1.2.3"')
		html.contains('data-ie-author="Pascal Liehne"')
		html.contains('data-ie-license="MIT"')
	}

	def "needs no additional content security policy"() {
		expect:
		provider.contentSecurityPolicy == null
	}
}
