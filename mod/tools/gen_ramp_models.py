"""Generates the surf ramp / surf slope block models (run from anywhere; writes into src/main/resources).

Minecraft models are boxes only, so a slope is drawn as a smooth tilted top face (half a pixel thick) over many thin
vertical slices that form the triangular sides. Each slice ends exactly at the slope height of its LOW edge, so no
slice ever pokes out above the slope; the gap left under the slope (at most one slice width x rise) stays smaller than
the top face's thickness, so the sides never show holes. All models rise toward +x (east); blockstates rotate them.
"""
import json
import math
import os

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'main', 'resources', 'assets', 'strafemc', 'models', 'block')
TEX = {"t": "minecraft:block/smooth_stone", "particle": "minecraft:block/smooth_stone"}


def side_slices(x_from, x_to, n, height_at, y_base, extra_last=None):
    """Thin slices across [x_from, x_to]; height_at(x) = slope height (block px, may exceed 16); y_base = this block's floor."""
    els = []
    dx = (x_to - x_from) / n
    for i in range(n):
        x0, x1 = x_from + i * dx, x_from + (i + 1) * dx
        top = min(16.0, height_at(x0) - y_base)
        if top <= 0:
            continue
        faces = {"north": {"texture": "#t", "uv": [round(16 - x1, 4), round(16 - top, 4), round(16 - x0, 4), 16]},
                 "south": {"texture": "#t", "uv": [round(x0, 4), round(16 - top, 4), round(x1, 4), 16]}}
        if y_base == 0:
            faces["down"] = {"texture": "#t", "uv": [round(x0, 4), 0, round(x1, 4), 16]}
        els.append({"from": [round(x0, 4), 0, 0], "to": [round(x1, 4), round(top, 4), 16], "faces": faces})
    return els


def top_face(x_origin, y_origin, length, angle):
    return {"from": [x_origin, y_origin - 0.5, 0], "to": [round(x_origin + length, 5), y_origin, 16],
            "faces": {"up": {"texture": "#t", "uv": [0, 0, 16, 16]}, "north": {"texture": "#t", "uv": [0, 0, 16, 0.5]},
                      "south": {"texture": "#t", "uv": [0, 0, 16, 0.5]}, "down": {"texture": "#t", "uv": [0, 0, 16, 16]}},
            "rotation": {"origin": [x_origin, y_origin, 8], "axis": "z", "angle": angle}}


def back_box(x0, y_base_is_floor):
    faces = {"east": {"texture": "#t", "uv": [0, 0, 16, 16]}, "north": {"texture": "#t", "uv": [0, 0, 16 - x0, 16]},
             "south": {"texture": "#t", "uv": [x0, 0, 16, 16]}}
    if y_base_is_floor:
        faces["down"] = {"texture": "#t", "uv": [x0, 0, 16, 16]}
    return {"from": [x0, 0, 0], "to": [16, 16, 16], "faces": faces}


def back_plate():
    """The full-height back face (x = 16): slices stop just under the slope, so the last one is a hair short of 16."""
    return {"from": [15.99, 0, 0], "to": [16, 16, 16], "faces": {"east": {"texture": "#t", "uv": [0, 0, 16, 16]}}}


def write(name, elements):
    with open(os.path.join(OUT, name + '.json'), 'w') as f:
        json.dump({"parent": "minecraft:block/block", "ambientocclusion": False, "textures": TEX, "elements": elements}, f, indent=1)


ramp_angle = math.degrees(math.atan2(2, 1))  # 63.4349: two up per one across
half_len = math.hypot(8, 16)
# surf ramp, lower half: slope from (0,0) to (8,16); solid behind it from x=8
write('surf_ramp_lower', side_slices(0, 8, 64, lambda x: 2 * x, 0) + [back_box(8, True), top_face(0, 0, half_len, round(ramp_angle, 4))])
# upper half (floor at 16 in ramp coordinates): slope from (8,16) to (16,32)
write('surf_ramp_upper', side_slices(8, 16, 64, lambda x: 2 * x, 16) + [back_plate(), top_face(8, 0, half_len, round(ramp_angle, 4))])
# surf slope: 45 degrees over one block
write('surf_slope', side_slices(0, 16, 64, lambda x: x, 0) + [back_plate(), top_face(0, 0, 16 * math.sqrt(2), 45)])
print('ok')
