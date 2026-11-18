"""端到端验证 Android 端 OCR 管线（det → DB 后处理 → 竖排旋转 → rec → CTC）。

不依赖 Android 设备，用 Python + onnxruntime 复现 Kotlin 侧同一套参数，
用于模型升级或调参后快速回归。运行：python3 tools/ocr/verify_ocr.py

模型来源（Apache-2.0）：
  当前用 PP-OCRv6_tiny（det 1.7MB / rec 4.3MB / 字符集 6904）
  det/rec: https://hf-mirror.com/PaddlePaddle/PP-OCRv6_tiny_{det,rec}_onnx → inference.onnx
  keys   : 从 rec 的 inference.yml 的 PostProcess.character_dict 提取，存为 assets/models/keys.txt
  需要回退 v4 时按上面同规则从 SWHL/RapidOCR 的 PP-OCRv4 目录重下（本地不留备份）
"""
import numpy as np
import onnxruntime as ort
from PIL import Image, ImageDraw, ImageFont

MODELS = "/Users/lenovo/Code/read-tool/android/app/src/main/assets/models"
FONT = "/System/Library/Fonts/Supplemental/Songti.ttc"


def make_image(vertical=False):
    W, H = 640, 420
    img = Image.new("RGB", (W, H), (246, 241, 228))
    d = ImageDraw.Draw(img)
    font = ImageFont.truetype(FONT, 64, index=0)
    if vertical:
        cols = ["般若波罗蜜", "多心经"]
        for ci, col in enumerate(cols):
            x = W - 100 - ci * 100
            for i, ch in enumerate(col):
                d.text((x, 30 + i * 72), ch, font=font, fill=(42, 39, 36))
    else:
        d.text((40, 60), "识字工具离线识别", font=font, fill=(42, 39, 36))
        d.text((40, 200), "般若波罗蜜多心经", font=font, fill=(42, 39, 36))
    return img


def det_input(img, max_side=960):
    ow, oh = img.size
    fit = min(max_side / ow, max_side / oh)
    rw = max(32, (round(ow * fit) // 32) * 32)
    rh = max(32, (round(oh * fit) // 32) * 32)
    im = img.resize((rw, rh), Image.BILINEAR)
    a = np.asarray(im).astype(np.float32) / 255.0
    mean = np.array([0.485, 0.456, 0.406], dtype=np.float32)
    std = np.array([0.229, 0.224, 0.225], dtype=np.float32)
    a = (a - mean) / std
    x = a.transpose(2, 0, 1)[None].astype(np.float32)
    return x, (ow, oh, rw, rh)


def find_boxes(prob, thresh=0.3, min_side=4):
    h, w = prob.shape
    vis = np.zeros((h, w), bool)
    out = []
    for y in range(h):
        for x in range(w):
            if vis[y, x] or prob[y, x] < thresh:
                continue
            stack = [(y, x)]
            vis[y, x] = True
            x0 = x1 = x
            y0 = y1 = y
            cnt = 0
            s = 0.0
            while stack:
                py, px = stack.pop()
                cnt += 1
                s += prob[py, px]
                x0 = min(x0, px); x1 = max(x1, px)
                y0 = min(y0, py); y1 = max(y1, py)
                for dy in (-1, 0, 1):
                    for dx in (-1, 0, 1):
                        ny, nx = py + dy, px + dx
                        if 0 <= ny < h and 0 <= nx < w and not vis[ny, nx] and prob[ny, nx] >= thresh:
                            vis[ny, nx] = True
                            stack.append((ny, nx))
            if cnt >= 16 and s / cnt >= 0.5 and x1 - x0 >= min_side and y1 - y0 >= min_side:
                out.append((x0, y0, x1, y1))
    return out


def rec_line(img, box, vertical, sess, keys):
    x0, y0, x1, y1 = box
    crop = img.crop((x0, y0, x1, y1))
    if vertical:
        crop = crop.rotate(90, expand=True)
    th = 48
    # 与 Android 端一致：识别模型按 320 宽训练，放宽反而掉精度（实测 480 更差）
    tw = max(16, min(320, round(th * crop.width / crop.height)))
    crop = crop.resize((tw, th), Image.BILINEAR)
    a = np.asarray(crop).astype(np.float32) / 255.0
    a = (a - 0.5) / 0.5
    x = a.transpose(2, 0, 1)[None].astype(np.float32)
    logits = sess.run(None, {sess.get_inputs()[0].name: x})[0][0]
    idx = logits.argmax(axis=1)
    out = []
    prev = -1
    for i in idx:
        i = int(i)
        if i != 0 and i != prev:
            out.append(keys[i - 1] if 1 <= i <= len(keys) else " ")
        prev = i
    return "".join(out)


def run(vertical):
    img = make_image(vertical)
    x, (ow, oh, rw, rh) = det_input(img)
    sess_det = ort.InferenceSession(f"{MODELS}/det.onnx", providers=["CPUExecutionProvider"])
    prob = sess_det.run(None, {sess_det.get_inputs()[0].name: x})[0][0][0]
    sx, sy = ow / rw, oh / rh
    bs = find_boxes(prob)
    sess_rec = ort.InferenceSession(f"{MODELS}/rec.onnx", providers=["CPUExecutionProvider"])
    keys = open(f"{MODELS}/keys.txt", encoding="utf-8").read().split("\n")
    print(f"=== {'竖排' if vertical else '横排'} 检测到 {len(bs)} 个框 ===")
    res = []
    for b in bs:
        rawW, rawH = (b[2] - b[0]) * sx, (b[3] - b[1]) * sy
        pad = min(rawW, rawH) * 0.15
        X0 = max(0, int(b[0] * sx - pad)); Y0 = max(0, int(b[1] * sy - pad))
        X1 = min(ow, int(b[2] * sx + pad)); Y1 = min(oh, int(b[3] * sy + pad))
        v = (Y1 - Y0) > (X1 - X0) * 1.25
        t = rec_line(img, (X0, Y0, X1, Y1), v, sess_rec, keys)
        res.append((Y0, -X0, t, v, (X0, Y0, X1, Y1)))
    for r in sorted(res):
        print(f"  {'竖' if r[3] else '横'}  {r[2]}   box={r[4]}")
    print()


run(False)
run(True)
