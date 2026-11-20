package ru.msklad.scanner.data

import java.math.BigDecimal
import java.math.RoundingMode

fun formatQuantity(value: Double): String =
    BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
