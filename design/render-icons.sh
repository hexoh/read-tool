#!/usr/bin/env bash
# 识字 · 图标与品牌图导出
#
# 用法：bash design/render-icons.sh
# 依赖：rsvg-convert（brew install librsvg）、字体 Songti SC（macOS 自带）
#      换了看不到宋体的机器，输出会退化成别的衬线体——所以导出的 PNG 已入库，
#      只有在改设计时才需要重跑本脚本。
#
# 产物：
#   android/.../res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_foreground.png  自适应图标前层
#   android/.../res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_monochrome.png  Android 13 主题图标
#   design/store/play-icon-512.png    商店列表图标（512×512 不透明）
#   design/out/*.png                  文档 / 预览用图
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
RES="$ROOT/android/app/src/main/res"
LOGO="$DIR/logo"

command -v rsvg-convert >/dev/null || { echo "缺少 rsvg-convert：brew install librsvg"; exit 1; }
[ -f "$LOGO/logo-mark.svg" ] || { echo "找不到 $LOGO/logo-mark.svg"; exit 1; }

mkdir -p "$DIR/store" "$DIR/out"

# 108dp 画布在五档密度下的像素尺寸（自适应图标固定 108dp）
for pair in mdpi:108 hdpi:162 xhdpi:216 xxhdpi:324 xxxhdpi:432; do
  d="${pair%%:*}"; px="${pair##*:}"
  mkdir -p "$RES/mipmap-$d"
  rsvg-convert -w "$px" -h "$px" "$LOGO/logo-glyph-paper.svg" -o "$RES/mipmap-$d/ic_launcher_foreground.png"
  rsvg-convert -w "$px" -h "$px" "$LOGO/logo-glyph-mono.svg"  -o "$RES/mipmap-$d/ic_launcher_monochrome.png"
  echo "  mipmap-$d  ${px}px"
done

rsvg-convert -w 512  -h 512  "$LOGO/logo-mark.svg"     -o "$DIR/store/play-icon-512.png"
rsvg-convert -w 1024 -h 1024 "$LOGO/logo-mark.svg"     -o "$DIR/out/mark-1024.png"
rsvg-convert -w 640  -h 200  "$LOGO/logo-lockup-h.svg" -o "$DIR/out/lockup-h.png"
rsvg-convert -w 400  -h 440  "$LOGO/logo-lockup-v.svg" -o "$DIR/out/lockup-v.png"

echo "完成：mipmap 前层 / 单色层、store 图标、out 预览图"
