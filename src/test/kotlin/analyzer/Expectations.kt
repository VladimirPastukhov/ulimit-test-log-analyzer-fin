package analyzer

import com.fasterxml.jackson.databind.JsonNode

/**
 * Compares a diagnosis with a partial expectation (`examples/INC-*.expected.json`). Only fields
 * present in the expectation are checked: a string must match exactly, an array of strings lists
 * the accepted values, and the list fields below are compared as sets (adapters by `ccNNN` id).
 */
object Expectations {
    private val setFields = setOf("affected_adapters", "affected_order_types")
    private val adapterId = Regex("""cc\d{3}""")

    fun mismatches(expected: JsonNode, actual: JsonNode, path: String = ""): List<String> =
        expected.fields().asSequence().flatMap { (field, want) ->
            val got = actual[field]
            val at = "$path$field"
            when {
                want.isObject -> if (got != null && got.isObject) mismatches(want, got, "$at.") else listOf("$at: expected an object, got $got")
                field in setFields -> {
                    val wantSet = want.map { it.asText() }.toSet()
                    val gotSet = got?.map { adapterId.find(it.asText())?.value ?: it.asText() }?.toSet()
                    if (wantSet == gotSet) emptyList() else listOf("$at: expected $wantSet, got $gotSet")
                }
                want.isArray -> {
                    val accepted = want.map { it.asText() }
                    if (got?.asText() in accepted) emptyList() else listOf("$at: expected one of $accepted, got $got")
                }
                else -> if (want == got) emptyList() else listOf("$at: expected $want, got $got")
            }
        }.toList()
}
