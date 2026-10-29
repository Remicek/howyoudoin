package com.devvalor.howyoudoing.app.ondevice

import com.devvalor.howyoudoing.core.domain.ondevice.FoundPerson
import kotlin.test.Test
import kotlin.test.assertEquals

class ParsePeopleTest {

    @Test
    fun readsTheNetworkSearchResults() {
        val json = """[{"id":"did:plc:a","name":"Ann","handle":"@ann.bsky.social","avatarUrl":"https://cdn/a.jpg"},""" +
            """{"id":"42","name":"","handle":""}]"""
        assertEquals(
            listOf(
                FoundPerson("did:plc:a", "Ann", "@ann.bsky.social", "https://cdn/a.jpg"),
                FoundPerson("42", "", null, null),
            ),
            parsePeople(json)
        )
    }

    @Test
    fun skipsWhatIsntAPerson() {
        assertEquals(emptyList(), parsePeople("""[{"name":"no id"}, 3]"""))
        assertEquals(emptyList(), parsePeople("not json"))
    }
}
