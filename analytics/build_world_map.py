#!/usr/bin/env python3
"""Build the lightweight visitor-map silhouette from Natural Earth data."""

from __future__ import annotations

import json
import math
import urllib.request
from pathlib import Path


SOURCE = (
    "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/"
    "master/geojson/ne_110m_land.geojson"
)
OUTPUT = Path(__file__).resolve().parents[1] / "site" / "zhaoye" / "assets" / "world-map.svg"
WIDTH = 1000
HEIGHT = 500


def project(lon: float, lat: float) -> tuple[float, float]:
    return ((lon + 180.0) * WIDTH / 360.0, (90.0 - lat) * HEIGHT / 180.0)


def perpendicular_distance(point, start, end) -> float:
    x, y = point
    x1, y1 = start
    x2, y2 = end
    dx, dy = x2 - x1, y2 - y1
    if dx == 0 and dy == 0:
        return math.hypot(x - x1, y - y1)
    return abs(dy * x - dx * y + x2 * y1 - y2 * x1) / math.hypot(dx, dy)


def simplify(points: list[tuple[float, float]], tolerance: float) -> list[tuple[float, float]]:
    if len(points) <= 3:
        return points
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        start, end = stack.pop()
        farthest, distance = -1, tolerance
        for index in range(start + 1, end):
            candidate = perpendicular_distance(points[index], points[start], points[end])
            if candidate > distance:
                farthest, distance = index, candidate
        if farthest >= 0:
            keep[farthest] = True
            stack.append((start, farthest))
            stack.append((farthest, end))
    return [point for point, selected in zip(points, keep) if selected]


def ring_area(points: list[tuple[float, float]]) -> float:
    return abs(sum(
        points[index][0] * points[(index + 1) % len(points)][1]
        - points[(index + 1) % len(points)][0] * points[index][1]
        for index in range(len(points))
    )) / 2.0


def ring_path(ring: list[list[float]]) -> str:
    projected = [project(lon, lat) for lon, lat, *_ in ring]
    projected = simplify(projected, 0.55)
    if len(projected) < 4 or ring_area(projected) < 1.0:
        return ""
    commands = [f"M{projected[0][0]:.1f},{projected[0][1]:.1f}"]
    commands.extend(f"L{x:.1f},{y:.1f}" for x, y in projected[1:])
    commands.append("Z")
    return "".join(commands)


def main() -> None:
    with urllib.request.urlopen(SOURCE, timeout=30) as response:
        collection = json.loads(response.read().decode("utf-8"))

    paths: list[str] = []
    for feature in collection["features"]:
        geometry = feature.get("geometry") or {}
        kind = geometry.get("type")
        coordinates = geometry.get("coordinates") or []
        polygons = [coordinates] if kind == "Polygon" else coordinates
        for polygon in polygons:
            for ring in polygon:
                path = ring_path(ring)
                if path:
                    paths.append(path)

    body = "".join(paths)
    svg = (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 500">'
        "<title>World land silhouette</title>"
        '<desc>Derived from Natural Earth 1:110m land data, public domain.</desc>'
        f'<path fill="#332c24" stroke="#4a4033" stroke-width="0.45" fill-rule="evenodd" d="{body}"/>'
        "</svg>"
    )
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(svg, encoding="utf-8")
    print(f"wrote {OUTPUT} ({len(svg)} bytes)")


if __name__ == "__main__":
    main()
