"""Build the missing Beijing line assets from the Beijing Subway public API cache.

Station order, names, coordinates, and endpoint service boundaries come from the
official API. Segment durations, intermediate service times, and headways remain
estimates and are explicitly marked for review in every generated asset.
"""

from __future__ import annotations

import json
import math
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "tools" / "line-pipeline" / "out"
ASSETS = ROOT / "app" / "src" / "main" / "assets" / "metro"
API = "https://www.bjsubway.com/api/guanwang/v2/getStationDetail?accLocation={}"
LINES_API = "https://www.bjsubway.com/api/guanwang/v2/lineStations"

LINES = {
    "11号线": ("bj11", "line_beijing_11.json"),
    "12号线": ("bj12", "line_beijing_12.json"),
    "15号线": ("bj15", "line_beijing_15.json"),
    "16号线": ("bj16", "line_beijing_16.json"),
    "17号线": ("bj17", "line_beijing_17.json"),
    "18号线": ("bj18", "line_beijing_18.json"),
    "19号线": ("bj19", "line_beijing_19.json"),
    "亦庄线": ("bj_yizhuang", "line_beijing_yizhuang.json"),
    "亦庄T1线": ("bj_yizhuang_t1", "line_beijing_yizhuang_t1.json"),
    "房山线": ("bj_fangshan", "line_beijing_fangshan.json"),
    "燕房线": ("bj_yanfang", "line_beijing_yanfang.json"),
    "S1线": ("bj_s1", "line_beijing_s1.json"),
    "昌平线": ("bj_changping", "line_beijing_changping.json"),
    "西郊线": ("bj_xijiao", "line_beijing_xijiao.json"),
    "首都机场线": ("bj_capital_airport", "line_beijing_capital_airport.json"),
    "大兴机场线": ("bj_daxing_airport", "line_beijing_daxing_airport.json"),
}

# The official station-detail endpoint currently returns (0, 0) for these two
# interchange/station records. Keep explicit reviewed OSM fallbacks instead of
# allowing a false coordinate to break nearest-station detection.
COORDINATE_FALLBACKS = {
    "安贞桥": {"latitude": 39.9675, "longitude": 116.4010},
    "望京西": {"latitude": 39.9935892, "longitude": 116.4411647},
}


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def fetch(station_id: int) -> None:
    target = OUT / f"official-station-{station_id}.json"
    if target.exists():
        try:
            if read_json(target).get("status") == 200:
                return
        except (ValueError, OSError):
            pass
    request = urllib.request.Request(API.format(station_id), headers={"User-Agent": "MetroNearby-LinePipeline/1.0"})
    with urllib.request.urlopen(request, timeout=30) as response:
        payload = response.read()
    parsed = json.loads(payload)
    if parsed.get("status") != 200:
        raise RuntimeError(f"station {station_id}: {parsed.get('message')}")
    target.write_bytes(payload)


def load_official_lines() -> list[dict]:
    target = OUT / "official-lines.json"
    OUT.mkdir(parents=True, exist_ok=True)
    if not target.exists():
        request = urllib.request.Request(LINES_API, headers={"User-Agent": "MetroNearby-LinePipeline/1.0"})
        with urllib.request.urlopen(request, timeout=30) as response:
            target.write_bytes(response.read())
    payload = read_json(target)
    if payload.get("status") != 200:
        raise RuntimeError(f"failed to fetch official line list: {payload.get('message')}")
    return payload["data"]


def clock_seconds(value: str | None, default: str) -> int:
    value = value or default
    hour, minute = (int(part) for part in value.split(":"))
    return hour * 3600 + minute * 60


def format_clock(seconds: int) -> str:
    return f"{seconds // 3600:02d}:{seconds % 3600 // 60:02d}"


def normalized_window(first: str | None, last: str | None) -> tuple[int, int]:
    start = clock_seconds(first, "05:30")
    end = clock_seconds(last, "23:00")
    if end < start:
        end += 24 * 3600
    return start, end


def haversine(a: dict, b: dict) -> float:
    radius = 6_371_000.0
    lat1, lat2 = math.radians(a["lat"]), math.radians(b["lat"])
    dlat = lat2 - lat1
    dlng = math.radians(b["lng"] - a["lng"])
    h = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlng / 2) ** 2
    return 2 * radius * math.asin(math.sqrt(h))


def estimated_run_seconds(a: dict, b: dict, line_name: str) -> int:
    speed = 18.0 if "机场" in line_name else (7.0 if line_name in {"西郊线", "亦庄T1线"} else 10.0)
    return max(75, min(900, int(round((haversine(a, b) / speed + 30) / 5) * 5)))


def rules(start: int, end: int, weekend: bool) -> list[dict]:
    if weekend:
        boundaries = [(9 * 3600, 480), (20 * 3600, 360), (end, 480)]
    else:
        boundaries = [(7 * 3600, 420), (9 * 3600 + 30 * 60, 150), (17 * 3600, 360),
                      (19 * 3600 + 30 * 60, 150), (end, 420)]
    result = []
    cursor = start
    for boundary, interval in boundaries:
        stop = min(max(boundary, cursor), end)
        if stop > cursor:
            result.append({"from": format_clock(cursor), "to": format_clock(stop), "intervalSeconds": interval})
            cursor = stop
        if cursor >= end:
            break
    if cursor < end:
        result.append({"from": format_clock(cursor), "to": format_clock(end), "intervalSeconds": 420 if not weekend else 480})
    return result


def endpoint_window(details: list[dict], line_name: str, start_station_id: int) -> tuple[int, int]:
    candidates = []
    for detail in details:
        candidates.extend(detail["data"].get("lines") or [])
    exact = [item for item in candidates if item.get("lineName") == line_name
             and item.get("isHalf") == 0
             and item.get("startStationDeviceLocation") == start_station_id
             and item.get("firstTime")]
    item = exact[0] if exact else None
    return normalized_window(item.get("firstTime") if item else None, item.get("lastTime") if item else None)


def aliases(name: str) -> list[str]:
    return [name] if name.endswith("站") else [name, f"{name}站"]


def build(line: dict, city_colors: dict[str, str]) -> dict:
    line_name = line["lineCnName"]
    line_id, _ = LINES[line_name]
    details = [read_json(OUT / f"official-station-{station['accLocation']}.json") for station in line["stations"]]
    stations = []
    for index, (source, detail) in enumerate(zip(line["stations"], details), 1):
        position = detail["data"]["station"]["fixedPosition"]
        if not position:
            raise RuntimeError(f"{line_name}/{source['stationName']} lacks official coordinates")
        if not position["latitude"] and not position["longitude"]:
            position = COORDINATE_FALLBACKS.get(source["stationName"])
            if not position:
                raise RuntimeError(f"{line_name}/{source['stationName']} has a zero official coordinate")
        stations.append({
            "id": f"{line_id}_{index:02d}", "name": source["stationName"],
            "lat": position["latitude"], "lng": position["longitude"],
            "aliases": aliases(source["stationName"]),
        })
    station_order = [station["id"] for station in stations]
    segments = []
    for first, second in zip(stations, stations[1:]):
        segments.append({"from": first["id"], "to": second["id"],
                         "runSeconds": estimated_run_seconds(first, second, line_name)})
    forward = endpoint_window(details, line_name, line["stations"][0]["accLocation"])
    reverse = endpoint_window(details, line_name, line["stations"][-1]["accLocation"])
    patterns = []
    for pattern_id, start, end, window in (
        ("forward", stations[0], stations[-1], forward),
        ("reverse", stations[-1], stations[0], reverse),
    ):
        services = []
        for service_type in ("weekday", "weekend"):
            services.append({
                "serviceType": service_type,
                "firstDeparture": format_clock(window[0]),
                "lastDeparture": format_clock(window[1]),
                "headways": rules(window[0], window[1], service_type == "weekend"),
            })
        patterns.append({
            "id": pattern_id, "name": "全程车", "directionId": pattern_id,
            "directionLabel": f"开往 {end['name']}", "startStationId": start["id"],
            "endStationId": end["id"], "isShortTurn": False, "services": services,
        })
    service_times = {}
    forward_offset = 0
    total = sum(segment["runSeconds"] for segment in segments)
    for index, station in enumerate(stations):
        if index:
            forward_offset += segments[index - 1]["runSeconds"]
        reverse_offset = total - forward_offset
        service_times[station["id"]] = {
            "forward": {"firstDeparture": format_clock(forward[0] + forward_offset),
                        "lastDeparture": format_clock(forward[1] + forward_offset)},
            "reverse": {"firstDeparture": format_clock(reverse[0] + reverse_offset),
                        "lastDeparture": format_clock(reverse[1] + reverse_offset)},
        }
    return {
        "schemaVersion": 1, "cityId": "beijing", "cityName": "北京",
        "lineId": line_id, "lineName": line_name, "color": city_colors[line_id],
        "coordSystem": "wgs84", "updatedAt": "2026-09-19",
        "dataSource": "站序、站名、坐标及两端全程车首末班边界来自北京地铁官方网站；逐段运行时长、沿途到站时间与全天发车间隔为估算",
        "accuracyLevel": "estimated", "needsReview": True,
        "note": "官方站序、站名和坐标已核对；官网坐标暂为零值的安贞桥、望京西使用 OSM 站点坐标兜底。两端全程车首末班取自北京地铁官网站点详情；segments 按站距估算，stationServiceTimes 由端点时间和逐段时长推算，工作日/周末发车间隔为分时段估算，仍须逐项核对。所有到站结果仅以“预计”展示。",
        "stationOrder": station_order, "stations": stations,
        "stationServiceTimes": service_times, "segments": segments,
        "patterns": patterns, "exactDepartures": {},
    }


def main() -> None:
    official = load_official_lines()
    selected = [line for line in official if line["lineCnName"] in LINES]
    missing_names = set(LINES) - {line["lineCnName"] for line in selected}
    if missing_names:
        raise RuntimeError(f"official line list is missing: {sorted(missing_names)}")
    station_ids = sorted({station["accLocation"] for line in selected for station in line["stations"]})
    with ThreadPoolExecutor(max_workers=12) as pool:
        futures = {pool.submit(fetch, station_id): station_id for station_id in station_ids}
        for future in as_completed(futures):
            try:
                future.result()
            except Exception as exc:
                raise RuntimeError(f"failed to fetch station {futures[future]}: {exc}") from exc

    city_path = ASSETS / "city_beijing.json"
    city = read_json(city_path)
    refs = {item["lineId"]: item for item in city["lines"]}
    for line in selected:
        line_id, filename = LINES[line["lineCnName"]]
        if line_id not in refs:
            refs[line_id] = {"lineId": line_id, "name": line["lineCnName"], "color": f"#{line['lineColor']}"}
            city["lines"].append(refs[line_id])
        refs[line_id]["dataFile"] = filename
    colors = {line_id: ref["color"] for line_id, ref in refs.items()}
    for line in selected:
        _, filename = LINES[line["lineCnName"]]
        (ASSETS / filename).write_text(json.dumps(build(line, colors), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    numeric_order = {f"bj{number}": number for number in range(1, 20)}
    suffix_order = {line_id: 100 + index for index, line_id in enumerate([
        "bj_yizhuang", "bj_yizhuang_t1", "bj_fangshan", "bj_yanfang", "bj_s1",
        "bj_changping", "bj_xijiao", "bj_capital_airport", "bj_daxing_airport",
    ])}
    city["lines"].sort(key=lambda item: numeric_order.get(item["lineId"], suffix_order.get(item["lineId"], 999)))
    city_path.write_text(json.dumps(city, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"generated {len(selected)} line assets with {len(station_ids)} unique official stations")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(error, file=sys.stderr)
        raise
