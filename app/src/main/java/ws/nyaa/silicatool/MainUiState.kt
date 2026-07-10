package ws.nyaa.silicatool

enum class ReaderStage { Idle, AwaitingSource, AwaitingWrite }
enum class WizardStep { AwaitRead, SystemSelect, ServiceSelect, ReadyToWrite }
enum class WriteMode { Full, IdmOnly }
enum class PmmMode { Read, Fixed }

data class TrimPrompt(val serviceCode: Int, val blockCount: Int)

data class MainUiState(
    val status: String = "カードを読み取ってください",
    val error: String? = null,
    val cardDump: CardDump? = null,
    val selection: SelectionState = SelectionState(),
    val isBusy: Boolean = false,
    val awaitingWrite: Boolean = false,
    val trimPrompt: TrimPrompt? = null,
    val serviceDetail: ServiceDump? = null,
    val wizardStep: WizardStep = WizardStep.AwaitRead,
    val readerStage: ReaderStage = ReaderStage.Idle,
)

object WriteConstraints {
    const val maxSelectableSystems = 4
    const val maxSelectableServices = 4
    const val writeBlockLimit = 12
    const val blockBytes = 16
}
