"""
PPTX Helper - PPT生成工具（容错版）

核心设计原则：对LLM生成代码高度容错
- 所有函数接受 **kwargs，忽略多余参数
- 参数名支持多种别名（bg_image/background_image/background_svg/bg 均可）
- 自动检测 SVG 文件并转换为 PNG
- 所有参数都有合理默认值
"""

import os
from typing import List, Dict, Optional, Any, Tuple, Union

try:
    from pptx import Presentation
    from pptx.util import Inches, Pt, Cm, Emu
    from pptx.enum.shapes import MSO_SHAPE
    from pptx.enum.text import PP_ALIGN, MSO_ANCHOR, MSO_AUTO_SIZE
    from pptx.enum.chart import XL_CHART_TYPE, XL_LEGEND_POSITION
    from pptx.chart.data import CategoryChartData
    from pptx.dml.color import RGBColor
except ImportError:
    raise ImportError("python-pptx is required. Install with: pip install python-pptx")


# ==================== 配色方案 ====================

COLOR_SCHEMES = {
    '商务蓝': {
        'primary': RGBColor(27, 58, 92),
        'secondary': RGBColor(232, 240, 254),
        'accent': RGBColor(255, 107, 53),
        'background': RGBColor(245, 247, 250),
        'text': RGBColor(44, 62, 80),
        'light_text': RGBColor(255, 255, 255),
        'bg_dark': RGBColor(27, 58, 92),
    },
    '科技紫': {
        'primary': RGBColor(74, 29, 142),
        'secondary': RGBColor(240, 230, 255),
        'accent': RGBColor(0, 212, 255),
        'background': RGBColor(13, 13, 26),
        'text': RGBColor(224, 224, 224),
        'light_text': RGBColor(255, 255, 255),
        'bg_dark': RGBColor(13, 13, 26),
    },
    '清新绿': {
        'primary': RGBColor(45, 125, 70),
        'secondary': RGBColor(232, 245, 233),
        'accent': RGBColor(255, 183, 77),
        'background': RGBColor(241, 248, 233),
        'text': RGBColor(55, 71, 79),
        'light_text': RGBColor(255, 255, 255),
        'bg_dark': RGBColor(45, 125, 70),
    },
    '简约灰': {
        'primary': RGBColor(55, 71, 79),
        'secondary': RGBColor(236, 239, 241),
        'accent': RGBColor(255, 112, 67),
        'background': RGBColor(250, 250, 250),
        'text': RGBColor(55, 71, 79),
        'light_text': RGBColor(255, 255, 255),
        'bg_dark': RGBColor(55, 71, 79),
    },
    '创意橙': {
        'primary': RGBColor(230, 81, 0),
        'secondary': RGBColor(255, 243, 224),
        'accent': RGBColor(124, 77, 255),
        'background': RGBColor(255, 248, 225),
        'text': RGBColor(78, 52, 46),
        'light_text': RGBColor(255, 255, 255),
        'bg_dark': RGBColor(230, 81, 0),
    },
    '暗色系': {
        'primary': RGBColor(187, 134, 252),
        'secondary': RGBColor(30, 30, 46),
        'accent': RGBColor(3, 218, 198),
        'background': RGBColor(18, 18, 18),
        'text': RGBColor(224, 224, 224),
        'light_text': RGBColor(255, 255, 255),
        'bg_dark': RGBColor(18, 18, 18),
    }
}

DEFAULT_FONT = '微软雅黑'
DEFAULT_FONT_FALLBACK = '思源黑体'


# ==================== 容错工具函数 ====================

def _resolve_image(image_path):
    """
    解析图片路径：如果是 SVG 自动转 PNG，路径不存在则返回 None。
    """
    if not image_path:
        return None
    if not os.path.exists(image_path):
        return None
    if image_path.lower().endswith('.svg'):
        png_path = image_path[:-4] + '.png'
        if os.path.exists(png_path):
            return png_path
        try:
            from svg_utils import svg_to_png
            if svg_to_png(image_path, png_path):
                return png_path
        except Exception as e:
            print(f"Warning: SVG conversion failed: {e}")
        return None  # SVG转换失败，不使用该图片
    return image_path


def _pick(kwargs, *names, default=None):
    """
    从 kwargs 中按优先级查找参数值（支持多种别名）。
    例如 _pick(kwargs, 'bg_image', 'background_image', 'background_svg', 'bg')
    """
    for name in names:
        if name in kwargs:
            val = kwargs.pop(name)
            return val
    return default


def _get_colors(style):
    """获取配色方案，支持 style=None 默认暗色系"""
    if isinstance(style, dict):
        return style
    return COLOR_SCHEMES.get(style, COLOR_SCHEMES['暗色系'])


# ==================== 演示文稿创建 ====================

def create_presentation(title="演示文稿", author="SVG PPTX", subject="", aspect_ratio="16:9", **kwargs):
    """创建演示文稿。忽略多余参数。"""
    prs = Presentation()
    if aspect_ratio == "16:9":
        prs.slide_width = Inches(10)
        prs.slide_height = Inches(5.625)
    elif aspect_ratio == "4:3":
        prs.slide_width = Inches(10)
        prs.slide_height = Inches(7.5)
    prs.core_properties.title = title
    prs.core_properties.author = author
    prs.core_properties.subject = subject
    return prs


# ==================== 辅助函数 ====================

def add_background_image(slide, image_path, opacity=1.0):
    img = _resolve_image(image_path)
    if not img:
        return
    try:
        slide.shapes.add_picture(img, Inches(0), Inches(0),
                                  width=Inches(10), height=Inches(5.625))
    except Exception as e:
        print(f"Warning: Failed to add background image: {e}")


def set_slide_background_color(slide, color):
    background = slide.background
    fill = background.fill
    fill.solid()
    fill.fore_color.rgb = color


# ==================== 幻灯片类型 ====================

def add_rich_title_slide(prs, title, subtitle="", bg_image=None,
                          decoration_image=None, style='暗色系', **kwargs):
    """
    封面页。
    参数别名: bg_image / background_image / background_svg / bg 均可
              decoration_image / decoration / decor / decoration_svg 均可
    """
    bg_image = bg_image or _pick(kwargs, 'background_image', 'background_svg', 'bg')
    decoration_image = decoration_image or _pick(kwargs, 'decoration', 'decor', 'decoration_svg', 'deco_image')

    colors = _get_colors(style)
    slide = prs.slides.add_slide(prs.slide_layouts[6])

    img = _resolve_image(bg_image)
    if img:
        add_background_image(slide, img)
    else:
        set_slide_background_color(slide, colors['background'])

    deco_img = _resolve_image(decoration_image)
    if deco_img:
        try:
            slide.shapes.add_picture(deco_img, Inches(6.5), Inches(2),
                                      width=Inches(3.5))
        except Exception as e:
            print(f"Warning: Failed to add decoration image: {e}")

    title_shape = slide.shapes.add_textbox(Inches(0.8), Inches(1.8), Inches(8), Inches(1.2))
    title_tf = title_shape.text_frame
    title_tf.text = title
    for p in title_tf.paragraphs:
        p.font.size = Pt(44)
        p.font.bold = True
        p.font.color.rgb = colors['light_text']
        p.font.name = DEFAULT_FONT

    if subtitle:
        subtitle_shape = slide.shapes.add_textbox(Inches(0.8), Inches(3.2), Inches(8), Inches(0.8))
        subtitle_tf = subtitle_shape.text_frame
        subtitle_tf.text = subtitle
        for p in subtitle_tf.paragraphs:
            p.font.size = Pt(24)
            p.font.color.rgb = colors['text']
            p.font.name = DEFAULT_FONT

    return slide


def add_icon_content_slide(prs, title, items, bg_image=None, style='暗色系', **kwargs):
    """
    图标内容页。items = [{'title': '..', 'desc': '..', 'icon': 'path.png'}, ...]
    参数别名: bg_image / background_image / background_svg / bg 均可
    """
    bg_image = bg_image or _pick(kwargs, 'background_image', 'background_svg', 'bg')
    colors = _get_colors(style)
    slide = prs.slides.add_slide(prs.slide_layouts[6])

    img = _resolve_image(bg_image)
    if img:
        add_background_image(slide, img)
    else:
        set_slide_background_color(slide, colors['background'])

    title_shape = slide.shapes.add_textbox(Inches(0.5), Inches(0.4), Inches(9), Inches(0.8))
    title_tf = title_shape.text_frame
    title_tf.text = title
    for p in title_tf.paragraphs:
        p.font.size = Pt(32)
        p.font.bold = True
        p.font.color.rgb = colors['primary']
        p.font.name = DEFAULT_FONT

    start_y = 1.5
    item_height = 0.9
    icon_size = 0.6

    for i, item in enumerate(items[:4]):
        y_pos = start_y + i * item_height
        icon_path = item.get('icon')
        icon_img = _resolve_image(icon_path)
        if icon_img:
            try:
                slide.shapes.add_picture(icon_img, Inches(0.6), Inches(y_pos),
                                          width=Inches(icon_size))
            except Exception as e:
                print(f"Warning: Failed to add icon: {e}")

        item_title = item.get('title', '')
        title_text = slide.shapes.add_textbox(Inches(1.4), Inches(y_pos), Inches(3), Inches(0.4))
        title_tf = title_text.text_frame
        title_tf.text = item_title
        for p in title_tf.paragraphs:
            p.font.size = Pt(18)
            p.font.bold = True
            p.font.color.rgb = colors['primary']
            p.font.name = DEFAULT_FONT

        desc = item.get('desc', '')
        if desc:
            desc_text = slide.shapes.add_textbox(Inches(1.4), Inches(y_pos + 0.35),
                                                   Inches(7), Inches(0.5))
            desc_tf = desc_text.text_frame
            desc_tf.text = desc
            for p in desc_tf.paragraphs:
                p.font.size = Pt(14)
                p.font.color.rgb = colors['text']
                p.font.name = DEFAULT_FONT

    return slide


def add_illustrated_slide(prs, title, content, illustration_image=None,
                           layout='left', bg_image=None, style='暗色系', **kwargs):
    """
    插图内容页。content 为字符串或字符串列表。
    参数别名: illustration_image / illustration / illust / illustration_svg 均可
              bg_image / background_image / background_svg / bg 均可
    """
    bg_image = bg_image or _pick(kwargs, 'background_image', 'background_svg', 'bg')
    illustration_image = illustration_image or _pick(kwargs, 'illustration', 'illust', 'illustration_svg')

    colors = _get_colors(style)
    slide = prs.slides.add_slide(prs.slide_layouts[6])

    img = _resolve_image(bg_image)
    if img:
        add_background_image(slide, img)
    else:
        set_slide_background_color(slide, colors['background'])

    if layout == 'left':
        text_left, text_width = 0.5, 5
        illust_left = 6
    else:
        text_left, text_width = 5.5, 4
        illust_left = 0.5

    title_shape = slide.shapes.add_textbox(Inches(text_left), Inches(0.5),
                                             Inches(text_width), Inches(0.8))
    title_tf = title_shape.text_frame
    title_tf.text = title
    for p in title_tf.paragraphs:
        p.font.size = Pt(32)
        p.font.bold = True
        p.font.color.rgb = colors['primary']
        p.font.name = DEFAULT_FONT

    content_y = 1.5
    content_items = [content] if isinstance(content, str) else content
    for item in content_items:
        content_shape = slide.shapes.add_textbox(
            Inches(text_left), Inches(content_y), Inches(text_width), Inches(0.5))
        content_tf = content_shape.text_frame
        content_tf.text = f"• {item}"
        for p in content_tf.paragraphs:
            p.font.size = Pt(16)
            p.font.color.rgb = colors['text']
            p.font.name = DEFAULT_FONT
        content_y += 0.5

    illust_img = _resolve_image(illustration_image)
    if illust_img:
        try:
            slide.shapes.add_picture(illust_img, Inches(illust_left),
                                      Inches(1.5), width=Inches(3.5))
        except Exception as e:
            print(f"Warning: Failed to add illustration: {e}")

    return slide


def add_chart_slide(prs, title, chart_type, categories, series_data,
                     bg_image=None, style='暗色系', **kwargs):
    """
    图表页。chart_type: 'bar'/'column'/'line'/'pie'
    参数别名: bg_image / background_image / background_svg / bg 均可
    """
    bg_image = bg_image or _pick(kwargs, 'background_image', 'background_svg', 'bg')
    colors = _get_colors(style)
    slide = prs.slides.add_slide(prs.slide_layouts[6])

    img = _resolve_image(bg_image)
    if img:
        add_background_image(slide, img)
    else:
        set_slide_background_color(slide, colors['background'])

    title_shape = slide.shapes.add_textbox(Inches(0.5), Inches(0.4), Inches(9), Inches(0.8))
    title_tf = title_shape.text_frame
    title_tf.text = title
    for p in title_tf.paragraphs:
        p.font.size = Pt(32)
        p.font.bold = True
        p.font.color.rgb = colors['primary']
        p.font.name = DEFAULT_FONT

    chart_types = {
        'bar': XL_CHART_TYPE.BAR_CLUSTERED,
        'column': XL_CHART_TYPE.COLUMN_CLUSTERED,
        'line': XL_CHART_TYPE.LINE,
        'pie': XL_CHART_TYPE.PIE,
    }
    xl_chart_type = chart_types.get(chart_type.lower(), XL_CHART_TYPE.COLUMN_CLUSTERED)

    chart_data = CategoryChartData()
    chart_data.categories = categories
    if isinstance(series_data, dict):
        for name, values in series_data.items():
            chart_data.add_series(name, values)
    else:
        chart_data.add_series('数据', series_data)

    chart = slide.shapes.add_chart(
        xl_chart_type, Inches(1), Inches(1.5), Inches(8), Inches(4), chart_data
    ).chart
    chart.has_legend = True
    chart.legend.position = XL_LEGEND_POSITION.BOTTOM
    chart.legend.include_in_layout = False

    return slide


def add_quote_slide(prs, quote, author="", bg_image=None, style='暗色系', **kwargs):
    """引用页。参数别名同上。"""
    bg_image = bg_image or _pick(kwargs, 'background_image', 'background_svg', 'bg')
    colors = _get_colors(style)
    slide = prs.slides.add_slide(prs.slide_layouts[6])

    img = _resolve_image(bg_image)
    if img:
        add_background_image(slide, img)
    else:
        set_slide_background_color(slide, colors['primary'])

    quote_shape = slide.shapes.add_textbox(Inches(1), Inches(1.8), Inches(8), Inches(2))
    quote_tf = quote_shape.text_frame
    quote_tf.word_wrap = True
    quote_tf.text = f"「{quote}」"
    for p in quote_tf.paragraphs:
        p.font.size = Pt(32)
        p.font.color.rgb = colors['light_text']
        p.font.name = DEFAULT_FONT
        p.alignment = PP_ALIGN.CENTER

    if author:
        author_shape = slide.shapes.add_textbox(Inches(1), Inches(4), Inches(8), Inches(0.5))
        author_tf = author_shape.text_frame
        author_tf.text = f"— {author}"
        for p in author_tf.paragraphs:
            p.font.size = Pt(18)
            p.font.color.rgb = colors['secondary']
            p.font.name = DEFAULT_FONT
            p.alignment = PP_ALIGN.RIGHT

    return slide


def add_section_slide(prs, section_title, section_number="", bg_image=None, style='暗色系', **kwargs):
    """章节分隔页。参数别名同上。"""
    bg_image = bg_image or _pick(kwargs, 'background_image', 'background_svg', 'bg')
    colors = _get_colors(style)
    slide = prs.slides.add_slide(prs.slide_layouts[6])

    img = _resolve_image(bg_image)
    if img:
        add_background_image(slide, img)
    else:
        set_slide_background_color(slide, colors['primary'])

    if section_number:
        num_shape = slide.shapes.add_textbox(Inches(0.5), Inches(2), Inches(9), Inches(1))
        num_tf = num_shape.text_frame
        num_tf.text = section_number
        for p in num_tf.paragraphs:
            p.font.size = Pt(72)
            p.font.bold = True
            p.font.color.rgb = colors['accent']
            p.font.name = DEFAULT_FONT
            p.alignment = PP_ALIGN.CENTER

    title_shape = slide.shapes.add_textbox(Inches(0.5), Inches(3.2), Inches(9), Inches(1))
    title_tf = title_shape.text_frame
    title_tf.text = section_title
    for p in title_tf.paragraphs:
        p.font.size = Pt(40)
        p.font.bold = True
        p.font.color.rgb = colors['light_text']
        p.font.name = DEFAULT_FONT
        p.alignment = PP_ALIGN.CENTER

    return slide


def add_bullet_slide(prs, title, bullets, bg_image=None, style='暗色系', **kwargs):
    """要点列表页。参数别名同上。"""
    bg_image = bg_image or _pick(kwargs, 'background_image', 'background_svg', 'bg')
    colors = _get_colors(style)
    slide = prs.slides.add_slide(prs.slide_layouts[6])

    img = _resolve_image(bg_image)
    if img:
        add_background_image(slide, img)
    else:
        set_slide_background_color(slide, colors['background'])

    title_shape = slide.shapes.add_textbox(Inches(0.5), Inches(0.4), Inches(9), Inches(0.8))
    title_tf = title_shape.text_frame
    title_tf.text = title
    for p in title_tf.paragraphs:
        p.font.size = Pt(32)
        p.font.bold = True
        p.font.color.rgb = colors['primary']
        p.font.name = DEFAULT_FONT

    start_y = 1.5
    for i, bullet in enumerate(bullets):
        bullet_shape = slide.shapes.add_textbox(
            Inches(0.8), Inches(start_y + i * 0.6), Inches(8.5), Inches(0.5))
        bullet_tf = bullet_shape.text_frame
        bullet_tf.text = f"• {bullet}"
        for p in bullet_tf.paragraphs:
            p.font.size = Pt(18)
            p.font.color.rgb = colors['text']
            p.font.name = DEFAULT_FONT

    return slide


def add_end_slide(prs, title="谢谢", subtitle="", bg_image=None, style='暗色系', **kwargs):
    """结尾页。参数别名同上。"""
    bg_image = bg_image or _pick(kwargs, 'background_image', 'background_svg', 'bg')
    colors = _get_colors(style)
    slide = prs.slides.add_slide(prs.slide_layouts[6])

    img = _resolve_image(bg_image)
    if img:
        add_background_image(slide, img)
    else:
        set_slide_background_color(slide, colors['background'])

    title_shape = slide.shapes.add_textbox(Inches(0.5), Inches(2), Inches(9), Inches(1.2))
    title_tf = title_shape.text_frame
    title_tf.text = title
    for p in title_tf.paragraphs:
        p.font.size = Pt(56)
        p.font.bold = True
        p.font.color.rgb = colors['primary']
        p.font.name = DEFAULT_FONT
        p.alignment = PP_ALIGN.CENTER

    if subtitle:
        subtitle_shape = slide.shapes.add_textbox(Inches(0.5), Inches(3.5),
                                                     Inches(9), Inches(0.8))
        subtitle_tf = subtitle_shape.text_frame
        subtitle_tf.text = subtitle
        for p in subtitle_tf.paragraphs:
            p.font.size = Pt(20)
            p.font.color.rgb = colors['text']
            p.font.name = DEFAULT_FONT
            p.alignment = PP_ALIGN.CENTER

    return slide


# ==================== 统一入口函数 ====================

def add_slide(prs, layout, title, content=None, bg_image=None, style='暗色系', **kwargs):
    """
    统一幻灯片添加入口 - 对LLM最友好的函数。

    layout 类型:
      "title"      → 封面页 (content 当作 subtitle)
      "section"    → 章节页 (content 当作 section_number)
      "bullets"    → 要点列表 (content 为字符串列表)
      "icons"      → 图标内容 (content 为 [{'title':.., 'desc':.., 'icon':..}, ...])
      "illustrated"→ 插图页 (content 为字符串或列表, kwargs['illustration'] 为图片路径)
      "chart"      → 图表页 (kwargs['categories'], kwargs['series_data'], kwargs['chart_type'])
      "quote"      → 引用页 (content 当作 quote, kwargs['author'] 为作者)
      "end"        → 结尾页 (content 当作 subtitle)

    参数别名: bg_image / background_image / background_svg / bg 均可
    """
    bg_image = bg_image or _pick(kwargs, 'background_image', 'background_svg', 'bg')

    if layout == "title":
        return add_rich_title_slide(prs, title, subtitle=content or "",
                                     bg_image=bg_image, style=style, **kwargs)
    elif layout == "section":
        return add_section_slide(prs, title, section_number=content or "",
                                  bg_image=bg_image, style=style, **kwargs)
    elif layout == "bullets":
        bullets = content if isinstance(content, list) else [content] if content else []
        return add_bullet_slide(prs, title, bullets=bullets,
                                 bg_image=bg_image, style=style, **kwargs)
    elif layout == "icons":
        items = content if isinstance(content, list) else []
        return add_icon_content_slide(prs, title, items=items,
                                       bg_image=bg_image, style=style, **kwargs)
    elif layout == "illustrated":
        illust = _pick(kwargs, 'illustration', 'illust', 'illustration_image', 'illustration_svg')
        return add_illustrated_slide(prs, title, content=content or [],
                                      illustration_image=illust, bg_image=bg_image,
                                      style=style, **kwargs)
    elif layout == "chart":
        categories = _pick(kwargs, 'categories', 'category') or []
        series_data = _pick(kwargs, 'series_data', 'series', 'data') or {}
        chart_type = _pick(kwargs, 'chart_type', 'type') or 'column'
        return add_chart_slide(prs, title, chart_type, categories, series_data,
                                bg_image=bg_image, style=style, **kwargs)
    elif layout == "quote":
        author = _pick(kwargs, 'author') or ""
        return add_quote_slide(prs, quote=content or title, author=author,
                                bg_image=bg_image, style=style, **kwargs)
    elif layout == "end":
        return add_end_slide(prs, title=title, subtitle=content or "",
                              bg_image=bg_image, style=style, **kwargs)
    else:
        # 未知 layout，退回到 bullet
        bullets = content if isinstance(content, list) else [content] if content else []
        return add_bullet_slide(prs, title, bullets=bullets,
                                 bg_image=bg_image, style=style, **kwargs)


# ==================== 保存 ====================

def save_presentation(prs, filename, verbose=True, **kwargs):
    """保存文件。忽略多余参数。"""
    output_dir = os.path.dirname(filename)
    if output_dir and not os.path.exists(output_dir):
        os.makedirs(output_dir)
    if not filename.endswith('.pptx'):
        filename += '.pptx'
    prs.save(filename)
    abs_path = os.path.abspath(filename)
    if verbose:
        file_size = os.path.getsize(abs_path) / (1024 * 1024)
        print(f"演示文稿已保存: {abs_path}")
        print(f"  页数: {len(prs.slides)}")
        print(f"  大小: {file_size:.2f} MB")
    return abs_path


def get_color_scheme(style_name):
    return COLOR_SCHEMES.get(style_name, COLOR_SCHEMES['暗色系'])


def list_color_schemes():
    return list(COLOR_SCHEMES.keys())
