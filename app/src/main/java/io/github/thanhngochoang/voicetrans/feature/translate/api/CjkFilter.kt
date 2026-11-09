package io.github.thanhngochoang.voicetrans.feature.translate.api

import java.lang.Character.UnicodeBlock
import java.lang.Character.UnicodeScript

object CjkFilter {

    private val CJK_BLOCKS = setOf(
        UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
        UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
        UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B,
        UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C,
        UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D,
        UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
        UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT,
        UnicodeBlock.CJK_RADICALS_SUPPLEMENT,
        UnicodeBlock.KANGXI_RADICALS,
        UnicodeBlock.IDEOGRAPHIC_DESCRIPTION_CHARACTERS,
        UnicodeBlock.BOPOMOFO,
        UnicodeBlock.BOPOMOFO_EXTENDED,
        UnicodeBlock.CJK_STROKES,
        UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION,
        UnicodeBlock.ENCLOSED_CJK_LETTERS_AND_MONTHS,
        UnicodeBlock.CJK_COMPATIBILITY,
        UnicodeBlock.CJK_COMPATIBILITY_FORMS,
        UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS,
    )

    fun isCjkCodePoint(cp: Int): Boolean {
        val script = try { UnicodeScript.of(cp) } catch (_: Throwable) { null }
        if (script == UnicodeScript.HAN || script == UnicodeScript.BOPOMOFO) return true
        val block = try { UnicodeBlock.of(cp) } catch (_: Throwable) { null }
        return block in CJK_BLOCKS
    }

    fun isCjk(ch: Char): Boolean = isCjkCodePoint(ch.code)

    fun hasCjk(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isCjkCodePoint(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    /**
     * Common historical, court, drama titles, names, and colloquial phrases that ML translation engines
     * sometimes leave untranslated. Mapping them to natural Vietnamese ensures accurate context without Hanzi.
     * Sorted by length descending so longer compounds match before individual characters.
     */
    private val COMMON_TERMS: List<Pair<String, String>> = listOf(
        // Compounds (4 chars)
        "谢主隆恩" to "tạ chủ long ân",
        // Compounds (2-3 chars)
        "大人" to "đại nhân",
        "皇上" to "hoàng thượng",
        "陛下" to "bệ hạ",
        "娘娘" to "nương nương",
        "皇后" to "hoàng hậu",
        "太后" to "thái hậu",
        "殿下" to "điện hạ",
        "将军" to "tướng quân",
        "公主" to "công chúa",
        "郡主" to "quận chúa",
        "王爷" to "vương gia",
        "世子" to "thế tử",
        "公子" to "công tử",
        "少爷" to "thiếu gia",
        "小姐" to "tiểu thư",
        "夫人" to "phu nhân",
        "姑娘" to "cô nương",
        "师父" to "sư phụ",
        "师傅" to "sư phụ",
        "徒儿" to "đồ nhi",
        "徒弟" to "đồ đệ",
        "太监" to "thái giám",
        "公公" to "công công",
        "奴才" to "nô tài",
        "属下" to "thuộc hạ",
        "微臣" to "vi thần",
        "爱卿" to "ái khanh",
        "老奴" to "lão nô",
        "父皇" to "phụ hoàng",
        "母后" to "mẫu hậu",
        "大哥" to "đại ca",
        "二哥" to "nhị ca",
        "小弟" to "tiểu đệ",
        "在下" to "tại hạ",
        "阁下" to "các hạ",
        "大夫" to "đại phu",
        "掌柜" to "chưởng quầy",
        "帮主" to "bang chủ",
        "教主" to "giáo chủ",
        "门主" to "môn chủ",
        "宗主" to "tông chủ",
        "长老" to "trưởng lão",
        "堂主" to "đường chủ",
        "侠客" to "hiệp khách",
        "刺客" to "thích khách",
        "知府" to "tri phủ",
        "知县" to "tri huyện",
        "丞相" to "thừa tướng",
        "宰相" to "tể tướng",
        "尚书" to "thượng thư",
        "侍郎" to "thị lang",
        "钦差" to "khâm sai",
        "圣上" to "thánh thượng",
        "万岁" to "vạn tuế",
        "千岁" to "thiên tuế",
        "奉旨" to "phụng chỉ",
        "遵命" to "tuân mệnh",
        "遵旨" to "tuân chỉ",
        "谢恩" to "tạ ơn",
        "平身" to "bình thân",
        "免礼" to "miễn lễ",
        "启奏" to "khởi tấu",
        "本王" to "bổn vương",
        "本座" to "bổn toạ",
        "本宫" to "bổn cung",
        "放肆" to "càn rỡ",
        "大胆" to "to gan",
        "来人" to "người đâu",
        "住手" to "dừng tay",
        "闭嘴" to "câm miệng",
        "退下" to "lui xuống",
        "好的" to "được",
        "明白" to "đã hiểu",
        "知道" to "đã biết",
        "等等" to "khoan đã",
        "救命" to "cứu mạng",
        "没关系" to "không sao",
        "对不起" to "xin lỗi",
        "谢谢" to "cảm ơn",
        "再见" to "tạm biệt",
        "抓起来" to "bắt lại",
        "带下去" to "áp giải xuống",
        // Compound surnames
        "司马" to "Tư Mã",
        "欧阳" to "Âu Dương",
        "诸葛" to "Gia Cát",
        "上官" to "Thượng Quan",
        "慕容" to "Mộ Dung",
        // Top Chinese single-char surnames
        "赵" to "Triệu",
        "钱" to "Tiền",
        "孙" to "Tôn",
        "李" to "Lý",
        "周" to "Chu",
        "吴" to "Ngô",
        "郑" to "Trịnh",
        "王" to "Vương",
        "冯" to "Phùng",
        "陈" to "Trần",
        "卫" to "Vệ",
        "蒋" to "Tưởng",
        "沈" to "Thẩm",
        "韩" to "Hàn",
        "杨" to "Dương",
        "朱" to "Chu",
        "秦" to "Tần",
        "许" to "Hứa",
        "何" to "Hà",
        "吕" to "Lữ",
        "张" to "Trương",
        "曹" to "Tào",
        "金" to "Kim",
        "魏" to "Ngụy",
        "谢" to "Tạ",
        "苏" to "Tô",
        "范" to "Phạm",
        "马" to "Mã",
        "方" to "Phương",
        "袁" to "Viên",
        "唐" to "Đường",
        "罗" to "La",
        "崔" to "Thôi",
        "程" to "Trình",
        "陆" to "Lục",
        "宋" to "Tống",
        "梁" to "Lương",
        "杜" to "Đỗ",
        "阮" to "Nguyễn",
        "林" to "Lâm",
        "徐" to "Từ",
        "高" to "Cao",
        "蔡" to "Thái",
        "胡" to "Hồ",
        "郭" to "Quách",
        "夏" to "Hạ",
        "卢" to "Lư",
        "邓" to "Đặng",
        "萧" to "Tiêu",
        "汪" to "Uông",
        "刘" to "Lưu",
        "龙" to "Long",
        "白" to "Bạch",
        "叶" to "Diệp",
        "黎" to "Lê",
    ).sortedByDescending { it.first.length }

    /**
     * Replaces known Chinese terms/names with Vietnamese equivalents padded with spaces.
     */
    fun cleanAndTransliterate(text: String): String {
        var result = text
        for ((cn, vn) in COMMON_TERMS) {
            if (result.contains(cn)) {
                result = result.replace(cn, " $vn ")
            }
        }
        return result
    }

    /**
     * Purges all Chinese characters, Chinese brackets, annotations, and Chinese punctuation
     * from translated output. Ensures output contains ZERO Chinese characters.
     */
    fun purgeCjk(text: String): String {
        if (text.isBlank()) return ""

        // Step 1: Remove parenthesized / bracketed annotations containing CJK characters FIRST
        // e.g. "đại nhân (大人)" -> "đại nhân"
        var s = text.replace(Regex("""[\(（\[【《「『〔〖〘][^\)）\]】》」』〕〗〙]*\p{IsHan}[^\)）\]】》」』〕〗〙]*[\)）\]】》」』〕〗〙]"""), " ")

        // Step 2: Normalize fullwidth punctuation to Latin equivalents
        s = s.replace('，', ',')
            .replace('。', '.')
            .replace('！', '!')
            .replace('？', '?')
            .replace('：', ':')
            .replace('；', ';')
            .replace('“', '"')
            .replace('”', '"')
            .replace('‘', '\'')
            .replace('’', '\'')
            .replace('、', ',')

        // Step 3: Pre-transliterate any known terms/titles/names (e.g. 赵大人 -> Triệu đại nhân)
        s = cleanAndTransliterate(s)

        // Step 4: Remove any remaining CJK characters (BMP and Supplementary planes)
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val charCount = Character.charCount(cp)
            if (!isCjkCodePoint(cp)) {
                sb.appendCodePoint(cp)
            }
            i += charCount
        }

        // Step 5: Clean up dangling punctuation and collapse whitespace
        return sb.toString()
            .replace(Regex("""[《》「」【】『』〔〕〖〗〘〙]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
