# SVG 模式速查

直接复制使用的 SVG 素材模板。所有路径变量假设已设置 `ASSETS_DIR`。

> **注意：所有函数都接受 `**kwargs`，传错参数名不会报错。**
> 例如 `make_dark_tech_bg(path, colors="#FF0000")` 和 `make_dark_tech_bg(path, accent_color="#FF0000")` 效果相同。

---

## 背景模板

### 科技风背景（最常用，一行搞定）
```python
make_dark_tech_bg(os.path.join(ASSETS_DIR, "bg.svg"))
svg_to_png(os.path.join(ASSETS_DIR, "bg.svg"), os.path.join(ASSETS_DIR, "bg.png"))
```

### 渐变背景
```python
make_gradient_bg(["#0A0A1A", "#1A1035", "#0D1B2A"], os.path.join(ASSETS_DIR, "bg.svg"))
svg_to_png(os.path.join(ASSETS_DIR, "bg.svg"), os.path.join(ASSETS_DIR, "bg.png"))
```

### 渐变 + 光晕
```python
grad = svg_linear_gradient("bg", ["#0A0A1A", "#1A1035"])
glow = svg_radial_gradient_with_opacity("glow", "#7C4DFF", 0.15, 0.0, cx="80%", cy="20%", r="35%")
content = f'{svg_defs(grad + chr(10) + glow)}\n{svg_rect(0, 0, 1920, 1080, fill="url(#bg)")}\n{svg_rect(0, 0, 1920, 1080, fill="url(#glow)")}'
save_svg(content, os.path.join(ASSETS_DIR, "bg.svg"))
svg_to_png(os.path.join(ASSETS_DIR, "bg.svg"), os.path.join(ASSETS_DIR, "bg.png"))
```

### 渐变 + 网格
```python
grad = svg_linear_gradient("bg", ["#0A0A1A", "#1A1035"])
grid = svg_grid_lines(1920, 1080, spacing=80, stroke="#4A1D8E", opacity=0.06)
content = f'{svg_defs(grad)}\n{svg_rect(0, 0, 1920, 1080, fill="url(#bg)")}\n{grid}'
save_svg(content, os.path.join(ASSETS_DIR, "bg.svg"))
svg_to_png(os.path.join(ASSETS_DIR, "bg.svg"), os.path.join(ASSETS_DIR, "bg.png"))
```

### 渐变 + 粒子星空
```python
grad = svg_linear_gradient("bg", ["#0D0D1A", "#1A1035"])
glow = svg_radial_gradient_with_opacity("glow", "#7C4DFF", 0.12, 0.0, cx="70%", cy="25%", r="30%")
particles = svg_particles(120, 1920, 1080, color="#FFFFFF", min_r=1, max_r=4, min_opacity=0.05, max_opacity=0.5)
content = f'{svg_defs(grad + chr(10) + glow)}\n{svg_rect(0, 0, 1920, 1080, fill="url(#bg)")}\n{svg_rect(0, 0, 1920, 1080, fill="url(#glow)")}\n{particles}'
save_svg(content, os.path.join(ASSETS_DIR, "bg.svg"))
svg_to_png(os.path.join(ASSETS_DIR, "bg.svg"), os.path.join(ASSETS_DIR, "bg.png"))
```

### 渐变 + 六边形网格
```python
grad = svg_linear_gradient("bg", ["#0D0D1A", "#1A1035"])
hexes = svg_hex_grid(1920, 1080, r=40, stroke="#4A1D8E", opacity=0.12)
content = f'{svg_defs(grad)}\n{svg_rect(0, 0, 1920, 1080, fill="url(#bg)")}\n{hexes}'
save_svg(content, os.path.join(ASSETS_DIR, "bg.svg"))
svg_to_png(os.path.join(ASSETS_DIR, "bg.svg"), os.path.join(ASSETS_DIR, "bg.png"))
```

### 渐变 + 波浪
```python
grad = svg_linear_gradient("bg", ["#0A0A1A", "#1A1035"])
wave = svg_wave(1920, 1080, y_base=0.65, amplitude=40, fill_color="#0D1B2A", opacity=0.8)
content = f'{svg_defs(grad)}\n{svg_rect(0, 0, 1920, 1080, fill="url(#bg)")}\n{wave}'
save_svg(content, os.path.join(ASSETS_DIR, "bg.svg"))
svg_to_png(os.path.join(ASSETS_DIR, "bg.svg"), os.path.join(ASSETS_DIR, "bg.png"))
```

### 内联SVG代码（不使用工具函数）
```python
svg = '''<defs>
  <linearGradient id="bg" x1="0%" y1="0%" x2="100%" y2="100%">
    <stop offset="0%" stop-color="#0A0A1A"/>
    <stop offset="100%" stop-color="#1A1035"/>
  </linearGradient>
</defs>
<rect width="1920" height="1080" fill="url(#bg)"/>
<circle cx="1500" cy="200" r="200" fill="#7C4DFF" opacity="0.15"/>'''
save_svg(svg, os.path.join(ASSETS_DIR, "bg.svg"))
svg_to_png(os.path.join(ASSETS_DIR, "bg.svg"), os.path.join(ASSETS_DIR, "bg.png"))
```

---

## 图标模板（256x256）

### 圆形徽章
```python
cx, cy = 128, 128
color = "#00D4FF"
svg = f'''{svg_circle(cx, cy, 100, fill=color, opacity=0.85)}
{svg_circle(cx, cy, 85, fill="white", opacity=0.1)}
{svg_text(cx, cy+5, "AI", fill="white", font_size=56, anchor="middle", bold=True)}'''
save_svg(svg, os.path.join(ASSETS_DIR, "icon.svg"), 256, 256)
svg_to_png(os.path.join(ASSETS_DIR, "icon.svg"), os.path.join(ASSETS_DIR, "icon.png"))
```

### 同心环
```python
cx, cy = 128, 128
svg = f'''<circle cx="{cx}" cy="{cy}" r="100" stroke="#7C4DFF" stroke-width="3" fill="none" opacity="0.3"/>
<circle cx="{cx}" cy="{cy}" r="70" stroke="#7C4DFF" stroke-width="3" fill="none" opacity="0.6"/>
<circle cx="{cx}" cy="{cy}" r="40" stroke="#7C4DFF" stroke-width="3" fill="none" opacity="0.9"/>
<circle cx="{cx}" cy="{cy}" r="15" fill="#00D4FF" opacity="0.85"/>'''
save_svg(svg, os.path.join(ASSETS_DIR, "icon.svg"), 256, 256)
svg_to_png(os.path.join(ASSETS_DIR, "icon.svg"), os.path.join(ASSETS_DIR, "icon.png"))
```

---

## 插图模板（800x600）

### 网络节点图
```python
nodes = svg_network_nodes(12, 800, 600, node_color="#00D4FF", line_color="#7C4DFF")
save_svg(nodes, os.path.join(ASSETS_DIR, "illust.svg"), 800, 600)
svg_to_png(os.path.join(ASSETS_DIR, "illust.svg"), os.path.join(ASSETS_DIR, "illust.png"))
```

### 趋势折线图
```python
w, h = 800, 600
ml, mb = 80, 60
cw, ch = w - ml - 40, h - mb - 60
values = [30, 55, 42, 78, 65, 92, 85, 95]
maxv = max(values)
points = [(ml + int(i / max(len(values)-1, 1) * cw), (h - mb) - int(v / maxv * ch)) for i, v in enumerate(values)]
fill_pts = points + [(points[-1][0], h - mb), (points[0][0], h - mb)]
parts = [svg_line(ml, 60, ml, h-mb, stroke="#666", width=2), svg_line(ml, h-mb, w-40, h-mb, stroke="#666", width=2)]
parts.append(svg_polygon(fill_pts, fill="#7C4DFF", opacity=0.1))
parts.append(svg_polyline(points, stroke="#7C4DFF", width=3))
for px, py in points:
    parts.append(svg_circle(px, py, 6, fill="#00D4FF"))
save_svg("\n".join(parts), os.path.join(ASSETS_DIR, "illust.svg"), w, h)
svg_to_png(os.path.join(ASSETS_DIR, "illust.svg"), os.path.join(ASSETS_DIR, "illust.png"))
```
