package com.mountriv.vaultsync.platform

import android.content.Context
import android.content.res.Configuration
import android.icu.util.ULocale
import android.os.LocaleList
import androidx.annotation.StringRes
import com.mountriv.vaultsync.R
import java.util.Locale

/**
 * 应用内语言设置（D-030、D-040，docs/ANDROID_PLATFORM.md §11）。`MainActivity` 与 `SyncService` 在 `attachBaseContext` 里用 [wrap]
 * 套上要用的语言，界面、通知文字与通知渠道名因此一致。
 *
 * 跟随系统时也要套：按系统语言列表从上往下找第一个支持的语言，都不支持时用英文。
 * 不能原样返回系统的 Context——那样文字虽然退回英文（`values/`），单复数规则和数字格式却还按系统语言
 * （俄语会把 21 当单数显示成 "21 file"，日语不分单复数显示成 "1 vaults"）。
 *
 * 以后加语言：加 `values-xx/strings.xml`，再在 [languages] 里加一项，并在 `tools/i18ncheck/check_resources.py` 里登记它的单复数类别。
 */
object AppLocale {

    /** 跟随系统（默认值）。 */
    const val SYSTEM = "system"

    /** 一种可选的语言：语言标签 + 用该语言自己的文字写的名称（资源里 `translatable="false"`，不随界面翻译）。 */
    class Language(val tag: String, @StringRes val nameRes: Int)

    val languages: List<Language> = listOf(
        Language(ZH_HANS, R.string.language_zh_cn),
        Language(ZH_HANT, R.string.language_zh_tw),
        Language("en", R.string.language_en),
        Language("ja", R.string.language_ja),
        Language("de", R.string.language_de),
        Language("es", R.string.language_es),
        Language("fr", R.string.language_fr),
        Language("ru", R.string.language_ru),
    )

    /** 按 [language] 套用语言的 Context；要用的语言与 [base] 现在的首选语言相同时原样返回。 */
    fun wrap(base: Context, language: String): Context {
        val current = base.resources.configuration
        val target = resolve(language, current.locales)
        if (current.locales[0] == target) return base
        val config = Configuration(current)
        config.setLocales(LocaleList(target))
        return base.createConfigurationContext(config)
    }

    /**
     * 实际要用的语言。选了哪种就用哪种（与系统语言是同一种时沿用系统的地区，如 en-GB、de-AT，只影响日期与数字的写法）；
     * 跟随系统（或不认识的值）时，按 [system] 从上往下找第一个支持的语言，都不支持时用英文。
     */
    private fun resolve(language: String, system: LocaleList): Locale {
        val systemLocales = (0 until system.size()).map { system[it] }
        if (languages.any { it.tag == language }) {
            return systemLocales.firstOrNull { supportedTag(it) == language && !isChinese(it) } ?: Locale.forLanguageTag(language)
        }
        for (locale in systemLocales) {
            val tag = supportedTag(locale) ?: continue
            // 中文用固定的 zh-CN / zh-TW，资源只按这两个找；其它语言保留系统的地区
            return if (isChinese(locale)) Locale.forLanguageTag(tag) else locale
        }
        return Locale.ENGLISH
    }

    /** [locale] 对应的支持语言的标签；不支持时为 null。中文按文字体系分：Hans → 简体，Hant（台湾、香港、澳门）→ 繁体。 */
    private fun supportedTag(locale: Locale): String? {
        if (isChinese(locale)) return if (script(locale) == "Hant") ZH_HANT else ZH_HANS
        return languages.firstOrNull { it.tag == locale.language }?.tag
    }

    private fun isChinese(locale: Locale) = locale.language == "zh"

    /** 补全文字体系（zh、zh-CN、zh-SG 是 Hans；zh-TW、zh-HK、zh-MO 是 Hant）。ICU 在 API 24 起可用。 */
    private fun script(locale: Locale): String = ULocale.addLikelySubtags(ULocale.forLocale(locale)).script

    private const val ZH_HANS = "zh-CN"
    private const val ZH_HANT = "zh-TW"
}
