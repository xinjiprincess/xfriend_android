package com.singu.xfriend

import org.json.JSONObject

/** Three-way field merge: UI edits win only on fields actually changed by that UI. */
internal fun mergeSalesChanges(before: SalesData, next: SalesData, latest: SalesData): SalesData {
    fun fields(old: JSONObject, changed: JSONObject, current: JSONObject): JSONObject {
        val result = JSONObject(current.toString())
        changed.keys().forEach { key ->
            val a = old.opt(key)
            val b = changed.opt(key)
            if (a is JSONObject && b is JSONObject && current.opt(key) is JSONObject) {
                result.put(key, fields(a, b, current.getJSONObject(key)))
            } else if (a?.toString() != b?.toString()) result.put(key, b)
        }
        return result
    }
    fun <T> merge(old: List<T>, changed: List<T>, current: List<T>, id: (T) -> String,
                  json: (T) -> JSONObject, read: (JSONObject) -> T): List<T> {
        val originals = old.associateBy(id)
        val edits = changed.associateBy(id)
        val currentIds = current.map(id).toSet()
        val retained = current.mapNotNull { value ->
            val original = originals[id(value)]
            val edit = edits[id(value)]
            when {
                original != null && edit == null -> null
                original != null && edit != null -> read(fields(json(original), json(edit), json(value)))
                else -> value
            }
        }
        // An entity removed on disk is never resurrected by a stale editor.
        return retained + changed.filter { id(it) !in originals && id(it) !in currentIds }
    }
    val customers = merge(before.customers, next.customers, latest.customers, Customer::id, Customer::json, Customer::read)
    val deletedCustomers = (before.customers + latest.customers).map { it.id }.toSet() - customers.map { it.id }.toSet()
    val records = merge(before.records, next.records, latest.records, Communication::id, Communication::json, Communication::read)
        .map { if (it.customerId in deletedCustomers) it.copy(customerId = "") else it }
    val deletedRecords = (before.records + latest.records).map { it.id }.toSet() - records.map { it.id }.toSet()
    return latest.copy(
        customers = customers,
        records = records,
        profiles = merge(before.profiles, next.profiles, latest.profiles, ModelProfile::id, ModelProfile::json, ModelProfile::read),
        opportunities = merge(before.opportunities, next.opportunities, latest.opportunities, Opportunity::id, Opportunity::json, Opportunity::read)
            .map { if (it.customerId in deletedCustomers) it.copy(customerId = "") else it },
        followUps = merge(before.followUps, next.followUps, latest.followUps, FollowUp::id, FollowUp::json, FollowUp::read)
            .map { it.copy(customerId = if (it.customerId in deletedCustomers) "" else it.customerId,
                recordId = if (it.recordId in deletedRecords) "" else it.recordId) },
        expertVersions = if (next.expertVersions != before.expertVersions) next.expertVersions else latest.expertVersions,
        active = if (next.active != before.active) next.active else latest.active,
        trialUrl = if (next.trialUrl != before.trialUrl) next.trialUrl else latest.trialUrl,
        useTrial = if (next.useTrial != before.useTrial) next.useTrial else latest.useTrial,
        mode = if (next.mode != before.mode) next.mode else latest.mode,
    )
}
