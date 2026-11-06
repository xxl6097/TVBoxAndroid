package com.hong.tvbox.ui

import android.content.Context
import com.hong.tvbox.core.CmsClient
import com.hong.tvbox.core.History
import com.hong.tvbox.data.ConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 「继续观看」的统一续播逻辑。
 *
 * 历史里只存了 vodId/siteKey/线路/集数/进度，没有播放地址；
 * 续播必须先回站点拉一次详情拿到剧集地址，再跳到对应线路和集。
 * 首页和历史页两处都要用，抽出来避免两份实现走偏。
 */
object PlaybackResumer {

    fun resume(
        scope: CoroutineScope,
        context: Context,
        configStore: ConfigStore,
        h: History,
        onError: (String) -> Unit = {}
    ) {
        val site = configStore.getConfig()?.sites?.find { it.key == h.siteKey }
        if (site == null) {
            onError("该站源已不在配置里")
            return
        }

        scope.launch {
            try {
                val vod = CmsClient(site).detail(h.vodId)
                val lines = CmsClient.splitLines(vod.from, vod.playUrl)
                if (lines.isEmpty()) {
                    onError("该片没有可播放地址")
                    return@launch
                }

                val lineIndex = h.lineIndex.coerceIn(0, lines.size - 1)
                val eps = lines[lineIndex].eps
                val epIndex = h.episodeIndex.coerceIn(0, eps.size - 1)

                PlayerActivity.start(
                    context = context,
                    vodId = h.vodId,
                    siteKey = h.siteKey,
                    vodName = h.vodName.ifEmpty { vod.name },
                    lineName = lines[lineIndex].name,
                    allEpisodes = eps,
                    episodeIndex = epIndex,
                    isDirect = lines[lineIndex].direct,
                    siteApi = site.api,
                    startPosition = h.position,
                    vodPic = h.vodPic.ifEmpty { vod.pic },
                    lineIndex = lineIndex
                )
            } catch (e: Exception) {
                onError("续播失败：${e.message}")
            }
        }
    }
}
