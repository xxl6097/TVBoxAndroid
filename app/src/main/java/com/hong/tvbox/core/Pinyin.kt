package com.hong.tvbox.core

/**
 * 汉字 → 拼音首字母（声母）映射，供搜索联想做「拼音首字母」猜测。
 * 数据源自 GB2312 字库（一、二级共 6763 字），另对多音字做了双声母修正。
 * 由 Pinyin.ets 自动生成，本地纯内存查表，不联网。
 */
object Pinyin {

    private const val MAIN = "$main"
    private const val EXTRA = "$extra"

    private val map: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        // 先放多音字（双声母），再放主表，主表不覆盖多音字
        EXTRA.split(';').forEach { p ->
            val eq = p.indexOf('=')
            if (eq > 0) m[p.substring(0, eq)] = p.substring(eq + 1)
        }
        MAIN.split(';').forEach { p ->
            val col = p.indexOf(':')
            if (col <= 0) return@forEach
            val ini = p.substring(0, col)
            val chars = p.substring(col + 1)
            for (k in chars.indices) {
                val ch = chars[k].toString()
                if (!m.containsKey(ch)) m[ch] = ini
            }
        }
        m
    }

    /** 取单字首字母候选（1~2 个字母；表外字返回空串） */
    fun initialsOf(ch: String): String = map[ch] ?: ""

    /** 生成一个词的所有「拼音首字母缩写」候选（多音字分叉，最多 8 组） */
    fun abbrsOf(word: String): List<String> {
        var out = listOf("")
        for (i in word.indices) {
            val c = word[i].toString()
            val arr = when {
                c[0] in '\u4e00'..'\u9fff' -> {
                    val inis = initialsOf(c)
                    if (inis.isEmpty()) emptyList() else inis.map { it.toString() }
                }
                c[0] in 'a'..'z' || c[0] in '0'..'9' -> listOf(c)
                c[0] in 'A'..'Z' -> listOf(c.lowercase())
                else -> emptyList()
            }
            if (arr.isEmpty()) continue
            val next = ArrayList<String>()
            for (a in out) for (b in arr) next.add(a + b)
            out = next
            if (out.size > 8) break
        }
        return out
    }

    private val T9 = arrayOf("", "", "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz")

    /** 把拼音首字母缩写映射成 T9 九宫格数字串（遥控器数字键输入） */
    fun abbrToT9(abbr: String): String {
        val sb = StringBuilder()
        for (ch in abbr) {
            sb.append(when (ch) {
                in 'a'..'c' -> '2'; in 'd'..'f' -> '3'; in 'g'..'i' -> '4'
                in 'j'..'l' -> '5'; in 'm'..'o' -> '6'; in 'p'..'s' -> '7'
                in 't'..'v' -> '8'; in 'w'..'z' -> '9'
                else -> ch
            })
        }
        return sb.toString()
    }

    /** 数字键对应的字母组（UI 提示），如「2」→「ABC」 */
    fun t9Letters(digit: String): String {
        val d = digit.toIntOrNull() ?: return digit
        return if (d in 2..9) T9[d].uppercase() else digit
    }
}
