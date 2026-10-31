#!/usr/bin/env python3
"""Small, privacy-conscious first-party visitor counter for Zhaoye."""

from __future__ import annotations

import hashlib
import hmac
import ipaddress
import json
import os
import secrets
import sqlite3
import threading
import time
import urllib.parse
import urllib.request
from pathlib import Path

from flask import Flask, g, jsonify, request


DB_PATH = Path(os.environ.get(
    "ZHAOYE_ANALYTICS_DB",
    "/var/lib/zhaoye-analytics/analytics.db",
))
ALLOWED_ORIGINS = {
    "https://zhaoye.8889.hair",
    "http://121.41.166.23",
}
GEO_ENDPOINT = "http://ip-api.com/json/{ip}"
GEO_FIELDS = "status,country,countryCode,regionName,city,lat,lon"
GEO_TTL_SECONDS = 86_400
VISIT_GAP_SECONDS = 8

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 2_048

_rate_lock = threading.Lock()
_last_request_by_visitor: dict[str, float] = {}


def db_connection() -> sqlite3.Connection:
    connection = sqlite3.connect(DB_PATH)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA journal_mode=WAL")
    connection.execute("PRAGMA busy_timeout=3000")
    return connection


def get_db() -> sqlite3.Connection:
    if "db" not in g:
        g.db = db_connection()
    return g.db


@app.teardown_appcontext
def close_db(_error=None) -> None:
    connection = g.pop("db", None)
    if connection is not None:
        connection.close()


def init_db() -> None:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    connection = db_connection()
    try:
        connection.executescript(
            """
            CREATE TABLE IF NOT EXISTS settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS geo_cache (
                visitor_hash TEXT PRIMARY KEY,
                location TEXT NOT NULL,
                country TEXT NOT NULL,
                country_code TEXT NOT NULL,
                lat REAL,
                lon REAL,
                updated_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS visits (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                visitor_hash TEXT NOT NULL,
                location TEXT NOT NULL,
                country TEXT NOT NULL,
                country_code TEXT NOT NULL,
                lat REAL,
                lon REAL,
                visit_date TEXT NOT NULL,
                created_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_visits_visitor ON visits(visitor_hash);
            CREATE INDEX IF NOT EXISTS idx_visits_location ON visits(location);
            """
        )
        connection.execute(
            "INSERT OR IGNORE INTO settings(key, value) VALUES('salt', ?)",
            (secrets.token_hex(32),),
        )
        connection.commit()
    finally:
        connection.close()


def salt() -> bytes:
    row = get_db().execute("SELECT value FROM settings WHERE key='salt'").fetchone()
    return bytes.fromhex(row["value"])


def visitor_hash(ip: str) -> str:
    return hmac.new(salt(), ip.encode("utf-8"), hashlib.sha256).hexdigest()


def client_ip() -> str:
    raw = request.headers.get("X-Real-IP", "").strip()
    try:
        return str(ipaddress.ip_address(raw))
    except ValueError:
        return ""


def lookup_location(visitor: str, ip: str) -> dict:
    db = get_db()
    cached = db.execute(
        "SELECT location, country, country_code, lat, lon FROM geo_cache WHERE visitor_hash=?",
        (visitor,),
    ).fetchone()
    now = int(time.time())
    if cached is not None:
        age = db.execute(
            "SELECT updated_at FROM geo_cache WHERE visitor_hash=?",
            (visitor,),
        ).fetchone()["updated_at"]
        if now - age < GEO_TTL_SECONDS:
            return dict(cached)

    result = {
        "location": "未知",
        "country": "",
        "country_code": "",
        "lat": None,
        "lon": None,
    }
    try:
        address = ipaddress.ip_address(ip)
        if not (address.is_private or address.is_loopback or address.is_reserved):
            query = urllib.parse.urlencode({
                "fields": GEO_FIELDS,
                "lang": "zh-CN",
            })
            url = f"{GEO_ENDPOINT.format(ip=urllib.parse.quote(ip))}?{query}"
            req = urllib.request.Request(url, headers={"User-Agent": "Zhaoye-Analytics/1.0"})
            with urllib.request.urlopen(req, timeout=2.5) as response:
                data = json.loads(response.read().decode("utf-8"))
            if data.get("status") == "success":
                result = {
                    "location": data.get("city") or data.get("regionName") or data.get("country") or "未知",
                    "country": data.get("country") or "",
                    "country_code": data.get("countryCode") or "",
                    "lat": data.get("lat"),
                    "lon": data.get("lon"),
                }
    except Exception:
        pass

    db.execute(
        """
        INSERT INTO geo_cache(visitor_hash, location, country, country_code, lat, lon, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(visitor_hash) DO UPDATE SET
          location=excluded.location,
          country=excluded.country,
          country_code=excluded.country_code,
          lat=excluded.lat,
          lon=excluded.lon,
          updated_at=excluded.updated_at
        """,
        (
            visitor,
            result["location"],
            result["country"],
            result["country_code"],
            result["lat"],
            result["lon"],
            now,
        ),
    )
    db.commit()
    return result


def stats_payload() -> dict:
    db = get_db()
    totals = db.execute(
        "SELECT COUNT(*) AS visits, COUNT(DISTINCT visitor_hash) AS visitors, MAX(created_at) AS updated_at FROM visits"
    ).fetchone()
    locations = db.execute(
        """
        SELECT location, country, lat, lon,
               COUNT(*) AS visits,
               COUNT(DISTINCT visitor_hash) AS visitors
        FROM visits
        WHERE lat IS NOT NULL AND lon IS NOT NULL
        GROUP BY location, country, lat, lon
        ORDER BY visits DESC, visitors DESC
        LIMIT 18
        """
    ).fetchall()
    city_count = db.execute(
        "SELECT COUNT(DISTINCT location) AS city_count FROM visits WHERE location != '未知'"
    ).fetchone()["city_count"]
    return {
        "ok": True,
        "views": totals["visits"],
        "visitors": totals["visitors"],
        "cities": city_count,
        "updated_at": totals["updated_at"],
        "locations": [dict(row) for row in locations],
    }


@app.get("/api/zhaoye/stats")
def stats():
    return jsonify(stats_payload())


@app.post("/api/zhaoye/visit")
def visit():
    origin = request.headers.get("Origin", "")
    if origin and origin not in ALLOWED_ORIGINS:
        return jsonify({"ok": False, "error": "origin not allowed"}), 403

    ip = client_ip()
    if not ip:
        return jsonify({"ok": False, "error": "client address unavailable"}), 400
    visitor = visitor_hash(ip)
    now = time.monotonic()
    with _rate_lock:
        previous = _last_request_by_visitor.get(visitor, 0.0)
        if now - previous < VISIT_GAP_SECONDS:
            return jsonify({"ok": True, "recorded": False, **stats_payload()})
        _last_request_by_visitor[visitor] = now

    location = lookup_location(visitor, ip)
    timestamp = time.gmtime()
    db = get_db()
    db.execute(
        """
        INSERT INTO visits(visitor_hash, location, country, country_code, lat, lon, visit_date, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            visitor,
            location["location"],
            location["country"],
            location["country_code"],
            location["lat"],
            location["lon"],
            time.strftime("%Y-%m-%d", timestamp),
            time.strftime("%Y-%m-%d %H:%M:%S", timestamp),
        ),
    )
    db.commit()
    return jsonify({"ok": True, "recorded": True, **stats_payload()})


@app.get("/health")
def health():
    return jsonify({"ok": True})


init_db()


if __name__ == "__main__":
    app.run(host="127.0.0.1", port=8090, debug=False)
