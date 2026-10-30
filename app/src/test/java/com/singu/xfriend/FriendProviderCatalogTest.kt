package com.singu.xfriend

import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class FriendProviderCatalogTest {
    private val desktopIds = setOf(
        "tencent-coding-plan", "tencent-token-plan", "tencent-token-plan-enterprise-cn", "tencent-hy-token-plan", "tencent-hai",
        "deepseek", "mimo", "modelscope", "openai", "anthropic", "gemini", "groq", "openrouter", "ollama",
        "kimi-cn", "kimi-intl", "kimi-codingplan", "minimax-cn", "minimax-intl", "zhipu-cn", "zhipu-intl",
        "zhipu-cn-codingplan", "zhipu-intl-codingplan", "dashscope", "dashscope-intl", "dashscope-us",
        "aliyun-codingplan-cn", "aliyun-codingplan-intl", "aliyun-tokenplan", "aliyun-tokenplan-intl",
        "volcengine-cn", "volcengine-cn-codingplan", "volcengine-cn-agentplan", "iflytek-astron-token-plan",
        "siliconflow-cn", "siliconflow-intl", "opencode-zen-openai", "opencode-zen-anthropic", "opencode-go-openai",
        "opencode-go-anthropic", "openai-codex", "onnx",
    )
    private val managedIds = setOf("workbuddy-cn", "workbuddy-global", "github-copilot", "xai-oauth", "claude-subscription", "gemini-subscription", "gemini-pool")

    @Test fun allFortyTwoDesktopTemplatesAndSevenManagedSourcesArePresentWithoutDuplicates() {
        val presets = FriendProviderCatalog.presets
        assertEquals(desktopIds + managedIds, presets.map { it.id }.toSet())
        assertEquals(49, presets.size)
        assertEquals(269, presets.filter { it.id in desktopIds && it.id !in setOf("openai-codex", "onnx") }.sumOf { it.models.size })
        presets.forEach { preset ->
            assertEquals(preset.models.size, preset.models.distinct().size)
            assertTrue(preset.models.all { it.isNotBlank() })
            assertEquals(preset, FriendProviderCatalog.find(preset.id))
        }
        assertNull(FriendProviderCatalog.find("not-a-provider"))
    }

    @Test fun protocolSelectionMatchesTheDesktopApiTemplatesAndNativeAccountContracts() {
        listOf("anthropic", "opencode-zen-anthropic", "opencode-go-anthropic").forEach {
            assertEquals("anthropic_messages", FriendProviderCatalog.find(it)!!.apiFormat)
        }
        listOf("openai-codex", "xai-oauth").forEach { assertEquals("openai_responses", FriendProviderCatalog.find(it)!!.apiFormat) }
        assertEquals("gemini_generate", FriendProviderCatalog.find("gemini-pool")!!.apiFormat)
        assertEquals("openai_chat", FriendProviderCatalog.find("gemini")!!.apiFormat)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", FriendProviderCatalog.find("gemini")!!.baseUrl)
        assertEquals("device_oauth", FriendProviderCatalog.find("openai-codex")!!.authMethod)
        assertEquals("device_oauth", FriendProviderCatalog.find("github-copilot")!!.authMethod)
    }

    @Test fun arkPlanPresetsNeverPointAtMeteredEndpointsOrSilentlyShareCredentialsBetweenPlans() {
        val coding = FriendProviderCatalog.find("volcengine-cn-codingplan")!!
        val agent = FriendProviderCatalog.find("volcengine-cn-agentplan")!!
        assertEquals("https://ark.cn-beijing.volces.com/api/coding/v3", coding.baseUrl)
        assertEquals("https://ark.cn-beijing.volces.com/api/plan/v3", agent.baseUrl)
        assertEquals("coding", coding.metadata["plan_type"])
        assertEquals("agent", agent.metadata["plan_type"])
        listOf(coding, agent).forEach {
            assertEquals("volcengine_ark", it.providerType)
            assertEquals("ark_plan", it.metadata["endpoint_policy"])
            assertNotEquals("https://ark.cn-beijing.volces.com/api/v3", it.baseUrl)
        }
        assertEquals("api", FriendProviderCatalog.find("volcengine-cn")!!.providerType)
    }

    @Test fun quotaOnlySubscriptionsAndLocalEmbeddingsNeverAdvertiseChatInference() {
        listOf("claude-subscription", "gemini-subscription", "onnx").forEach { id ->
            val preset = FriendProviderCatalog.find(id)!!
            assertFalse(preset.inference)
            assertFalse(FriendProviderCatalog.canInfer(FriendModelConnection(providerId = id, providerType = preset.providerType)))
        }
        assertFalse(FriendProviderCatalog.canInfer(FriendModelConnection(providerId = "custom", providerType = "claude_subscription")))
        assertTrue(FriendProviderCatalog.canInfer(FriendModelConnection(providerId = "anthropic")))
        assertEquals(3, FriendProviderCatalog.find("onnx")!!.models.size)
    }

    @Test fun phonePresetsCannotReferToDesktopLoopbackOrGloballyEnableHttp() {
        FriendProviderCatalog.presets.filter { it.baseUrl.isNotBlank() }.forEach { preset ->
            val uri = URI(preset.baseUrl)
            assertEquals("https", uri.scheme)
            assertNull(uri.rawUserInfo); assertNull(uri.rawQuery); assertNull(uri.rawFragment)
        }
        val ollama = FriendProviderCatalog.find("ollama")!!
        assertEquals("", ollama.baseUrl)
        assertEquals("https_remote", ollama.metadata["endpoint_policy"])
        assertEquals("true", ollama.metadata["api_key_optional"])
    }

    @Test fun everyPresetCanBecomeAPersistableCredentialFreeDraft() {
        FriendProviderCatalog.presets.forEach { preset ->
            val draft = FriendModelConnection(id = preset.id, name = preset.name, providerId = preset.id,
                providerType = preset.providerType, apiFormat = preset.apiFormat, baseUrl = preset.baseUrl,
                model = preset.models.firstOrNull().orEmpty(), models = preset.models, metadata = preset.metadata)
            val restored = FriendModelConnectionCodec.decodeConfig(FriendModelConnectionCodec.encodeConfig(listOf(draft))).single()
            assertEquals(draft, restored)
            assertTrue(restored.credentials.isEmpty())
        }
    }
}
