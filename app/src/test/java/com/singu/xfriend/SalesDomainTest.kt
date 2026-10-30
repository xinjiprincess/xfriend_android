package com.singu.xfriend

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SalesDomainTest {
    private fun bundle() = JSONObject().put("format", "xfriend-mobile-export").put("version", 1)
        .put("experts", JSONArray().put(JSONObject().put("id", "one").put("name", "销售专家").put("prompt", "依据事实")))
        .put("teams", JSONArray().put(JSONObject().put("id", "team").put("name", "团队").put("members", JSONArray().put("one"))))
        .put("providers", JSONArray().put(JSONObject().put("id", "mimo").put("name", "小米").put("base_url", "https://api.xiaomimimo.com/v1").put("api_key", "test-only").put("models", JSONArray().put("mimo-v2.6-flash"))))
    @Test fun stableImportPreservesTeamReferences() {
        val a = DesktopBundle.parse(bundle().toString()); val b = DesktopBundle.parse(bundle().toString())
        assertEquals(a, b); assertEquals(a.experts.first().id, a.teams.first().members.first()); assertEquals(1, a.secretCount)
    }
    @Test fun unknownFormatRejected() { assertThrows(IllegalArgumentException::class.java) { DesktopBundle.parse("{}") } }
    @Test fun missingTeamMemberRejected() { val j = bundle(); j.getJSONArray("teams").getJSONObject(0).put("members", JSONArray().put("missing")); assertThrows(IllegalArgumentException::class.java) { DesktopBundle.parse(j.toString()) } }
    @Test fun duplicateIdsRejected() { val j = bundle(); j.getJSONArray("experts").put(j.getJSONArray("experts").getJSONObject(0)); assertThrows(IllegalArgumentException::class.java) { DesktopBundle.parse(j.toString()) } }
    @Test fun unsafeProviderRejected() { val j = bundle(); j.getJSONArray("providers").getJSONObject(0).put("base_url", "http://192.168.1.3/v1"); assertThrows(IllegalArgumentException::class.java) { DesktopBundle.parse(j.toString()) } }
    @Test fun missingSkillRejected() { val j = bundle(); j.getJSONArray("experts").getJSONObject(0).put("skills", JSONArray().put("missing")); assertThrows(IllegalArgumentException::class.java) { DesktopBundle.parse(j.toString()) } }
    @Test fun phoneDedupPreservesExistingCustomer() { val old = Customer(name = "客户A", phone = "13800138000"); assertEquals(listOf(old), mergeCustomers(listOf(old), listOf(Customer(name = "重复", phone = "+86 138-0013-8000")))) }
    @Test fun customersWithoutPhoneRemainDistinct() { assertEquals(2, mergeCustomers(listOf(Customer(name = "甲")), listOf(Customer(name = "乙"))).size) }
    @Test fun salesDataRoundTripIncludesCredentialsAndLinks() { val c = Customer(name = "客户"); val d = SalesData(listOf(c), listOf(Communication(title = "拜访", customerId = c.id)), listOf(ModelProfile(key = "fake-key"))); assertEquals(d, SalesData.read(d.json())) }
    @Test fun speechUsesAssistantRoleAndPresetVoice() { val j = mimoSpeech(ModelProfile(), "你好"); assertEquals("assistant", j.getJSONArray("messages").getJSONObject(1).getString("role")); assertEquals("mimo_default", j.getJSONObject("audio").getString("voice")) }
    @Test fun voiceCloneRequiresAuthorizedSampleInput() { assertThrows(IllegalArgumentException::class.java) { mimoSpeech(ModelProfile(tts = "mimo-v2.5-tts-voiceclone"), "你好") } }
    @Test fun asrUsesAudioDataUrl() { val j = mimoAsr("mimo-v2.5-asr", "data:audio/wav;base64,AAAA"); assertEquals("input_audio", j.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("type")) }
    @Test fun wavIsLittleEndianPcm() { val b = ByteBuffer.wrap(wavHeader(32000)).order(ByteOrder.LITTLE_ENDIAN); assertEquals(32036, b.getInt(4)); assertEquals(16000, b.getInt(24)); assertEquals(32000, b.getInt(40)) }
    @Test fun urlsCannotCarryCredentialsOrQuery() { listOf("https://key@example.com/v1", "https://example.com/v1?key=x", "https://example.com/#x").forEach { assertThrows(IllegalArgumentException::class.java) { requireSafeApiUrl(it) } } }
}
