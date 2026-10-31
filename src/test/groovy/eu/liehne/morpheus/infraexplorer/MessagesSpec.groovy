package eu.liehne.morpheus.infraexplorer

import spock.lang.Shared
import spock.lang.Specification

/**
 * Checks the overlay texts (infra-explorer-messages.js): every language has the keys and placeholders of English,
 * and every key the script and the template use exists.
 */
class MessagesSpec extends Specification {

	@Shared Map<String, Map<String, String>> languages = parse(new File('src/assets/javascripts/infra-explorer-messages.js').text)

	def "English and at least German are present"() {
		expect:
		languages.keySet().containsAll(['en', 'de'])
		languages.en.size() > 100
	}

	def "every language has exactly the keys and placeholders of English"() {
		expect:
		languages.each { String lang, Map<String, String> texts ->
			Set<String> base = languages.en.keySet().collect { it.replaceAll(/\.(one|other|zero|two|few|many)$/, '') } as Set
			assert texts.keySet().collect { it.replaceAll(/\.(one|other|zero|two|few|many)$/, '') } as Set == base: lang
			texts.each { String key, String text ->
				String english = languages.en[key] ?: languages.en[key.replaceAll(/\.\w+$/, '.other')]
				assert placeholders(text) == placeholders(english): "${lang} ${key}"
			}
		}
	}

	def "every key used by the script and the template exists in English"() {
		given:
		String script = new File('src/assets/javascripts/infra-explorer.js').text
		String template = new File('src/main/resources/renderer/hbs/infraExplorerOverlay.hbs').text
		Set<String> used = ((script =~ /'([a-z]+\.[a-zA-Z]+)'/).collect { it[1] } +
			(template =~ /data-ie-i18n(?:-label|-title)?="([a-zA-Z.]+)"/).collect { it[1] }) as Set
		Set<String> known = languages.en.keySet().collect { it.replaceAll(/\.(one|other)$/, '') } as Set

		expect:
		used - known == [] as Set
	}

	private static Set<String> placeholders(String text) {
		(text =~ /\{(\w+)\}/).collect { it[1] } as Set
	}

	/** Language blocks ("xx: {" … "}") with their 'key': 'text' lines */
	private static Map<String, Map<String, String>> parse(String source) {
		Map<String, Map<String, String>> result = [:]
		String current = null
		source.eachLine { String line ->
			def block = line =~ /^\t(['"]?)([a-z-]+)\1: \{$/
			def entry = line =~ /^\t\t'([^']+)': '((?:[^'\\]|\\.)*)',?$/
			if (block.matches()) {
				current = block[0][2]
				result[current] = [:]
			} else if (current && entry.matches()) {
				result[current][entry[0][1]] = entry[0][2]
			}
		}
		return result
	}
}
