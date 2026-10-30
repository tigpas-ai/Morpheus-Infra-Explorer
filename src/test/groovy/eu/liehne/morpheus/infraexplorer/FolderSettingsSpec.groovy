package eu.liehne.morpheus.infraexplorer

import com.morpheusdata.core.MorpheusContext
import eu.liehne.morpheus.infraexplorer.source.InfraNode
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

class FolderSettingsSpec extends Specification {

	def "parses the comma-separated tag names of the setting"() {
		expect:
		FolderSettings.parse(json) == expected

		where:
		json                                        || expected
		'{"folderTags":"Level-1, Level-2"}'         || ['Level-1', 'Level-2']
		'{"folderTags":" A ,,B, A "}'               || ['A', 'B']
		'{"folderTags":["X","Y"]}'                  || ['X', 'Y']
		'{"folderTags":""}'                         || FolderSettings.DEFAULT_TAGS
		'{"other":"value"}'                         || FolderSettings.DEFAULT_TAGS
		'{}'                                        || FolderSettings.DEFAULT_TAGS
		''                                          || FolderSettings.DEFAULT_TAGS
		null                                        || FolderSettings.DEFAULT_TAGS
		'[]'                                        || FolderSettings.DEFAULT_TAGS
	}

	def "limits the number of levels"() {
		given:
		String names = (1..15).collect { "L$it" }.join(',')

		expect:
		FolderSettings.parse("{\"folderTags\":\"$names\"}").size() == FolderSettings.MAX_LEVELS
	}

	def "reads the setting from Morpheus and falls back to the defaults on errors"() {
		given:
		MorpheusContext morpheus = Stub {
			getSettings(_) >>> [Single.just('{"folderTags":"Team"}'), Single.error(new IllegalStateException('down'))]
		}
		FolderSettings settings = new FolderSettings(new InfraExplorerPlugin(), morpheus)

		expect:
		settings.tagNames() == ['Team']
		settings.tagNames() == FolderSettings.DEFAULT_TAGS
	}

	def "builds the folder path up to the first level without value"() {
		expect:
		FolderSettings.folderPath(new InfraNode(tags: tags), ['Folder-1', 'Folder-2', 'Folder-3']) == expected

		where:
		tags                                                  || expected
		['Folder-1': 'Prod', 'Folder-2': 'Web']               || ['Prod', 'Web']
		['Folder-1': ' Prod ', 'Folder-2': '', 'Folder-3': 'X'] || ['Prod']
		['Folder-2': 'Web']                                   || []
		['folder-1': 'Prod']                                  || []
		[:]                                                   || []
		null                                                  || []
	}

	def "cleans a folder path given by the user"() {
		expect:
		FolderSettings.cleanPath(raw, ['A', 'B', 'C']) == expected

		where:
		raw                   || expected
		[' Prod ', 'Web']     || ['Prod', 'Web']
		['Prod', '', ' ']     || ['Prod']
		[]                    || []
		null                  || []
		['', '']              || []
	}

	def "rejects invalid folder paths"() {
		when:
		FolderSettings.cleanPath(raw, ['A', 'B'])

		then:
		thrown(IllegalArgumentException)

		where:
		raw << [['a', 'b', 'c'], ['', 'b'], ['x' * 101], ["a\nb"], ["a\u200Bb"], ["\u202Eab"]]
	}

	def "cleans level names"() {
		expect:
		FolderSettings.cleanLevelName(' Location ') == 'Location'

		when:
		FolderSettings.cleanLevelName(raw)

		then:
		thrown(IllegalArgumentException)

		where:
		raw << [null, ' ', 'a,b', 'x' * 101, "a\u200Bb"]
	}

	def "offers the setting as text field with the defaults"() {
		expect:
		with(FolderSettings.optionType()) {
			fieldName == 'folderTags'
			defaultValue == 'Folder-1,Folder-2,Folder-3'
		}
	}
}
