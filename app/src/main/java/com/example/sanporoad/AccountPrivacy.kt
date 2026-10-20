package com.example.sanporoad

/** Never put the complete account address in visible or accessibility text. */
internal fun maskAccountEmail(email: String?): String {
    val value = email?.trim().orEmpty()
    val at = value.lastIndexOf('@')
    if (at <= 0 || at == value.lastIndex) return "Googleでログイン済み"
    val local = value.substring(0, at)
    val domain = value.substring(at + 1)
    val maskedLocal = if (local.length <= 2) "**" else "${local.first()}**${local.last()}"
    return "$maskedLocal@${domain.first()}**"
}
