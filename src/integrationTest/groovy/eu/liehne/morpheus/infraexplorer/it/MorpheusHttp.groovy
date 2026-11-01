package eu.liehne.morpheus.infraexplorer.it

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration

/** Result of an HTTP call: status, content type, body and redirect target. */
class HttpResult {
	int status
	String contentType
	String body
	String location

	boolean isJson() {
		contentType?.contains('json')
	}

	Object json() {
		if (!isJson()) {
			throw new IllegalStateException("expected JSON, got HTTP $status ${contentType ?: ''}: ${preview()}")
		}
		new JsonSlurper().parseText(body ?: 'null')
	}

	String preview() {
		(body ?: '').take(300).replaceAll(/\s+/, ' ')
	}

	@Override
	String toString() {
		"HTTP $status${location ? " -> $location" : ''} ${contentType ?: ''}"
	}
}

/** Thrown for API errors; the message never contains credentials. */
class MorpheusApiException extends RuntimeException {
	final int status

	MorpheusApiException(String message, int status) {
		super(message)
		this.status = status
	}
}

/**
 * Minimal HTTP access to Morpheus with java.net.http: the REST API with an API token ({@link MorpheusApi}) and the
 * UI with a session cookie ({@link UiSession}, needed for plugin routes, which do not accept API tokens).
 */
abstract class MorpheusHttp {

	protected final TestEnvironment env
	protected final HttpClient client

	protected MorpheusHttp(TestEnvironment env, CookieManager cookies) {
		this.env = env
		HttpClient.Builder builder = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(20))
			.followRedirects(HttpClient.Redirect.NEVER)
			.sslContext(sslContext(env))
		if (cookies) {
			builder.cookieHandler(cookies)
		}
		client = builder.build()
	}

	protected HttpResult send(HttpRequest.Builder request) {
		HttpResponse<String> response = client.send(request.timeout(Duration.ofSeconds(120)).build(),
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
		new HttpResult(status: response.statusCode(),
			contentType: response.headers().firstValue('Content-Type').orElse(null),
			body: response.body(),
			location: response.headers().firstValue('Location').orElse(null))
	}

	protected URI uri(String path) {
		URI.create(env.url + path)
	}

	private static SSLContext sslContext(TestEnvironment env) {
		SSLContext context = SSLContext.getInstance('TLS')
		if (env.insecure) {
			TrustManager trustAll = new X509TrustManager() {
				void checkClientTrusted(X509Certificate[] chain, String authType) {}
				void checkServerTrusted(X509Certificate[] chain, String authType) {}
				X509Certificate[] getAcceptedIssuers() { new X509Certificate[0] }
			}
			context.init(null, [trustAll] as TrustManager[], null)
		} else if (env.caCert) {
			KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType())
			store.load(null, null)
			new File(env.caCert).withInputStream { input ->
				CertificateFactory.getInstance('X.509').generateCertificates(input).eachWithIndex { cert, i ->
					store.setCertificateEntry("ca-$i", cert)
				}
			}
			TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
			factory.init(store)
			context.init(null, factory.trustManagers, null)
		} else {
			return SSLContext.getDefault()
		}
		context
	}
}

/** REST API (/api/…) with the API token. */
class MorpheusApi extends MorpheusHttp {

	MorpheusApi(TestEnvironment env) {
		super(env, null)
	}

	HttpResult call(String method, String path, Object body = null) {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
			.header('Authorization', "Bearer ${env.token}")
			.header('Accept', 'application/json')
		if (body == null) {
			request.method(method, HttpRequest.BodyPublishers.noBody())
		} else {
			request.header('Content-Type', 'application/json')
				.method(method, HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(body), StandardCharsets.UTF_8))
		}
		send(request)
	}

	Map get(String path) {
		checked('GET', path, call('GET', path))
	}

	Map post(String path, Object body) {
		checked('POST', path, call('POST', path, body))
	}

	Map put(String path, Object body) {
		checked('PUT', path, call('PUT', path, body))
	}

	Map delete(String path) {
		checked('DELETE', path, call('DELETE', path))
	}

	/** Multipart upload of a file in the form field {@code file}. */
	Map upload(String path, File file) {
		String boundary = "----infra-explorer-it-${UUID.randomUUID()}"
		ByteArrayOutputStream bytes = new ByteArrayOutputStream()
		bytes.write(("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n" +
			"Content-Type: application/java-archive\r\n\r\n").getBytes(StandardCharsets.UTF_8))
		bytes.write(file.bytes)
		bytes.write("\r\n--$boundary--\r\n".getBytes(StandardCharsets.UTF_8))
		HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
			.header('Authorization', "Bearer ${env.token}")
			.header('Accept', 'application/json')
			.header('Content-Type', "multipart/form-data; boundary=$boundary")
			.POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray()))
		checked('POST', path, send(request))
	}

	private static Map checked(String method, String path, HttpResult result) {
		Object json = result.isJson() ? result.json() : null
		if (result.status >= 300 || !(json instanceof Map)) {
			String message = json instanceof Map ? (json.msg ?: json.message ?: json.error ?: json.errors) : result.preview()
			throw new MorpheusApiException("$method $path: $result ${message ?: ''}".trim(), result.status)
		}
		if (json.success == false) {
			throw new MorpheusApiException("$method $path: success=false ${json.msg ?: json.errors ?: ''}".trim(), result.status)
		}
		json as Map
	}
}

/** UI session of a user (cookie), for plugin routes and pages. */
class UiSession extends MorpheusHttp {

	UiSession(TestEnvironment env) {
		super(env, new CookieManager(null, CookiePolicy.ACCEPT_ALL))
	}

	/** Logs in with the login form; fails if Morpheus sends the user back to the login page. */
	UiSession login(String username, String password) {
		HttpResult page = get('/login/auth')
		String csrf = csrfToken(page.body)
		if (!csrf) {
			throw new IllegalStateException("login page without CSRF token ($page)")
		}
		String form = [username: username, password: password, _csrf: csrf].collect { k, v ->
			URLEncoder.encode(k, 'UTF-8') + '=' + URLEncoder.encode(v, 'UTF-8')
		}.join('&')
		HttpResult result = send(HttpRequest.newBuilder(uri('/login/process'))
			.header('Content-Type', 'application/x-www-form-urlencoded')
			.POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8)))
		if (result.status != 302 || result.location == null || result.location.contains('/login')) {
			throw new IllegalStateException("UI login of $username failed ($result)")
		}
		this
	}

	HttpResult get(String path, Map<String, String> headers = [:]) {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET()
		headers.each { k, v -> request.header(k, v) }
		send(request)
	}

	/** GET of a plugin route the way the overlay calls it. */
	HttpResult route(String path) {
		get(path, [Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest'])
	}

	static String csrfToken(String html) {
		def input = html =~ /<input[^>]*name="_csrf"[^>]*>/
		if (input.find()) {
			def value = input.group() =~ /value="([^"]+)"/
			if (value.find()) {
				return value.group(1)
			}
		}
		null
	}
}
