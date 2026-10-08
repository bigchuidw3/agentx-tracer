"""
SVG Utils - SVG 矢量图生成与转换工具

提供 SVG 文档生成、常用图形元素构建、SVG→PNG 转换等功能。

依赖: cairosvg（用于 SVG→PNG 转换）
安装: pip install cairosvg
      Windows 需安装 GTK3-Runtime 并确保其在 PATH 中
"""

import os
import sys
import math
import random
from typing import Tuple, List

# ==================== Windows GTK 路径修复 ====================
if sys.platform == 'win32':
    _gtk_candidates = [
        r'C:\Program Files\GTK3-Runtime Win64\bin',
        r'C:\Program Files (x86)\GTK3-Runtime Win64\bin',
        r'C:\msys64\mingw64\bin',
        r'C:\GTK\bin',
    ]
    for _gtk in _gtk_candidates:
        if os.path.isfile(os.path.join(_gtk, 'libcairo-2.dll')):
            os.add_dll_directory(_gtk)
            os.environ['PATH'] = _gtk + os.pathsep + os.environ.get('PATH', '')
            break


# ==================== SVG 文档工具 ====================

SVG_XML_DECL = '<?xml version="1.0" encoding="UTF-8"?>'
SVG_NS = 'xmlns="http://www.w3.org/2000/svg"'


def svg_document(w: int, h: int, content: str, viewBox: str = None) -> str:
    """生成完整的 SVG 文档字符串。"""
    vb = f' viewBox="{viewBox}"' if viewBox else ''
    return f'{SVG_XML_DECL}\n<svg width="{w}" height="{h}" {SVG_NS}{vb}>\n{content}\n</svg>'


def save_svg(svg_content: str, path: str, w: int = 1920, h: int = 1080, **kwargs) -> str:
    """
    保存 SVG 内容到文件。

    svg_content: SVG 内容字符串（不含根元素的 <svg> 和 </svg>）
    path: 输出文件路径
    w, h: 宽高
    """
    doc = svg_document(w, h, svg_content)
    _ensure_dir(path)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(doc)
    return path


def save_svg_doc(svg_doc: str, path: str, **kwargs) -> str:
    """保存已完整的 SVG 文档字符串到文件。"""
    _ensure_dir(path)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(svg_doc)
    return path


def _ensure_dir(path):
    """确保文件所在目录存在。"""
    d = os.path.dirname(path)
    if d:
        os.makedirs(d, exist_ok=True)


def svg_to_png(svg_path: str, png_path: str, dpi: int = 150, **kwargs) -> bool:
    """
    将 SVG 转换为 PNG。优先 cairosvg，回退 svglib。

    Returns: 是否转换成功
    """
    _ensure_dir(png_path)

    # 优先使用 cairosvg（高质量）
    try:
        import cairosvg
        cairosvg.svg2png(url=svg_path, write_to=png_path, dpi=dpi)
        return True
    except ImportError:
        print("  cairosvg 未安装，请运行: pip install cairosvg")
    except Exception as e:
        print(f"  cairosvg 转换失败: {e}")

    # 回退到 svglib（纯 Python）
    try:
        from svglib.svglib import svg2rlg
        from reportlab.graphics import renderPM
        drawing = svg2rlg(svg_path)
        if drawing:
            renderPM.drawToFile(drawing, png_path, fmt='PNG', dpi=dpi)
            return True
    except ImportError:
        pass
    except Exception as e:
        print(f"  svglib 转换失败: {e}")

    print("  错误: 所有 SVG→PNG 转换方式均失败")
    print("  请安装: pip install cairosvg")
    return False


def svg_to_png_or_skip(svg_path: str, png_path: str, dpi: int = 150) -> str:
    """转换 SVG→PNG，失败则返回 SVG 路径。"""
    if svg_to_png(svg_path, png_path, dpi):
        return png_path
    print(f"  警告: 转换失败，使用 SVG 文件: {svg_path}")
    return svg_path


# ==================== 颜色工具 ====================

def hex_to_rgb(hex_color: str) -> Tuple[int, int, int]:
    h = hex_color.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))


def rgb_to_hex(r: int, g: int, b: int) -> str:
    return f"#{r:02x}{g:02x}{b:02x}"


def hex_to_rgba_str(hex_color: str, opacity: float = 1.0) -> str:
    r, g, b = hex_to_rgb(hex_color)
    return f"rgba({r},{g},{b},{opacity})"


def lerp_color_hex(c1: str, c2: str, t: float) -> str:
    r1, g1, b1 = hex_to_rgb(c1)
    r2, g2, b2 = hex_to_rgb(c2)
    return rgb_to_hex(int(r1+(r2-r1)*t), int(g1+(g2-g1)*t), int(b1+(b2-b1)*t))


# ==================== SVG 元素构建器 ====================

def svg_defs(inner: str) -> str:
    return f'<defs>\n{inner}\n</defs>'


def svg_linear_gradient(id: str, colors: List[str],
                         x1: str = "0%", y1: str = "0%",
                         x2: str = "100%", y2: str = "100%") -> str:
    stops = []
    n = len(colors)
    for i, c in enumerate(colors):
        offset = f"{i / (n - 1) * 100:.0f}%"
        stops.append(f'  <stop offset="{offset}" stop-color="{c}"/>')
    return (f'<linearGradient id="{id}" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}">\n'
            f'{chr(10).join(stops)}\n</linearGradient>')


def svg_radial_gradient(id: str, colors: List[str],
                         cx: str = "50%", cy: str = "50%", r: str = "50%") -> str:
    stops = []
    n = len(colors)
    for i, c in enumerate(colors):
        offset = f"{i / (n - 1) * 100:.0f}%"
        stops.append(f'  <stop offset="{offset}" stop-color="{c}"/>')
    return (f'<radialGradient id="{id}" cx="{cx}" cy="{cy}" r="{r}">\n'
            f'{chr(10).join(stops)}\n</radialGradient>')


def svg_radial_gradient_with_opacity(id: str, color: str,
                                      inner_opacity: float = 0.3,
                                      outer_opacity: float = 0.0,
                                      cx: str = "50%", cy: str = "50%",
                                      r: str = "50%") -> str:
    return (f'<radialGradient id="{id}" cx="{cx}" cy="{cy}" r="{r}">\n'
            f'  <stop offset="0%" stop-color="{color}" stop-opacity="{inner_opacity}"/>\n'
            f'  <stop offset="100%" stop-color="{color}" stop-opacity="{outer_opacity}"/>\n'
            f'</radialGradient>')


def svg_filter_blur(id: str, std_deviation: float = 10) -> str:
    return f'<filter id="{id}"><feGaussianBlur stdDeviation="{std_deviation}"/></filter>'


def svg_filter_glow(id: str, std_deviation: float = 5, color: str = "#FFFFFF") -> str:
    return (f'<filter id="{id}">\n'
            f'  <feGaussianBlur in="SourceGraphic" stdDeviation="{std_deviation}" result="blur"/>\n'
            f'  <feFlood flood-color="{color}" flood-opacity="0.5"/>\n'
            f'  <feComposite in2="blur" operator="in"/>\n'
            f'  <feMerge><feMergeNode/><feMergeNode in="SourceGraphic"/></feMerge>\n'
            f'</filter>')


# ==================== 基本图形 ====================

def svg_rect(x, y, w, h, fill="black", opacity=1.0, rx=0, stroke=None, stroke_width=1):
    attrs = f'x="{x}" y="{y}" width="{w}" height="{h}" fill="{fill}" opacity="{opacity}"'
    if rx: attrs += f' rx="{rx}"'
    if stroke: attrs += f' stroke="{stroke}" stroke-width="{stroke_width}"'
    return f'<rect {attrs}/>'


def svg_circle(cx, cy, r, fill="black", opacity=1.0, stroke=None, stroke_width=1):
    attrs = f'cx="{cx}" cy="{cy}" r="{r}" fill="{fill}" opacity="{opacity}"'
    if stroke: attrs += f' stroke="{stroke}" stroke-width="{stroke_width}"'
    return f'<circle {attrs}/>'


def svg_ellipse(cx, cy, rx, ry, fill="black", opacity=1.0):
    return f'<ellipse cx="{cx}" cy="{cy}" rx="{rx}" ry="{ry}" fill="{fill}" opacity="{opacity}"/>'


def svg_line(x1, y1, x2, y2, stroke="white", width=1, opacity=1.0):
    return f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{stroke}" stroke-width="{width}" opacity="{opacity}"/>'


def svg_polygon(points: list, fill="black", opacity=1.0, stroke=None, stroke_width=1):
    pts_str = " ".join(f"{x:.1f},{y:.1f}" for x, y in points)
    attrs = f'points="{pts_str}" fill="{fill}" opacity="{opacity}"'
    if stroke: attrs += f' stroke="{stroke}" stroke-width="{stroke_width}"'
    return f'<polygon {attrs}/>'


def svg_polyline(points: list, stroke="white", width=2, fill="none", opacity=1.0):
    pts_str = " ".join(f"{x:.1f},{y:.1f}" for x, y in points)
    return f'<polyline points="{pts_str}" stroke="{stroke}" stroke-width="{width}" fill="{fill}" opacity="{opacity}"/>'


def svg_text(x, y, text, fill="white", font_size=16, font_family="sans-serif",
             anchor="start", opacity=1.0, bold=False):
    weight = ' font-weight="bold"' if bold else ''
    return (f'<text x="{x}" y="{y}" fill="{fill}" font-size="{font_size}" '
            f'font-family="{font_family}" text-anchor="{anchor}" opacity="{opacity}"{weight}>'
            f'{text}</text>')


def svg_rounded_rect(x, y, w, h, rx, fill="black", opacity=1.0, stroke=None, stroke_width=1):
    attrs = f'x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" ry="{rx}" fill="{fill}" opacity="{opacity}"'
    if stroke: attrs += f' stroke="{stroke}" stroke-width="{stroke_width}"'
    return f'<rect {attrs}/>'


def svg_arc(cx, cy, r, start_deg, end_deg, stroke="white", width=2, opacity=1.0, fill="none"):
    start_rad = math.radians(start_deg)
    end_rad = math.radians(end_deg)
    x1 = cx + r * math.cos(start_rad)
    y1 = cy + r * math.sin(start_rad)
    x2 = cx + r * math.cos(end_rad)
    y2 = cy + r * math.sin(end_rad)
    large_arc = 1 if (end_deg - start_deg) > 180 else 0
    d = f'M {x1:.1f} {y1:.1f} A {r} {r} 0 {large_arc} 1 {x2:.1f} {y2:.1f}'
    return f'<path d="{d}" stroke="{stroke}" stroke-width="{width}" fill="{fill}" opacity="{opacity}"/>'


# ==================== 复合图形生成器 ====================

def svg_particles(count: int, w: int, h: int, color: str = "#FFFFFF",
                   min_r: int = 1, max_r: int = 4,
                   min_opacity: float = 0.1, max_opacity: float = 0.5,
                   **kwargs) -> str:
    parts = []
    for _ in range(count):
        x = random.randint(0, w)
        y = random.randint(0, h)
        r = random.randint(min_r, max_r)
        op = round(random.uniform(min_opacity, max_opacity), 2)
        parts.append(svg_circle(x, y, r, fill=color, opacity=op))
    return "\n".join(parts)


def svg_hex_grid(w: int, h: int, r: int = 40,
                  stroke: str = "#4A1D8E", opacity: float = 0.12) -> str:
    parts = []
    row_h = int(r * 1.732)
    for row in range(-1, h // row_h + 2):
        for col in range(-1, w // (r * 2) + 2):
            cx = col * r * 2 + (r if row % 2 else 0)
            cy = row * row_h
            pts = []
            for i in range(6):
                angle = math.pi / 6 + i * math.pi / 3
                pts.append((cx + r * math.cos(angle), cy + r * math.sin(angle)))
            parts.append(svg_polygon(pts, fill="none", stroke=stroke,
                                      stroke_width=1, opacity=opacity))
    return "\n".join(parts)


def svg_grid_lines(w: int, h: int, spacing: int = 80,
                    stroke: str = "#4A1D8E", opacity: float = 0.05) -> str:
    parts = []
    for x in range(0, w + spacing, spacing):
        parts.append(svg_line(x, 0, x, h, stroke=stroke, width=1, opacity=opacity))
    for y in range(0, h + spacing, spacing):
        parts.append(svg_line(0, y, w, y, stroke=stroke, width=1, opacity=opacity))
    return "\n".join(parts)


def svg_network_nodes(count: int, w: int, h: int,
                       node_color: str = "#00D4FF", line_color: str = "#7C4DFF",
                       node_r: int = 15, connect_dist: int = 250) -> str:
    nodes = [(random.randint(80, w - 80), random.randint(80, h - 80))
             for _ in range(count)]
    parts = []
    for i, (x1, y1) in enumerate(nodes):
        for j, (x2, y2) in enumerate(nodes):
            if i < j and math.hypot(x2 - x1, y2 - y1) < connect_dist:
                parts.append(svg_line(x1, y1, x2, y2, stroke=line_color,
                                       width=2, opacity=0.25))
    for x, y in nodes:
        parts.append(svg_circle(x, y, node_r + 8, fill=node_color, opacity=0.1))
        parts.append(svg_circle(x, y, node_r, fill=node_color, opacity=0.7))
    return "\n".join(parts)


def svg_wave(w: int, h: int, y_base: float = 0.65, amplitude: float = 40,
              fill_color: str = "#0D1B2A", opacity: float = 1.0) -> str:
    points = []
    for x in range(0, w + 1, 4):
        y = int(h * y_base + amplitude * math.sin(x * 2 * math.pi / w) +
                amplitude * 0.5 * math.sin(x * 4 * math.pi / w + 1))
        points.append((x, y))
    points.append((w, h))
    points.append((0, h))
    return svg_polygon(points, fill=fill_color, opacity=opacity)


def svg_divider(w: int = 600, y: int = 10, color: str = "#00D4FF") -> str:
    return (f'<line x1="0" y1="{y}" x2="{w//3}" y2="{y}" stroke="{color}" stroke-width="2" opacity="0.8"/>\n'
            f'<circle cx="{w//2}" cy="{y}" r="5" fill="{color}" opacity="0.85"/>\n'
            f'<line x1="{w*2//3}" y1="{y}" x2="{w}" y2="{y}" stroke="{color}" stroke-width="2" opacity="0.8"/>')


# ==================== 快速背景生成 ====================

def make_gradient_bg(colors: list, output_path: str, w: int = 1920, h: int = 1080,
                      direction: str = "diagonal", **kwargs) -> str:
    # 别名：colors / color_list / color_list 都行
    colors = kwargs.pop('color_list', colors) or kwargs.pop('color', colors) or colors
    if direction == "horizontal":
        x1, y1, x2, y2 = "0%", "0%", "100%", "0%"
    elif direction == "vertical":
        x1, y1, x2, y2 = "0%", "0%", "0%", "100%"
    else:
        x1, y1, x2, y2 = "0%", "0%", "100%", "100%"
    grad = svg_linear_gradient("bg", colors, x1, y1, x2, y2)
    rect = svg_rect(0, 0, w, h, fill="url(#bg)")
    save_svg(f'{svg_defs(grad)}\n{rect}', output_path, w, h)
    return output_path


def make_dark_tech_bg(output_path: str, w: int = 1920, h: int = 1080,
                       accent_color: str = "#7C4DFF", particle_count: int = 80,
                       **kwargs) -> str:
    # 别名：accent_color / accent / color / colors 都行
    accent_color = (kwargs.pop('accent', None) or kwargs.pop('color', None)
                    or kwargs.pop('colors', None) or accent_color)
    # 如果 colors 传了列表，取第一个作为 accent_color
    if isinstance(accent_color, list):
        accent_color = accent_color[0]
    particle_count = kwargs.pop('particles', particle_count) or particle_count

    grad = svg_linear_gradient("bg", ["#0A0A1A", "#1A1035", "#0D1B2A"])
    glow1 = svg_radial_gradient_with_opacity("glow1", accent_color, 0.15, 0.0,
                                              cx="80%", cy="20%", r="35%")
    glow2 = svg_radial_gradient_with_opacity("glow2", "#00D4FF", 0.08, 0.0,
                                              cx="15%", cy="75%", r="30%")
    defs = svg_defs(f'{grad}\n{glow1}\n{glow2}')
    bg_rect = svg_rect(0, 0, w, h, fill="url(#bg)")
    glow_rect1 = svg_rect(0, 0, w, h, fill="url(#glow1)")
    glow_rect2 = svg_rect(0, 0, w, h, fill="url(#glow2)")
    grid = svg_grid_lines(w, h, spacing=80, stroke=accent_color, opacity=0.04)
    particles = svg_particles(particle_count, w, h, min_opacity=0.05, max_opacity=0.3)
    save_svg(f'{defs}\n{bg_rect}\n{glow_rect1}\n{glow_rect2}\n{grid}\n{particles}',
             output_path, w, h)
    return output_path
