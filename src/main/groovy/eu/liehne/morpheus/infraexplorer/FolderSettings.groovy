package eu.liehne.morpheus.infraexplorer

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.OptionType
import eu.liehne.morpheus.infraexplorer.source.InfraNode
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

/**
 * Folder levels of the VM view. Each level is a tag on the VM; the tag names come from the plugin setting
 * {@code folderTags} (comma-separated). A VM's folder path ends at the first level without a value.
 */
@Slf4j
class FolderSettings {

	static final String FIELD_NAME = 'folderTags'
	static final List<String> DEFAULT_TAGS = ['Folder-1', 'Folder-2', 'Folder-3'].asImmutable()
	static final int MAX_LEVELS = 10

	private final Plugin plugin
	private final MorpheusContext morpheus

	FolderSettings(Plugin plugin, MorpheusContext morpheus) {
		this.plugin = plugin
		this.morpheus = morpheus
	}

	/** Setting shown under Administration → Integrations → Plugins */
	static OptionType optionType() {
		new OptionType(name: 'Folder tags', code: 'infra-explorer.folderTags', fieldName: FIELD_NAME,
			fieldLabel: 'Folder tags per level', displayOrder: 0, required: false,
			inputType: OptionType.InputType.TEXT, defaultValue: DEFAULT_TAGS.join(','),
			helpText: 'Tag names of the folder levels in the instances view, comma-separated, outermost level first')
	}

	/** Configured tag names; the defaults if the setting is missing, empty or unreadable */
	List<String> tagNames() {
		try {
			String json = morpheus.getSettings(plugin)?.blockingGet()
			return parse(json)
		} catch (Exception e) {
			log.warn("Infra Explorer: could not read plugin settings, using default folder tags", e)
			return DEFAULT_TAGS
		}
	}

	static List<String> parse(String settingsJson) {
		if (!settingsJson?.trim()) {
			return DEFAULT_TAGS
		}
		def settings = new JsonSlurper().parseText(settingsJson)
		def value = settings instanceof Map ? settings[FIELD_NAME] : null
		List<String> names = (value instanceof Collection ? value : value?.toString()?.tokenize(','))
			?.collect { it?.toString()?.trim() }
			?.findAll { it }
			?.unique()
		return names ? names.take(MAX_LEVELS) : DEFAULT_TAGS
	}

	/** Longest folder name; tag values in Morpheus are free text, the limit keeps the tree readable */
	static final int MAX_NAME_LENGTH = 100

	/**
	 * Checks a folder path given by the user: trimmed names, no empty level, at most one name per configured level,
	 * no control or invisible formatting characters (e.g. zero-width or direction marks, which would make equal
	 * looking but different folders). An empty path means "no folder".
	 * @throws IllegalArgumentException with a message that can be shown to the user
	 */
	static List<String> cleanPath(List<String> raw, List<String> tagNames) {
		List<String> path = (raw ?: []).collect { it == null ? '' : it.trim() }
		while (path && path.last().isEmpty()) {
			path.remove(path.size() - 1)
		}
		if (path.size() > tagNames.size()) {
			throw new IllegalArgumentException("at most ${tagNames.size()} folder levels".toString())
		}
		if (path.any { it.isEmpty() }) {
			throw new IllegalArgumentException('folder names must not be empty while a deeper level follows')
		}
		if (path.any { it.length() > MAX_NAME_LENGTH }) {
			throw new IllegalArgumentException("folder names have at most ${MAX_NAME_LENGTH} characters".toString())
		}
		if (path.any { it.chars.any { char c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT } }) {
			throw new IllegalArgumentException('folder names must not contain control or invisible characters')
		}
		return path
	}

	/**
	 * Checks a level (tag) name given by the user: trimmed, 1–100 characters, no comma (the setting is a comma
	 * separated list), no control or invisible formatting characters.
	 * @throws IllegalArgumentException with a message that can be shown to the user
	 */
	static String cleanLevelName(String raw) {
		String name = raw?.trim() ?: ''
		if (!name) {
			throw new IllegalArgumentException('level names must not be empty')
		}
		if (name.length() > MAX_NAME_LENGTH) {
			throw new IllegalArgumentException("level names have at most ${MAX_NAME_LENGTH} characters".toString())
		}
		if (name.contains(',')) {
			throw new IllegalArgumentException('level names must not contain a comma')
		}
		if (name.chars.any { char c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT }) {
			throw new IllegalArgumentException('level names must not contain control or invisible characters')
		}
		return name
	}

	/** Folder path of a node: values of the level tags in order, up to the first missing one */
	static List<String> folderPath(InfraNode node, List<String> tagNames) {
		List<String> path = []
		for (String name : tagNames) {
			String value = node.tags?.get(name)?.trim()
			if (!value) {
				break
			}
			path << value
		}
		return path
	}
}
