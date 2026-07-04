package com.aashishgodambe.arcana.core.data.importer

import android.content.Context
import android.net.Uri
import androidx.annotation.VisibleForTesting
import com.aashishgodambe.arcana.core.data.database.entity.CollectibleCategory
import com.aashishgodambe.arcana.core.data.importer.model.FunkoImportMetadata
import com.aashishgodambe.arcana.core.data.importer.model.ImportResult
import com.aashishgodambe.arcana.core.data.importer.model.ImportedItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord
import java.io.BufferedInputStream
import java.io.InputStream
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Parses a HobbyDB CSV export. Handles the real-world quirks of the format:
 * - URL columns wrapped in Excel `=HYPERLINK("...")` syntax
 * - `Series` and `Production Status` as comma-separated multi-values
 * - `UPC` and `Reference Number` kept as String (leading zeros matter)
 * - `Exclusive To == "NFT Redeemable"` treated as a flag, not a retailer
 * - ragged dates ("2021", "2021/8", "2021-06-26")
 * Lenient: a row that can't be parsed is skipped with a warning; the rest succeed.
 *
 * The core parse lives in the companion so it is unit-testable with any [InputStream] — no Context.
 */
class HobbyDbCsvImporter @Inject constructor(
    @ApplicationContext private val context: Context,
) : CollectionImporter {

    override val sourceName = SOURCE_NAME
    override val supportedMimeTypes = MIME_TYPES

    override suspend fun parse(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            ?: return@withContext ImportResult.Failed(
                IllegalStateException("Cannot open $uri"), "Couldn't open the selected file.",
            )
        stream.use { parse(it) }
    }

    companion object {
        const val SOURCE_NAME = "HobbyDB"
        val MIME_TYPES = setOf("text/csv", "application/csv", "text/comma-separated-values")

        private val HYPERLINK = Regex("=HYPERLINK\\(\"([^\"]*)\"", RegexOption.IGNORE_CASE)

        /** Core parse, decoupled from Android so it is unit-testable with any [InputStream]. */
        @VisibleForTesting
        fun parse(input: InputStream): ImportResult {
            val warnings = mutableListOf<String>()
            val items = mutableListOf<ImportedItem>()
            var skipped = 0
            return try {
                val reader = stripBom(BufferedInputStream(input)).reader(Charsets.UTF_8)
                val format = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setTrim(true)
                    .build()
                format.parse(reader).use { parser ->
                    for (record in parser) {
                        try {
                            val item = record.toImportedItem()
                            if (item == null) {
                                skipped++
                                warnings += "Row ${record.recordNumber} skipped: missing required Name."
                            } else {
                                items += item
                            }
                        } catch (e: Exception) {
                            skipped++
                            warnings += "Row ${record.recordNumber} skipped: ${e.message}"
                        }
                    }
                }
                ImportResult.Success(items = items, itemsParsed = items.size, itemsSkipped = skipped, warnings = warnings)
            } catch (e: Exception) {
                ImportResult.Failed(e, "Couldn't parse the CSV: ${e.message}")
            }
        }

        private fun CSVRecord.toImportedItem(): ImportedItem? {
            val name = col("Name")
            if (name.isBlank()) return null

            val funko = FunkoImportMetadata(
                upc = col("UPC").ifBlank { null },
                popNumber = col("Reference Number").ifBlank { null },
                exclusiveTo = col("Exclusive To").ifBlank { null },
                isNftRedeemable = col("Exclusive To").equals("NFT Redeemable", ignoreCase = true),
                scale = col("Scale").ifBlank { null },
                releaseDate = parseLooseDate(col("Release Date")),
                hdbcNumber = col("HDBC Number").ifBlank { null },
            )
            return ImportedItem(
                sourceId = col("HDBID"),
                sourceName = SOURCE_NAME,
                listName = col("List Name").ifBlank { null },
                category = CollectibleCategory.Funko,
                name = name,
                brand = col("Brand"),
                quantity = col("Quantity").toIntOrNull() ?: 1,
                estimatedValueCents = parseCents(col("Estimated Value")),
                itemCondition = col("Item Condition").ifBlank { null },
                packagingCondition = col("Packaging Condition").ifBlank { null },
                dateAdded = parseLooseDate(col("Date Added To Collectible")),
                imageUrl = unwrapHyperlink(col("Image URL")).ifBlank { null },
                series = splitMulti(col("Series")),
                productionTags = splitMulti(col("Production Status")),
                funkoMetadata = funko,
                pricePaidCents = parseCents(col("Price Paid")),
                acquiredFrom = col("Acquired From").ifBlank { null },
                datePurchased = parseLooseDate(col("Date Purchased")),
                storageLocation = col("Storage Location").ifBlank { null },
                privateNotes = col("Private Notes").ifBlank { null },
            )
        }

        private fun CSVRecord.col(name: String): String =
            if (isMapped(name) && isSet(name)) get(name).trim() else ""

        @VisibleForTesting
        fun unwrapHyperlink(raw: String): String {
            val v = raw.trim()
            return HYPERLINK.find(v)?.groupValues?.get(1) ?: v
        }

        @VisibleForTesting
        fun splitMulti(raw: String): List<String> =
            raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        @VisibleForTesting
        fun parseCents(raw: String): Int? {
            val v = raw.trim().removePrefix("$").replace(",", "")
            if (v.isBlank()) return null
            return v.toDoubleOrNull()?.let { (it * 100).roundToInt() }
        }

        /**
         * HobbyDB dates are ragged: "2021-06-26" (ISO), "2021/8/3", "2021/10" (no day),
         * "2021" (year only). Best-effort; missing month/day default to 1; null if hopeless.
         */
        @VisibleForTesting
        fun parseLooseDate(raw: String): LocalDate? {
            val v = raw.trim()
            if (v.isBlank()) return null
            runCatching { return LocalDate.parse(v) }
            val parts = v.split('/', '-').mapNotNull { it.trim().toIntOrNull() }
            val year = parts.getOrNull(0)?.takeIf { it in 1..9999 } ?: return null
            val month = parts.getOrNull(1)?.coerceIn(1, 12) ?: 1
            val day = parts.getOrNull(2) ?: 1
            return runCatching { LocalDate.of(year, month, day) }.getOrNull()
                ?: runCatching { LocalDate.of(year, month, 1) }.getOrNull()
        }

        private fun stripBom(input: BufferedInputStream): BufferedInputStream {
            input.mark(3)
            val bom = ByteArray(3)
            val read = input.read(bom, 0, 3)
            val isBom = read == 3 &&
                bom[0] == 0xEF.toByte() && bom[1] == 0xBB.toByte() && bom[2] == 0xBF.toByte()
            if (!isBom) input.reset()
            return input
        }
    }
}
