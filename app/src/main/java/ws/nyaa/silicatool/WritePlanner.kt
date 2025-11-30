package ws.nyaa.silicatool

sealed interface WritePlanResult {
    data class Ready(val image: SiliCaImage) : WritePlanResult
    data class NeedsTrim(val serviceCode: Int, val blockCount: Int) : WritePlanResult
    data class Invalid(val reason: String) : WritePlanResult
}

object WritePlanner {
    fun initialSelection(card: CardDump): SelectionState {
        val defaultSystems =
            card.systems.take(WriteConstraints.maxSelectableSystems).map { it.systemCode }.toSet()
        val defaultServices = card.systems.firstOrNull()
            ?.services
            ?.firstOrNull()
            ?.let { setOf(it.primaryServiceCode) }
            ?: emptySet()
        val defaultWriteService =
            card.systems.firstOrNull()?.services?.firstOrNull()?.primaryServiceCode
        return SelectionState(
            selectedSystems = defaultSystems,
            selectedServices = defaultServices,
            writeService = defaultWriteService
        )
    }

    fun build(card: CardDump, selection: SelectionState, trimAllowed: Boolean): WritePlanResult {
        val selectedSystems = if (selection.selectedSystems.isNotEmpty()) {
            selection.selectedSystems
        } else {
            card.systems.take(WriteConstraints.maxSelectableSystems).map { it.systemCode }.toSet()
        }
        if (selectedSystems.size > WriteConstraints.maxSelectableSystems) {
            return WritePlanResult.Invalid("システムは${WriteConstraints.maxSelectableSystems}つまで選択できます")
        }

        val systemsForWrite = card.systems
            .filter { selectedSystems.contains(it.systemCode) }
            .take(WriteConstraints.maxSelectableSystems)
            .ifEmpty { card.systems }

        val servicesPool = systemsForWrite.flatMap { it.services }
        val servicesForWrite = if (selection.selectedServices.isNotEmpty()) {
            servicesPool.filter { selection.selectedServices.contains(it.primaryServiceCode) }
        } else {
            servicesPool
        }
        if (servicesForWrite.isEmpty()) {
            return WritePlanResult.Invalid("サービス情報が取得できませんでした")
        }

        val targetServiceCode = selection.writeService
            ?.takeIf { code -> servicesForWrite.any { it.primaryServiceCode == code } }
            ?: servicesForWrite.firstOrNull()?.primaryServiceCode
            ?: return WritePlanResult.Invalid("書き込むサービスを1つ選択してください")

        val serviceCodesForImage = servicesForWrite
            .flatMap { it.serviceCodes }
            .distinct()
            .toMutableList()
        if (!serviceCodesForImage.contains(targetServiceCode)) {
            serviceCodesForImage.add(0, targetServiceCode)
        }
        if (serviceCodesForImage.size > WriteConstraints.maxSelectableServices) {
            return WritePlanResult.Invalid("選択されたシステムに含まれるサービスコードが多すぎます（最大${WriteConstraints.maxSelectableServices}）")
        }

        val targetService =
            servicesForWrite.firstOrNull { it.primaryServiceCode == targetServiceCode }
                ?: return WritePlanResult.Invalid("選択したサービスのデータがありません")

        if (targetService.blocks.isEmpty()) {
            return WritePlanResult.Invalid("ブロックデータが取得できていません")
        }
        if (targetService.blockCount > WriteConstraints.writeBlockLimit && !trimAllowed) {
            return WritePlanResult.NeedsTrim(
                targetService.primaryServiceCode,
                targetService.blockCount
            )
        }

        val trimmedBlocks =
            targetService.blocks.take(WriteConstraints.writeBlockLimit).map { it.data }
        val paddedBlocks = if (trimmedBlocks.size < WriteConstraints.writeBlockLimit) {
            trimmedBlocks + List(WriteConstraints.writeBlockLimit - trimmedBlocks.size) {
                ByteArray(WriteConstraints.blockBytes)
            }
        } else {
            trimmedBlocks
        }

        val systemsForImage =
            systemsForWrite.take(WriteConstraints.maxSelectableSystems).map { it.systemCode }
        val servicesForImage = serviceCodesForImage.take(WriteConstraints.maxSelectableServices)

        return WritePlanResult.Ready(
            SiliCaImage(
                idm = card.idm,
                pmm = card.pmm,
                systemCodes = systemsForImage,
                serviceCodes = servicesForImage,
                blocks = paddedBlocks,
            )
        )
    }
}
