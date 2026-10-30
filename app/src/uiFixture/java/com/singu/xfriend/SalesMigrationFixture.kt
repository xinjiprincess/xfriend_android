package com.singu.xfriend

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Synthetic legacy encrypted file -> actual Room database; only exists in isolated QA builds. */
internal fun verifySalesMigration(context: Context) {
    val scope = "e".repeat(64)
    val legacy = File(context.noBackupFilesDir, "sales/$scope/state.bin")
    legacy.parentFile!!.mkdirs()
    val marker = File(context.filesDir, "qa-migration-evidence.json")
    val alias = "${context.packageName}.sales.v1"
    val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val key = keys.getKey(alias, null) as? SecretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
    }.generateKey()
    val expected = SalesData(customers = listOf(Customer("migration-customer", "迁移测试客户", "13800000001", "示例公司")),
        records = listOf(Communication("migration-record", "旧版沟通", "migration-customer", text = "保留完整旧原文", report = "保留旧报告")))
    if (!legacy.isFile) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val encrypted = JSONObject().put("iv", Base64.getEncoder().encodeToString(cipher.iv))
            .put("data", Base64.getEncoder().encodeToString(cipher.doFinal(expected.json().toString().toByteArray())))
        writeAtomic(AtomicFile(legacy), encrypted.toString())
    }
    fun digest() = MessageDigest.getInstance("SHA-256").digest(legacy.readBytes()).joinToString("") { "%02x".format(it) }
    val originalHash = digest()
    val repository = SalesRepository(context, scope)
    val migrated = repository.load()
    check(migrated.customers.single().name == "迁移测试客户")
    check(migrated.records.single().text == "保留完整旧原文")
    repository.update { it.copy(records = it.records.map { record -> record.copy(report = "新版已保存") }) }
    check(repository.load().records.single().report == "新版已保存")
    check(digest() == originalHash)
    marker.writeText(JSONObject().put("passed", true).put("synthetic_only", true).put("legacy_preserved", true)
        .put("idempotent_load", true).put("room_database_exists", File(legacy.parentFile, "crm.db").isFile).toString())
}
