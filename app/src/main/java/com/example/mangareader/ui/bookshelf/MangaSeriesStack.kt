package com.example.mangareader.ui.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.mangareader.ui.bookshelf.theme.BambooPalette

/**
 * 书架上「一套漫画」的堆叠效果：底下垫 1~2 层书脊制造厚度，顶层放主封面。
 *
 * @param coverUrl 封面（Coil 支持的任意 model：Uri / File / String / [MangaPageModel] 等）
 * @param totalVolumes 总册数，>= 2 才会出现垫层
 */
@Composable
fun MangaSeriesStack(
    coverUrl: Any?,
    totalVolumes: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(top = 12.dp, bottom = 4.dp)
            .aspectRatio(0.72f), // 经典漫画单行本 1:1.4 左右的高宽比
        contentAlignment = Alignment.BottomCenter,
    ) {
        // 底层书籍 3 (深陷微旋，营造套书厚度)
        if (totalVolumes >= 3) {
            Box(
                modifier = Modifier
                    .fillMaxSize(0.92f)
                    .offset(x = 6.dp, y = (-8).dp)
                    .rotate(3f)
                    .shadow(4.dp, RoundedCornerShape(3.dp))
                    .background(Color(0xFFE2DDD3), RoundedCornerShape(3.dp)),
            )
        }

        // 底层书籍 2 (中间层)
        if (totalVolumes >= 2) {
            Box(
                modifier = Modifier
                    .fillMaxSize(0.95f)
                    .offset(x = 3.dp, y = (-4).dp)
                    .rotate(1.5f)
                    .shadow(5.dp, RoundedCornerShape(3.dp))
                    .background(Color(0xFFECE7DE), RoundedCornerShape(3.dp)),
            )
        }

        // 顶层主封面 (正在读或第 1 卷)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(4.dp),
                    spotColor = Color(0x66000000),
                ),
        ) {
            // 每次重组都重建 ImageRequest 会让 Coil 重新比对请求，套在 remember 里。
            // 注意：LocalContext.current 本身是 @Composable，必须在 remember 外面取，
            // 否则报 "@Composable invocations can only happen from the context of a @Composable function"
            val context = LocalContext.current
            val request = remember(coverUrl, context) {
                ImageRequest.Builder(context)
                    .data(coverUrl)
                    .crossfade(true)
                    .build()
            }

            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF2C2C34), RoundedCornerShape(4.dp)),
            )

            // 书本左侧装订中缝微高光（仿胶装单行本的反光柱）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.horizontalGradient(
                            0.0f to Color.White.copy(alpha = 0.25f),
                            0.04f to Color.White.copy(alpha = 0.05f),
                            0.08f to Color.Transparent,
                        ),
                    ),
            )

            // 套装册数标牌（如：12 卷）
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.75f),
                        shape = RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "${totalVolumes}P",
                    color = BambooPalette.BambooLight,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
