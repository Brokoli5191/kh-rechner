package app.brokoli5191.khrechner.ui.calculator

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.brokoli5191.khrechner.data.EntryRepository
import app.brokoli5191.khrechner.data.OpenFoodFactsClient
import app.brokoli5191.khrechner.data.ProductLookup
import app.brokoli5191.khrechner.data.ProductRepository
import app.brokoli5191.khrechner.data.SavedEntry
import app.brokoli5191.khrechner.data.ScanResult
import app.brokoli5191.khrechner.domain.CalcField
import app.brokoli5191.khrechner.domain.CalcInput
import app.brokoli5191.khrechner.domain.CalcResult
import app.brokoli5191.khrechner.domain.CarbCalculator
import app.brokoli5191.khrechner.domain.DecimalText
import app.brokoli5191.khrechner.domain.DisplayNumber
import app.brokoli5191.khrechner.domain.EatenSource
import app.brokoli5191.khrechner.domain.Fraction
import app.brokoli5191.khrechner.domain.field
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** An editable number field: its text plus the filter that sanitises what the user types. */
class NumberFieldModel internal constructor(
    val state: TextFieldState,
    val input: InputTransformation,
)

/** The fields of the form, in screen order. All but [gramsPerPiece] are editable. */
class CalculatorFields internal constructor(
    val carbsPer100: NumberFieldModel,
    val totalWeight: NumberFieldModel,
    val pieceCount: NumberFieldModel,
    /** Display only; written by the view model, never by the user. */
    val gramsPerPiece: TextFieldState,
    val eatenPercent: NumberFieldModel,
    val eatenPieces: NumberFieldModel,
    val eatenGrams: NumberFieldModel,
)

/** Product a barcode scan filled in. [suggestedWeight] is offered once and never applied on its own. */
data class ProductInfo(val name: String, val suggestedWeight: String? = null)

sealed interface LookupStatus {
    data object Idle : LookupStatus
    data object Loading : LookupStatus
    data object NotFound : LookupStatus
    data class NoCarbs(val name: String) : LookupStatus
    data object Offline : LookupStatus
    data object ScannerUnavailable : LookupStatus
}

/** Everything needed to put the calculator back the way it was (undo, saved entries). */
data class CalculatorSnapshot(val input: CalcInput = CalcInput(), val product: ProductInfo? = null)

data class CalculatorUiState(
    val result: CalcResult = CalcResult(),
    /** Display text for values the app computed: grams per piece and the non-source "eaten" fields. */
    val derived: Map<CalcField, DisplayNumber> = emptyMap(),
    val carbsEaten: DisplayNumber? = null,
    /** The result in whole grams, as handed to AAPS. */
    val wholeGrams: Int? = null,
    val product: ProductInfo? = null,
    val lookup: LookupStatus = LookupStatus.Idle,
    val hasInput: Boolean = false,
) {
    val canSave: Boolean get() = result.carbsEaten != null
}

/**
 * Holds the text of every field. Field text lives in [TextFieldState] rather than in the
 * [StateFlow] so typing never waits for a round trip through the flow; the flow carries what is
 * computed from it.
 *
 * Text is canonical (digits and `.`); the screen swaps in the locale's decimal separator.
 */
class CalculatorViewModel(
    private val savedState: SavedStateHandle,
    private val entries: EntryRepository,
    private val products: ProductRepository,
) : ViewModel() {

    private var eatenSource: EatenSource? by mutableStateOf(
        savedState.get<String>(KEY_SOURCE)?.let { name -> EatenSource.entries.firstOrNull { it.name == name } },
    )

    // Typing over a scanned value means it no longer belongs to that product.
    private val carbsPer100: NumberFieldModel = field(savedState[KEY_CARBS], DECIMAL) { clearProduct() }
    private val totalWeight: NumberFieldModel = field(savedState[KEY_WEIGHT], DECIMAL)
    private val pieceCount: NumberFieldModel = field(savedState[KEY_PIECES], WHOLE)
    private val eatenPercent: NumberFieldModel = newEatenField(EatenSource.PERCENT)
    private val eatenPieces: NumberFieldModel = newEatenField(EatenSource.PIECES)
    private val eatenGrams: NumberFieldModel = newEatenField(EatenSource.GRAMS)

    private val gramsPerPiece = TextFieldState()

    val fields = CalculatorFields(carbsPer100, totalWeight, pieceCount, gramsPerPiece, eatenPercent, eatenPieces, eatenGrams)

    private val _uiState = MutableStateFlow(
        CalculatorUiState(
            product = savedState.get<String>(KEY_PRODUCT)?.let { ProductInfo(it, savedState[KEY_PRODUCT_WEIGHT]) },
        ),
    )
    val uiState: StateFlow<CalculatorUiState> = _uiState.asStateFlow()

    private var lookupJob: Job? = null

    init {
        viewModelScope.launch {
            snapshotFlow(::readInput).collect { recalculate(it) }
        }
    }

    private fun readInput(): CalcInput {
        val source = eatenSource
        return CalcInput(
            carbsPer100 = carbsPer100.text,
            totalWeight = totalWeight.text,
            pieceCount = pieceCount.text,
            eatenSource = source,
            eatenValue = source?.let { eatenField(it).text }.orEmpty(),
        )
    }

    private fun recalculate(input: CalcInput) {
        // The user typed again before this ran; the next emission carries the current text.
        if (input != readInput()) return
        val result = CarbCalculator.calculate(input)

        val derived = buildMap {
            val perPiece = result.gramsPerPiece?.let(DecimalText::format)
            if (perPiece != null) put(CalcField.GRAMS_PER_PIECE, perPiece)
            if (gramsPerPiece.text.toString() != perPiece?.text.orEmpty()) {
                gramsPerPiece.setTextAndPlaceCursorAtEnd(perPiece?.text.orEmpty())
            }
            for (source in EatenSource.entries) {
                // The field the user owns is never overwritten.
                if (source == result.eatenSource) continue
                val display = result.eaten(source)?.let(DecimalText::format)
                if (display != null) put(source.field, display)
                val text = display?.text.orEmpty()
                val state = eatenField(source).state
                if (state.text.toString() != text) state.setTextAndPlaceCursorAtEnd(text)
            }
        }

        _uiState.update {
            it.copy(
                result = result,
                derived = derived,
                carbsEaten = result.carbsEaten?.let(DecimalText::format),
                wholeGrams = result.carbsEaten?.let(DecimalText::roundToWhole),
                hasInput = !input.isBlank,
            )
        }
        savedState[KEY_CARBS] = input.carbsPer100
        savedState[KEY_WEIGHT] = input.totalWeight
        savedState[KEY_PIECES] = input.pieceCount
        savedState[KEY_SOURCE] = input.eatenSource?.name
        savedState[KEY_EATEN] = input.eatenValue
    }

    // --- saving, resetting, loading ---------------------------------------------------------

    /** Saves in the view model's scope so leaving the screen cannot interrupt the write. */
    fun save(name: String): Job {
        val input = readInput()
        val productName = _uiState.value.product?.name
        return viewModelScope.launch { entries.save(name, input, productName) }
    }

    /** Clears everything and returns what was there, for the undo action. */
    fun reset(): CalculatorSnapshot {
        val previous = CalculatorSnapshot(readInput(), _uiState.value.product)
        restore(CalculatorSnapshot())
        return previous
    }

    fun load(entry: SavedEntry) {
        restore(CalculatorSnapshot(entry.input, entry.productName?.let { ProductInfo(it) }))
    }

    fun restore(snapshot: CalculatorSnapshot) {
        lookupJob?.cancel()
        val input = snapshot.input
        carbsPer100.state.setTextAndPlaceCursorAtEnd(input.carbsPer100)
        totalWeight.state.setTextAndPlaceCursorAtEnd(input.totalWeight)
        pieceCount.state.setTextAndPlaceCursorAtEnd(input.pieceCount)
        eatenSource = input.eatenSource
        for (source in EatenSource.entries) {
            eatenField(source).state.setTextAndPlaceCursorAtEnd(if (source == input.eatenSource) input.eatenValue else "")
        }
        setProduct(snapshot.product)
        _uiState.update { it.copy(lookup = LookupStatus.Idle) }
    }

    // --- barcode lookup ---------------------------------------------------------------------

    fun onScanResult(result: ScanResult) {
        when (result) {
            ScanResult.Cancelled -> Unit
            ScanResult.Unavailable -> _uiState.update { it.copy(lookup = LookupStatus.ScannerUnavailable) }
            is ScanResult.Code -> lookUp(result.barcode)
        }
    }

    private fun lookUp(barcode: String) {
        lookupJob?.cancel()
        if (!OpenFoodFactsClient.BARCODE.matches(barcode)) {
            _uiState.update { it.copy(lookup = LookupStatus.NotFound) }
            return
        }
        _uiState.update { it.copy(lookup = LookupStatus.Loading) }
        lookupJob = viewModelScope.launch {
            val status = when (val lookup = products.lookup(barcode)) {
                is ProductLookup.Found -> {
                    val product = lookup.product
                    carbsPer100.state.setTextAndPlaceCursorAtEnd(product.carbsPer100)
                    setProduct(
                        ProductInfo(
                            name = product.name.ifBlank { barcode },
                            // Nothing to offer if the field already holds that weight.
                            suggestedWeight = product.quantityGrams?.takeIf { it != DecimalText.normalize(totalWeight.text) },
                        ),
                    )
                    LookupStatus.Idle
                }
                is ProductLookup.NoCarbs -> LookupStatus.NoCarbs(lookup.name.ifBlank { barcode })
                ProductLookup.NotFound -> LookupStatus.NotFound
                ProductLookup.Offline -> LookupStatus.Offline
            }
            _uiState.update { it.copy(lookup = status) }
        }
    }

    /** The user accepted the package weight the scanned product suggested. */
    fun applySuggestedWeight() {
        val product = _uiState.value.product ?: return
        val weight = product.suggestedWeight ?: return
        totalWeight.state.setTextAndPlaceCursorAtEnd(weight)
        setProduct(product.copy(suggestedWeight = null))
    }

    fun clearProduct() {
        setProduct(null)
        _uiState.update { state -> if (state.lookup == LookupStatus.Loading) state else state.copy(lookup = LookupStatus.Idle) }
    }

    private fun setProduct(product: ProductInfo?) {
        _uiState.update { it.copy(product = product) }
        savedState[KEY_PRODUCT] = product?.name
        savedState[KEY_PRODUCT_WEIGHT] = product?.suggestedWeight
    }

    // --- field plumbing ---------------------------------------------------------------------

    private fun eatenField(source: EatenSource): NumberFieldModel = when (source) {
        EatenSource.PERCENT -> eatenPercent
        EatenSource.PIECES -> eatenPieces
        EatenSource.GRAMS -> eatenGrams
    }

    private fun newEatenField(source: EatenSource): NumberFieldModel = field(
        initial = if (eatenSource == source) savedState[KEY_EATEN] else null,
        pattern = DECIMAL,
        // Editing one of the three linked fields makes it the source of truth for the other two.
        onUserEdit = { eatenSource = source },
    )

    private fun field(initial: String?, pattern: Regex, onUserEdit: () -> Unit = {}): NumberFieldModel =
        NumberFieldModel(TextFieldState(initial.orEmpty()), NumberInput(pattern, onUserEdit))

    private val NumberFieldModel.text: String get() = state.text.toString()

    private fun CalcResult.eaten(source: EatenSource): Fraction? = when (source) {
        EatenSource.PERCENT -> eatenPercent
        EatenSource.PIECES -> eatenPieces
        EatenSource.GRAMS -> eatenGrams
    }

    /**
     * Runs only for edits made by the user (keyboard, paste), not for text the view model sets.
     * Turns `,` into `.`, refuses anything that is not a short non-negative number, and reports
     * real changes.
     */
    private class NumberInput(
        private val pattern: Regex,
        private val onUserEdit: () -> Unit,
    ) : InputTransformation {
        override fun TextFieldBuffer.transformInput() {
            var comma = asCharSequence().indexOf(',')
            while (comma >= 0) {
                replace(comma, comma + 1, ".")
                comma = asCharSequence().indexOf(',')
            }
            if (!pattern.matches(asCharSequence())) {
                revertAllChanges()
                return
            }
            if (asCharSequence().toString() != originalText.toString()) onUserEdit()
        }
    }

    private companion object {
        /** Up to 99999.99; enough for any food and short enough for the field. */
        val DECIMAL = Regex("""\d{0,5}(\.\d{0,2})?""")
        val WHOLE = Regex("""\d{0,4}""")

        const val KEY_CARBS = "carbs"
        const val KEY_WEIGHT = "weight"
        const val KEY_PIECES = "pieces"
        const val KEY_SOURCE = "source"
        const val KEY_EATEN = "eaten"
        const val KEY_PRODUCT = "product"
        const val KEY_PRODUCT_WEIGHT = "productWeight"
    }
}
