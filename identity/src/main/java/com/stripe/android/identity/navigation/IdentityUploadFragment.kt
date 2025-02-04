package com.stripe.android.identity.navigation

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavArgument
import androidx.navigation.fragment.findNavController
import com.stripe.android.identity.R
import com.stripe.android.identity.databinding.IdentityUploadFragmentBinding
import com.stripe.android.identity.networking.models.ClearDataParam
import com.stripe.android.identity.networking.models.CollectedDataParam
import com.stripe.android.identity.networking.models.DocumentUploadParam
import com.stripe.android.identity.networking.models.VerificationPage
import com.stripe.android.identity.networking.models.VerificationPage.Companion.requireSelfie
import com.stripe.android.identity.networking.models.VerificationPageData.Companion.isMissingBack
import com.stripe.android.identity.networking.models.VerificationPageStaticContentDocumentCapturePage
import com.stripe.android.identity.states.IdentityScanState
import com.stripe.android.identity.utils.ARG_IS_NAVIGATED_UP_TO
import com.stripe.android.identity.utils.ARG_SHOULD_SHOW_CHOOSE_PHOTO
import com.stripe.android.identity.utils.ARG_SHOULD_SHOW_TAKE_PHOTO
import com.stripe.android.identity.utils.IdentityIO
import com.stripe.android.identity.utils.fragmentIdToScreenName
import com.stripe.android.identity.utils.isNavigatedUpTo
import com.stripe.android.identity.utils.navigateToDefaultErrorFragment
import com.stripe.android.identity.utils.navigateToSelfieOrSubmit
import com.stripe.android.identity.utils.postVerificationPageData
import com.stripe.android.identity.viewmodel.IdentityUploadViewModel
import com.stripe.android.identity.viewmodel.IdentityViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Fragment to upload front and back of a document.
 *
 */
internal abstract class IdentityUploadFragment(
    identityIO: IdentityIO,
    private val identityViewModelFactory: ViewModelProvider.Factory
) : Fragment() {

    @get:StringRes
    abstract val titleRes: Int

    @get:StringRes
    abstract val contextRes: Int

    @get:StringRes
    abstract val frontTextRes: Int

    @get:StringRes
    open var backTextRes: Int? = null

    @get:StringRes
    abstract val frontCheckMarkContentDescription: Int

    @get:StringRes
    open var backCheckMarkContentDescription: Int? = null

    @get:IdRes
    abstract val fragmentId: Int

    abstract val frontScanType: IdentityScanState.ScanType

    open var backScanType: IdentityScanState.ScanType? = null

    abstract val collectedDataParamType: CollectedDataParam.Type

    lateinit var binding: IdentityUploadFragmentBinding

    private var shouldShowTakePhoto: Boolean = false

    private var shouldShowChoosePhoto: Boolean = false

    @VisibleForTesting
    internal var identityUploadViewModelFactory: ViewModelProvider.Factory =
        IdentityUploadViewModel.FrontBackUploadViewModelFactory(
            { this },
            identityIO
        )

    private val identityUploadViewModel: IdentityUploadViewModel by viewModels {
        identityUploadViewModelFactory
    }

    protected val identityViewModel: IdentityViewModel by activityViewModels { identityViewModelFactory }

    abstract val presentedId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        identityUploadViewModel.registerActivityResultCaller(
            activityResultCaller = this,
            onFrontPhotoTaken = {
                uploadResult(
                    uri = it,
                    uploadMethod = DocumentUploadParam.UploadMethod.MANUALCAPTURE,
                    isFront = true,
                    scanType = frontScanType
                )
            },
            onBackPhotoTaken = {
                uploadResult(
                    uri = it,
                    uploadMethod = DocumentUploadParam.UploadMethod.MANUALCAPTURE,
                    isFront = false,
                    scanType = requireNotNull(backScanType) { "null backScanType" }
                )
            },
            onFrontImageChosen = {
                uploadResult(
                    uri = it,
                    uploadMethod = DocumentUploadParam.UploadMethod.FILEUPLOAD,
                    isFront = true,
                    scanType = frontScanType
                )
            },
            onBackImageChosen = {
                uploadResult(
                    uri = it,
                    uploadMethod = DocumentUploadParam.UploadMethod.FILEUPLOAD,
                    isFront = false,
                    scanType = requireNotNull(backScanType) { "null backScanType" }
                )
            }
        )
    }

    /**
     * Check how this fragment is navigated from and reset uploaded state when needed.
     *
     * The upload state should only be kept when scanning fails and user is redirected to this
     * fragment through CouldNotCaptureFragment, in which case it's possible that the front is
     * already scanned and uploaded, and this fragment should correctly updating the front uploaded UI.
     *
     * For all other cases the upload state should be reset in order to reupload both front and back.
     */
    private fun maybeResetUploadedState() {
        val isPreviousEntryCouldNotCapture =
            findNavController().previousBackStackEntry?.destination?.id == R.id.couldNotCaptureFragment

        if (findNavController().isNavigatedUpTo() || !isPreviousEntryCouldNotCapture) {
            identityViewModel.resetDocumentUploadedState()
        }

        // flip the argument to indicate it's no longer navigated through back pressed
        findNavController().currentDestination?.addArgument(
            ARG_IS_NAVIGATED_UP_TO,
            NavArgument.Builder()
                .setDefaultValue(false)
                .build()
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = requireNotNull(arguments) {
            "Argument to FrontBackUploadFragment is null"
        }
        shouldShowTakePhoto = args[ARG_SHOULD_SHOW_TAKE_PHOTO] as Boolean
        shouldShowChoosePhoto = args[ARG_SHOULD_SHOW_CHOOSE_PHOTO] as Boolean

        binding = IdentityUploadFragmentBinding.inflate(layoutInflater, container, false)
        binding.titleText.text = getString(titleRes)
        binding.contentText.text = getString(contextRes)

        binding.labelFront.text = getString(frontTextRes)
        binding.finishedCheckMarkFront.contentDescription =
            getString(frontCheckMarkContentDescription)
        binding.selectFront.setOnClickListener {
            buildDialog(frontScanType).show()
        }

        binding.separator.visibility = View.GONE
        binding.backUpload.visibility = View.GONE

        binding.kontinue.toggleToDisabled()
        binding.kontinue.setText(getString(R.string.kontinue))
        return binding.root
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(presentedId, true)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (savedInstanceState?.getBoolean(presentedId, false) != true) {
            maybeResetUploadedState()
        }

        savedInstanceState?.remove(presentedId)

        collectUploadedStateAndUpdateUI()

        lifecycleScope.launch(identityViewModel.workContext) {
            identityViewModel.screenTracker.screenTransitionFinish(fragmentId.fragmentIdToScreenName())
        }
        identityViewModel.sendAnalyticsRequest(
            identityViewModel.identityAnalyticsRequestFactory.screenPresented(
                scanType = frontScanType,
                screenName = fragmentId.fragmentIdToScreenName()
            )
        )
    }

    private fun checkBackFields(
        nonNullBlock: (String, String, IdentityScanState.ScanType) -> Unit,
        nullBlock: () -> Unit
    ) {
        runCatching {
            nonNullBlock(
                getString(requireNotNull(backTextRes)),
                getString(requireNotNull(backCheckMarkContentDescription)),
                requireNotNull(backScanType)
            )
        }.onFailure {
            nullBlock()
        }
    }

    private fun IdentityScanState.ScanType.toType(): CollectedDataParam.Type =
        when (this) {
            IdentityScanState.ScanType.ID_FRONT -> CollectedDataParam.Type.IDCARD
            IdentityScanState.ScanType.ID_BACK -> CollectedDataParam.Type.IDCARD
            IdentityScanState.ScanType.PASSPORT -> CollectedDataParam.Type.PASSPORT
            IdentityScanState.ScanType.DL_FRONT -> CollectedDataParam.Type.DRIVINGLICENSE
            IdentityScanState.ScanType.DL_BACK -> CollectedDataParam.Type.DRIVINGLICENSE
            else -> {
                throw IllegalArgumentException("Unknown type: $this")
            }
        }

    private fun collectUploadedStateAndUpdateUI() {
        lifecycleScope.launch {
            identityViewModel.documentFrontUploadedState.collectLatest { latestState ->
                if (latestState.hasError()) {
                    navigateToDefaultErrorFragment(latestState.getError())
                } else if (latestState.isHighResUploaded()) {
                    showFrontUploading()
                    val front = requireNotNull(latestState.highResResult.data)
                    postFrontCollectedDataParam(
                        CollectedDataParam(
                            idDocumentFront = DocumentUploadParam(
                                highResImage = requireNotNull(front.uploadedStripeFile.id) {
                                    "front uploaded file id is null"
                                },
                                uploadMethod = requireNotNull(front.uploadMethod)
                            ),
                            idDocumentType = collectedDataParamType
                        )
                    )
                }
            }
        }

        lifecycleScope.launch {
            identityViewModel.documentBackUploadedState.collectLatest { latestState ->
                if (latestState.hasError()) {
                    navigateToDefaultErrorFragment(latestState.getError())
                } else if (latestState.isHighResUploaded()) {
                    showBackUploading()
                    val back = requireNotNull(latestState.highResResult.data)
                    postBackCollectedDataParam(
                        CollectedDataParam(
                            idDocumentBack = DocumentUploadParam(
                                highResImage = requireNotNull(back.uploadedStripeFile.id) {
                                    "front uploaded file id is null"
                                },
                                uploadMethod = requireNotNull(back.uploadMethod)
                            ),
                            idDocumentType = collectedDataParamType
                        )
                    )
                }
            }
        }
    }

    private fun turnOnBackUploadingUI() {
        binding.separator.visibility = View.VISIBLE
        binding.backUpload.visibility = View.VISIBLE

        binding.labelBack.text = getString(requireNotNull(backTextRes))
        binding.finishedCheckMarkBack.contentDescription =
            getString(requireNotNull(backCheckMarkContentDescription))
        binding.selectBack.setOnClickListener {
            buildDialog(requireNotNull(backScanType)).show()
        }
    }

    private fun postFrontCollectedDataParam(
        collectedDataParam: CollectedDataParam
    ) = identityViewModel.observeForVerificationPage(
        viewLifecycleOwner,
        onSuccess = { verificationPage ->
            lifecycleScope.launch {
                runCatching {
                    postVerificationPageData(
                        identityViewModel = identityViewModel,
                        collectedDataParam =
                        collectedDataParam,
                        clearDataParam = if (verificationPage.requireSelfie()) ClearDataParam.UPLOAD_FRONT_SELFIE else ClearDataParam.UPLOAD_FRONT,
                        fromFragment = fragmentId
                    ) { verificationPageDataWithNoError ->
                        showFrontDone()
                        if (collectedDataParamType == CollectedDataParam.Type.PASSPORT) {
                            enableContinueButton(verificationPage)
                        } else {
                            if (verificationPageDataWithNoError.isMissingBack()) {
                                turnOnBackUploadingUI()
                            } else {
                                enableContinueButton(verificationPage)
                            }
                        }
                    }
                }.onFailure {
                    Log.e(TAG, "Fail to observeForVerificationPage: $it")
                    navigateToDefaultErrorFragment(it)
                }
            }
        },
        onFailure = { throwable ->
            Log.e(TAG, "Fail to observeForVerificationPage: $throwable")
            navigateToDefaultErrorFragment(throwable)
        }
    )

    private fun postBackCollectedDataParam(
        collectedDataParam: CollectedDataParam
    ) = identityViewModel.observeForVerificationPage(
        viewLifecycleOwner,
        onSuccess = { verificationPage ->
            lifecycleScope.launch {
                runCatching {
                    postVerificationPageData(
                        identityViewModel = identityViewModel,
                        collectedDataParam =
                        collectedDataParam,
                        clearDataParam = if (verificationPage.requireSelfie()) ClearDataParam.UPLOAD_TO_SELFIE else ClearDataParam.UPLOAD_TO_CONFIRM,
                        fromFragment = fragmentId
                    ) {
                        showBackDone()
                        enableContinueButton(verificationPage)
                    }
                }.onFailure {
                    Log.e(TAG, "Fail to observeForVerificationPage: $it")
                    navigateToDefaultErrorFragment(it)
                }
            }
        },
        onFailure = { throwable ->
            Log.e(TAG, "Fail to observeForVerificationPage: $throwable")
            navigateToDefaultErrorFragment(throwable)
        }
    )

    private fun getTitleFromScanType(scanType: IdentityScanState.ScanType): String {
        return when (scanType) {
            IdentityScanState.ScanType.ID_FRONT -> {
                getString(R.string.upload_dialog_title_id_front)
            }
            IdentityScanState.ScanType.ID_BACK -> {
                getString(R.string.upload_dialog_title_id_back)
            }
            IdentityScanState.ScanType.DL_FRONT -> {
                getString(R.string.upload_dialog_title_dl_front)
            }
            IdentityScanState.ScanType.DL_BACK -> {
                getString(R.string.upload_dialog_title_dl_back)
            }
            IdentityScanState.ScanType.PASSPORT -> {
                getString(R.string.upload_dialog_title_passport)
            }
            else -> {
                throw java.lang.IllegalArgumentException("invalid scan type: $scanType")
            }
        }
    }

    private fun buildDialog(
        scanType: IdentityScanState.ScanType
    ) = AppCompatDialog(requireContext()).also { dialog ->
        dialog.setContentView(R.layout.get_local_image_dialog)
        dialog.setTitle(getTitleFromScanType(scanType))
        if (shouldShowTakePhoto) {
            dialog.findViewById<Button>(R.id.take_photo)?.setOnClickListener {
                if (scanType == frontScanType) {
                    identityUploadViewModel.takePhotoFront(requireContext())
                } else if (scanType == backScanType) {
                    identityUploadViewModel.takePhotoBack(requireContext())
                }
                dialog.dismiss()
            }
        } else {
            requireNotNull(dialog.findViewById(R.id.take_photo)).visibility = View.GONE
        }

        if (shouldShowChoosePhoto) {
            dialog.findViewById<Button>(R.id.choose_file)?.setOnClickListener {
                if (scanType == frontScanType) {
                    identityUploadViewModel.chooseImageFront()
                } else if (scanType == backScanType) {
                    identityUploadViewModel.chooseImageBack()
                }
                dialog.dismiss()
            }
        } else {
            requireNotNull(dialog.findViewById(R.id.choose_file)).visibility = View.GONE
        }
    }

    private fun observeForDocCapturePage(
        onSuccess: (VerificationPageStaticContentDocumentCapturePage) -> Unit
    ) {
        identityViewModel.observeForVerificationPage(
            viewLifecycleOwner,
            onSuccess = {
                onSuccess(it.documentCapture)
            },
            onFailure = {
                navigateToDefaultErrorFragment(it)
            }
        )
    }

    private fun uploadResult(
        uri: Uri,
        uploadMethod: DocumentUploadParam.UploadMethod,
        isFront: Boolean,
        scanType: IdentityScanState.ScanType
    ) {
        if (isFront) {
            showFrontUploading()
        } else {
            showBackUploading()
        }
        observeForDocCapturePage { docCapturePage ->
            identityViewModel.uploadManualResult(
                uri = uri,
                isFront = isFront,
                docCapturePage = docCapturePage,
                uploadMethod = uploadMethod,
                scanType = scanType
            )
        }
    }

    private fun showFrontUploading() {
        binding.selectFront.visibility = View.GONE
        binding.progressCircularFront.visibility = View.VISIBLE
        binding.finishedCheckMarkFront.visibility = View.GONE
    }

    private fun showFrontDone() {
        binding.selectFront.visibility = View.GONE
        binding.progressCircularFront.visibility = View.GONE
        binding.finishedCheckMarkFront.visibility = View.VISIBLE
    }

    private fun showBackUploading() {
        binding.selectBack.visibility = View.GONE
        binding.progressCircularBack.visibility = View.VISIBLE
        binding.finishedCheckMarkBack.visibility = View.GONE
    }

    private fun showBackDone() {
        binding.selectBack.visibility = View.GONE
        binding.progressCircularBack.visibility = View.GONE
        binding.finishedCheckMarkBack.visibility = View.VISIBLE
    }

    private fun enableContinueButton(verificationPage: VerificationPage) {
        binding.kontinue.toggleToButton()
        binding.kontinue.setOnClickListener {
            binding.kontinue.toggleToLoading()
            lifecycleScope.launch {
                navigateToSelfieOrSubmit(
                    verificationPage,
                    identityViewModel,
                    fragmentId
                )
            }
        }
    }

    companion object {
        val TAG: String = IdentityUploadFragment::class.java.simpleName
    }
}
