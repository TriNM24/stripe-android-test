package com.stripe.android.paymentsheet

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.stripe.android.link.LinkPaymentLauncher
import com.stripe.android.link.model.AccountStatus
import com.stripe.android.link.ui.inline.InlineSignupViewState
import com.stripe.android.link.ui.inline.LinkInlineSignup
import com.stripe.android.model.CardBrand
import com.stripe.android.model.PaymentMethod
import com.stripe.android.paymentsheet.forms.FormFieldValues
import com.stripe.android.paymentsheet.forms.FormViewModel.Companion.getElements
import com.stripe.android.paymentsheet.model.PaymentSelection
import com.stripe.android.paymentsheet.model.getPMAddForm
import com.stripe.android.paymentsheet.paymentdatacollection.FormFragmentArguments
import com.stripe.android.paymentsheet.ui.Loading
import com.stripe.android.paymentsheet.ui.PaymentMethodForm
import com.stripe.android.paymentsheet.ui.PrimaryButton
import com.stripe.android.paymentsheet.viewmodels.BaseSheetViewModel
import com.stripe.android.ui.core.FieldValuesToParamsMapConverter
import com.stripe.android.ui.core.PaymentsTheme
import com.stripe.android.ui.core.elements.FormElement
import com.stripe.android.ui.core.elements.IdentifierSpec
import com.stripe.android.ui.core.forms.resources.LpmRepository
import kotlinx.coroutines.flow.MutableStateFlow

internal abstract class BaseAddPaymentMethodFragment : Fragment() {
    abstract val viewModelFactory: ViewModelProvider.Factory
    abstract val sheetViewModel: BaseSheetViewModel<*>

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ) = ComposeView(requireContext()).apply {
        val elementsFlow = MutableStateFlow<List<FormElement>?>(null)
        val showCheckboxFlow = MutableStateFlow(false)

        setContent {
            val isRepositoryReady by sheetViewModel.isResourceRepositoryReady.observeAsState(false)
            val processing by sheetViewModel.processing.observeAsState(false)

            val linkConfig by sheetViewModel.linkConfiguration.observeAsState()
            val linkAccountStatus by linkConfig?.let {
                sheetViewModel.linkLauncher.getAccountStatusFlow(it).collectAsState(null)
            } ?: mutableStateOf(null)

            if (isRepositoryReady == true) {
                var selectedPaymentMethodCode: String by rememberSaveable {
                    mutableStateOf(
                        sheetViewModel.newPaymentSelection?.paymentMethodCreateParams?.typeCode
                            ?: sheetViewModel.supportedPaymentMethods.first().code
                    )
                }
                val selectedItem = remember(selectedPaymentMethodCode) {
                    sheetViewModel.supportedPaymentMethods.first {
                        it.code == selectedPaymentMethodCode
                    }
                }

                val layoutFormDescriptor = selectedItem.getPMAddForm(
                    requireNotNull(sheetViewModel.stripeIntent.value),
                    sheetViewModel.config
                )

                val showLinkInlineSignup = sheetViewModel.isLinkEnabled.value == true &&
                    sheetViewModel.stripeIntent.value
                    ?.linkFundingSources?.contains(PaymentMethod.Type.Card.code) == true &&
                    selectedItem.code == PaymentMethod.Type.Card.code &&
                    linkAccountStatus == AccountStatus.SignedOut

                val arguments = remember(selectedItem, showLinkInlineSignup) {
                    FormFragmentArguments(
                        paymentMethodCode = selectedPaymentMethodCode,
                        showCheckbox = layoutFormDescriptor.showCheckbox && !showLinkInlineSignup,
                        showCheckboxControlledFields = sheetViewModel.newPaymentSelection?.let {
                            sheetViewModel.newPaymentSelection?.customerRequestedSave ==
                                PaymentSelection.CustomerRequestedSave.RequestReuse
                        } ?: layoutFormDescriptor.showCheckboxControlledFields,
                        merchantName = sheetViewModel.merchantName,
                        amount = sheetViewModel.amount.value,
                        billingDetails = sheetViewModel.config?.defaultBillingDetails,
                        shippingDetails = sheetViewModel.config?.shippingDetails,
                        injectorKey = sheetViewModel.injectorKey,
                        initialPaymentMethodCreateParams =
                        sheetViewModel.newPaymentSelection?.paymentMethodCreateParams?.typeCode
                            ?.takeIf {
                                it == selectedItem.code
                            }?.let {
                                when (val selection = sheetViewModel.newPaymentSelection) {
                                    is PaymentSelection.New.GenericPaymentMethod ->
                                        selection.paymentMethodCreateParams
                                    is PaymentSelection.New.Card ->
                                        selection.paymentMethodCreateParams
                                    else -> null
                                }
                            }
                    )
                }

                LaunchedEffect(arguments) {
                    elementsFlow.emit(
                        getElements(
                            requireContext(),
                            arguments,
                            sheetViewModel.lpmResourceRepository,
                            sheetViewModel.addressResourceRepository
                        )
                    )
                    showCheckboxFlow.emit(
                        arguments.showCheckbox
                    )
                }

                PaymentsTheme {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (sheetViewModel.supportedPaymentMethods.size > 1) {
                            PaymentMethodsUI(
                                selectedIndex = sheetViewModel.supportedPaymentMethods.indexOf(
                                    selectedItem
                                ),
                                isEnabled = !processing,
                                paymentMethods = sheetViewModel.supportedPaymentMethods,
                                onItemSelectedListener = { selectedLpm ->
                                    if (selectedItem != selectedLpm) {
                                        sheetViewModel.updatePrimaryButtonUIState(null)
                                        selectedPaymentMethodCode = selectedLpm.code
                                    }
                                },
                                modifier = Modifier.padding(top = 18.dp, bottom = 6.dp)
                            )
                        }

                        if (selectedItem.code == PaymentMethod.Type.USBankAccount.code) {
                            Text("Bank Account form goes here")
                        } else {
                            PaymentMethodForm(
                                args = arguments,
                                enabled = !processing,
                                onFormFieldValuesChanged = { formValues ->
                                    sheetViewModel.updateSelection(
                                        transformToPaymentSelection(
                                            formValues,
                                            selectedItem
                                        )
                                    )
                                },
                                elementsFlow = elementsFlow,
                                showCheckboxFlow = showCheckboxFlow,
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )
                        }

                        if (showLinkInlineSignup) {
                            LinkInlineSignup(
                                linkPaymentLauncher = sheetViewModel.linkLauncher,
                                enabled = !processing,
                                onStateChanged = ::onLinkSignupStateChanged,
                                modifier = Modifier
                                    .padding(horizontal = 20.dp, vertical = 6.dp)
                                    .fillMaxWidth()
                            )
                        }
                    }
                }
            } else {
                Loading()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sheetViewModel.headerText.value =
            getString(R.string.stripe_paymentsheet_add_payment_method_title)

        sheetViewModel.eventReporter.onShowNewPaymentOptionForm(
            linkEnabled = sheetViewModel.isLinkEnabled.value ?: false,
            activeLinkSession = sheetViewModel.activeLinkSession.value ?: false
        )
    }

    private fun onLinkSignupStateChanged(
        config: LinkPaymentLauncher.Configuration,
        viewState: InlineSignupViewState
    ) {
        sheetViewModel.updatePrimaryButtonUIState(
            if (viewState.useLink) {
                val userInput = viewState.userInput
                if (userInput != null &&
                    sheetViewModel.selection.value != null
                ) {
                    PrimaryButton.UIState(
                        label = null,
                        onClick = {
                            sheetViewModel.payWithLinkInline(
                                config,
                                userInput
                            )
                        },
                        enabled = true,
                        visible = true
                    )
                } else {
                    PrimaryButton.UIState(
                        label = null,
                        onClick = null,
                        enabled = false,
                        visible = true
                    )
                }
            } else {
                null
            }
        )
    }

    @VisibleForTesting
    internal fun transformToPaymentSelection(
        formFieldValues: FormFieldValues?,
        selectedPaymentMethodResources: LpmRepository.SupportedPaymentMethod
    ) = formFieldValues?.let {
        FieldValuesToParamsMapConverter.transformToPaymentMethodCreateParams(
            formFieldValues.fieldValuePairs
                .filterNot { entry ->
                    entry.key == IdentifierSpec.SaveForFutureUse ||
                        entry.key == IdentifierSpec.CardBrand
                },
            selectedPaymentMethodResources.code,
            selectedPaymentMethodResources.requiresMandate
        ).run {
            if (selectedPaymentMethodResources.code == PaymentMethod.Type.Card.code) {
                PaymentSelection.New.Card(
                    paymentMethodCreateParams = this,
                    brand = CardBrand.fromCode(
                        formFieldValues.fieldValuePairs[IdentifierSpec.CardBrand]?.value
                    ),
                    customerRequestedSave = formFieldValues.userRequestedReuse

                )
            } else {
                PaymentSelection.New.GenericPaymentMethod(
                    getString(selectedPaymentMethodResources.displayNameResource),
                    selectedPaymentMethodResources.iconResource,
                    this,
                    customerRequestedSave = formFieldValues.userRequestedReuse
                )
            }
        }
    }
}
