"""
Img Utils - 图片绘制工具函数

提供颜色转换、渐变绘制等通用辅助函数。
AI 在生成脚本时直接使用这些工具 + 自定义 Pillow 代码。

依赖: Pillow
安装: pip install Pillow
"""

from typing import Tuple

try:
    from PIL import Image, ImageDraw
except ImportError:
    raise ImportError("Pillow is required. Install with: pip install Pillow")


# ==================== 颜色工具 ====================

def hex_to_rgb(hex_color: str) -> Tuple[int, int, int]:
    """十六进制颜色转 RGB 元组"""
    hex_color = hex_color.lstrip('#')
    return (int(hex_color[0:2], 16), int(hex_color[2:4], 16), int(hex_color[4:6], 16))


def hex_to_rgba(hex_color: str, opacity: float = 1.0) -> Tuple[int, int, int, int]:
    """十六进制颜色 + 透明度转 RGBA 元组"""
    r, g, b = hex_to_rgb(hex_color)
    return (r, g, b, round(opacity * 255))


def lerp_color(c1: tuple, c2: tuple, t: float) -> tuple:
    """线性插值两个颜色（支持 RGB 或 RGBA）"""
    return tuple(int(a + (b - a) * t) for a, b in zip(c1, c2))


# ==================== 渐变绘制 ====================

def draw_gradient(img: Image.Image, direction: str, *colors: tuple):
    """
    在图片上绘制多色渐变。

    Args:
        img: RGBA 模式的图片
        direction: 'horizontal' | 'vertical' | 'diagonal'
        colors: RGB 或 RGBA 元组序列，至少2个颜色
    """
    draw = ImageDraw.Draw(img)
    w, h = img.size

    if direction == 'horizontal':
        for x in range(w):
            t = x / max(w - 1, 1)
            color = _multi_lerp(colors, t)
            draw.line([(x, 0), (x, h - 1)], fill=color)
    elif direction == 'vertical':
        for y in range(h):
            t = y / max(h - 1, 1)
            color = _multi_lerp(colors, t)
            draw.line([(0, y), (w - 1, y)], fill=color)
    elif direction == 'diagonal':
        steps = max(w, h)
        for i in range(steps):
            t = i / max(steps - 1, 1)
            color = _multi_lerp(colors, t)
            x = int(w * t)
            y = int(h * t)
            draw.line([(x, 0), (0, y)], fill=color)


def draw_radial_gradient(img: Image.Image, center: tuple, radius: int, *colors: tuple):
    """
    在图片上绘制径向渐变。

    Args:
        img: RGBA 模式的图片
        center: (cx, cy) 圆心
        radius: 最大半径
        colors: RGB 或 RGBA 元组序列，从中心到边缘
    """
    draw = ImageDraw.Draw(img)
    steps = min(radius, 200)
    for i in range(steps, 0, -1):
        t = 1 - i / steps
        r = int(radius * i / steps)
        color = _multi_lerp(colors, t)
        cx, cy = center
        draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color)


def _multi_lerp(colors: tuple, t: float) -> tuple:
    """多色线性插值"""
    if not colors:
        return (0, 0, 0)
    if len(colors) < 2:
        return colors[0]
    n = len(colors) - 1
    idx = min(int(t * n), n - 1)
    local_t = (t * n) - idx
    return lerp_color(colors[idx], colors[idx + 1], local_t)


# ==================== 几何辅助 ====================

def draw_particle_field(draw: ImageDraw.ImageDraw, width: int, height: int,
                        count: int = 80, color: tuple = (255, 255, 255, 40),
                        min_r: int = 1, max_r: int = 6):
    """绘制随机粒子场"""
    import random
    for _ in range(count):
        x = random.randint(0, width)
        y = random.randint(0, height)
        r = random.randint(min_r, max_r)
        draw.ellipse([x - r, y - r, x + r, y + r], fill=color)


def save_image(img: Image.Image, path: str) -> str:
    """保存图片，自动处理模式转换"""
    if img.mode == 'RGBA':
        img.save(path, 'PNG')
    else:
        img.convert('RGB').save(path, 'PNG')
    return path
