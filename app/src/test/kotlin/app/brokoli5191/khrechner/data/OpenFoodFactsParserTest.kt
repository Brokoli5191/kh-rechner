package app.brokoli5191.khrechner.data

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenFoodFactsParserTest {

    @Test
    fun `a real answer yields name, carbs and package weight`() {
        // Shape of GET /api/v2/product/3017624010701.json?fields=... as answered in November 2026.
        val body = """
            {"code":"3017624010701","status":1,"status_verbose":"product found",
             "product":{"product_name":"Nutella","product_quantity":400,"product_quantity_unit":"g","quantity":"400.0 g",
                        "nutriments":{"carbohydrates":57.5,"carbohydrates_100g":57.5,"carbohydrates_unit":"g"}}}
        """.trimIndent()
        assertEquals(RemoteProduct.Found("Nutella", "57.5", "400"), OpenFoodFactsParser.parse(body))
    }

    @Test
    fun `an unknown barcode is not found`() {
        assertEquals(
            RemoteProduct.NotFound,
            OpenFoodFactsParser.parse("""{"code":"00000017","status":0,"status_verbose":"no code or invalid code"}"""),
        )
        assertEquals(RemoteProduct.NotFound, OpenFoodFactsParser.parse("""{"status":1}"""))
    }

    @Test
    fun `a product without a usable carb value says so and keeps its name`() {
        fun product(nutriments: String) = """{"status":1,"product":{"product_name":" Wasser ","nutriments":$nutriments}}"""
        assertEquals(RemoteProduct.NoCarbs("Wasser"), OpenFoodFactsParser.parse(product("{}")))
        assertEquals(RemoteProduct.NoCarbs("Wasser"), OpenFoodFactsParser.parse(product("""{"carbohydrates_100g":null}""")))
        assertEquals(RemoteProduct.NoCarbs("Wasser"), OpenFoodFactsParser.parse(product("""{"carbohydrates_100g":"n/a"}""")))
        assertEquals(RemoteProduct.NoCarbs("Wasser"), OpenFoodFactsParser.parse(product("""{"carbohydrates_100g":-1}""")))
        assertEquals(RemoteProduct.NoCarbs("Wasser"), OpenFoodFactsParser.parse(product("""{"carbohydrates_100g":250}""")))
        assertEquals(RemoteProduct.NoCarbs(""), OpenFoodFactsParser.parse("""{"status":1,"product":{}}"""))
    }

    @Test
    fun `numbers stored as text and float noise are cleaned up`() {
        fun carbs(value: String) = (
            OpenFoodFactsParser.parse("""{"status":"1","product":{"nutriments":{"carbohydrates_100g":$value}}}""") as RemoteProduct.Found
            ).carbsPer100
        assertEquals("12.5", carbs("\"12.5\""))
        assertEquals("12.3", carbs("12.300000000000001"))
        assertEquals("0", carbs("0"))
        assertEquals("100", carbs("100.0"))
        assertEquals("7.13", carbs("7.126"))
    }

    @Test
    fun `package weight is only offered for weights`() {
        fun quantity(fields: String) = (
            OpenFoodFactsParser.parse("""{"status":1,"product":{"nutriments":{"carbohydrates_100g":10},$fields}}""") as RemoteProduct.Found
            ).quantityGrams
        assertEquals("250", quantity(""""product_quantity":"250","product_quantity_unit":"g""""))
        assertEquals("1500", quantity(""""product_quantity":1.5,"product_quantity_unit":"kg""""))
        assertNull(quantity(""""product_quantity":330,"product_quantity_unit":"ml","quantity":"330 ml""""))
        assertNull(quantity(""""product_quantity":0,"product_quantity_unit":"g""""))
        // No pre-parsed unit: read the label text instead.
        assertEquals("125", quantity(""""quantity":"125 g""""))
        assertEquals("150", quantity(""""quantity":"6 x 25 g""""))
        assertNull(quantity(""""quantity":"1 l""""))
        assertNull(quantity(""""quantity":"""""))
    }

    @Test
    fun `label text is parsed for grams`() {
        fun grams(text: String?) = OpenFoodFactsParser.parseGrams(text)?.stripTrailingZeros()?.toPlainString()
        assertEquals("400", grams("400 g"))
        assertEquals("400", grams("400.0 g"))
        assertEquals("500", grams("500g e"))
        assertEquals("1500", grams("1,5 kg"))
        assertEquals("750", grams("3 × 250 G"))
        assertEquals("200", grams("Netto: 200 Gramm"))
        assertNull(grams("330 ml"))
        assertNull(grams("500 mg"))
        assertNull(grams("12 Stück"))
        assertNull(grams("0 g"))
        assertNull(grams(null))
        assertEquals(BigDecimal("2000"), OpenFoodFactsParser.parseGrams("2 kg")?.setScale(0))
    }

    @Test
    fun `anything unreadable counts as unavailable`() {
        assertEquals(RemoteProduct.Unavailable, OpenFoodFactsParser.parse("<html>502 bad gateway</html>"))
        assertEquals(RemoteProduct.Unavailable, OpenFoodFactsParser.parse(""))
        assertEquals(RemoteProduct.Unavailable, OpenFoodFactsParser.parse("[]"))
    }
}
