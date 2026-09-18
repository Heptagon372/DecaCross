package kr.decacross.collector

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kr.decacross.collector.config.CLI_USAGE
import kr.decacross.collector.config.CliOptions
import kr.decacross.collector.config.CliParse
import kr.decacross.collector.config.parseCli
import kr.decacross.collector.content.LicensePolicy
import kr.decacross.collector.run.ExitCode
import kr.decacross.collector.run.Runner
import kr.decacross.collector.store.RedistributionPolicy
import java.io.IOException
import kotlin.system.exitProcess

/**
 * 수집기 진입점. `--once` / `--loop` (사용법은 `--help`).
 *
 * # 불변식 (CLAUDE.md 17)
 * 모든 외부 호출은 식별 가능한 User-Agent + 연락처. generic UA 금지 (PaperMC 정책).
 * UA 는 `CollectorSettings.userAgent` 하나에서 나오고 `KtorHttp` 가 모든 요청에 붙인다.
 */
fun main(args: Array<String>) {
    val code = when (val parsed = parseCli(args, System.getenv())) {
        CliParse.Help -> {
            println(CLI_USAGE)
            ExitCode.OK
        }

        is CliParse.Error -> {
            System.err.println("인자 오류: ${parsed.message}")
            System.err.println("사용법: --help")
            ExitCode.FATAL
        }

        is CliParse.Ok -> runBlocking { Runner(parsed.options).run() }
    }
    exitProcess(code)
}

/** 라이선스 정책 리소스 (WP-C3 소유). */
internal const val LICENSE_POLICY_RESOURCE: String = LicensePolicy.RESOURCE

/**
 * 정합성 검사 S9 가 쓰는 `license-policy.json` 의 재배포 스위치 + 허용 목록.
 * 수집기가 `content.redistributable` 을 정할 때와 **같은 해석**([LicensePolicy.fromJson])을 쓴다 (INV-4).
 * 리소스가 없거나 형식이 틀리거나 `redistributableSpdx` 키가 없으면 null (S9 가 WARN).
 * UTF-8 BOM 은 벗긴다 (V-1: 운영자가 Windows 편집기로 저장해도 S9 가 조용히 WARN 으로 떨어지지 않게).
 */
internal fun loadRedistributionPolicy(resource: String = LICENSE_POLICY_RESOURCE): RedistributionPolicy? {
    val text = try {
        CliOptions::class.java.getResourceAsStream(resource)?.use { it.readBytes().decodeToString() }
    } catch (e: IOException) {
        null
    } ?: return null
    return try {
        val root = CollectorJson.parseToJsonElement(stripUtf8Bom(text)) as? JsonObject ?: return null
        if (REDISTRIBUTABLE_SPDX_KEY !in root) return null
        val policy = LicensePolicy.fromJson(root)
        RedistributionPolicy(enabled = policy.enabled, allowlist = policy.allow)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalStateException) {
        // LicensePolicy.fromJson 의 형식 오류
        null
    }
}

private const val REDISTRIBUTABLE_SPDX_KEY: String = "redistributableSpdx"
