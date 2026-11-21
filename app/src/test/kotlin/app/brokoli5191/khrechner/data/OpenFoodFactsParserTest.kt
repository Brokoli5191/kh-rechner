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

    // --- pieces ------------------------------------------------------------------------------

    private fun pieces(fields: String): ProductPieces? = (
        OpenFoodFactsParser.parse("""{"status":1,"product":{"nutriments":{"carbohydrates_100g":10},$fields}}""") as RemoteProduct.Found
        ).pieces

    @Test
    fun `a real biscuit package yields weight and pieces from the serving size`() {
        // GET /api/v2/product/4017100291005.json as answered in November 2026, cut down to the fields the app asks for.
        val body = """
            {"code":"4017100291005","status":1,"status_verbose":"product found",
             "product":{"product_name":"CHOCO LEIBNIZ EDELHERB","quantity":"125 gram","product_quantity":125,"product_quantity_unit":"g",
                        "serving_size":"13.9 g","serving_quantity":13.9,"serving_quantity_unit":"g",
                        "nutriments":{"carbohydrates":58,"carbohydrates_100g":58,"carbohydrates_serving":8.06,"carbohydrates_unit":"g"}}}
        """.trimIndent()
        assertEquals(
            RemoteProduct.Found("CHOCO LEIBNIZ EDELHERB", "58", "125", ProductPieces(9, estimated = true)),
            OpenFoodFactsParser.parse(body),
        )
    }

    @Test
    fun `a label that counts the pieces is taken at its word`() {
        assertEquals(ProductPieces(6, estimated = false), pieces(""""quantity":"6 x 25 g","product_quantity":150,"product_quantity_unit":"g""""))
        assertEquals(ProductPieces(4, estimated = false), pieces(""""quantity":"4×125g""""))
        // The serving says something else; the label wins.
        assertEquals(
            ProductPieces(10, estimated = false),
            pieces(""""quantity":"10 x 20 g","product_quantity":200,"product_quantity_unit":"g","serving_size":"40 g","serving_quantity":40,"serving_quantity_unit":"g""""),
        )
    }

    @Test
    fun `a serving rounded on the label still gives a whole number of pieces`() {
        // 125 g / 14 g is 8.93: nine biscuits, with the serving rounded up from 13.9 g.
        assertEquals(
            ProductPieces(9, estimated = true),
            pieces(""""product_quantity":125,"product_quantity_unit":"g","serving_size":"14g","serving_quantity":14,"serving_quantity_unit":"g""""),
        )
        assertEquals(
            ProductPieces(48, estimated = true),
            pieces(""""product_quantity":400,"product_quantity_unit":"g","serving_quantity":8.3,"serving_quantity_unit":"g""""),
        )
    }

    @Test
    fun `a serving described as several pieces is split up`() {
        fun serving(text: String, grams: String) =
            pieces(""""product_quantity":252,"product_quantity_unit":"g","serving_size":"$text","serving_quantity":$grams,"serving_quantity_unit":"g"""")
        assertEquals(ProductPieces(18, estimated = true), serving("2 Kekse (28 g)", "28"))
        assertEquals(ProductPieces(18, estimated = true), serving("28 g (2 biscuits)", "28"))
        assertEquals(ProductPieces(18, estimated = true), serving("ca. 2 Stück (28g)", "28"))
        assertEquals(ProductPieces(18, estimated = true), serving("1 Keks (14 g)", "14"))
        // A decimal count is not guessed at; the serving then counts as one piece.
        assertEquals(ProductPieces(9, estimated = true), serving("2.5 biscuits (28 g)", "28"))
    }

    @Test
    fun `the serving text is enough where the numbers are missing`() {
        assertEquals(ProductPieces(9, estimated = true), pieces(""""quantity":"125 g","serving_size":"1 Keks (13,9 g)""""))
        assertEquals(ProductPieces(5, estimated = true), pieces(""""quantity":"1 kg","serving_size":"200 g""""))
    }

    @Test
    fun `no pieces where a serving is not a piece`() {
        fun serving(total: String, grams: String) =
            pieces(""""product_quantity":$total,"product_quantity_unit":"g","serving_size":"$grams g","serving_quantity":$grams,"serving_quantity_unit":"g"""")
        // 125 g in servings of 28 g is 4.46 of something.
        assertNull(serving("125", "28"))
        assertNull(serving("400", "15"))
        assertNull(serving("750", "40"))
        // The whole package is one serving: nothing to count.
        assertNull(serving("150", "150"))
        assertNull(serving("100", "250"))
    }

    @Test
    fun `no pieces without a weight to divide`() {
        assertNull(pieces(""""serving_size":"14 g","serving_quantity":14,"serving_quantity_unit":"g""""))
        assertNull(pieces(""""product_quantity":125,"product_quantity_unit":"g""""))
        assertNull(pieces(""""product_quantity":125,"product_quantity_unit":"g","serving_size":"1 Keks""""))
        // Drinks are measured in millilitres, on either side.
        assertNull(pieces(""""product_quantity":1000,"product_quantity_unit":"ml","serving_size":"250 ml","serving_quantity":250,"serving_quantity_unit":"ml""""))
        assertNull(pieces(""""product_quantity":500,"product_quantity_unit":"g","serving_size":"100 ml","serving_quantity":100,"serving_quantity_unit":"ml""""))
        assertNull(pieces(""""product_quantity":125,"product_quantity_unit":"g","serving_quantity":0,"serving_quantity_unit":"g""""))
    }
}
