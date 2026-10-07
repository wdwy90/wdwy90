package com.wdwy90.pullupmenu.core

/** Which website a link belongs to, for the in-app menu page. Pure logic. */
object WebLinks {
    /**
     * The registrable part of [host]: "www.mcdonalds.com" and "order.mcdonalds.com" are both
     * "mcdonalds.com". Country domains with a short second level (co.uk, com.au) keep three parts.
     */
    fun site(host: String): String {
        val labels = host.lowercase().trimEnd('.').split('.')
        if (labels.size <= 2) return labels.joinToString(".")
        val keep = if (labels.last().length == 2 && labels[labels.size - 2].length <= 3) 3 else 2
        return labels.takeLast(keep).joinToString(".")
    }

    /** True when both hosts are known and belong to the same website. */
    fun sameSite(a: String?, b: String?): Boolean =
        !a.isNullOrBlank() && !b.isNullOrBlank() && site(a) == site(b)
}
