package cy.txtracker.export

import com.google.common.truth.Truth.assertThat
import cy.txtracker.domain.TimeBucket
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class BackupSerializationTest {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    private val sample = Backup(
        version = 1,
        exportedAt = Instant.parse("2026-05-09T12:30:00Z"),
        categories = listOf(
            BackupCategory("Food", color = 0xFFEF5350.toInt(), sortOrder = 0, isCustom = false),
            BackupCategory("Pets", color = 0x80FFFFFF.toInt(), sortOrder = 10, isCustom = true),
        ),
        merchantMappings = listOf(
            BackupMerchantMapping("MCDONALDS", "Food", Instant.parse("2026-04-01T08:00:00Z")),
        ),
        merchantDescriptionMappings = listOf(
            BackupMerchantDescriptionMapping(
                merchant = "MCDONALDS",
                bucket = TimeBucket.MIDDAY,
                description = "lunch",
                learnedAt = Instant.parse("2026-04-15T05:00:00Z"),
            ),
        ),
        categoryDescriptionMappings = listOf(
            BackupCategoryDescriptionMapping(
                categoryName = "Food",
                bucket = TimeBucket.MIDDAY,
                description = "lunch",
                learnedAt = Instant.parse("2026-04-15T05:00:00Z"),
            ),
        ),
    )

    @Test
    fun roundtrip_preserves_all_fields() {
        val text = json.encodeToString(sample)
        val parsed = json.decodeFromString<Backup>(text)
        assertThat(parsed).isEqualTo(sample)
    }

    @Test
    fun instant_serializes_as_iso8601() {
        val text = json.encodeToString(sample)
        // Spot-check a few key fields.
        assertThat(text).contains("\"exportedAt\": \"2026-05-09T12:30:00Z\"")
        assertThat(text).contains("\"learnedAt\": \"2026-04-01T08:00:00Z\"")
    }

    @Test
    fun timebucket_serializes_as_enum_name() {
        val text = json.encodeToString(sample)
        assertThat(text).contains("\"bucket\": \"MIDDAY\"")
    }

    @Test
    fun version_field_is_present_so_future_format_changes_can_branch() {
        val text = json.encodeToString(sample)
        assertThat(text).contains("\"version\": 1")
    }

    @Test
    fun extra_fields_in_input_are_ignored_for_forward_compatibility() {
        // A backup written by a future version with new fields should still parse so the
        // user can at least restore the parts we recognize.
        val futuristic = """
            {
              "version": 1,
              "exportedAt": "2026-05-09T12:30:00Z",
              "categories": [],
              "merchantMappings": [],
              "merchantDescriptionMappings": [],
              "categoryDescriptionMappings": [],
              "futureField": "ignored",
              "anotherFuture": [1, 2, 3]
            }
        """.trimIndent()
        val parsed = json.decodeFromString<Backup>(futuristic)
        assertThat(parsed.version).isEqualTo(1)
        assertThat(parsed.categories).isEmpty()
    }

    @Test
    fun userFacingSources_roundtrip() {
        val original = Backup(
            exportedAt = Instant.parse("2026-05-11T00:00:00Z"),
            categories = emptyList(),
            merchantMappings = emptyList(),
            merchantDescriptionMappings = emptyList(),
            categoryDescriptionMappings = emptyList(),
            userFacingSources = listOf(
                BackupUserFacingSource("com.example.app",
                    Instant.parse("2026-05-10T10:00:00Z")),
            ),
        )

        val json = BackupExporter.JSON.encodeToString(Backup.serializer(), original)
        val parsed = BackupExporter.JSON.decodeFromString(Backup.serializer(), json)

        assertThat(parsed.userFacingSources).hasSize(1)
        assertThat(parsed.userFacingSources.single().packageName).isEqualTo("com.example.app")
        assertThat(parsed.version).isEqualTo(4)
    }

    @Test
    fun v1_backup_parses_with_empty_userFacingSources() {
        // A serialized v1 file (no userFacingSources field) must still parse.
        val v1Json = """
            {
              "version": 1,
              "exportedAt": "2026-05-01T00:00:00Z",
              "categories": [],
              "merchantMappings": [],
              "merchantDescriptionMappings": [],
              "categoryDescriptionMappings": []
            }
        """.trimIndent()

        val parsed = BackupExporter.JSON.decodeFromString(Backup.serializer(), v1Json)

        assertThat(parsed.userFacingSources).isEmpty()
    }

    @Test
    fun approvedSources_roundtrip() {
        val original = Backup(
            exportedAt = Instant.parse("2026-05-11T00:00:00Z"),
            categories = emptyList(),
            merchantMappings = emptyList(),
            merchantDescriptionMappings = emptyList(),
            categoryDescriptionMappings = emptyList(),
            approvedSources = listOf(
                BackupApprovedSource("com.cimb.cimbocto",
                    Instant.parse("2026-05-10T10:00:00Z")),
            ),
        )

        val json = BackupExporter.JSON.encodeToString(Backup.serializer(), original)
        val parsed = BackupExporter.JSON.decodeFromString(Backup.serializer(), json)

        assertThat(parsed.approvedSources).hasSize(1)
        assertThat(parsed.approvedSources.single().packageName).isEqualTo("com.cimb.cimbocto")
        assertThat(parsed.version).isEqualTo(4)
    }

    @Test
    fun v2_backup_parses_with_empty_approvedSources() {
        // A serialized v2 file (no approvedSources field) must still parse cleanly.
        val v2Json = """
            {
              "version": 2,
              "exportedAt": "2026-05-01T00:00:00Z",
              "categories": [],
              "merchantMappings": [],
              "merchantDescriptionMappings": [],
              "categoryDescriptionMappings": [],
              "userFacingSources": []
            }
        """.trimIndent()

        val parsed = BackupExporter.JSON.decodeFromString(Backup.serializer(), v2Json)

        assertThat(parsed.approvedSources).isEmpty()
    }

    @Test
    fun merchantNotes_roundtrip() {
        val original = Backup(
            exportedAt = Instant.parse("2026-05-11T00:00:00Z"),
            categories = emptyList(),
            merchantMappings = emptyList(),
            merchantDescriptionMappings = emptyList(),
            categoryDescriptionMappings = emptyList(),
            merchantNotes = listOf(
                BackupMerchantNote(
                    merchant = "WARUNG UNCLE",
                    note = "SS15 kopitiam, only takes cash",
                    updatedAt = Instant.parse("2026-05-10T10:00:00Z"),
                ),
            ),
        )

        val json = BackupExporter.JSON.encodeToString(Backup.serializer(), original)
        val parsed = BackupExporter.JSON.decodeFromString(Backup.serializer(), json)

        assertThat(parsed.merchantNotes).hasSize(1)
        val note = parsed.merchantNotes.single()
        assertThat(note.merchant).isEqualTo("WARUNG UNCLE")
        assertThat(note.note).isEqualTo("SS15 kopitiam, only takes cash")
        assertThat(note.updatedAt).isEqualTo(Instant.parse("2026-05-10T10:00:00Z"))
        assertThat(parsed.version).isEqualTo(4)
    }

    @Test
    fun v3_backup_parses_with_empty_merchantNotes() {
        // A serialized v3 file (no merchantNotes field) must still parse cleanly.
        val v3Json = """
            {
              "version": 3,
              "exportedAt": "2026-05-01T00:00:00Z",
              "categories": [],
              "merchantMappings": [],
              "merchantDescriptionMappings": [],
              "categoryDescriptionMappings": [],
              "userFacingSources": [],
              "approvedSources": []
            }
        """.trimIndent()

        val parsed = BackupExporter.JSON.decodeFromString(Backup.serializer(), v3Json)

        assertThat(parsed.merchantNotes).isEmpty()
    }
}
