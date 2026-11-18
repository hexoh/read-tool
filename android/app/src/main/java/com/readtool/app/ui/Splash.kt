package com.readtool.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 品牌标记：靛青圆角方块 + 纸白「字」。
 *
 * 字形尺寸按 dp 折算成 sp（除以 fontScale），否则用户把系统字号调到 150% 时
 * 字会撑出色块 —— 图标不该跟着无障碍字号缩放，可读性由下面的标题承担。
 */
@Composable
fun BrandMark(size: Dp = 76.dp) {
    val glyphSize = with(LocalDensity.current) { (size.toPx() * 0.518f).toSp() }
    Box(
        Modifier.size(size).background(Primary, RoundedCornerShape(size * 0.31f)),
        contentAlignment = Alignment.Center,
    ) {
        // 行盒（ascent/descent）不是墨迹框：宋体「字」按行盒居中会偏低约 2.5%，
        // 往上一抬才与图标里的字形位置对齐（实测值，勿随手改）
        Text(
            "字",
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,   // 与图标里的宋体 Bold 同字重，Regular 会显得虚
            fontSize = glyphSize,
            color = Paper,
            modifier = Modifier.offset(y = -(size * 0.025f)),
        )
    }
}

/**
 * 启动页：字库与发音包（约 20MB）载入期间显示。
 *
 * 与系统启动画面同画面 —— 纸色底、靛青标记，API 31 以上从系统 splash 交接过来不跳色。
 * 不做假延迟：载完就淡出，慢的机器多停一会儿是应该的。
 */
@Composable
fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(
            Modifier.fillMaxSize().padding(bottom = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BrandMark(84.dp)
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("识", fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, color = Ink)
                Spacer(Modifier.width(6.dp))
                Text("字", fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, color = Ink)
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.width(24.dp).height(1.dp).background(LineC))
            Spacer(Modifier.height(14.dp))
            Text("拍照 · 点字 · 听读音", fontSize = 12.sp, color = Ink3)
        }

        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LinearProgressIndicator(
                modifier = Modifier.width(96.dp).height(2.dp),
                color = Primary,
                trackColor = LineC,
            )
            Spacer(Modifier.height(12.dp))
            Text("正在装入字库与发音包…", fontSize = 11.sp, color = Ink3)
        }
    }
}
