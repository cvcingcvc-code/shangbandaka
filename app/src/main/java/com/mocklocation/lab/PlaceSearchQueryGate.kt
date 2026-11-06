package com.mocklocation.lab

/** A response or list item belongs to the exact query revision that produced it. */
internal class PlaceSearchQueryGate {
    data class Ticket internal constructor(val revision: Long, val query: String)
    private var revision = 0L

    fun invalidate() { ++revision }

    fun capture(query: String): Ticket = Ticket(revision, query.trim())

    fun accepts(ticket: Ticket, currentQuery: String): Boolean =
        ticket.revision == revision && ticket.query == currentQuery.trim()
}
