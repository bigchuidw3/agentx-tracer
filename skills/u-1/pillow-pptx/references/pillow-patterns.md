# Pillow 绘图模式库

AI 生成 PPT 时的视觉素材代码模板。每个模式都可自由改编参数和配色。

---

## 1. 背景模式

### 1.1 三色对角线渐变

```python
from PIL import Image, ImageDraw
from img_utils import hex_to_rgb, lerp_color

def make_gradient_bg(colors_hex, output_path, w=1920, h=1080):
    """多色对角线渐变背景"""
    colors = [hex_to_rgb(c) for c in colors_hex]
    img = Image.new('RGB', (w, h))
    draw = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(h - 1, 1)
        n = len(colors) - 1
        idx = min(int(t * n), n - 1)
        local_t = t * n - idx
        color = lerp_color(colors[idx], colors[idx + 1], local_t)
        draw.line([(0, y), (w - 1, y)], fill=color)
    img.save(output_path, 'PNG')

# 用法
make_gradient_bg(["#0D0D1A", "#4A1D8E", "#7C4DFF"], "bg.png")
```

### 1.2 径向渐变 + 装饰圆

```python
from PIL import Image, ImageDraw
from img_utils import hex_to_rgb, hex_to_rgba, draw_radial_gradient

def make_radial_bg(base_color, center_color, accent_rgba, output_path, w=1920, h=1080):
    """径向渐变 + 装饰半透明圆"""
    img = Image.new('RGBA', (w, h), hex_to_rgba(base_color, 1.0))
    draw_radial_gradient(img, (w // 4, h // 4), int(w * 0.7),
                         hex_to_rgba(base_color, 1.0),
                         hex_to_rgba(center_color, 0.6))
    # 叠加装饰
    overlay = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    odraw = ImageDraw.Draw(overlay)
    odraw.ellipse([w - 500, -200, w + 100, 400], fill=accent_rgba)
    odraw.ellipse([-300, h - 500, 200, h + 100], fill=accent_rgba)
    img = Image.alpha_composite(img, overlay)
    img.convert('RGB').save(output_path, 'PNG')

# 用法
make_radial_bg("#0D0D1A", "#4A1D8E", (255, 255, 255, 20), "bg_radial.png")
```

### 1.3 网格纹理

```python
import math
from PIL import Image, ImageDraw
from img_utils import hex_to_rgba

def make_grid_bg(bg_color, line_color_hex, output_path, w=1920, h=1080, spacing=80):
    """网格线纹理背景"""
    img = Image.new('RGBA', (w, h), hex_to_rgba(bg_color, 1.0))
    draw = ImageDraw.Draw(img)
    lc = hex_to_rgba(line_color_hex, 0.08)
    for x in range(0, w + spacing, spacing):
        draw.line([(x, 0), (x, h)], fill=lc, width=1)
    for y in range(0, h + spacing, spacing):
        draw.line([(0, y), (w, y)], fill=lc, width=1)
    img.convert('RGB').save(output_path, 'PNG')
```

### 1.4 粒子星空

```python
import random
from PIL import Image, ImageDraw
from img_utils import hex_to_rgba, draw_gradient

def make_starfield_bg(colors_hex, output_path, w=1920, h=1080, stars=120):
    """粒子星空背景"""
    colors = [hex_to_rgba(c, 1.0) for c in colors_hex]
    img = Image.new('RGBA', (w, h))
    draw_gradient(img, 'horizontal', *colors)
    draw = ImageDraw.Draw(img)
    for _ in range(stars):
        x, y = random.randint(0, w), random.randint(0, h)
        r = random.randint(1, 4)
        alpha = random.randint(30, 120)
        draw.ellipse([x-r, y-r, x+r, y+r], fill=(255, 255, 255, alpha))
    # 大光晕
    for _ in range(5):
        x, y = random.randint(0, w), random.randint(0, h)
        r = random.randint(40, 120)
        draw.ellipse([x-r, y-r, x+r, y+r], fill=(255, 255, 255, 8))
    img.convert('RGB').save(output_path, 'PNG')
```

### 1.5 波浪分割

```python
from PIL import Image, ImageDraw
from img_utils import hex_to_rgb, hex_to_rgba, lerp_color, draw_gradient

def make_wave_bg(top_colors, bottom_color, output_path, w=1920, h=1080):
    """波浪分割线背景"""
    colors = [hex_to_rgba(c, 1.0) for c in top_colors]
    img = Image.new('RGBA', (w, h))
    draw_gradient(img, 'horizontal', *colors)

    bottom = Image.new('RGBA', (w, h), hex_to_rgba(bottom_color, 1.0))
    bdraw = ImageDraw.Draw(bottom)

    # 绘制波浪路径（上方留白，下方填充底色）
    wave_pts = []
    for x in range(w + 1):
        y = int(h * 0.65 + 40 * math.sin(x * 2 * math.pi / w) +
                20 * math.sin(x * 4 * math.pi / w + 1))
        wave_pts.append((x, y))
    wave_pts.append((w, h))
    wave_pts.append((0, h))
    bdraw.polygon(wave_pts, fill=hex_to_rgba(bottom_color, 1.0))

    img = Image.alpha_composite(img, bottom)
    img.convert('RGB').save(output_path, 'PNG')
```

---

## 2. 图标模式

图标统一为 256x256 像素，RGBA 模式（透明背景）。

### 2.1 圆形徽章图标

```python
def make_badge_icon(symbol, color_hex, output_path, size=256):
    """圆形徽章图标，中心放置文字符号"""
    from PIL import Image, ImageDraw, ImageFont
    from img_utils import hex_to_rgb, hex_to_rgba
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    c = hex_to_rgb(color_hex)
    cx, cy = size // 2, size // 2
    r = size // 2 - 10
    # 外圈光晕
    draw.ellipse([cx-r-10, cy-r-10, cx+r+10, cy+r+10], fill=c + (30,))
    # 主体圆
    draw.ellipse([cx-r, cy-r, cx+r, cy+r], fill=c + (220,))
    # 内部高光
    draw.ellipse([cx-r+15, cy-r+15, cx+r-15, cy+r-15], fill=(255, 255, 255, 25))
    # 中心符号
    try:
        font = ImageFont.truetype("msyh.ttc", size // 3)
    except:
        font = ImageFont.load_default()
    draw.text((cx, cy), symbol, fill=(255, 255, 255, 240),
              font=font, anchor="mm")
    img.save(output_path, 'PNG')
```

### 2.2 几何线条图标

```python
def make_line_icon(paths, color_hex, output_path, size=256, stroke=6):
    """线条风格图标，paths 为路径列表"""
    from PIL import Image, ImageDraw
    from img_utils import hex_to_rgb
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    c = hex_to_rgb(color_hex)
    for path in paths:
        if len(path) == 2:
            # 线段
            draw.line([path[0], path[1]], fill=c + (255,), width=stroke)
        elif len(path) == 4:
            # 矩形
            draw.rectangle(path, outline=c + (255,), width=stroke)
    img.save(output_path, 'PNG')
```

### 2.3 同心环形图标

```python
def make_ring_icon(rings, center_fill, output_path, size=256):
    """同心环图标"""
    from PIL import Image, ImageDraw
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    cx, cy = size // 2, size // 2
    for r, color, width in rings:
        draw.ellipse([cx-r, cy-r, cx+r, cy+r], outline=color, width=width)
    if center_fill:
        cr = rings[-1][0] - rings[-1][2]
        draw.ellipse([cx-cr, cy-cr, cx+cr, cy+cr], fill=center_fill)
    img.save(output_path, 'PNG')
```

---

## 3. 场景插图模式

插图统一为 800x600 像素，RGBA 模式。

### 3.1 抽象网络图

```python
import random, math
from PIL import Image, ImageDraw
from img_utils import hex_to_rgba

def make_network_illustration(node_count, colors_hex, output_path, w=800, h=600):
    """抽象网络节点图"""
    img = Image.new('RGBA', (w, h), (255, 255, 255, 0))
    draw = ImageDraw.Draw(img)
    c1, c2 = [hex_to_rgba(c, 1.0) for c in colors_hex[:2]]

    # 生成随机节点
    nodes = [(random.randint(80, w-80), random.randint(80, h-80),
              random.randint(20, 50)) for _ in range(node_count)]

    # 连线（距离近的节点互连）
    for i, (x1, y1, _) in enumerate(nodes):
        for j, (x2, y2, _) in enumerate(nodes):
            if i < j and math.hypot(x2-x1, y2-y1) < 250:
                draw.line([(x1, y1), (x2, y2)], fill=c1[:3] + (60,), width=2)

    # 绘制节点
    for x, y, r in nodes:
        # 外层光晕
        draw.ellipse([x-r-8, y-r-8, x+r+8, y+r+8], fill=c2[:3] + (25,))
        # 节点
        draw.ellipse([x-r, y-r, x+r, y+r], fill=c1[:3] + (180,))

    img.save(output_path, 'PNG')
```

### 3.2 数据趋势图

```python
def make_trend_illustration(values, colors_hex, output_path, w=800, h=600):
    """手绘风格趋势图"""
    from PIL import Image, ImageDraw
    from img_utils import hex_to_rgb, lerp_color
    img = Image.new('RGBA', (w, h), (255, 255, 255, 255))
    draw = ImageDraw.Draw(img)

    c_fill = hex_to_rgb(colors_hex[0])
    c_line = hex_to_rgb(colors_hex[1])
    c_dot = hex_to_rgb(colors_hex[1])

    # 坐标系
    margin_l, margin_b = 80, 60
    chart_w = w - margin_l - 40
    chart_h = h - margin_b - 60

    draw.line([(margin_l, 60), (margin_l, h - margin_b)], fill=(200, 200, 200), width=2)
    draw.line([(margin_l, h - margin_b), (w - 40, h - margin_b)], fill=(200, 200, 200), width=2)

    # 数据点
    max_v = max(values)
    points = []
    for i, v in enumerate(values):
        x = margin_l + int(i / max(len(values) - 1, 1) * chart_w)
        y = (h - margin_b) - int(v / max_v * chart_h)
        points.append((x, y))

    # 填充区域
    fill_pts = points + [(points[-1][0], h - margin_b), (points[0][0], h - margin_b)]
    draw.polygon(fill_pts, fill=c_fill + (30,))
    # 趋势线
    draw.line(points, fill=c_line, width=4)
    # 数据点
    for px, py in points:
        draw.ellipse([px-8, py-8, px+8, py+8], fill=c_dot)
    img.save(output_path, 'PNG')
```

### 3.3 柱状对比图

```python
def make_bars_illustration(data, colors_hex, output_path, w=800, h=600):
    """手绘风格柱状图"""
    from PIL import Image, ImageDraw
    from img_utils import hex_to_rgba
    img = Image.new('RGBA', (w, h), (255, 255, 255, 255))
    draw = ImageDraw.Draw(img)

    margin_l, margin_b = 80, 60
    chart_h = h - margin_b - 80
    bar_w = max(20, (w - margin_l - 40) // (len(data) * 2))
    max_v = max(d[1] for d in data)
    colors = [hex_to_rgba(c, 220) for c in colors_hex]

    for i, (label, value) in enumerate(data):
        x = margin_l + i * (bar_w * 2) + bar_w // 2
        bar_h = int(value / max_v * chart_h)
        color = colors[i % len(colors)]
        draw.rounded_rectangle(
            [x, h - margin_b - bar_h, x + bar_w, h - margin_b],
            radius=5, fill=color
        )

    draw.line([(margin_l, h - margin_b), (w - 40, h - margin_b)], fill=(200, 200, 200), width=2)
    img.save(output_path, 'PNG')
```

---

## 4. 装饰元素模式

### 4.1 角落装饰

```python
def make_corner_decoration(color_hex, output_path, w=400, h=400, corner='lt'):
    """角落弧线装饰"""
    from PIL import Image, ImageDraw
    from img_utils import hex_to_rgba
    img = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    c = hex_to_rgba(color_hex, 0.8)

    if corner == 'lt':
        draw.arc([-w//2, -h//2, w//2, h//2], 270, 360, fill=c, width=4)
        draw.arc([-w//3, -h//3, w//3, h//3], 270, 360, fill=c, width=3)
    elif corner == 'rt':
        draw.arc([w//2, -h//2, w*3//2, h//2], 180, 270, fill=c, width=4)
    elif corner == 'lb':
        draw.arc([-w//2, h//2, w//2, h*3//2], 0, 90, fill=c, width=4)
    elif corner == 'rb':
        draw.arc([w//2, h//2, w*3//2, h*3//2], 90, 180, fill=c, width=4)

    img.save(output_path, 'PNG')
```

### 4.2 分隔线

```python
def make_divider(color_hex, output_path, w=600, h=20):
    """圆点分隔线"""
    from PIL import Image, ImageDraw
    from img_utils import hex_to_rgb
    img = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    c = hex_to_rgb(color_hex)
    cy = h // 2
    draw.line([(0, cy), (w//3, cy)], fill=c + (200,), width=2)
    draw.ellipse([w//2-5, cy-5, w//2+5, cy+5], fill=c + (220,))
    draw.line([(w*2//3, cy), (w, cy)], fill=c + (200,), width=2)
    img.save(output_path, 'PNG')
```

---

## 5. 配色方案速查

| 风格 | 主色 | 辅色 | 强调色 | 背景色 |
|------|------|------|--------|--------|
| 商务蓝 | #1B3A5C | #E8F0FE | #FF6B35 | #F5F7FA |
| 科技紫 | #4A1D8E | #F0E6FF | #00D4FF | #0D0D1A |
| 清新绿 | #2D7D46 | #E8F5E9 | #FFB74D | #F1F8E9 |
| 简约灰 | #37474F | #ECEFF1 | #FF7043 | #FAFAFA |
| 创意橙 | #E65100 | #FFF3E0 | #7C4DFF | #FFF8E1 |
| 暗色系 | #BB86FC | #1E1E2E | #03DAC6 | #121212 |
