---
name: pillow-pptx
description: "基于Pillow位图引擎的PPT生成技能，通过Pillow动态生成定制化图片素材（背景、图标、插图、装饰），创建专业美观的中文演示文稿。适用场景：创建PPT演示文稿、制作幻灯片、设计演示文稿。支持需求澄清、动态图片生成、多风格模板、丰富布局。触发词：生成PPT、创建PPT、制作演示文稿、做幻灯片、做PPT、create presentation、make slides、generate pptx。特点：适合需要复杂渐变、光晕、粒子效果的场景"
---

# Pillow PPTX - 基于位图的PPT生成技能

## 概述

本技能通过 Python 脚本生成专业美观的中文 PPT 演示文稿。核心特色是 **AI 直接编写定制化的 Pillow 绘图代码**，为每个 PPT 生成独特的视觉素材。

**适用场景**：需要复杂渐变、光晕效果、粒子星空、精细像素控制的 PPT。如需矢量不失真效果，请使用 `svg-pptx` 技能。

## 触发条件

当用户要求生成 PPT、创建演示文稿、制作幻灯片时触发本技能。典型触发语句：
- "帮我生成一个关于XX的PPT"
- "创建一个演示文稿"
- "制作一个PPT"
- "做一份幻灯片"

## 工作流程

**重要：严格按以下四个阶段顺序执行。**

---

### 第一阶段：需求澄清（必须执行）

当用户的需求不够具体时，**必须主动询问**以下信息：

1. **主题**：PPT的主题是什么？
2. **页数**：需要多少页？（建议给出默认值，如"建议8-12页"）
3. **风格**：偏好的视觉风格？（提供选项）
   - 商务蓝 - 稳重专业，适合汇报/提案
   - 科技紫 - 现代前沿，适合产品/技术
   - 清新绿 - 自然活力，适合教育/环保
   - 简约灰 - 极简克制，适合数据/分析
   - 创意橙 - 活泼亮眼，适合营销/创意
   - 暗色系 - 深色背景，适合发布会/展示
   - 自定义 - 用户指定配色
4. **目标受众**：给谁看的？
5. **是否需要数据图表？**
6. **其他特殊要求？**

**如果用户已提供充分信息（主题明确、风格清晰），可以直接进入第二阶段。**

---

### 第二阶段：内容规划

根据确认的需求，规划每页的内容结构：

1. 输出页面大纲（标题、内容要点、素材类型）
2. 向用户确认内容方向
3. 标注每页需要的视觉素材类型（背景/图标/插图/装饰）

---

### 第三阶段：生成 Python 脚本

根据内容规划，生成一个完整的 Python 脚本，包含 **内联的 Pillow 绘图代码** 和 **PPTX 组装代码**。

#### 脚本结构

```python
import os, sys, math, random
from PIL import Image, ImageDraw, ImageFont, ImageFilter

# 导入工具模块
SKILL_DIR = r"C:\Users\Lenovo\.claude\skills\pillow-pptx"
sys.path.insert(0, os.path.join(SKILL_DIR, "scripts"))
from img_utils import *
from pptx_helper import *

# === 配置 ===
OUTPUT_DIR = os.path.expanduser("~/Desktop")
PPTX_NAME = "xxx.pptx"
ASSETS_DIR = os.path.join(OUTPUT_DIR, "pptx_assets")
os.makedirs(ASSETS_DIR, exist_ok=True)

# === 1. 图片素材生成（自定义 Pillow 代码）===
# 在这里为每个页面编写定制的绘图代码

# === 2. PPT 组装（使用 pptx_helper）===
# 创建演示文稿，添加各页面

# === 3. 保存 ===
```

#### 图片生成原则

**关键：不要调用任何预设的 generate_* 函数。直接用 Pillow API 编写绘图代码。**

每页都需要视觉素材，至少包括：
- **背景图**：渐变/纹理/几何图案，1920x1080 像素
- **装饰元素**：圆弧、粒子、光晕等增强视觉层次
- **图标**（如需要）：用几何图形组合，贴合主题风格
- **插图**（如需要）：抽象场景图，表达页面核心概念

#### Pillow 绘图指南

以下提供常用绘图模式的代码模板，请根据具体 PPT 主题 **改编和组合** 这些模式。

##### 渐变背景

```python
# 对角线三色渐变背景
c1, c2, c3 = hex_to_rgb("#4A1D8E"), hex_to_rgb("#7C4DFF"), hex_to_rgb("#00D4FF")
img = Image.new('RGBA', (1920, 1080))
draw = ImageDraw.Draw(img)
for y in range(1080):
    for x in range(1920):
        t = (x / 1919 + y / 1079) / 2  # 对角线方向
        if t < 0.5:
            color = lerp_color(c1, c2, t * 2)
        else:
            color = lerp_color(c2, c3, (t - 0.5) * 2)
        draw.point((x, y), fill=color)
```

注意：逐像素绘制较慢。更快的方式是逐行/逐列绘制：

```python
# 水平渐变（快速）
img = Image.new('RGBA', (1920, 1080))
draw_gradient(img, 'horizontal',
    hex_to_rgb("#4A1D8E"),
    hex_to_rgb("#7C4DFF"),
    hex_to_rgb("#00D4FF")
)
```

##### 半透明叠加装饰

```python
# 在渐变背景上叠加装饰圆弧
overlay = Image.new('RGBA', (1920, 1080), (0, 0, 0, 0))
odraw = ImageDraw.Draw(overlay)

# 大光晕 - 右上角
odraw.ellipse([1400, -300, 2220, 520], fill=(255, 255, 255, 20))

# 中等圆弧 - 左下角
odraw.ellipse([-200, 600, 400, 1200], fill=(255, 255, 255, 15))

# 小光点散布
import random
for _ in range(30):
    x, y = random.randint(0, 1920), random.randint(0, 1080)
    r = random.randint(2, 8)
    alpha = random.randint(10, 40)
    odraw.ellipse([x-r, y-r, x+r, y+r], fill=(255, 255, 255, alpha))

img = Image.alpha_composite(img, overlay)
```

##### 几何图案背景

```python
# 六边形网格图案
img = Image.new('RGBA', (1920, 1080), (13, 13, 26, 255))
draw = ImageDraw.Draw(img)
hex_r = 40
row_h = int(hex_r * 1.732)
for row in range(-1, 1080 // row_h + 2):
    for col in range(-1, 1920 // (hex_r * 2) + 2):
        cx = col * hex_r * 2 + (hex_r if row % 2 else 0)
        cy = row * row_h
        pts = []
        for i in range(6):
            angle = math.pi / 6 + i * math.pi / 3
            pts.append((cx + hex_r * math.cos(angle), cy + hex_r * math.sin(angle)))
        draw.polygon(pts, outline=(74, 29, 142, 30), width=1)
```

##### 抽象图标

用 Pillow 几何图形组合表达概念，保持风格统一：

```python
# 科技感"大脑/AI"图标示例
size = 256
img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
draw = ImageDraw.Draw(img)
c = hex_to_rgba("#00D4FF")
cx, cy = size // 2, size // 2

# 外圈
draw.ellipse([cx-90, cy-90, cx+90, cy+90], outline=c[:3] + (80,), width=3)
# 内圈
draw.ellipse([cx-60, cy-60, cx+60, cy+60], outline=c[:3] + (150,), width=3)
# 核心
draw.ellipse([cx-30, cy-30, cx+30, cy+30], fill=c[:3] + (220,))
# 连接节点
for angle in [0, 72, 144, 216, 288]:
    rad = math.radians(angle)
    nx = cx + int(75 * math.cos(rad))
    ny = cy + int(75 * math.sin(rad))
    draw.ellipse([nx-8, ny-8, nx+8, ny+8], fill=c[:3] + (180,))
    draw.line([(cx, cy), (nx, ny)], fill=c[:3] + (60,), width=2)
```

##### 场景插图

用多层几何图形构建抽象场景：

```python
# "数据流动"场景插图
img = Image.new('RGBA', (800, 600), (255, 255, 255, 255))
draw = ImageDraw.Draw(img)

# 底层：柔和背景形状
draw.rounded_rectangle([50, 100, 750, 500], radius=30,
                       fill=(240, 230, 255, 255))

# 中层：数据管道/流动曲线
points = [(80, 300), (200, 200), (400, 350), (600, 180), (720, 300)]
for i in range(len(points) - 1):
    draw.line([points[i], points[i+1]], fill=(74, 29, 142, 180), width=4)
    # 节点
    draw.ellipse([points[i][0]-10, points[i][1]-10,
                  points[i][0]+10, points[i][1]+10],
                 fill=(0, 212, 255, 220))

# 顶层：光晕和标注
for px, py in points:
    draw.ellipse([px-25, py-25, px+25, py+25], fill=(0, 212, 255, 30))
```

---

### 第四阶段：执行脚本

使用 Bash 工具执行生成的 Python 脚本。

**安装依赖（首次）：**
```bash
pip install python-pptx Pillow
```

---

## 配色方案

| 风格 | 主色 | 辅色 | 强调色 | 背景色 | 文字色 |
|------|------|------|--------|--------|--------|
| 商务蓝 | #1B3A5C | #E8F0FE | #FF6B35 | #F5F7FA | #2C3E50 |
| 科技紫 | #4A1D8E | #F0E6FF | #00D4FF | #0D0D1A | #E0E0E0 |
| 清新绿 | #2D7D46 | #E8F5E9 | #FFB74D | #F1F8E9 | #37474F |
| 简约灰 | #37474F | #ECEFF1 | #FF7043 | #FAFAFA | #37474F |
| 创意橙 | #E65100 | #FFF3E0 | #7C4DFF | #FFF8E1 | #4E342E |
| 暗色系 | #BB86FC | #1E1E2E | #03DAC6 | #121212 | #E0E0E0 |

---

## PPTX 组装

使用 `scripts/pptx_helper.py` 组装 PPT。主要函数：

```python
from pptx_helper import (
    create_presentation,          # 创建演示文稿
    add_rich_title_slide,         # 封面页（背景+装饰图）
    add_icon_content_slide,       # 图标内容页（图标+文字）
    add_illustrated_slide,        # 插图内容页（左文右图/右文左图）
    add_chart_slide,              # 图表页（柱/线/饼）
    add_quote_slide,              # 引用页
    add_section_slide,            # 章节分隔页
    add_bullet_slide,             # 要点列表页
    add_end_slide,                # 结尾页
    save_presentation,            # 保存文件
    COLOR_SCHEMES,                # 配色方案
)
```

详细 API 参考 `scripts/pptx_helper.py`。

---

## 设计规范

### 布局
- 16:9 宽屏（10" x 5.625"）
- 标题：36-44pt，正文：16-20pt
- 左右留白 ≥ 0.8 英寸，上下留白 ≥ 0.5 英寸
- 每页不超过 6 个要点

### 视觉层次
- 标题 > 副标题 > 正文 > 注释
- 重要信息用强调色高亮
- 图文比建议 图40% + 文60%

### 背景设计原则
- 封面页：丰富渐变 + 多层装饰
- 内容页：简洁纹理或淡色底部装饰
- 章节页：中等渐变
- 结尾页：与封面呼应
- **每页必须有背景，禁止纯白背景**

### 图标设计原则
- 风格统一（同一种几何语言）
- 颜色与页面主色或强调色一致
- 大小统一，建议 256x256 生成

### 图片生成性能
- 背景图 1920x1080，图标 256x256，插图 800x600
- 逐像素操作较慢，优先用 `draw.line()` / `draw_gradient()` 逐行绘制
- 每个背景图生成应控制在 3 秒以内

---

## 常见问题

**依赖安装？**
```bash
pip install python-pptx Pillow
```

**中文字体异常？**
- 使用"微软雅黑"字体，避免系统中未安装的字体
- 在 pptx_helper.py 中可修改 DEFAULT_FONT

**PPT 文件太大？**
- 减少大尺寸插图数量
- 控制图片尺寸（背景 1920x1080 足够）

---

## 更多绘图模式

详细的 Pillow 绘图代码模板和灵感，参见 `references/pillow-patterns.md`。
