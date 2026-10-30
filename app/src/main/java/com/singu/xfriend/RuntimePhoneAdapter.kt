package com.singu.xfriend

import org.json.JSONObject

/** Bound by the Android service; test adapters cannot alter account or mode admission. */
internal interface RuntimePhoneAdapter {
    suspend fun execute(name: String, args: JSONObject, requestId: String): String
}
