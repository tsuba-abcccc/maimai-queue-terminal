import hmac
import json
import os
import re
import sqlite3
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Any
from uuid import UUID

from flask import Flask, current_app, jsonify, request


PUBLIC_SCHEMA_VERSION = 2
SUPPORTED_SCHEMA_VERSIONS = {1, 2}
MAX_PAYLOAD_BYTES = 128 * 1024
MAX_REGISTRATIONS_PER_MACHINE = 20
MAX_EVENTS_PER_SNAPSHOT = 200
MAX_STORED_EVENTS_PER_QUEUE = 2_000
MAX_LOG_PAGE_SIZE = 100
PUBLIC_ID_PATTERN = re.compile(r"^[0-9a-f]{24}$")
PREFERENCES = {"SOLO", "OPEN_TO_JOIN"}
STOP_REASONS = {"NOT_POWERED_ON", "NETWORK_DISCONNECTED", "OTHER"}
REGISTRATION_TYPES = {"TEMPORARY", "PLAYER_PROFILE"}
MACHINE_NAMES = {"A": "左侧 · 机台 A", "B": "右侧 · 机台 B"}
MAX_MACHINE_REMARK_CHARACTERS = 8
PUBLIC_EVENT_TYPES = {
    "REGISTRATION_ADDED",
    "REGISTRATION_REMOVED",
    "REGISTRATION_UPDATED",
    "QUEUE_REORDERED",
    "PLAYING_CHANGED",
    "NO_SHOW_DEFERRED",
    "NO_SHOW_MOVED_TO_TAIL",
    "NO_SHOW_REMOVED",
    "TEMPORARY_AWAY_EXPIRED",
    "ABSENCE_CHANGED",
    "MACHINE_STOPPED",
    "MACHINE_RESTORED",
    "REGISTRATION_OPENED",
    "REGISTRATION_CLOSED",
    "QUEUE_RESTORED",
    "QUEUE_RESET",
    "OTHER",
}


class ValidationError(ValueError):
    pass


def create_app(config: dict[str, Any] | None = None) -> Flask:
    app = Flask(__name__)
    app.config.update(
        DATABASE_PATH=os.getenv(
            "QUEUE_DATABASE_PATH",
            str(Path(__file__).resolve().parent / "data" / "queue.db"),
        ),
        SYNC_TOKEN=os.getenv("QUEUE_SYNC_TOKEN", ""),
        ALLOWED_DEVICE_ID=os.getenv("QUEUE_DEVICE_ID", ""),
        PRIMARY_DEVICE_ID=os.getenv("QUEUE_PRIMARY_DEVICE_ID", ""),
        ONLINE_TIMEOUT_SECONDS=int(os.getenv("QUEUE_ONLINE_TIMEOUT_SECONDS", "90")),
        CORS_ORIGIN=os.getenv("QUEUE_CORS_ORIGIN", "https://abcccc.top"),
        MAX_CONTENT_LENGTH=MAX_PAYLOAD_BYTES,
        JSON_AS_ASCII=False,
    )
    if config:
        app.config.update(config)

    initialize_database(app.config["DATABASE_PATH"])
    register_routes(app)
    return app


@contextmanager
def open_database():
    connection = sqlite3.connect(current_app.config["DATABASE_PATH"], timeout=10)
    connection.row_factory = sqlite3.Row
    try:
        yield connection
    finally:
        connection.close()


def initialize_database(database_path: str) -> None:
    path = Path(database_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(path, timeout=10)
    try:
        connection.execute("PRAGMA journal_mode = WAL")
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS queue_snapshot (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                queue_id TEXT NOT NULL,
                revision INTEGER NOT NULL,
                payload TEXT NOT NULL,
                device_id TEXT NOT NULL,
                received_at INTEGER NOT NULL
            )
            """
        )
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS queue_event (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                queue_id TEXT NOT NULL,
                event_id TEXT NOT NULL,
                occurred_at INTEGER NOT NULL,
                machine_id TEXT,
                event_type TEXT NOT NULL,
                title TEXT NOT NULL,
                detail TEXT NOT NULL,
                registration_ids TEXT NOT NULL,
                UNIQUE(queue_id, event_id)
            )
            """
        )
        connection.execute(
            """
            CREATE INDEX IF NOT EXISTS queue_event_queue_order
            ON queue_event(queue_id, id DESC)
            """
        )
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS retired_queue (
                queue_id TEXT PRIMARY KEY,
                retired_at INTEGER NOT NULL
            )
            """
        )
        connection.commit()
    finally:
        connection.close()


def register_routes(app: Flask) -> None:
    @app.after_request
    def add_response_headers(response):
        origin = current_app.config["CORS_ORIGIN"]
        response.headers["Access-Control-Allow-Origin"] = origin
        response.headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
        response.headers["Access-Control-Allow-Headers"] = (
            "Authorization, Content-Type, X-Device-ID, X-Queue-Schema-Version"
        )
        response.headers["Cache-Control"] = "no-store, max-age=0"
        response.headers["Pragma"] = "no-cache"
        response.headers["Vary"] = "Origin"
        return response

    @app.errorhandler(413)
    def payload_too_large(_error):
        return jsonify({"ok": False, "error": "队列数据超过大小限制"}), 413

    @app.get("/healthz")
    def health():
        with open_database() as connection:
            connection.execute("SELECT 1").fetchone()
        return jsonify({"status": "ok", "service": "maimai-queue-status"})

    @app.route("/api/queue-status", methods=["GET", "POST", "OPTIONS"])
    def queue_status():
        if request.method == "OPTIONS":
            return "", 204
        if request.method == "GET":
            return read_snapshot()
        return publish_snapshot()

    @app.get("/api/queue-logs")
    def queue_logs():
        return read_queue_logs()


def publish_snapshot():
    authorization_error = authorize_terminal()
    if authorization_error is not None:
        return authorization_error

    device_id = request.headers.get("X-Device-ID", "").strip()
    if not device_id or len(device_id) > 128:
        return jsonify({"ok": False, "error": "终端编号无效"}), 400

    payload = request.get_json(silent=True)
    if not isinstance(payload, dict):
        return jsonify({"ok": False, "error": "请求内容必须是 JSON 对象"}), 400

    try:
        normalized = normalize_snapshot(payload, device_id)
    except ValidationError as error:
        return jsonify({"ok": False, "error": str(error)}), 400

    events = normalized.pop("recent_events", [])
    queue_id = normalized["queue_id"]
    revision = normalized["revision"]
    now = int(time.time())
    serialized = json.dumps(normalized, ensure_ascii=False, separators=(",", ":"))

    with open_database() as connection:
        connection.execute("BEGIN IMMEDIATE")
        current = connection.execute(
            "SELECT queue_id, revision, device_id, received_at FROM queue_snapshot WHERE id = 1"
        ).fetchone()
        retired = connection.execute(
            "SELECT 1 FROM retired_queue WHERE queue_id = ?", (queue_id,)
        ).fetchone()

        if current is not None and current["device_id"] != device_id:
            primary_device_id = current_app.config["PRIMARY_DEVICE_ID"]
            incoming_is_primary = bool(primary_device_id) and device_id == primary_device_id
            current_is_online = (
                now - current["received_at"]
                <= current_app.config["ONLINE_TIMEOUT_SECONDS"]
            )
            if not incoming_is_primary and current_is_online:
                connection.rollback()
                return jsonify({"ok": False, "error": "另一终端仍在线，暂不能接管同步"}), 409

        if retired is not None:
            connection.rollback()
            return jsonify({"ok": False, "error": "此队列批次已经结束"}), 409

        if current is not None and current["queue_id"] == queue_id:
            if revision < current["revision"]:
                connection.rollback()
                return jsonify({"ok": False, "error": "队列版本早于服务器版本"}), 409
        elif current is not None and current["device_id"] == device_id:
            connection.execute(
                "INSERT OR IGNORE INTO retired_queue (queue_id, retired_at) VALUES (?, ?)",
                (current["queue_id"], now),
            )

        connection.execute(
            """
            INSERT INTO queue_snapshot
                (id, queue_id, revision, payload, device_id, received_at)
            VALUES
                (1, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                queue_id = excluded.queue_id,
                revision = excluded.revision,
                payload = excluded.payload,
                device_id = excluded.device_id,
                received_at = excluded.received_at
            """,
            (queue_id, revision, serialized, device_id, now),
        )
        for event in sorted(events, key=lambda value: value["occurred_at"]):
            connection.execute(
                """
                INSERT OR IGNORE INTO queue_event
                    (queue_id, event_id, occurred_at, machine_id, event_type,
                     title, detail, registration_ids)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    queue_id,
                    event["event_id"],
                    event["occurred_at"],
                    event["machine_id"],
                    event["type"],
                    event["title"],
                    event["detail"],
                    json.dumps(event["registration_ids"], separators=(",", ":")),
                ),
            )
        connection.execute(
            """
            DELETE FROM queue_event
            WHERE queue_id = ? AND id NOT IN (
                SELECT id FROM queue_event
                WHERE queue_id = ?
                ORDER BY id DESC
                LIMIT ?
            )
            """,
            (queue_id, queue_id, MAX_STORED_EVENTS_PER_QUEUE),
        )
        connection.commit()

    return "", 204


def read_snapshot():
    with open_database() as connection:
        row = connection.execute(
            "SELECT payload, device_id, received_at FROM queue_snapshot WHERE id = 1"
        ).fetchone()
    if row is None:
        return jsonify({"ok": False, "error": "排队终端暂未同步"}), 404

    payload = json.loads(row["payload"])
    now = int(time.time())
    last_seen_seconds = max(0, now - row["received_at"])
    payload["received_at"] = row["received_at"] * 1000
    payload["terminal"] = {
        **payload.get("terminal", {}),
        "id": row["device_id"],
        "online": last_seen_seconds <= current_app.config["ONLINE_TIMEOUT_SECONDS"],
        "last_seen_at": row["received_at"] * 1000,
        "last_seen_seconds": last_seen_seconds,
        "offline_after_seconds": current_app.config["ONLINE_TIMEOUT_SECONDS"],
    }
    payload["capabilities"] = public_capabilities()
    return jsonify(payload)


def read_queue_logs():
    queue_id = request.args.get("queue_id", "").strip()
    before_text = request.args.get("before", "").strip()
    limit_text = request.args.get("limit", "50").strip()

    try:
        limit = int(limit_text)
    except ValueError:
        return jsonify({"ok": False, "error": "limit 数值无效"}), 400
    if not 1 <= limit <= MAX_LOG_PAGE_SIZE:
        return jsonify({"ok": False, "error": "limit 数值无效"}), 400

    before = None
    if before_text:
        try:
            before = int(before_text)
        except ValueError:
            return jsonify({"ok": False, "error": "before 数值无效"}), 400
        if before <= 0:
            return jsonify({"ok": False, "error": "before 数值无效"}), 400

    with open_database() as connection:
        if not queue_id:
            current = connection.execute(
                "SELECT queue_id FROM queue_snapshot WHERE id = 1"
            ).fetchone()
            if current is None:
                return jsonify({"ok": False, "error": "排队终端暂未同步"}), 404
            queue_id = current["queue_id"]
        else:
            try:
                queue_id = str(UUID(queue_id))
            except ValueError:
                return jsonify({"ok": False, "error": "queue_id 必须是 UUID"}), 400

        query = """
            SELECT id, event_id, occurred_at, machine_id, event_type,
                   title, detail, registration_ids
            FROM queue_event
            WHERE queue_id = ?
        """
        parameters: list[Any] = [queue_id]
        if before is not None:
            query += " AND id < ?"
            parameters.append(before)
        query += " ORDER BY id DESC LIMIT ?"
        parameters.append(limit + 1)
        rows = connection.execute(query, parameters).fetchall()

    has_more = len(rows) > limit
    visible_rows = rows[:limit]
    logs = [
        {
            "cursor": row["id"],
            "event_id": row["event_id"],
            "occurred_at": row["occurred_at"],
            "machine_id": row["machine_id"],
            "type": row["event_type"],
            "title": row["title"],
            "detail": row["detail"],
            "registration_ids": json.loads(row["registration_ids"]),
        }
        for row in visible_rows
    ]
    return jsonify(
        {
            "queue_id": queue_id,
            "logs": logs,
            "next_cursor": visible_rows[-1]["id"] if has_more and visible_rows else None,
            "capabilities": public_capabilities(),
        }
    )


def public_capabilities() -> dict[str, Any]:
    return {
        "public_logs": True,
        "local_self_marking": True,
        "remote_actions": False,
        "transport": "polling",
    }


def authorize_terminal():
    expected_token = current_app.config["SYNC_TOKEN"]
    if not expected_token:
        return jsonify({"ok": False, "error": "服务器尚未配置终端令牌"}), 503

    authorization = request.headers.get("Authorization", "")
    supplied_token = authorization[7:] if authorization.startswith("Bearer ") else ""
    if not hmac.compare_digest(supplied_token, expected_token):
        return jsonify({"ok": False, "error": "终端认证失败"}), 401

    allowed_device_id = current_app.config["ALLOWED_DEVICE_ID"]
    supplied_device_id = request.headers.get("X-Device-ID", "").strip()
    if allowed_device_id and not hmac.compare_digest(supplied_device_id, allowed_device_id):
        return jsonify({"ok": False, "error": "此终端未获准同步"}), 403
    return None


def normalize_snapshot(payload: dict[str, Any], device_id: str) -> dict[str, Any]:
    schema_version = read_integer(payload, "schema_version", minimum=1)
    if schema_version not in SUPPORTED_SCHEMA_VERSIONS:
        raise ValidationError("不支持的队列数据版本")

    queue_id = read_uuid(payload, "queue_id")
    revision = read_integer(payload, "revision", minimum=1, maximum=2**63 - 1)
    captured_at = read_integer(payload, "captured_at", minimum=1)
    registration_open = read_boolean(payload, "registration_open")
    terminal = payload.get("terminal")
    if not isinstance(terminal, dict):
        raise ValidationError("terminal 必须是对象")
    app_version = read_string(terminal, "app_version", maximum_length=32)

    machines_source = payload.get("machines")
    if not isinstance(machines_source, dict):
        raise ValidationError("machines 必须是对象")
    if not all(machine_id in machines_source for machine_id in MACHINE_NAMES):
        raise ValidationError("必须同时提供机台 A 和机台 B")

    machines = {
        machine_id: normalize_machine(
            machine_id,
            machines_source[machine_id],
            allow_custom_name=schema_version >= 2,
        )
        for machine_id in MACHINE_NAMES
    }
    registration_ids = [
        registration["registration_id"]
        for machine in machines.values()
        for registration in all_machine_registrations(machine)
    ]
    if len(registration_ids) != len(set(registration_ids)):
        raise ValidationError("登记公开编号不能重复")

    recent_events = normalize_public_events(payload.get("recent_events", []))

    return {
        "schema_version": PUBLIC_SCHEMA_VERSION,
        "queue_id": queue_id,
        "revision": revision,
        "captured_at": captured_at,
        "registration_open": registration_open,
        "terminal": {
            "id": device_id,
            "online": True,
            "app_version": app_version,
            "last_seen_at": captured_at,
        },
        "machines": machines,
        "recent_events": recent_events,
    }


def normalize_public_events(source: Any) -> list[dict[str, Any]]:
    if not isinstance(source, list):
        raise ValidationError("recent_events 必须是数组")
    if len(source) > MAX_EVENTS_PER_SNAPSHOT:
        raise ValidationError("recent_events 数量超过限制")

    events = [normalize_public_event(value) for value in source]
    event_ids = [event["event_id"] for event in events]
    if len(event_ids) != len(set(event_ids)):
        raise ValidationError("公开事件编号不能重复")
    return events


def normalize_public_event(source: Any) -> dict[str, Any]:
    if not isinstance(source, dict):
        raise ValidationError("recent_events 包含无效事件")
    machine_id = source.get("machine_id")
    if machine_id is not None and machine_id not in MACHINE_NAMES:
        raise ValidationError("公开事件机台编号无效")
    registration_ids = source.get("registration_ids")
    if not isinstance(registration_ids, list) or len(registration_ids) > 20:
        raise ValidationError("公开事件登记编号无效")
    normalized_registration_ids = [
        read_public_id({"registration_id": value}, "registration_id")
        for value in registration_ids
    ]
    if len(normalized_registration_ids) != len(set(normalized_registration_ids)):
        raise ValidationError("公开事件登记编号不能重复")
    return {
        "event_id": read_uuid(source, "event_id"),
        "occurred_at": read_integer(source, "occurred_at", minimum=1),
        "machine_id": machine_id,
        "type": read_choice(source, "type", PUBLIC_EVENT_TYPES),
        "title": read_string(source, "title", maximum_length=120),
        "detail": read_string(source, "detail", maximum_length=2_000),
        "registration_ids": normalized_registration_ids,
    }


def normalize_machine(
    machine_id: str, source: Any, *, allow_custom_name: bool = False
) -> dict[str, Any]:
    if not isinstance(source, dict):
        raise ValidationError(f"机台 {machine_id} 必须是对象")

    operational = read_boolean(source, "operational")
    stop_reason = read_optional_choice(source, "stop_reason", STOP_REASONS)
    if operational and stop_reason is not None:
        raise ValidationError(f"正常使用的机台 {machine_id} 不能包含停止原因")
    if not operational and stop_reason is None:
        raise ValidationError(f"停止使用的机台 {machine_id} 必须包含停止原因")

    playing = normalize_registration_list(source.get("playing"), f"机台 {machine_id} 游玩位置")
    if len(playing) > 2:
        raise ValidationError(f"机台 {machine_id} 游玩位置不能超过 2 个登记")
    validate_registration_group(playing, f"机台 {machine_id} 游玩位置")

    positions_source = source.get("waiting_positions")
    if not isinstance(positions_source, list):
        raise ValidationError(f"机台 {machine_id} 的 waiting_positions 必须是数组")
    waiting_positions = [
        normalize_waiting_position(machine_id, index, position)
        for index, position in enumerate(positions_source)
    ]

    registration_count = len(playing) + sum(
        len(position["registrations"]) for position in waiting_positions
    )
    if registration_count > MAX_REGISTRATIONS_PER_MACHINE:
        raise ValidationError(f"机台 {machine_id} 超过 20 个登记")

    position_ids = [position["position_id"] for position in waiting_positions]
    if len(position_ids) != len(set(position_ids)):
        raise ValidationError(f"机台 {machine_id} 的等待位置编号不能重复")

    return {
        "id": machine_id,
        "name": normalize_machine_name(machine_id, source, allow_custom_name),
        "operational": operational,
        "stop_reason": stop_reason,
        "stopped_at": read_optional_integer(source, "stopped_at", minimum=1),
        "playing_started_at": read_optional_integer(
            source, "playing_started_at", minimum=1
        ),
        "registration_count": registration_count,
        "waiting_position_count": len(waiting_positions),
        "playing": playing,
        "waiting_positions": waiting_positions,
    }


def normalize_machine_name(
    machine_id: str, source: dict[str, Any], allow_custom_name: bool
) -> str:
    if not allow_custom_name:
        return MACHINE_NAMES[machine_id]

    suffix = f" · 机台 {machine_id}"
    name = read_string(
        source,
        "name",
        maximum_length=MAX_MACHINE_REMARK_CHARACTERS + len(suffix),
    )
    if not name.endswith(suffix):
        raise ValidationError(f"机台 {machine_id} 名称必须以“{suffix.strip()}”结尾")
    remark = name[: -len(suffix)]
    if not remark or remark != remark.strip() or len(remark) > MAX_MACHINE_REMARK_CHARACTERS:
        raise ValidationError(f"机台 {machine_id} 备注必须为 1 至 8 个字符")
    return f"{remark}{suffix}"


def normalize_waiting_position(machine_id: str, index: int, source: Any) -> dict[str, Any]:
    if not isinstance(source, dict):
        raise ValidationError(f"机台 {machine_id} 的等待位置必须是对象")
    registrations = normalize_registration_list(
        source.get("registrations"), f"机台 {machine_id} 位置 {index + 1}"
    )
    if not 1 <= len(registrations) <= 2:
        raise ValidationError(f"机台 {machine_id} 的每个等待位置必须包含 1 或 2 个登记")
    validate_registration_group(registrations, f"机台 {machine_id} 位置 {index + 1}")

    fixed_pair = read_boolean(source, "fixed_pair")
    actual_fixed_pair = registrations[0]["fixed_pair"] if registrations else False
    if fixed_pair != actual_fixed_pair:
        raise ValidationError(f"机台 {machine_id} 位置 {index + 1} 的固定组合状态不一致")

    return {
        "index": index + 1,
        "position_id": read_public_id(source, "position_id"),
        "fixed_pair": fixed_pair,
        "estimated_wait_minutes": read_optional_integer(
            source, "estimated_wait_minutes", minimum=0, maximum=24 * 60
        ),
        "registrations": registrations,
    }


def normalize_registration_list(source: Any, label: str) -> list[dict[str, Any]]:
    if not isinstance(source, list):
        raise ValidationError(f"{label}的登记必须是数组")
    return [normalize_registration(value, label) for value in source]


def normalize_registration(source: Any, label: str) -> dict[str, Any]:
    if not isinstance(source, dict):
        raise ValidationError(f"{label}包含无效登记")
    fixed_pair = read_boolean(source, "fixed_pair")
    fixed_pair_id = read_optional_public_id(source, "fixed_pair_id")
    if fixed_pair != (fixed_pair_id is not None):
        raise ValidationError(f"{label}的固定组合编号不一致")
    temporarily_away = source.get("temporarily_away", False)
    if type(temporarily_away) is not bool:
        raise ValidationError("temporarily_away 必须是布尔值")
    temporary_away_skipped_turns = source.get("temporary_away_skipped_turns", 0)
    if (
        type(temporary_away_skipped_turns) is not int
        or temporary_away_skipped_turns < 0
        or temporary_away_skipped_turns > 3
    ):
        raise ValidationError("temporary_away_skipped_turns 数值无效")
    if not temporarily_away and temporary_away_skipped_turns != 0:
        raise ValidationError("非暂时离开登记不能包含轮空次数")

    return {
        "registration_id": read_public_id(source, "registration_id"),
        "display_id": read_string(source, "display_id", maximum_length=18),
        "preference": read_choice(source, "preference", PREFERENCES),
        "deferred_once": read_boolean(source, "deferred_once"),
        "temporarily_away": temporarily_away,
        "temporary_away_skipped_turns": temporary_away_skipped_turns,
        "fixed_pair": fixed_pair,
        "fixed_pair_id": fixed_pair_id,
        "no_show_count": read_integer(source, "no_show_count", minimum=0, maximum=10_000),
        "last_no_show_action_was_defer": read_boolean(
            source, "last_no_show_action_was_defer"
        ),
        "registration_type": read_choice(
            source, "registration_type", REGISTRATION_TYPES
        ),
        "created_at": read_integer(source, "created_at", minimum=1),
        "last_played_at": read_optional_integer(source, "last_played_at", minimum=1),
    }


def validate_registration_group(registrations: list[dict[str, Any]], label: str) -> None:
    fixed_registrations = [registration for registration in registrations if registration["fixed_pair"]]
    if not fixed_registrations:
        return
    if len(registrations) != 2 or len(fixed_registrations) != 2:
        raise ValidationError(f"{label}的固定组合必须正好包含 2 个登记")
    pair_ids = {registration["fixed_pair_id"] for registration in fixed_registrations}
    if len(pair_ids) != 1:
        raise ValidationError(f"{label}的固定组合编号不一致")


def all_machine_registrations(machine: dict[str, Any]):
    yield from machine["playing"]
    for position in machine["waiting_positions"]:
        yield from position["registrations"]


def read_string(source: dict[str, Any], key: str, maximum_length: int) -> str:
    value = source.get(key)
    if not isinstance(value, str):
        raise ValidationError(f"{key} 必须是文本")
    value = value.strip()
    if not value or len(value) > maximum_length:
        raise ValidationError(f"{key} 长度无效")
    return value


def read_boolean(source: dict[str, Any], key: str) -> bool:
    value = source.get(key)
    if type(value) is not bool:
        raise ValidationError(f"{key} 必须是布尔值")
    return value


def read_integer(
    source: dict[str, Any],
    key: str,
    minimum: int,
    maximum: int | None = None,
) -> int:
    value = source.get(key)
    if type(value) is not int or value < minimum or (maximum is not None and value > maximum):
        raise ValidationError(f"{key} 数值无效")
    return value


def read_optional_integer(
    source: dict[str, Any],
    key: str,
    minimum: int,
    maximum: int | None = None,
) -> int | None:
    if source.get(key) is None:
        return None
    return read_integer(source, key, minimum, maximum)


def read_choice(source: dict[str, Any], key: str, choices: set[str]) -> str:
    value = source.get(key)
    if value not in choices:
        raise ValidationError(f"{key} 选项无效")
    return value


def read_optional_choice(
    source: dict[str, Any], key: str, choices: set[str]
) -> str | None:
    if source.get(key) is None:
        return None
    return read_choice(source, key, choices)


def read_uuid(source: dict[str, Any], key: str) -> str:
    value = source.get(key)
    if not isinstance(value, str):
        raise ValidationError(f"{key} 必须是 UUID")
    try:
        return str(UUID(value))
    except ValueError as error:
        raise ValidationError(f"{key} 必须是 UUID") from error


def read_public_id(source: dict[str, Any], key: str) -> str:
    value = source.get(key)
    if not isinstance(value, str) or PUBLIC_ID_PATTERN.fullmatch(value) is None:
        raise ValidationError(f"{key} 公开编号无效")
    return value


def read_optional_public_id(source: dict[str, Any], key: str) -> str | None:
    if source.get(key) is None:
        return None
    return read_public_id(source, key)


app = create_app()


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.getenv("PORT", "8080")), debug=False)
