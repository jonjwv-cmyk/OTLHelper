package com.example.otlhelper.domain.model

data class MolRecord(
    val remoteId: Long = 0,
    val warehouseId: String = "",
    val warehouseName: String = "",
    val warehouseDesc: String = "",
    val warehouseMark: String = "",
    val warehouseKeeper: String = "",
    val warehouseWorkPhones: String = "",
    val fio: String = "",
    val status: String = "",
    val position: String = "",
    val mobile: String = "",
    val work: String = "",
    val mail: String = "",
    val tab: String = "",
    val searchText: String = "",
    val createdAt: String = ""
)

/**
 * Returns true when the record points to a real physical warehouse.
 *
 * Some rows in the base have `warehouseId = "МОЛ"` — that's a legacy marker
 * meaning the person is materially responsible but not tied to a specific
 * warehouse. Those must NEVER render as a warehouse pill or pinned card.
 */
fun MolRecord.hasRealWarehouse(): Boolean {
    val id = warehouseId.trim()
    if (id.isBlank()) return false
    val upper = id.uppercase()
    return upper != "МОЛ" && upper != "MOL"
}
