---
name: svg-pptx
description: "基于SVG矢量引擎的PPT生成技能，通过AI直接编写SVG代码生成矢量图片素材（背景、图标、插图、装饰），创建专业美观的中文演示文稿。适用场景：创建PPT演示文稿、制作幻灯片、设计演示文稿。触发词：svg生成PPT、矢量PPT、create SVG presentation。特点：矢量不失真、文件更小、生成速度更快、LLM生成SVG更自然"
---

# SVG PPTX - 矢量PPT生成技能

## 重要：API 容错设计

**所有函数都接受 `**kwargs`，传错参数名不会报 TypeError。** 以下写法都能正常运行：

```python
# bg_image 是标准参数名，但以下别名也都行：
add_rich_title_slide(prs, "标题", bg_image="bg.png")          # 标准写法
add_rich_title_slide(prs, "标题", background_image="bg.png")   # 别名也行
add_rich_title_slide(prs, "标题", background_svg="bg.png")     # 别名也行
add_rich_title_slide(prs, "标题", bg="bg.png")                 # 别名也行

# make_dark_tech_bg 的 accent_color 也接受别名：
make_dark_tech_bg("bg.svg", accent_color="#FF0000")   # 标准写法
make_dark_tech_bg("bg.svg", colors="#FF0000")          # 别名也行
make_dark_tech_bg("bg.svg", accent="#FF0000")          # 别名也行
make_dark_tech_bg("bg.svg", color="#FF0000")           # 别名也行

# 传了不存在的参数也不会报错，会被静默忽略
add_bullet_slide(prs, "标题", bullets=["a"], unknown_param="xxx")
```

**此外，传入 SVG 文件路径时函数会自动转 PNG**，不需要手动调用 `svg_to_png` 再传给 pptx 函数。

---

## 工作流程

1. **需求澄清** → 如用户信息不完整，询问主题/页数/风格/受众
2. **内容规划** → 输出每页大纲，向用户确认
3. **生成脚本** → 生成一个完整的 Python 脚本（见下方模板）
4. **执行脚本** → 用 Bash 工具运行脚本

如果用户已提供充分信息，跳过澄清直接执行。

---

## 完整脚本模板（必须严格按此结构）

```python
import os, sys

SKILL_DIR = r"C:\Users\Lenovo\.claude\skills\svg-pptx"
sys.path.insert(0, os.path.join(SKILL_DIR, "scripts"))
from svg_utils import *
from pptx_helper import *

# ===== 配置 =====
OUTPUT_DIR = os.path.expanduser("~/Desktop")
PPTX_NAME = "xxx.pptx"   # 改为实际文件名
ASSETS_DIR = os.path.join(OUTPUT_DIR, "pptx_svg_assets")
os.makedirs(ASSETS_DIR, exist_ok=True)

# ===== 第1步：生成SVG素材并转PNG =====

# 快速生成科技风背景（一行搞定）
make_dark_tech_bg(os.path.join(ASSETS_DIR, "bg_cover.svg"))
svg_to_png(os.path.join(ASSETS_DIR, "bg_cover.svg"), os.path.join(ASSETS_DIR, "bg_cover.png"))

# 快速生成渐变背景
make_gradient_bg(["#0A0A1A", "#1A1035", "#0D1B2A"], os.path.join(ASSETS_DIR, "bg_content.svg"))
svg_to_png(os.path.join(ASSETS_DIR, "bg_content.svg"), os.path.join(ASSETS_DIR, "bg_content.png"))

# ===== 第2步：组装PPT =====
prs = create_presentation("PPT标题")

# 封面页（bg_image / background_image / background_svg / bg 都可以）
add_rich_title_slide(prs, "主标题", subtitle="副标题",
                     bg_image=os.path.join(ASSETS_DIR, "bg_cover.png"))

# 要点列表页
add_bullet_slide(prs, "要点标题", bullets=["要点1", "要点2", "要点3"],
                 bg_image=os.path.join(ASSETS_DIR, "bg_content.png"))

# 图标内容页
add_icon_content_slide(prs, "特性标题",
    items=[
        {"title": "特性1", "desc": "描述1", "icon": os.path.join(ASSETS_DIR, "icon1.png")},
        {"title": "特性2", "desc": "描述2", "icon": os.path.join(ASSETS_DIR, "icon2.png")},
    ],
    bg_image=os.path.join(ASSETS_DIR, "bg_content.png"))

# 插图页（layout="left" 左文右图, layout="right" 右文左图）
add_illustrated_slide(prs, "标题", content=["内容1", "内容2"],
                      illustration_image=os.path.join(ASSETS_DIR, "illust.png"),
                      layout="left",
                      bg_image=os.path.join(ASSETS_DIR, "bg_content.png"))

# 图表页（chart_type: "bar" / "column" / "line" / "pie"）
add_chart_slide(prs, "图表标题", chart_type="column",
    categories=["Q1", "Q2", "Q3", "Q4"],
    series_data={"收入": [100, 200, 150, 300], "成本": [80, 120, 100, 180]},
    bg_image=os.path.join(ASSETS_DIR, "bg_content.png"))

# 引用页
add_quote_slide(prs, quote="引用内容", author="作者",
                bg_image=os.path.join(ASSETS_DIR, "bg_content.png"))

# 章节分隔页
add_section_slide(prs, "章节标题", section_number="01",
                  bg_image=os.path.join(ASSETS_DIR, "bg_content.png"))

# 结尾页
add_end_slide(prs, title="谢谢", subtitle="联系方式",
              bg_image=os.path.join(ASSETS_DIR, "bg_cover.png"))

# ===== 保存 =====
save_presentation(prs, os.path.join(OUTPUT_DIR, PPTX_NAME))
```

---

## 函数签名速查

### pptx_helper.py — PPT组装

| 函数 | 必填参数 | 关键可选参数 |
|------|----------|-------------|
| `create_presentation(title)` | title | aspect_ratio |
| `add_rich_title_slide(prs, title)` | prs, title | subtitle, bg_image, decoration_image, style |
| `add_bullet_slide(prs, title, bullets)` | prs, title, bullets | bg_image, style |
| `add_icon_content_slide(prs, title, items)` | prs, title, items | bg_image, style |
| `add_illustrated_slide(prs, title, content)` | prs, title, content | illustration_image, layout, bg_image, style |
| `add_chart_slide(prs, title, chart_type, categories, series_data)` | 全部必填 | bg_image, style |
| `add_quote_slide(prs, quote)` | prs, quote | author, bg_image, style |
| `add_section_slide(prs, section_title)` | prs, section_title | section_number, bg_image, style |
| `add_end_slide(prs)` | prs | title, subtitle, bg_image, style |
| `save_presentation(prs, filename)` | prs, filename | - |

### 参数说明

| 参数 | 说明 | 接受的别名 |
|------|------|-----------|
| `bg_image` | 背景图片路径（PNG 或 SVG 均可） | `background_image`, `background_svg`, `bg` |
| `illustration_image` | 插图路径 | `illustration`, `illust`, `illustration_svg` |
| `decoration_image` | 装饰图路径 | `decoration`, `decor`, `decoration_svg` |
| `style` | 配色方案，默认 `'暗色系'` | - |
| `items` | `[{'title':'..', 'desc':'..', 'icon':'path'}, ...]` 最多4项 | - |
| `bullets` | `['要点1', '要点2', ...]` 最多6项 | - |
| `chart_type` | `'bar'` / `'column'` / `'line'` / `'pie'` | - |
| `series_data` | `{'系列名': [值1, 值2, ...]}` | - |

### svg_utils.py — SVG素材生成

| 函数 | 说明 | 接受的别名 |
|------|------|-----------|
| `save_svg(content, path, w, h)` | 保存SVG到文件 | 额外参数静默忽略 |
| `svg_to_png(svg_path, png_path)` | SVG转PNG | 额外参数静默忽略 |
| `make_gradient_bg(colors, output_path)` | 一行生成渐变背景 | `color_list`, `color` → colors |
| `make_dark_tech_bg(output_path)` | 一行生成科技风背景 | `colors`, `color`, `accent` → accent_color; `particles` → particle_count |
| `svg_linear_gradient(id, colors)` | 线性渐变定义 | - |
| `svg_radial_gradient_with_opacity(id, color, ...)` | 径向光晕 | - |
| `svg_particles(count, w, h)` | 随机粒子星点 | 额外参数静默忽略 |
| `svg_grid_lines(w, h)` | 网格纹理 | - |
| `svg_hex_grid(w, h)` | 六边形网格 | - |
| `svg_wave(w, h)` | 波浪形状 | - |
| `svg_network_nodes(count, w, h)` | 网络节点图 | - |

---

## 配色方案

| 风格 | 主色 | 辅色 | 强调色 | 背景色 |
|------|------|------|--------|--------|
| 商务蓝 | #1B3A5C | #E8F0FE | #FF6B35 | #F5F7FA |
| 科技紫 | #4A1D8E | #F0E6FF | #00D4FF | #0D0D1A |
| 清新绿 | #2D7D46 | #E8F5E9 | #FFB74D | #F1F8E9 |
| 简约灰 | #37474F | #ECEFF1 | #FF7043 | #FAFAFA |
| 创意橙 | #E65100 | #FFF3E0 | #7C4DFF | #FFF8E1 |
| 暗色系 | #BB86FC | #1E1E2E | #03DAC6 | #121212 |

---

## 设计规范

- 16:9 宽屏，标题 36-44pt，正文 16-20pt
- 每页不超过 6 个要点，图标页不超过 4 项
- 每页必须有背景，禁止纯白背景
- 背景图 1920x1080，图标 256x256，插图 800x600

---

## 依赖

```bash
pip install python-pptx cairosvg
```

## 更多SVG模式

详见 `references/svg-patterns.md`。
