package com.singu.xfriend

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SalesResponseTest {
    private fun response(content: Any, finish: String = "stop") = JSONObject().put("choices",
        JSONArray().put(JSONObject().put("finish_reason", finish)
            .put("message", JSONObject().put("content", content))))

    @Test fun normalTextIsPreserved() {
        assertEquals("客户要求周三回访", SalesApi().content(response("客户要求周三回访")))
    }

    @Test fun truncatedReportCannotBecomeSuccessfulAnalysis() {
        assertThrows(IllegalArgumentException::class.java) { SalesApi().content(response("不完整报告", "length")) }
    }

    @Test fun nullOrBlankResponseIsNotASuccess() {
        for (content in listOf(JSONObject.NULL, "   ", JSONObject().put("text", "wrong shape"))) {
            assertThrows(IllegalArgumentException::class.java) { SalesApi().content(response(content)) }
        }
    }
}
