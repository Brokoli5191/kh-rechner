package app.brokoli5191.khrechner.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryCatalogTest {

    private val sample = """
        {
          "libraries": [
            {"uniqueId": "androidx.compose.ui:ui", "artifactVersion": "1.13.0", "name": "Compose UI",
             "website": "https://developer.android.com/jetpack/androidx/releases/compose-ui#1.13.0", "licenses": ["Apache-2.0"]},
            {"uniqueId": "androidx.compose.ui:ui-text", "artifactVersion": "1.13.0", "name": "Compose UI Text", "licenses": ["Apache-2.0"]},
            {"uniqueId": "androidx.compose:compose-bom", "artifactVersion": "2026.11.01", "name": "Jetpack Compose Libraries BOM", "licenses": ["Apache-2.0"]},
            {"uniqueId": "org.jetbrains.kotlin:kotlin-stdlib", "artifactVersion": "2.4.21", "name": "Kotlin Stdlib",
             "website": "https://kotlinlang.org/", "licenses": ["Apache-2.0"], "somethingNew": 1},
            {"uniqueId": "com.google.mlkit:common", "artifactVersion": "18.9.0", "name": "common", "licenses": ["abc123"]},
            {"uniqueId": "com.google.mlkit:vision-common", "artifactVersion": "17.0.0", "name": "vision-common", "licenses": ["abc123"]},
            {"uniqueId": "com.example:orphan", "name": "", "website": "ftp://example.com", "licenses": ["missing"]}
          ],
          "licenses": {
            "Apache-2.0": {"name": "The Apache License, Version 2.0", "url": "http://www.apache.org/licenses/LICENSE-2.0.txt", "spdxId": "Apache-2.0"},
            "abc123": {"name": "ML Kit Terms of Service", "url": "https://developers.google.com/ml-kit/terms"}
          }
        }
    """.trimIndent()

    @Test
    fun `artifacts are folded into one entry per project, sorted by name`() {
        assertEquals(
            listOf(
                // Several artifacts: named after the group, version shown only because they agree.
                LibraryGroup(
                    name = "androidx.compose.ui", version = "1.13.0", moduleCount = 2, licences = listOf("Apache-2.0"),
                    url = "https://developer.android.com/jetpack/androidx/releases/compose-ui#1.13.0",
                ),
                // No usable name, licence or web link of its own.
                LibraryGroup(name = "com.example", version = null, moduleCount = 1, licences = emptyList(), url = null),
                // Versions differ, no website: no version, and the licence text is the link.
                LibraryGroup(
                    name = "com.google.mlkit", version = null, moduleCount = 2, licences = listOf("ML Kit Terms of Service"),
                    url = "https://developers.google.com/ml-kit/terms",
                ),
                LibraryGroup(
                    name = "Kotlin Stdlib", version = "2.4.21", moduleCount = 1, licences = listOf("Apache-2.0"),
                    url = "https://kotlinlang.org/",
                ),
            ),
            LibraryCatalog.parse(sample),
        )
    }

    @Test
    fun `an empty catalogue is an empty list`() {
        assertEquals(emptyList<LibraryGroup>(), LibraryCatalog.parse("{}"))
    }
}
