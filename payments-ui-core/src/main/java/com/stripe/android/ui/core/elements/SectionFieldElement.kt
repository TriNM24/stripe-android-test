package com.stripe.android.ui.core.elements

import androidx.annotation.RestrictTo
import com.stripe.android.ui.core.forms.FormFieldEntry
import kotlinx.coroutines.flow.Flow

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
sealed interface SectionFieldElement {
    val identifier: IdentifierSpec
    val shouldRenderOutsideCard: Boolean
        get() = false

    fun getFormFieldValueFlow(): Flow<List<Pair<IdentifierSpec, FormFieldEntry>>>
    fun sectionFieldErrorController(): SectionFieldErrorController
    fun setRawValue(rawValuesMap: Map<IdentifierSpec, String?>)
    fun getTextFieldIdentifiers(): Flow<List<IdentifierSpec>>
}
