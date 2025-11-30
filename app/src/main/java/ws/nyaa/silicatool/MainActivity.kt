package ws.nyaa.silicatool

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ws.nyaa.silicatool.ui.theme.SiliCaToolTheme

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null
    private val uiState = mutableStateOf(MainUiState())
    private var pendingImage: SiliCaImage? = null
    private var nfcTaskToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            updateState { copy(status = "この端末はNFCに対応していません") }
        }

        setContent {
            SiliCaToolTheme {
                MainScreen(
                    state = uiState.value,
                    onStartRead = ::startReadMode,
                    onToggleSystem = ::toggleSystem,
                    onToggleService = ::toggleService,
                    onSelectWriteService = ::selectWriteService,
                    onRequestWrite = { requestWriteFlow(trimAllowed = false) },
                    onCancelTrim = { updateState { copy(trimPrompt = null) } },
                    onConfirmTrim = { requestWriteFlow(trimAllowed = true) },
                    onShowServiceDetail = { updateState { copy(serviceDetail = it) } },
                    onDismissDetail = { updateState { copy(serviceDetail = null) } },
                    onNextStep = ::navigateToStep,
                    onBackStep = ::stepBack,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enableReaderMode()
    }

    override fun onPause() {
        super.onPause()
        disableReaderMode()
    }

    override fun onTagDiscovered(tag: Tag) {
        when (uiState.value.readerStage) {
            ReaderStage.AwaitingSource -> readFromTag(tag)
            ReaderStage.AwaitingWrite -> writeToSiliCa(tag)
            ReaderStage.Idle -> Unit
        }
    }

    private fun startReadMode() {
        if (uiState.value.isBusy) return
        pendingImage = null
        updateState {
            copy(
                status = "FeliCa をかざしてください",
                error = null,
                awaitingWrite = false,
                trimPrompt = null,
                wizardStep = WizardStep.AwaitRead,
                readerStage = ReaderStage.AwaitingSource
            )
        }
    }

    private fun applyReadResult(dump: CardDump) {
        updateState {
            copy(
                cardDump = dump,
                selection = WritePlanner.initialSelection(dump),
                status = statusForStep(WizardStep.SystemSelect),
                wizardStep = WizardStep.SystemSelect
            )
        }
    }

    private fun readFromTag(tag: Tag) {
        launchNfcTask(
            busyMessage = "読み取り中...",
            action = { runCatching { FelicaClient(tag).readCard() } },
            onResult = { result ->
                updateState { copy(readerStage = ReaderStage.Idle) }
                if (result.isSuccess) {
                    applyReadResult(result.getOrThrow())
                } else {
                    updateState {
                        copy(
                            error = "読み取りに失敗しました: ${result.exceptionOrNull()?.message}",
                            status = "カードを読み取ってください"
                        )
                    }
                }
            }
        )
    }

    private fun requestWriteFlow(trimAllowed: Boolean) {
        pendingImage = null
        updateState { copy(awaitingWrite = false, trimPrompt = null) }

        val card = uiState.value.cardDump ?: run {
            updateState { copy(status = "先にFeliCaを読み取ってください") }
            return
        }

        when (val plan = WritePlanner.build(card, uiState.value.selection, trimAllowed)) {
            is WritePlanResult.Ready -> {
                pendingImage = plan.image
                updateState {
                    copy(
                        awaitingWrite = true,
                        status = "SiliCa をかざしてください",
                        readerStage = ReaderStage.AwaitingWrite,
                        wizardStep = WizardStep.ReadyToWrite
                    )
                }
            }

            is WritePlanResult.NeedsTrim -> {
                updateState { copy(trimPrompt = TrimPrompt(plan.serviceCode, plan.blockCount)) }
            }

            is WritePlanResult.Invalid -> {
                updateState { copy(status = plan.reason) }
            }
        }
    }

    private fun stepBack() {
        cancelPendingNfcOperations()
        updateState {
            val previousStep = when (wizardStep) {
                WizardStep.ReadyToWrite -> WizardStep.ServiceSelect
                WizardStep.ServiceSelect -> WizardStep.SystemSelect
                WizardStep.SystemSelect, WizardStep.AwaitRead -> WizardStep.AwaitRead
            }
            copy(wizardStep = previousStep, status = statusForStep(previousStep))
        }
    }

    private fun navigateToStep(step: WizardStep) {
        cancelPendingNfcOperations()
        updateState { copy(wizardStep = step, status = statusForStep(step)) }
    }

    private fun cancelPendingNfcOperations() {
        val hasPending =
            uiState.value.readerStage != ReaderStage.Idle ||
                    uiState.value.awaitingWrite ||
                    uiState.value.isBusy
        nfcTaskToken++
        pendingImage = null
        updateState {
            copy(
                awaitingWrite = false,
                readerStage = ReaderStage.Idle,
                trimPrompt = null,
                isBusy = false,
                status = if (hasPending) "操作をキャンセルしました" else status
            )
        }
    }

    private fun writeToSiliCa(tag: Tag) {
        val image = pendingImage ?: return
        launchNfcTask(
            busyMessage = "SiliCa に書き込み中...",
            action = { runCatching { FelicaClient(tag).writeSiliCa(image) } },
            onResult = { result ->
                updateState { copy(awaitingWrite = false, readerStage = ReaderStage.Idle) }
                if (result.isSuccess) {
                    pendingImage = null
                    updateState { copy(status = "書き込みが完了しました") }
                } else {
                    updateState {
                        copy(
                            error = "書き込みに失敗しました: ${result.exceptionOrNull()?.message}",
                            status = "もう一度お試しください"
                        )
                    }
                }
            }
        )
    }

    private fun toggleSystem(systemCode: Int, checked: Boolean) {
        val current = uiState.value.selection
        val updated = current.selectedSystems.toMutableSet()
        if (checked) {
            if (!updated.contains(systemCode) && updated.size >= WriteConstraints.maxSelectableSystems) {
                updateState { copy(status = "システムは${WriteConstraints.maxSelectableSystems}つまで選択できます") }
                return
            }
            updated += systemCode
        } else {
            updated -= systemCode
        }
        updateState { copy(selection = current.copy(selectedSystems = updated)) }
    }

    private fun toggleService(serviceCode: Int, checked: Boolean) {
        val current = uiState.value.selection
        val updated = current.selectedServices.toMutableSet()
        var writeService = current.writeService
        if (checked) {
            if (!updated.contains(serviceCode) && updated.size >= WriteConstraints.maxSelectableServices) {
                updateState { copy(status = "サービスは${WriteConstraints.maxSelectableServices}つまで選択できます") }
                return
            }
            updated += serviceCode
        } else {
            updated -= serviceCode
            if (writeService == serviceCode) {
                writeService = null
            }
        }
        updateState {
            copy(
                selection = current.copy(
                    selectedServices = updated,
                    writeService = writeService
                )
            )
        }
    }

    private fun selectWriteService(serviceCode: Int) {
        val current = uiState.value.selection
        val updated = current.selectedServices.toMutableSet()
        if (!updated.contains(serviceCode) && updated.size >= WriteConstraints.maxSelectableServices) {
            updateState { copy(status = "サービスは${WriteConstraints.maxSelectableServices}つまで選択できます") }
            return
        }
        updated += serviceCode
        updateState {
            copy(
                selection = current.copy(
                    selectedServices = updated,
                    writeService = serviceCode
                )
            )
        }
    }

    private fun enableReaderMode() {
        val flags = NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        nfcAdapter?.enableReaderMode(this, this, flags, null)
    }

    private fun disableReaderMode() {
        nfcAdapter?.disableReaderMode(this)
    }

    private fun <T> launchNfcTask(
        busyMessage: String,
        action: suspend () -> Result<T>,
        onResult: (Result<T>) -> Unit,
    ) {
        if (uiState.value.isBusy) return
        val token = nfcTaskToken
        updateState { copy(isBusy = true, status = busyMessage, error = null) }
        lifecycleScope.launch(Dispatchers.IO) {
            val result = action()
            withContext(Dispatchers.Main) {
                if (token != nfcTaskToken) return@withContext
                updateState { copy(isBusy = false) }
                onResult(result)
            }
        }
    }

    private fun updateState(transform: MainUiState.() -> MainUiState) {
        uiState.value = transform(uiState.value)
    }

    private fun statusForStep(step: WizardStep): String = when (step) {
        WizardStep.AwaitRead -> "カードを読み取ってください"
        WizardStep.SystemSelect -> "システムを選択してください"
        WizardStep.ServiceSelect -> "サービスと書き込み先を選択してください"
        WizardStep.ReadyToWrite -> "書き込み内容を確認してください"
    }
}
