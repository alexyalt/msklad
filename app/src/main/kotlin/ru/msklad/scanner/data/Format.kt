package ru.msklad.scanner.data

import java.math.BigDecimal
import java.math.RoundingMode

fun formatQuantity(value: Double): String =
    BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

/** Количество с единицей измерения: «2 шт». Если единица не задана — только число. */
fun withUom(value: Double, uom: String): String =
    if (uom.isBlank()) formatQuantity(value) else "${formatQuantity(value)} $uom"
