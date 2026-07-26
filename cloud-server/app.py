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


PUBLIC_SCHEMA_VERSION = 4
SUPPORTED_SCHEMA_VERSIONS = {1, 2, 3, 4}
MAX_PAYLOAD_BYTES = 1024 * 1024
MAX_REGISTRATIONS_PER_MACHINE = 20
MAX_PRIVATE_CONTACTS = MAX_REGISTRATIONS_PER_MACHINE * 2
MAX_PLAYER_PROFILES = 500
MAX_EVENTS_PER_SNAPSHOT = 200
MAX_STORED_EVENTS_PER_QUEUE = 2_000
MAX_LOG_PAGE_SIZE = 100
MAX_STOP_REASON_DETAIL_CHARACTERS = 40
PUBLIC_ID_PATTERN = re.compile(r"^[0-9a-f]{24}$")
QQ_NUMBER_PATTERN = re.compile(r"^[0-9]{5,12}$")
PREFERENCES = {"SOLO", "OPEN_TO_JOIN"}
STOP_REASONS = {"NOT_POWERED_ON", "NETWORK_DISCONNECTED", "MAINTENANCE", "OTHER"}
REGISTRATION_TYPES = {"TEMPORARY", "PLAYER_PROFILE"}
PLAYER_GENDERS = {"MALE", "FEMALE", "UNDISCLOSED"}
PROFILE_PREFERENCES = {"SOLO", "OPEN_TO_JOIN", "ASK_EVERY_TIME"}
PROFILE_UPDATE_COMMAND = "UPDATE_PLAYER_PROFILE"
QUEUE_OPERATION_COMMAND = "QUEUE_OPERATION"
QUEUE_OPERATIONS = {
    "JOIN_QUEUE",
    "DEFER_ONE_ROUND",
    "CANCEL_DEFER_ONE_ROUND",
    "TEMPORARILY_LEAVE",
    "CANCEL_TEMPORARY_LEAVE",
    "TRANSFER_MACHINE",
    "CHANGE_PLAY_PREFERENCE",
    "LEAVE_QUEUE",
}
RESULT_SOURCE_TERMINAL = "TERMINAL"
RESULT_SOURCE_SERVER_TIMEOUT = "SERVER_TIMEOUT"
RESULT_SOURCE_SERVER_MIGRATION = "SERVER_MIGRATION"
RESULT_SOURCE_BOT_DISABLED = "BOT_DISABLED"
COMMAND_TIMEOUT_DETAIL = "终端未在有效时间内处理这次修改，请重新提交。"
BOT_DISABLED_DETAIL = "QQ Bot 联动已关闭，这次修改没有执行。"
TEST_SYNC_ENDED_DETAIL = "测试同步已经结束，这次修改没有执行，请重新提交。"
SYNC_MODES = {"test", "takeover"}
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
    "ONLINE_CHECK_IN_TIMED_OUT",
    "ONLINE_CHECK_IN_MISSED",
    "ABSENCE_CHANGED",
    "MACHINE_STOPPED",
    "MACHINE_RESTORED",
    "REGISTRATION_OPENED",
    "REGISTRATION_CLOSED",
    "QUEUE_RESTORED",
    "QUEUE_RESET",
    "OTHER",
}
OPERATION_SOURCES = {
    "ON_SITE_TERMINAL",
    "QQ_BOT",
    "SYSTEM_AUTOMATIC",
    "WEBSITE_REMOTE",
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
        BOT_TOKEN=os.getenv("QUEUE_BOT_TOKEN", ""),
        PROFILE_SCOPE_ID=os.getenv("QUEUE_PROFILE_SCOPE_ID", "default"),
        ALLOWED_DEVICE_ID=os.getenv("QUEUE_DEVICE_ID", ""),
        PRIMARY_DEVICE_ID=os.getenv("QUEUE_PRIMARY_DEVICE_ID", ""),
        ONLINE_TIMEOUT_SECONDS=int(os.getenv("QUEUE_ONLINE_TIMEOUT_SECONDS", "90")),
        COMMAND_TIMEOUT_SECONDS=int(os.getenv("QUEUE_COMMAND_TIMEOUT_SECONDS", "600")),
        COMMAND_RETENTION_SECONDS=int(
            os.getenv("QUEUE_COMMAND_RETENTION_SECONDS", "2592000")
        ),
        EVENT_RECIPIENT_RETENTION_SECONDS=int(
            os.getenv("QUEUE_EVENT_RECIPIENT_RETENTION_SECONDS", "2592000")
        ),
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
    connection.execute("PRAGMA secure_delete = ON")
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
        connection.execute("PRAGMA secure_delete = ON")
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
            CREATE TABLE IF NOT EXISTS terminal_command (
                command_id TEXT PRIMARY KEY,
                device_id TEXT NOT NULL,
                command_type TEXT NOT NULL,
                payload TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                completed_at INTEGER,
                result_detail TEXT,
                claimed_at INTEGER,
                claimed_terminal TEXT,
                result_source TEXT
            )
            """
        )
        terminal_command_columns = {
            row[1] for row in connection.execute("PRAGMA table_info(terminal_command)")
        }
        for column_name, declaration in (
            ("claimed_at", "INTEGER"),
            ("claimed_terminal", "TEXT"),
            ("result_source", "TEXT"),
        ):
            if column_name not in terminal_command_columns:
                connection.execute(
                    f"ALTER TABLE terminal_command ADD COLUMN {column_name} {declaration}"
                )
        connection.execute(
            """
            UPDATE terminal_command
            SET result_source = CASE
                WHEN status = 'REJECTED' AND result_detail = ? THEN ?
                WHEN status != 'PENDING' THEN ?
                ELSE NULL
            END
            WHERE result_source IS NULL
            """,
            (
                COMMAND_TIMEOUT_DETAIL,
                RESULT_SOURCE_SERVER_TIMEOUT,
                RESULT_SOURCE_TERMINAL,
            ),
        )
        connection.execute(
            """
            CREATE INDEX IF NOT EXISTS terminal_command_pending
            ON terminal_command(device_id, status, created_at)
            """
        )
        pending_profile_commands = connection.execute(
            """
            SELECT command_id, device_id, command_type,
                   json_extract(payload, '$.profile_id') AS profile_id
            FROM terminal_command
            WHERE status = 'PENDING' AND command_type = 'UPDATE_PLAYER_PROFILE'
            ORDER BY created_at, command_id
            """
        ).fetchall()
        seen_pending_profiles: set[tuple[str, str, str]] = set()
        duplicate_pending_command_ids = []
        for command_id, device_id, command_type, profile_id in pending_profile_commands:
            identity = (device_id, command_type, profile_id or "")
            if identity in seen_pending_profiles:
                duplicate_pending_command_ids.append((int(time.time()), command_id))
            else:
                seen_pending_profiles.add(identity)
        connection.executemany(
            """
            UPDATE terminal_command
            SET status = 'REJECTED', completed_at = ?,
                result_detail = '检测到重复的待处理命令，请重新提交。',
                result_source = ?
            WHERE command_id = ?
            """,
            [
                (completed_at, RESULT_SOURCE_SERVER_MIGRATION, command_id)
                for completed_at, command_id in duplicate_pending_command_ids
            ],
        )
        connection.execute(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS terminal_command_one_pending_profile
            ON terminal_command(
                device_id,
                command_type,
                json_extract(payload, '$.profile_id')
            )
            WHERE status = 'PENDING'
            """
        )
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS player_profile (
                device_id TEXT NOT NULL,
                profile_id TEXT NOT NULL,
                nickname TEXT NOT NULL,
                gender TEXT NOT NULL,
                default_preference TEXT NOT NULL,
                qq_number TEXT,
                usage_count INTEGER NOT NULL,
                last_used_at INTEGER,
                created_at INTEGER NOT NULL,
                profile_updated_at INTEGER NOT NULL,
                received_at INTEGER NOT NULL,
                PRIMARY KEY(device_id, profile_id)
            )
            """
        )
        connection.execute(
            """
            CREATE INDEX IF NOT EXISTS player_profile_qq
            ON player_profile(device_id, qq_number)
            """
        )
        duplicate_profile_contacts = connection.execute(
            """
            SELECT device_id, qq_number
            FROM player_profile
            WHERE qq_number IS NOT NULL
            GROUP BY device_id, qq_number
            HAVING COUNT(*) > 1
            """
        ).fetchall()
        connection.executemany(
            """
            UPDATE player_profile
            SET qq_number = NULL
            WHERE device_id = ? AND qq_number = ?
            """,
            duplicate_profile_contacts,
        )
        connection.execute(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS player_profile_unique_qq
            ON player_profile(device_id, qq_number)
            WHERE qq_number IS NOT NULL
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
                operation_source TEXT NOT NULL DEFAULT 'ON_SITE_TERMINAL',
                registration_ids TEXT NOT NULL,
                UNIQUE(queue_id, event_id)
            )
            """
        )
        queue_event_columns = {
            row[1] for row in connection.execute("PRAGMA table_info(queue_event)")
        }
        if "operation_source" not in queue_event_columns:
            connection.execute(
                """
                ALTER TABLE queue_event
                ADD COLUMN operation_source TEXT NOT NULL DEFAULT 'ON_SITE_TERMINAL'
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
            CREATE TABLE IF NOT EXISTS queue_event_recipient (
                queue_id TEXT NOT NULL,
                event_id TEXT NOT NULL,
                registration_id TEXT NOT NULL,
                profile_id TEXT NOT NULL,
                qq_number TEXT NOT NULL,
                stored_at INTEGER NOT NULL,
                PRIMARY KEY(queue_id, event_id, registration_id)
            )
            """
        )
        event_recipient_columns = {
            row[1] for row in connection.execute("PRAGMA table_info(queue_event_recipient)")
        }
        if "stored_at" not in event_recipient_columns:
            connection.execute(
                "ALTER TABLE queue_event_recipient ADD COLUMN stored_at INTEGER"
            )
        connection.execute(
            "UPDATE queue_event_recipient SET stored_at = ? WHERE stored_at IS NULL",
            (int(time.time()),),
        )
        connection.execute(
            """
            CREATE INDEX IF NOT EXISTS queue_event_recipient_queue
            ON queue_event_recipient(queue_id, event_id)
            """
        )
        connection.execute(
            """
            CREATE INDEX IF NOT EXISTS queue_event_recipient_retention
            ON queue_event_recipient(stored_at)
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
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS queue_private_contact (
                queue_id TEXT NOT NULL,
                registration_id TEXT NOT NULL,
                player_id TEXT NOT NULL,
                qq_number TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(queue_id, registration_id),
                UNIQUE(queue_id, player_id)
            )
            """
        )
        connection.execute(
            """
            CREATE INDEX IF NOT EXISTS queue_private_contact_qq
            ON queue_private_contact(queue_id, qq_number)
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
        response.headers["Access-Control-Allow-Methods"] = "GET, POST, PATCH, OPTIONS"
        response.headers["Access-Control-Allow-Headers"] = (
            "Authorization, Content-Type, X-Device-ID, X-Queue-Schema-Version, "
            "X-Queue-Sync-Mode"
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

    @app.route("/api/queue-bot/players", methods=["GET", "POST"])
    def queue_bot_players():
        authorization_error = authorize_bot_link()
        if authorization_error is not None:
            return authorization_error
        return read_bot_players()

    @app.route("/api/queue-bot/events", methods=["GET", "POST"])
    def queue_bot_events():
        authorization_error = authorize_bot_link()
        if authorization_error is not None:
            return authorization_error
        return read_bot_events()

    @app.route("/api/queue-bot/profiles", methods=["GET", "POST"])
    def queue_bot_profiles():
        authorization_error = authorize_bot_link()
        if authorization_error is not None:
            return authorization_error
        return read_bot_profiles()

    @app.patch("/api/queue-bot/profiles/<profile_id>")
    def queue_bot_update_profile(profile_id: str):
        authorization_error = authorize_bot_link()
        if authorization_error is not None:
            return authorization_error
        return create_profile_update_command(profile_id)

    @app.post("/api/queue-bot/queue-commands")
    def queue_bot_create_queue_command():
        authorization_error = authorize_bot_link()
        if authorization_error is not None:
            return authorization_error
        return create_bot_queue_operation_command()

    @app.get("/api/queue-bot/commands/<command_id>")
    def queue_bot_command(command_id: str):
        authorization_error = authorize_bot_link()
        if authorization_error is not None:
            return authorization_error
        return read_bot_command(command_id)

    @app.post("/api/queue-online/profile")
    def queue_online_profile():
        return read_online_profile()

    @app.post("/api/queue-online/join")
    def queue_online_join():
        return create_website_join_command()

    @app.get("/api/queue-online/commands/<command_id>")
    def queue_online_command(command_id: str):
        return read_website_command(command_id)

    @app.get("/api/queue-terminal/commands")
    def queue_terminal_commands():
        authorization_error = authorize_terminal()
        if authorization_error is not None:
            return authorization_error
        return read_terminal_commands()

    @app.get("/api/queue-terminal/profiles")
    def queue_terminal_profiles():
        authorization_error = authorize_terminal()
        if authorization_error is not None:
            return authorization_error
        return read_synced_profiles(allow_qq_filter=False)

    @app.post("/api/queue-terminal/commands/<command_id>/result")
    def queue_terminal_command_result(command_id: str):
        authorization_error = authorize_terminal()
        if authorization_error is not None:
            return authorization_error
        return complete_terminal_command(command_id)


def publish_snapshot():
    authorization_error = authorize_terminal()
    if authorization_error is not None:
        return authorization_error

    device_id = request.headers.get("X-Device-ID", "").strip()
    if not device_id or len(device_id) > 128:
        return jsonify({"ok": False, "error": "终端编号无效"}), 400

    requested_sync_mode = request.headers.get("X-Queue-Sync-Mode", "").strip().lower()
    if requested_sync_mode and requested_sync_mode not in SYNC_MODES:
        return jsonify({"ok": False, "error": "同步方式无效"}), 400

    payload = request.get_json(silent=True)
    if not isinstance(payload, dict):
        return jsonify({"ok": False, "error": "请求内容必须是 JSON 对象"}), 400

    try:
        normalized = normalize_snapshot(payload, device_id)
    except ValidationError as error:
        return jsonify({"ok": False, "error": str(error)}), 400

    events = normalized.pop("recent_events", [])
    private_contacts = normalized.pop("private_player_contacts", [])
    private_profiles = normalized.pop("private_player_profiles", None)
    queue_id = normalized["queue_id"]
    revision = normalized["revision"]
    onebot_sync_enabled = normalized["onebot_sync_enabled"]
    current_registration_ids = {
        registration["registration_id"]
        for machine in normalized["machines"].values()
        for registration in all_machine_registrations(machine)
    }
    current_contact_ids = {
        contact["registration_id"] for contact in private_contacts
    }
    now = int(time.time())

    with open_database() as connection:
        connection.execute("BEGIN IMMEDIATE")
        cleanup_expired_event_recipients(connection, now)
        current = connection.execute(
            """
            SELECT queue_id, revision, payload, device_id, received_at
            FROM queue_snapshot WHERE id = 1
            """
        ).fetchone()
        current_payload = json.loads(current["payload"]) if current is not None else None
        current_is_test = bool(current_payload and current_payload.get("test_data", False))
        incoming_is_test = requested_sync_mode == "test"
        primary_device_id = current_app.config["PRIMARY_DEVICE_ID"]
        incoming_is_primary = bool(primary_device_id) and device_id == primary_device_id
        current_is_online = bool(
            current
            and now - current["received_at"]
            <= current_app.config["ONLINE_TIMEOUT_SECONDS"]
        )

        if current is None:
            # There is no existing queue to displace, so the first authenticated terminal can
            # establish the official snapshot without a takeover choice.
            incoming_is_test = requested_sync_mode == "test"
        elif current["device_id"] == device_id:
            if not requested_sync_mode:
                incoming_is_test = current_is_test
        elif incoming_is_primary:
            # The configured primary terminal always restores the official view. This is what
            # makes a test session temporary even if the test terminal is still running.
            incoming_is_test = requested_sync_mode == "test"
        else:
            if current_is_online:
                connection.rollback()
                return jsonify(
                    {
                        "ok": False,
                        "code": "TERMINAL_STILL_ONLINE",
                        "error": "另一终端仍在线，暂不能开始测试或接管同步",
                    }
                ), 409
            if not requested_sync_mode:
                connection.rollback()
                return sync_mode_required_response(
                    current_payload, normalized, current_is_online
                )

        normalized["test_data"] = incoming_is_test
        serialized = json.dumps(normalized, ensure_ascii=False, separators=(",", ":"))
        previous_queue_contacts = {}
        if current is not None and current["queue_id"] != queue_id:
            previous_queue_contacts = {
                row["registration_id"]: row
                for row in connection.execute(
                    """
                    SELECT registration_id, player_id, qq_number
                    FROM queue_private_contact
                    WHERE queue_id = ?
                    """,
                    (current["queue_id"],),
                ).fetchall()
            }
        retired = connection.execute(
            "SELECT 1 FROM retired_queue WHERE queue_id = ?", (queue_id,)
        ).fetchone()

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
        if current is None or current["queue_id"] != queue_id:
            connection.execute("DELETE FROM queue_private_contact")
        stored_contacts = {
            row["registration_id"]: row
            for row in connection.execute(
                """
                SELECT registration_id, player_id, qq_number
                FROM queue_private_contact
                WHERE queue_id = ?
                """,
                (queue_id,),
            ).fetchall()
        }
        connection.executemany(
            """
            DELETE FROM queue_private_contact
            WHERE queue_id = ? AND player_id = ? AND registration_id != ?
            """,
            [
                (
                    queue_id,
                    contact["profile_id"],
                    contact["registration_id"],
                )
                for contact in private_contacts
            ],
        )
        connection.executemany(
            """
            INSERT INTO queue_private_contact
                (queue_id, registration_id, player_id, qq_number, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(queue_id, registration_id) DO UPDATE SET
                player_id = excluded.player_id,
                qq_number = excluded.qq_number,
                updated_at = excluded.updated_at
            """,
            [
                (
                    queue_id,
                    contact["registration_id"],
                    contact["profile_id"],
                    contact["qq_number"],
                    now,
                )
                for contact in private_contacts
            ],
        )
        stored_contacts.update(
            {
                contact["registration_id"]: {
                    "registration_id": contact["registration_id"],
                    "player_id": contact["profile_id"],
                    "qq_number": contact["qq_number"],
                }
                for contact in private_contacts
            }
        )
        event_contacts = {
            registration_id: contact
            for registration_id, contact in stored_contacts.items()
            if registration_id not in current_registration_ids
            or registration_id in current_contact_ids
        }
        if private_profiles is not None:
            profile_scope_id = current_app.config["PROFILE_SCOPE_ID"]
            connection.executemany(
                """
                UPDATE player_profile
                SET qq_number = NULL
                WHERE device_id = ? AND profile_id != ? AND qq_number = ?
                """,
                [
                    (profile_scope_id, profile["profile_id"], profile["qq_number"])
                    for profile in private_profiles
                    if profile["qq_number"] is not None
                ],
            )
            connection.executemany(
                """
                INSERT INTO player_profile
                    (device_id, profile_id, nickname, gender, default_preference,
                     qq_number, usage_count, last_used_at, created_at,
                     profile_updated_at, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(device_id, profile_id) DO UPDATE SET
                    nickname = excluded.nickname,
                    gender = excluded.gender,
                    default_preference = excluded.default_preference,
                    qq_number = excluded.qq_number,
                    usage_count = excluded.usage_count,
                    last_used_at = excluded.last_used_at,
                    created_at = excluded.created_at,
                    profile_updated_at = excluded.profile_updated_at,
                    received_at = excluded.received_at
                """,
                [
                    (
                        profile_scope_id,
                        profile["profile_id"],
                        profile["nickname"],
                        profile["gender"],
                        profile["default_preference"],
                        profile["qq_number"],
                        profile["usage_count"],
                        profile["last_used_at"],
                        profile["created_at"],
                        profile["updated_at"],
                        now,
                    )
                    for profile in private_profiles
                ],
            )
        if not onebot_sync_enabled:
            connection.execute(
                "DELETE FROM queue_event_recipient WHERE queue_id = ?",
                (queue_id,),
            )
            connection.execute(
                """
                UPDATE terminal_command
                SET status = 'REJECTED', completed_at = ?,
                    result_detail = ?, result_source = ?
                WHERE status = 'PENDING' AND (
                    command_type = ?
                    OR json_extract(payload, '$.operation_source') = 'QQ_BOT'
                )
                """,
                (
                    now,
                    BOT_DISABLED_DETAIL,
                    RESULT_SOURCE_BOT_DISABLED,
                    PROFILE_UPDATE_COMMAND,
                ),
            )
        for event in sorted(events, key=lambda value: value["occurred_at"]):
            event_registration_ids = (
                list(previous_queue_contacts)
                if event["type"] == "QUEUE_RESET" and previous_queue_contacts
                else event["registration_ids"]
            )
            inserted_event = connection.execute(
                """
                INSERT OR IGNORE INTO queue_event
                    (queue_id, event_id, occurred_at, machine_id, event_type,
                     title, detail, operation_source, registration_ids)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    queue_id,
                    event["event_id"],
                    event["occurred_at"],
                    event["machine_id"],
                    event["type"],
                    event["title"],
                    event["detail"],
                    event["operation_source"],
                    json.dumps(event_registration_ids, separators=(",", ":")),
                ),
            )
            if inserted_event.rowcount == 1 and onebot_sync_enabled:
                if event["type"] == "QUEUE_RESET" and previous_queue_contacts:
                    recipient_contacts = previous_queue_contacts.values()
                else:
                    recipient_contacts = (
                        event_contacts[registration_id]
                        for registration_id in event_registration_ids
                        if registration_id in event_contacts
                    )
                connection.executemany(
                    """
                    INSERT INTO queue_event_recipient
                        (queue_id, event_id, registration_id, profile_id, qq_number,
                         stored_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    [
                        (
                            queue_id,
                            event["event_id"],
                            contact["registration_id"],
                            contact["player_id"],
                            contact["qq_number"],
                            now,
                        )
                        for contact in recipient_contacts
                    ],
                )
        stale_current_contact_ids = current_registration_ids - current_contact_ids
        connection.executemany(
            """
            DELETE FROM queue_private_contact
            WHERE queue_id = ? AND registration_id = ?
            """,
            [(queue_id, registration_id) for registration_id in stale_current_contact_ids],
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
        connection.execute(
            """
            DELETE FROM queue_event_recipient
            WHERE queue_id = ? AND event_id NOT IN (
                SELECT event_id FROM queue_event WHERE queue_id = ?
            )
            """,
            (queue_id, queue_id),
        )
        if current is not None and current["device_id"] != device_id:
            if incoming_is_test:
                # Official commands remain assigned to the official terminal while a temporary
                # test queue is active.
                pass
            elif current_is_test:
                connection.execute(
                    """
                    UPDATE terminal_command
                    SET status = 'REJECTED', completed_at = ?, result_detail = ?,
                        result_source = ?
                    WHERE status = 'PENDING' AND device_id = ?
                    """,
                    (
                        now,
                        TEST_SYNC_ENDED_DETAIL,
                        RESULT_SOURCE_SERVER_MIGRATION,
                        current["device_id"],
                    ),
                )
            else:
                connection.execute(
                    """
                    UPDATE terminal_command
                    SET device_id = ?, claimed_at = NULL, claimed_terminal = NULL
                    WHERE status = 'PENDING'
                    """,
                    (device_id,),
                )
        connection.commit()

    return "", 204


def sync_mode_required_response(
    current_payload: dict[str, Any] | None,
    incoming_payload: dict[str, Any],
    current_online: bool,
):
    return jsonify(
        {
            "ok": False,
            "code": "SYNC_MODE_REQUIRED",
            "error": "请选择测试同步或接管同步后再继续",
            "current_registration_count": snapshot_registration_count(current_payload),
            "local_registration_count": snapshot_registration_count(incoming_payload),
            "current_terminal_online": current_online,
        }
    ), 409


def snapshot_registration_count(snapshot: dict[str, Any] | None) -> int:
    if not snapshot:
        return 0
    return sum(
        machine.get("registration_count", 0)
        for machine in snapshot.get("machines", {}).values()
        if isinstance(machine, dict)
    )


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
    terminal_online = last_seen_seconds <= current_app.config["ONLINE_TIMEOUT_SECONDS"]
    payload["terminal"] = {
        **payload.get("terminal", {}),
        "id": row["device_id"],
        "online": terminal_online,
        "last_seen_at": row["received_at"] * 1000,
        "last_seen_seconds": last_seen_seconds,
        "offline_after_seconds": current_app.config["ONLINE_TIMEOUT_SECONDS"],
    }
    payload["capabilities"] = public_capabilities(payload, terminal_online)
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
                   title, detail, operation_source, registration_ids
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
            "operation_source": row["operation_source"],
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


def read_bot_players():
    qq_number, filter_error = read_private_qq_filter()
    if filter_error is not None:
        return filter_error

    with open_database() as connection:
        snapshot_row = connection.execute(
            "SELECT queue_id, revision, payload, received_at FROM queue_snapshot WHERE id = 1"
        ).fetchone()
        if snapshot_row is None:
            return jsonify({"ok": False, "error": "排队终端暂未同步"}), 404
        query = """
            SELECT registration_id, player_id, qq_number, updated_at
            FROM queue_private_contact
            WHERE queue_id = ?
        """
        parameters: list[Any] = [snapshot_row["queue_id"]]
        if qq_number:
            query += " AND qq_number = ?"
            parameters.append(qq_number)
        query += " ORDER BY registration_id"
        contacts = connection.execute(query, parameters).fetchall()

    snapshot = json.loads(snapshot_row["payload"])
    last_seen_seconds = max(0, int(time.time()) - snapshot_row["received_at"])
    registration_context = index_snapshot_registrations(snapshot)
    players = []
    for contact in contacts:
        context = registration_context.get(contact["registration_id"])
        if context is None:
            continue
        players.append(
            {
                "registration_id": contact["registration_id"],
                "profile_id": contact["player_id"],
                "qq_number": contact["qq_number"],
                "display_id": context["registration"]["display_id"],
                "machine_id": context["machine_id"],
                "machine_name": context["machine_name"],
                "machine_operational": context["machine_operational"],
                "machine_stop_reason": context["machine_stop_reason"],
                "machine_stop_reason_detail": context[
                    "machine_stop_reason_detail"
                ],
                "playing_started_at": context["playing_started_at"],
                "position": context["position"],
                "position_index": context["position_index"],
                "estimated_wait_minutes": context["estimated_wait_minutes"],
                "co_player_display_ids": [
                    registration["display_id"]
                    for registration in context["position_registrations"]
                    if registration["registration_id"] != contact["registration_id"]
                ],
                "preference": context["registration"]["preference"],
                "fixed_pair": context["registration"]["fixed_pair"],
                "registration_type": context["registration"]["registration_type"],
                "last_played_at": context["registration"]["last_played_at"],
                "deferred_once": context["registration"]["deferred_once"],
                "temporarily_away": context["registration"]["temporarily_away"],
                "temporary_away_skipped_turns": context["registration"][
                    "temporary_away_skipped_turns"
                ],
                "no_show_count": context["registration"]["no_show_count"],
                "last_no_show_action_was_defer": context["registration"][
                    "last_no_show_action_was_defer"
                ],
                "online_registration_pending_check_in": context["registration"].get(
                    "online_registration_pending_check_in", False
                ),
                "updated_at": contact["updated_at"] * 1000,
            }
        )
    return jsonify(
        {
            "queue_id": snapshot_row["queue_id"],
            "revision": snapshot_row["revision"],
            "received_at": snapshot_row["received_at"] * 1000,
            "registration_open": snapshot.get("registration_open", True),
            "test_data": bool(snapshot.get("test_data", False)),
            "business_hours": snapshot.get("business_hours")
            or normalize_public_business_hours(None),
            "queue_rules": snapshot.get("queue_rules")
            or normalize_public_queue_rules(None),
            "terminal": {
                "online": last_seen_seconds
                <= current_app.config["ONLINE_TIMEOUT_SECONDS"],
                "last_seen_seconds": last_seen_seconds,
            },
            "players": players,
            "capabilities": {
                "read_players": True,
                "remote_actions": True,
                "online_registration": online_registration_allowed(snapshot),
            },
        }
    )


def read_bot_events():
    qq_number, filter_error = read_private_qq_filter({"after", "limit"})
    if filter_error is not None:
        return filter_error
    source = request.get_json(silent=True) if request.method == "POST" else None
    after_text = str(
        source.get("after", 0) if isinstance(source, dict) else request.args.get("after", "0")
    ).strip()
    limit_text = str(
        source.get("limit", 50) if isinstance(source, dict) else request.args.get("limit", "50")
    ).strip()
    try:
        after = int(after_text)
        limit = int(limit_text)
    except ValueError:
        return jsonify({"ok": False, "error": "after 或 limit 数值无效"}), 400
    if after < 0 or not 1 <= limit <= MAX_LOG_PAGE_SIZE:
        return jsonify({"ok": False, "error": "after 或 limit 数值无效"}), 400

    with open_database() as connection:
        connection.execute("BEGIN IMMEDIATE")
        cleanup_expired_event_recipients(connection, int(time.time()))
        snapshot_row = connection.execute(
            "SELECT queue_id, revision FROM queue_snapshot WHERE id = 1"
        ).fetchone()
        if snapshot_row is None:
            connection.commit()
            return jsonify({"ok": False, "error": "排队终端暂未同步"}), 404
        recipient_rows = connection.execute(
            """
            SELECT event_id, registration_id, profile_id, qq_number
            FROM queue_event_recipient
            WHERE queue_id = ?
            """,
            (snapshot_row["queue_id"],),
        ).fetchall()
        event_rows = connection.execute(
            """
            SELECT id, event_id, occurred_at, machine_id, event_type,
                   title, detail, operation_source, registration_ids
            FROM queue_event
            WHERE queue_id = ? AND id > ?
            ORDER BY id ASC
            LIMIT ?
            """,
            (snapshot_row["queue_id"], after, MAX_STORED_EVENTS_PER_QUEUE),
        ).fetchall()
        connection.commit()

    recipients_by_event: dict[str, list[dict[str, str]]] = {}
    for row in recipient_rows:
        recipients_by_event.setdefault(row["event_id"], []).append(
            {
                "registration_id": row["registration_id"],
                "profile_id": row["profile_id"],
                "qq_number": row["qq_number"],
            }
        )
    events = []
    last_scanned_cursor = after
    has_more = False
    for row in event_rows:
        last_scanned_cursor = row["id"]
        affected_players = recipients_by_event.get(row["event_id"], [])
        if qq_number:
            affected_players = [
                player
                for player in affected_players
                if player["qq_number"] == qq_number
            ]
            if not affected_players:
                continue
        events.append(
            {
                "cursor": row["id"],
                "event_id": row["event_id"],
                "occurred_at": row["occurred_at"],
                "machine_id": row["machine_id"],
                "type": row["event_type"],
                "title": row["title"],
                "detail": row["detail"],
                "operation_source": row["operation_source"],
                "affected_players": affected_players,
            }
        )
        if len(events) == limit:
            has_more = row["id"] < event_rows[-1]["id"]
            last_scanned_cursor = row["id"]
            break

    return jsonify(
        {
            "queue_id": snapshot_row["queue_id"],
            "revision": snapshot_row["revision"],
            "events": events,
            "next_cursor": last_scanned_cursor,
            "latest_cursor": event_rows[-1]["id"] if event_rows else after,
            "has_more": has_more,
            "capabilities": {
                "related_event_notifications": True,
                "playing_notifications": True,
                "remote_actions": False,
            },
        }
    )


def read_bot_profiles():
    return read_synced_profiles(allow_qq_filter=True)


def read_synced_profiles(*, allow_qq_filter: bool):
    if allow_qq_filter:
        qq_number, filter_error = read_private_qq_filter()
        if filter_error is not None:
            return filter_error
    else:
        qq_number = request.args.get("qq", "").strip()
    if qq_number and not allow_qq_filter:
        return jsonify({"ok": False, "error": "终端资料接口不支持 qq 参数"}), 400

    with open_database() as connection:
        snapshot_row = connection.execute(
            "SELECT device_id, queue_id, revision FROM queue_snapshot WHERE id = 1"
        ).fetchone()
        if snapshot_row is None:
            return jsonify({"ok": False, "error": "排队终端暂未同步"}), 404
        query = """
            SELECT profile_id, nickname, gender, default_preference, qq_number,
                   usage_count, last_used_at, created_at, profile_updated_at,
                   received_at
            FROM player_profile
            WHERE device_id = ?
        """
        parameters: list[Any] = [current_app.config["PROFILE_SCOPE_ID"]]
        if qq_number:
            query += " AND qq_number = ?"
            parameters.append(qq_number)
        query += " ORDER BY nickname, profile_id"
        rows = connection.execute(query, parameters).fetchall()

    return jsonify(
        {
            "queue_id": snapshot_row["queue_id"],
            "revision": snapshot_row["revision"],
            "profiles": [
                {
                    "profile_id": row["profile_id"],
                    "nickname": row["nickname"],
                    "gender": row["gender"],
                    "default_preference": row["default_preference"],
                    "qq_number": row["qq_number"],
                    "usage_count": row["usage_count"],
                    "last_used_at": row["last_used_at"],
                    "created_at": row["created_at"],
                    "updated_at": row["profile_updated_at"],
                    "synced_at": row["received_at"] * 1000,
                }
                for row in rows
            ],
            "capabilities": {
                "profile_updates": allow_qq_filter,
                "terminal_is_source_of_truth": True,
            },
        }
    )


def read_private_qq_filter(allowed_extra_fields: set[str] | None = None):
    if request.method == "GET":
        if "qq" in request.args:
            return None, (
                jsonify({"ok": False, "error": "按 QQ 查询请使用 POST JSON 请求体"}),
                400,
            )
        return "", None

    source = request.get_json(silent=True)
    if not isinstance(source, dict):
        return None, (jsonify({"ok": False, "error": "请求内容必须是 JSON 对象"}), 400)
    unknown_fields = set(source) - {"qq"} - (allowed_extra_fields or set())
    if unknown_fields:
        return None, (jsonify({"ok": False, "error": "请求包含不支持的查询字段"}), 400)
    qq_number = source.get("qq", "")
    if not isinstance(qq_number, str) or (
        qq_number and QQ_NUMBER_PATTERN.fullmatch(qq_number) is None
    ):
        return None, (jsonify({"ok": False, "error": "qq 必须是 5 至 12 位数字"}), 400)
    return qq_number, None


def read_online_profile():
    source = request.get_json(silent=True)
    if not isinstance(source, dict):
        return jsonify({"ok": False, "error": "请求内容必须是 JSON 对象"}), 400
    if set(source) != {"qq"}:
        return jsonify({"ok": False, "error": "请求包含不支持的查询字段"}), 400
    try:
        qq_number = read_qq_number(source, "qq")
    except ValidationError as error:
        return jsonify({"ok": False, "error": str(error)}), 400

    with open_database() as connection:
        snapshot_row = connection.execute(
            "SELECT queue_id, payload, received_at FROM queue_snapshot WHERE id = 1"
        ).fetchone()
        if snapshot_row is None:
            return jsonify({"ok": False, "error": "排队终端暂未同步"}), 404
        snapshot = json.loads(snapshot_row["payload"])
        if not online_registration_allowed(snapshot):
            return jsonify({"ok": False, "error": "现场规则暂不允许线上登记"}), 503
        profile = find_player_profile_by_qq(connection, qq_number)
        if profile is None:
            return jsonify(
                {
                    "ok": False,
                    "code": "PROFILE_NOT_FOUND",
                    "error": (
                        "这个 QQ 还没有关联玩家资料。请先前往现场终端，"
                        "在玩家资料库中创建资料并填写 QQ。"
                    ),
                }
            ), 404
        registrations = find_qq_registration_contexts(
            connection, snapshot_row["queue_id"], snapshot, qq_number
        )

    terminal_online = snapshot_is_online(snapshot_row)
    return jsonify(
        {
            "queue_id": snapshot_row["queue_id"],
            "profile": serialize_player_profile(profile),
            "existing_registration": serialize_registration_context(registrations[0])
            if registrations
            else None,
            "registration_open": snapshot.get("registration_open", True),
            "business_hours": snapshot.get("business_hours")
            or normalize_public_business_hours(None),
            "terminal": {"online": terminal_online},
            "machines": [
                serialize_remote_machine(machine_id, machine)
                for machine_id, machine in snapshot.get("machines", {}).items()
            ],
            "capabilities": {
                "online_registration": bool(
                    terminal_online
                    and snapshot.get("website_remote_enabled", False)
                    and online_registration_allowed(snapshot)
                ),
                "terminal_is_source_of_truth": True,
            },
        }
    )


def create_website_join_command():
    source = request.get_json(silent=True)
    if not isinstance(source, dict):
        return jsonify({"ok": False, "error": "请求内容必须是 JSON 对象"}), 400
    allowed_fields = {"request_id", "qq", "machine_id", "preference"}
    if set(source) - allowed_fields:
        return jsonify({"ok": False, "error": "请求包含不支持的线上登记字段"}), 400
    normalized = dict(source)
    normalized["actor_qq"] = normalized.pop("qq", None)
    normalized["operation"] = "JOIN_QUEUE"
    return create_queue_operation_command(normalized, "WEBSITE_REMOTE")


def create_bot_queue_operation_command():
    source = request.get_json(silent=True)
    if not isinstance(source, dict):
        return jsonify({"ok": False, "error": "请求内容必须是 JSON 对象"}), 400
    allowed_fields = {
        "request_id",
        "actor_qq",
        "operation",
        "machine_id",
        "target_machine_id",
        "preference",
    }
    if set(source) - allowed_fields:
        return jsonify({"ok": False, "error": "请求包含不支持的排队操作字段"}), 400
    return create_queue_operation_command(source, "QQ_BOT")


def create_queue_operation_command(source: dict[str, Any], operation_source: str):
    try:
        command_id = read_uuid(source, "request_id")
        actor_qq = read_qq_number(source, "actor_qq")
        operation = read_choice(source, "operation", QUEUE_OPERATIONS)
        machine_id = read_optional_machine_id(source, "machine_id")
        target_machine_id = read_optional_machine_id(source, "target_machine_id")
        preference = read_optional_choice(source, "preference", PREFERENCES)
    except ValidationError as error:
        return jsonify({"ok": False, "error": str(error)}), 400

    request_identity = {
        "operation_source": operation_source,
        "actor_qq": actor_qq,
        "operation": operation,
        "machine_id": machine_id,
        "target_machine_id": target_machine_id,
        "preference": preference,
    }

    with open_database() as connection:
        connection.execute("BEGIN IMMEDIATE")
        now = int(time.time())
        expire_pending_commands(connection, now)
        connection.commit()
        connection.execute("BEGIN IMMEDIATE")

        existing_command = connection.execute(
            "SELECT * FROM terminal_command WHERE command_id = ?", (command_id,)
        ).fetchone()
        if existing_command is not None:
            existing_payload = json.loads(existing_command["payload"])
            if (
                existing_command["command_type"] != QUEUE_OPERATION_COMMAND
                or existing_payload.get("_request") != request_identity
            ):
                return jsonify({"ok": False, "error": "request_id 已用于其他命令"}), 409
            return jsonify(serialize_command(existing_command)), 200

        snapshot_row = connection.execute(
            "SELECT queue_id, device_id, payload, received_at FROM queue_snapshot WHERE id = 1"
        ).fetchone()
        if snapshot_row is None:
            return jsonify({"ok": False, "error": "排队终端暂未同步"}), 404
        snapshot = json.loads(snapshot_row["payload"])
        availability_error = remote_operation_availability_error(
            snapshot_row, snapshot, operation_source, operation
        )
        if availability_error is not None:
            detail, status_code = availability_error
            return jsonify({"ok": False, "error": detail}), status_code

        profile = find_player_profile_by_qq(connection, actor_qq)
        if profile is None:
            return jsonify(
                {
                    "ok": False,
                    "code": "PROFILE_NOT_FOUND",
                    "error": (
                        "当前 QQ 尚未关联玩家资料。请先在现场终端的玩家资料库中"
                        "创建资料并填写 QQ。"
                    ),
                }
            ), 404
        registration_contexts = find_qq_registration_contexts(
            connection,
            snapshot_row["queue_id"],
            snapshot,
            actor_qq,
        )

        desired, validation_error = build_queue_operation_payload(
            snapshot=snapshot,
            queue_id=snapshot_row["queue_id"],
            profile=profile,
            actor_qq=actor_qq,
            operation=operation,
            operation_source=operation_source,
            machine_id=machine_id,
            target_machine_id=target_machine_id,
            preference=preference,
            registration_contexts=registration_contexts,
            request_identity=request_identity,
        )
        if validation_error is not None:
            detail, status_code = validation_error
            return jsonify({"ok": False, "error": detail}), status_code

        pending = connection.execute(
            """
            SELECT 1 FROM terminal_command
            WHERE status = 'PENDING' AND (
                json_extract(payload, '$.actor_qq') = ?
                OR json_extract(payload, '$.profile_id') = ?
            )
            """,
            (actor_qq, profile["profile_id"]),
        ).fetchone()
        if pending is not None:
            return jsonify({"ok": False, "error": "你已有一项操作正在等待终端处理"}), 409

        connection.execute(
            """
            INSERT INTO terminal_command
                (command_id, device_id, command_type, payload, status, created_at)
            VALUES (?, ?, ?, ?, 'PENDING', ?)
            """,
            (
                command_id,
                snapshot_row["device_id"],
                QUEUE_OPERATION_COMMAND,
                json.dumps(desired, ensure_ascii=False, separators=(",", ":")),
                now,
            ),
        )
        connection.commit()
        created = connection.execute(
            "SELECT * FROM terminal_command WHERE command_id = ?", (command_id,)
        ).fetchone()
    return jsonify(serialize_command(created)), 202


def build_queue_operation_payload(
    *,
    snapshot: dict[str, Any],
    queue_id: str,
    profile: sqlite3.Row,
    actor_qq: str,
    operation: str,
    operation_source: str,
    machine_id: str | None,
    target_machine_id: str | None,
    preference: str | None,
    registration_contexts: list[dict[str, Any]],
    request_identity: dict[str, Any],
):
    payload = {
        "queue_id": queue_id,
        "profile_id": profile["profile_id"],
        "actor_qq": actor_qq,
        "operation": operation,
        "operation_source": operation_source,
        "_request": request_identity,
    }

    if operation == "JOIN_QUEUE":
        if not online_registration_allowed(snapshot):
            return None, ("现场规则暂不允许线上登记", 409)
        if registration_contexts:
            return None, ("你已经有一份正在排队的登记，不能重复加入", 409)
        if not snapshot.get("registration_open", True):
            return None, ("现场当前没有使用登记排队，暂不能线上加入排队", 409)
        machine = snapshot.get("machines", {}).get(machine_id or "")
        if machine is None:
            return None, ("请选择有效的排队机台", 400)
        if not machine.get("operational", False):
            return None, (f"{machine['name']}已停止使用，暂不能加入", 409)
        if machine.get("registration_count", 0) >= MAX_REGISTRATIONS_PER_MACHINE:
            return None, (f"{machine['name']}的登记已满，请选择其他机台", 409)
        default_preference = profile["default_preference"]
        if default_preference == "ASK_EVERY_TIME":
            if preference not in PREFERENCES:
                return None, ("请选择本次游玩偏好", 400)
            resolved_preference = preference
        else:
            if preference is not None and preference != default_preference:
                return None, ("这份玩家资料使用固定的默认游玩偏好", 409)
            resolved_preference = default_preference
        payload.update(
            {
                "machine_id": machine_id,
                "preference": resolved_preference,
            }
        )
        return payload, None

    if len(registration_contexts) != 1:
        return None, ("当前没有可以执行此操作的排队登记", 409)
    context = registration_contexts[0]
    registration = context["registration"]
    pending_check_in = registration.get("online_registration_pending_check_in", False)
    if pending_check_in and operation != "LEAVE_QUEUE":
        return None, ("线上登记完成现场签到后，才能进行这项操作", 409)

    queue_rules = snapshot.get("queue_rules") or normalize_public_queue_rules(None)
    if operation == "DEFER_ONE_ROUND":
        if not queue_rules["allow_defer_one_round"]:
            return None, ("系统规则不允许暂缓一轮", 409)
        if registration.get("deferred_once") or registration.get("temporarily_away"):
            return None, ("请先取消当前的暂缓或暂时离开状态", 409)
    elif operation == "CANCEL_DEFER_ONE_ROUND":
        if not registration.get("deferred_once"):
            return None, ("这份登记当前没有暂缓一轮", 409)
    elif operation == "TEMPORARILY_LEAVE":
        if not queue_rules["allow_temporary_leave"]:
            return None, ("系统规则不允许暂时离开", 409)
        if registration.get("deferred_once") or registration.get("temporarily_away"):
            return None, ("请先取消当前的暂缓或暂时离开状态", 409)
    elif operation == "CANCEL_TEMPORARY_LEAVE":
        if not registration.get("temporarily_away"):
            return None, ("这份登记当前没有处于暂时离开状态", 409)
    elif operation == "TRANSFER_MACHINE":
        if context["position"] == "PLAYING":
            return None, ("处于游玩位置的登记暂不能切换机台", 409)
        target_machine = snapshot.get("machines", {}).get(target_machine_id or "")
        if target_machine is None or target_machine_id == context["machine_id"]:
            return None, ("请选择其他可用机台", 400)
        if not target_machine.get("operational", False):
            return None, (f"{target_machine['name']}已停止使用，暂不能转入", 409)
        if target_machine.get("registration_count", 0) >= MAX_REGISTRATIONS_PER_MACHINE:
            return None, (f"{target_machine['name']}的登记已满，暂不能转入", 409)
        payload["target_machine_id"] = target_machine_id
    elif operation == "CHANGE_PLAY_PREFERENCE":
        if preference not in PREFERENCES:
            return None, ("请选择本次游玩偏好", 400)
        payload["preference"] = preference
    elif operation != "LEAVE_QUEUE":
        return None, ("不支持这项排队操作", 400)

    payload.update(
        {
            "registration_id": registration["registration_id"],
            "machine_id": context["machine_id"],
        }
    )
    return payload, None


def read_website_command(command_id: str):
    try:
        command_id = str(UUID(command_id))
    except ValueError:
        return jsonify({"ok": False, "error": "命令编号无效"}), 400
    with open_database() as connection:
        connection.execute("BEGIN IMMEDIATE")
        expire_pending_commands(connection, int(time.time()))
        row = connection.execute(
            "SELECT * FROM terminal_command WHERE command_id = ?", (command_id,)
        ).fetchone()
        connection.commit()
    if row is None:
        return jsonify({"ok": False, "error": "没有找到这条命令"}), 404
    payload = json.loads(row["payload"])
    if payload.get("operation_source") != "WEBSITE_REMOTE":
        return jsonify({"ok": False, "error": "没有找到这条命令"}), 404
    command = serialize_command(row)
    command.pop("payload", None)
    return jsonify(command)


def find_player_profile_by_qq(connection: sqlite3.Connection, qq_number: str):
    return connection.execute(
        """
        SELECT profile_id, nickname, gender, default_preference, qq_number,
               usage_count, last_used_at, created_at, profile_updated_at
        FROM player_profile
        WHERE device_id = ? AND qq_number = ?
        """,
        (current_app.config["PROFILE_SCOPE_ID"], qq_number),
    ).fetchone()


def serialize_player_profile(profile: sqlite3.Row) -> dict[str, Any]:
    return {
        "profile_id": profile["profile_id"],
        "nickname": profile["nickname"],
        "gender": profile["gender"],
        "default_preference": profile["default_preference"],
        "qq_number": profile["qq_number"],
        "usage_count": profile["usage_count"],
        "last_used_at": profile["last_used_at"],
        "created_at": profile["created_at"],
        "updated_at": profile["profile_updated_at"],
    }


def find_qq_registration_contexts(
    connection: sqlite3.Connection,
    queue_id: str,
    snapshot: dict[str, Any],
    qq_number: str,
) -> list[dict[str, Any]]:
    registration_ids = {
        row["registration_id"]
        for row in connection.execute(
            """
            SELECT registration_id FROM queue_private_contact
            WHERE queue_id = ? AND qq_number = ?
            """,
            (queue_id, qq_number),
        ).fetchall()
    }
    indexed = index_snapshot_registrations(snapshot)
    return [indexed[value] for value in registration_ids if value in indexed]


def serialize_registration_context(context: dict[str, Any]) -> dict[str, Any]:
    registration = context["registration"]
    return {
        "registration_id": registration["registration_id"],
        "display_id": registration["display_id"],
        "machine_id": context["machine_id"],
        "position": context["position"],
        "position_index": context["position_index"],
        "online_registration_pending_check_in": registration.get(
            "online_registration_pending_check_in", False
        ),
    }


def serialize_remote_machine(machine_id: str, machine: dict[str, Any]) -> dict[str, Any]:
    unavailable_reason = None
    if not machine.get("operational", False):
        unavailable_reason = "机台已停止使用"
    elif machine.get("registration_count", 0) >= MAX_REGISTRATIONS_PER_MACHINE:
        unavailable_reason = "登记已满"
    return {
        "id": machine_id,
        "name": machine["name"],
        "operational": machine["operational"],
        "registration_count": machine.get("registration_count", 0),
        "estimated_wait_minutes": machine.get(
            "new_registration_estimated_wait_minutes"
        ),
        "available": unavailable_reason is None,
        "unavailable_reason": unavailable_reason,
    }


def snapshot_is_online(snapshot_row: sqlite3.Row) -> bool:
    return int(time.time()) - snapshot_row["received_at"] <= current_app.config[
        "ONLINE_TIMEOUT_SECONDS"
    ]


def remote_operation_availability_error(
    snapshot_row: sqlite3.Row,
    snapshot: dict[str, Any],
    operation_source: str,
    operation: str,
):
    if not snapshot_is_online(snapshot_row):
        return "现场终端暂时离线，暂不能执行远程排队操作", 503
    if operation_source == "WEBSITE_REMOTE" and not snapshot.get(
        "website_remote_enabled", False
    ):
        return "现场终端已关闭网站同步，暂不能在线操作", 503
    if operation_source == "QQ_BOT" and not snapshot.get("onebot_sync_enabled", True):
        return "现场终端已关闭 QQ Bot 联动", 503
    if operation == "JOIN_QUEUE" and not online_registration_allowed(snapshot):
        return "现场规则暂不允许线上登记", 503
    return None


def read_optional_machine_id(source: dict[str, Any], key: str) -> str | None:
    value = source.get(key)
    if value is None:
        return None
    if not isinstance(value, str) or not value.strip() or len(value.strip()) > 8:
        raise ValidationError(f"{key} 机台编号无效")
    return value.strip().upper()


def create_profile_update_command(profile_id: str):
    try:
        profile_id = str(UUID(profile_id))
    except ValueError:
        return jsonify({"ok": False, "error": "玩家资料编号无效"}), 400
    source = request.get_json(silent=True)
    if not isinstance(source, dict):
        return jsonify({"ok": False, "error": "请求内容必须是 JSON 对象"}), 400
    try:
        command_id = read_uuid(source, "request_id")
        actor_qq = read_qq_number(source, "actor_qq")
    except ValidationError as error:
        return jsonify({"ok": False, "error": str(error)}), 400

    allowed_update_fields = {"nickname", "gender", "default_preference"}
    supplied_update_fields = allowed_update_fields.intersection(source)
    if not supplied_update_fields:
        return jsonify({"ok": False, "error": "没有需要更新的玩家资料字段"}), 400
    unknown_fields = set(source) - allowed_update_fields - {"request_id", "actor_qq"}
    if unknown_fields:
        return jsonify({"ok": False, "error": "请求包含不支持的玩家资料字段"}), 400
    try:
        requested_update = {}
        if "nickname" in source:
            requested_update["nickname"] = read_string(
                source, "nickname", maximum_length=18
            )
        if "gender" in source:
            requested_update["gender"] = read_choice(source, "gender", PLAYER_GENDERS)
        if "default_preference" in source:
            requested_update["default_preference"] = read_choice(
                source, "default_preference", PROFILE_PREFERENCES
            )
    except ValidationError as error:
        return jsonify({"ok": False, "error": str(error)}), 400
    request_identity = {
        "profile_id": profile_id,
        "actor_qq": actor_qq,
        "update": requested_update,
    }

    with open_database() as connection:
        connection.execute("BEGIN IMMEDIATE")
        now = int(time.time())
        expire_pending_commands(connection, now)
        connection.commit()
        connection.execute("BEGIN IMMEDIATE")
        snapshot = connection.execute(
            "SELECT device_id FROM queue_snapshot WHERE id = 1"
        ).fetchone()
        if snapshot is None:
            return jsonify({"ok": False, "error": "排队终端暂未同步"}), 404
        existing_command = connection.execute(
            "SELECT * FROM terminal_command WHERE command_id = ?", (command_id,)
        ).fetchone()
        if existing_command is not None:
            existing_payload = json.loads(existing_command["payload"])
            if (
                existing_command["command_type"] != PROFILE_UPDATE_COMMAND
                or existing_payload.get("_request") != request_identity
            ):
                return jsonify({"ok": False, "error": "request_id 已用于其他命令"}), 409
            return jsonify(serialize_command(existing_command)), 200

        profile_scope_id = current_app.config["PROFILE_SCOPE_ID"]
        profile = connection.execute(
            """
            SELECT profile_id, nickname, gender, default_preference, qq_number,
                   profile_updated_at
            FROM player_profile
            WHERE device_id = ? AND profile_id = ?
            """,
            (profile_scope_id, profile_id),
        ).fetchone()
        if profile is None:
            return jsonify({"ok": False, "error": "没有找到这份玩家资料"}), 404
        if profile["qq_number"] != actor_qq:
            return jsonify({"ok": False, "error": "只能修改当前 QQ 对应的玩家资料"}), 403

        desired = {
            "profile_id": profile_id,
            "qq_number": actor_qq,
            "actor_qq": actor_qq,
            "operation_source": "QQ_BOT",
            "expected_updated_at": profile["profile_updated_at"],
            "nickname": requested_update.get("nickname", profile["nickname"]),
            "gender": requested_update.get("gender", profile["gender"]),
            "default_preference": requested_update.get(
                "default_preference", profile["default_preference"]
            ),
            "_request": request_identity,
        }

        duplicate_nickname = connection.execute(
            """
            SELECT 1 FROM player_profile
            WHERE device_id = ? AND profile_id != ? AND lower(nickname) = lower(?)
            """,
            (profile_scope_id, profile_id, desired["nickname"]),
        ).fetchone()
        if duplicate_nickname is not None:
            return jsonify({"ok": False, "error": "这个昵称已经用于其他玩家资料"}), 409

        pending = connection.execute(
            """
            SELECT 1 FROM terminal_command
            WHERE device_id = ? AND status = 'PENDING' AND (
                json_extract(payload, '$.actor_qq') = ?
                OR json_extract(payload, '$.profile_id') = ?
            )
            """,
            (snapshot["device_id"], actor_qq, profile_id),
        ).fetchone()
        if pending is not None:
            return jsonify({"ok": False, "error": "你已有一项操作正在等待终端处理"}), 409

        connection.execute(
            """
            INSERT INTO terminal_command
                (command_id, device_id, command_type, payload, status, created_at)
            VALUES (?, ?, ?, ?, 'PENDING', ?)
            """,
            (
                command_id,
                snapshot["device_id"],
                PROFILE_UPDATE_COMMAND,
                json.dumps(desired, ensure_ascii=False, separators=(",", ":")),
                now,
            ),
        )
        connection.commit()
        created = connection.execute(
            "SELECT * FROM terminal_command WHERE command_id = ?", (command_id,)
        ).fetchone()
    return jsonify(serialize_command(created)), 202


def read_bot_command(command_id: str):
    try:
        command_id = str(UUID(command_id))
    except ValueError:
        return jsonify({"ok": False, "error": "命令编号无效"}), 400
    with open_database() as connection:
        connection.execute("BEGIN IMMEDIATE")
        expire_pending_commands(connection, int(time.time()))
        row = connection.execute(
            "SELECT * FROM terminal_command WHERE command_id = ?", (command_id,)
        ).fetchone()
        connection.commit()
    if row is None:
        return jsonify({"ok": False, "error": "没有找到这条命令"}), 404
    return jsonify(serialize_command(row))


def read_terminal_commands():
    device_id = request.headers.get("X-Device-ID", "").strip()
    with open_database() as connection:
        connection.execute("BEGIN IMMEDIATE")
        now = int(time.time())
        expire_pending_commands(connection, now)
        command_ids = [
            row["command_id"]
            for row in connection.execute(
                """
                SELECT command_id FROM terminal_command
                WHERE device_id = ? AND status = 'PENDING'
                ORDER BY created_at, command_id
                LIMIT 20
                """,
                (device_id,),
            ).fetchall()
        ]
        connection.executemany(
            """
            UPDATE terminal_command
            SET claimed_at = COALESCE(claimed_at, ?), claimed_terminal = ?
            WHERE command_id = ? AND device_id = ? AND status = 'PENDING'
            """,
            [(now, device_id, command_id, device_id) for command_id in command_ids],
        )
        rows = connection.execute(
            """
            SELECT * FROM terminal_command
            WHERE device_id = ? AND status = 'PENDING' AND claimed_terminal = ?
            ORDER BY created_at, command_id
            LIMIT 20
            """,
            (device_id, device_id),
        ).fetchall()
        connection.commit()
    return jsonify({"commands": [serialize_command(row) for row in rows]})


def complete_terminal_command(command_id: str):
    try:
        command_id = str(UUID(command_id))
    except ValueError:
        return jsonify({"ok": False, "error": "命令编号无效"}), 400
    source = request.get_json(silent=True)
    if not isinstance(source, dict):
        return jsonify({"ok": False, "error": "请求内容必须是 JSON 对象"}), 400
    status = source.get("status")
    detail = source.get("detail", "")
    if status not in {"APPLIED", "REJECTED"}:
        return jsonify({"ok": False, "error": "命令结果状态无效"}), 400
    if not isinstance(detail, str) or len(detail) > 500:
        return jsonify({"ok": False, "error": "命令结果说明无效"}), 400
    detail = detail.strip()
    device_id = request.headers.get("X-Device-ID", "").strip()

    with open_database() as connection:
        connection.execute("BEGIN IMMEDIATE")
        expire_pending_commands(connection, int(time.time()))
        row = connection.execute(
            "SELECT * FROM terminal_command WHERE command_id = ?", (command_id,)
        ).fetchone()
        if row is None:
            return jsonify({"ok": False, "error": "没有找到这条命令"}), 404
        if row["device_id"] != device_id:
            return jsonify({"ok": False, "error": "此命令不属于当前终端"}), 403
        current_terminal = connection.execute(
            "SELECT device_id FROM queue_snapshot WHERE id = 1"
        ).fetchone()
        late_timeout_result = (
            row["status"] == "REJECTED"
            and row["result_source"] == RESULT_SOURCE_SERVER_TIMEOUT
            and row["claimed_at"] is not None
            and row["claimed_terminal"] == device_id
            and current_terminal is not None
            and current_terminal["device_id"] == device_id
        )
        if row["status"] == "PENDING" or late_timeout_result:
            connection.execute(
                """
                UPDATE terminal_command
                SET status = ?, completed_at = ?, result_detail = ?, result_source = ?
                WHERE command_id = ? AND device_id = ?
                  AND (
                      status = 'PENDING'
                      OR (
                          status = 'REJECTED' AND result_source = ?
                          AND claimed_at IS NOT NULL AND claimed_terminal = ?
                      )
                  )
                """,
                (
                    status,
                    int(time.time()),
                    detail or None,
                    RESULT_SOURCE_TERMINAL,
                    command_id,
                    device_id,
                    RESULT_SOURCE_SERVER_TIMEOUT,
                    device_id,
                ),
            )
            connection.commit()
            row = connection.execute(
                "SELECT * FROM terminal_command WHERE command_id = ?", (command_id,)
            ).fetchone()
        else:
            connection.commit()
    return jsonify(serialize_command(row))


def expire_pending_commands(connection: sqlite3.Connection, now: int) -> None:
    timeout_seconds = max(1, current_app.config["COMMAND_TIMEOUT_SECONDS"])
    connection.execute(
        """
        UPDATE terminal_command
        SET status = 'REJECTED', completed_at = ?,
            result_detail = ?, result_source = ?
        WHERE status = 'PENDING' AND created_at <= ?
        """,
        (
            now,
            COMMAND_TIMEOUT_DETAIL,
            RESULT_SOURCE_SERVER_TIMEOUT,
            now - timeout_seconds,
        ),
    )
    retention_seconds = max(1, current_app.config["COMMAND_RETENTION_SECONDS"])
    connection.execute(
        """
        DELETE FROM terminal_command
        WHERE status != 'PENDING' AND completed_at IS NOT NULL AND completed_at <= ?
        """,
        (now - retention_seconds,),
    )


def serialize_command(row: sqlite3.Row) -> dict[str, Any]:
    return {
        "command_id": row["command_id"],
        "type": row["command_type"],
        "payload": json.loads(row["payload"]),
        "status": row["status"],
        "created_at": row["created_at"] * 1000,
        "claimed_at": row["claimed_at"] * 1000 if row["claimed_at"] else None,
        "claimed_terminal": row["claimed_terminal"],
        "completed_at": row["completed_at"] * 1000 if row["completed_at"] else None,
        "result_source": row["result_source"],
        "result_detail": row["result_detail"],
    }


def cleanup_expired_event_recipients(
    connection: sqlite3.Connection, now: int
) -> None:
    retention_seconds = max(
        1, current_app.config["EVENT_RECIPIENT_RETENTION_SECONDS"]
    )
    connection.execute(
        "DELETE FROM queue_event_recipient WHERE stored_at <= ?",
        (now - retention_seconds,),
    )


def index_snapshot_registrations(snapshot: dict[str, Any]) -> dict[str, dict[str, Any]]:
    indexed: dict[str, dict[str, Any]] = {}
    for machine_id, machine in snapshot.get("machines", {}).items():
        machine_context = {
            "machine_name": machine["name"],
            "machine_operational": machine["operational"],
            "machine_stop_reason": machine["stop_reason"],
            "machine_stop_reason_detail": machine["stop_reason_detail"],
            "playing_started_at": machine["playing_started_at"],
        }
        for registration in machine.get("playing", []):
            indexed[registration["registration_id"]] = {
                "registration": registration,
                "position_registrations": machine.get("playing", []),
                "machine_id": machine_id,
                "position": "PLAYING",
                "position_index": None,
                "estimated_wait_minutes": 0 if machine["operational"] else None,
                **machine_context,
            }
        for waiting_position in machine.get("waiting_positions", []):
            for registration in waiting_position.get("registrations", []):
                indexed[registration["registration_id"]] = {
                    "registration": registration,
                    "position_registrations": waiting_position.get("registrations", []),
                    "machine_id": machine_id,
                    "position": "WAITING",
                    "position_index": waiting_position["index"],
                    "estimated_wait_minutes": waiting_position[
                        "estimated_wait_minutes"
                    ],
                    **machine_context,
                }
    return indexed


def public_capabilities(
    snapshot: dict[str, Any] | None = None,
    terminal_online: bool = False,
) -> dict[str, Any]:
    online_registration = bool(
        snapshot
        and terminal_online
        and snapshot.get("website_remote_enabled", False)
        and online_registration_allowed(snapshot)
    )
    return {
        "public_logs": True,
        "local_self_marking": True,
        "registration_qq": True,
        "remote_actions": online_registration,
        "online_registration": online_registration,
        "transport": "polling",
    }


def online_registration_allowed(snapshot: dict[str, Any]) -> bool:
    queue_rules = snapshot.get("queue_rules")
    return not isinstance(queue_rules, dict) or queue_rules.get(
        "allow_online_registration", True
    ) is not False


def authorize_terminal():
    expected_token = configured_auth_token("SYNC_TOKEN", "BOT_TOKEN")
    if expected_token is None:
        return jsonify({"ok": False, "error": "服务器鉴权配置无效"}), 503

    authorization = request.headers.get("Authorization", "")
    supplied_token = authorization[7:] if authorization.startswith("Bearer ") else ""
    try:
        supplied_token_bytes = supplied_token.encode("utf-8")
    except UnicodeEncodeError:
        supplied_token_bytes = b""
    if not hmac.compare_digest(supplied_token_bytes, expected_token):
        return jsonify({"ok": False, "error": "终端认证失败"}), 401

    allowed_device_id = current_app.config["ALLOWED_DEVICE_ID"]
    supplied_device_id = request.headers.get("X-Device-ID", "").strip()
    if allowed_device_id and not hmac.compare_digest(supplied_device_id, allowed_device_id):
        return jsonify({"ok": False, "error": "此终端未获准同步"}), 403
    return None


def authorize_bot():
    expected_token = configured_auth_token("BOT_TOKEN", "SYNC_TOKEN")
    if expected_token is None:
        return jsonify({"ok": False, "error": "服务器鉴权配置无效"}), 503

    authorization = request.headers.get("Authorization", "")
    supplied_token = authorization[7:] if authorization.startswith("Bearer ") else ""
    try:
        supplied_token_bytes = supplied_token.encode("utf-8")
    except UnicodeEncodeError:
        supplied_token_bytes = b""
    if not hmac.compare_digest(supplied_token_bytes, expected_token):
        return jsonify({"ok": False, "error": "Bot 认证失败"}), 401
    return None


def authorize_bot_link():
    authorization_error = authorize_bot()
    if authorization_error is not None:
        return authorization_error
    with open_database() as connection:
        row = connection.execute(
            "SELECT payload FROM queue_snapshot WHERE id = 1"
        ).fetchone()
    if row is not None:
        snapshot = json.loads(row["payload"])
        if snapshot.get("onebot_sync_enabled", True) is False:
            return jsonify({"ok": False, "error": "QQ Bot 联动已关闭"}), 503
    return None


def configured_auth_token(token_key: str, other_token_key: str) -> bytes | None:
    token = current_app.config.get(token_key)
    if not isinstance(token, str):
        return None
    try:
        token_bytes = token.encode("utf-8")
    except UnicodeEncodeError:
        return None
    if len(token_bytes) < 32:
        return None

    other_token = current_app.config.get(other_token_key)
    if isinstance(other_token, str) and other_token:
        try:
            other_token_bytes = other_token.encode("utf-8")
        except UnicodeEncodeError:
            other_token_bytes = b""
        if hmac.compare_digest(token_bytes, other_token_bytes):
            return None
    return token_bytes


def normalize_snapshot(payload: dict[str, Any], device_id: str) -> dict[str, Any]:
    schema_version = read_integer(payload, "schema_version", minimum=1)
    if schema_version not in SUPPORTED_SCHEMA_VERSIONS:
        raise ValidationError("不支持的队列数据版本")

    queue_id = read_uuid(payload, "queue_id")
    revision = read_integer(payload, "revision", minimum=1, maximum=2**63 - 1)
    captured_at = read_integer(payload, "captured_at", minimum=1)
    registration_open = read_boolean(payload, "registration_open")
    website_remote_enabled = payload.get("website_remote_enabled", False)
    if type(website_remote_enabled) is not bool:
        raise ValidationError("website_remote_enabled 必须是布尔值")
    onebot_sync_enabled = payload.get("onebot_sync_enabled", True)
    if type(onebot_sync_enabled) is not bool:
        raise ValidationError("onebot_sync_enabled 必须是布尔值")
    queue_rules = normalize_public_queue_rules(payload.get("queue_rules"))
    business_hours = normalize_public_business_hours(payload.get("business_hours"))
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
    private_player_profiles = (
        normalize_private_profiles(payload.get("private_player_profiles", []))
        if schema_version >= 3
        else None
    )
    private_player_contacts = normalize_private_contacts(
        payload.get("private_player_contacts", []) if schema_version >= 3 else [],
        machines,
        private_player_profiles or [],
    )
    attach_public_registration_contacts(machines, private_player_contacts)

    return {
        "schema_version": PUBLIC_SCHEMA_VERSION,
        "queue_id": queue_id,
        "revision": revision,
        "captured_at": captured_at,
        "registration_open": registration_open,
        "website_remote_enabled": website_remote_enabled,
        "onebot_sync_enabled": onebot_sync_enabled,
        "queue_rules": queue_rules,
        "business_hours": business_hours,
        "terminal": {
            "id": device_id,
            "online": True,
            "app_version": app_version,
            "last_seen_at": captured_at,
        },
        "machines": machines,
        "recent_events": recent_events,
        "private_player_contacts": private_player_contacts,
        "private_player_profiles": private_player_profiles,
    }


def normalize_private_profiles(source: Any) -> list[dict[str, Any]]:
    if not isinstance(source, list):
        raise ValidationError("private_player_profiles 必须是数组")
    if len(source) > MAX_PLAYER_PROFILES:
        raise ValidationError("玩家资料数量超过限制")

    profiles = []
    for value in source:
        if not isinstance(value, dict):
            raise ValidationError("private_player_profiles 包含无效资料")
        qq_number = value.get("qq_number")
        if qq_number is not None and (
            not isinstance(qq_number, str)
            or QQ_NUMBER_PATTERN.fullmatch(qq_number) is None
        ):
            raise ValidationError("玩家资料 QQ 号必须是 5 至 12 位数字")
        profiles.append(
            {
                "profile_id": read_uuid(value, "profile_id"),
                "nickname": read_string(value, "nickname", maximum_length=18),
                "gender": read_choice(value, "gender", PLAYER_GENDERS),
                "default_preference": read_choice(
                    value, "default_preference", PROFILE_PREFERENCES
                ),
                "qq_number": qq_number,
                "usage_count": read_integer(
                    value, "usage_count", minimum=0, maximum=2**31 - 1
                ),
                "last_used_at": read_optional_integer(
                    value, "last_used_at", minimum=1
                ),
                "created_at": read_integer(value, "created_at", minimum=1),
                "updated_at": read_integer(value, "updated_at", minimum=1),
            }
        )
    profile_ids = [profile["profile_id"] for profile in profiles]
    if len(profile_ids) != len(set(profile_ids)):
        raise ValidationError("玩家资料编号不能重复")
    nickname_keys = [profile["nickname"].casefold() for profile in profiles]
    if len(nickname_keys) != len(set(nickname_keys)):
        raise ValidationError("玩家资料昵称不能重复")
    qq_numbers = [profile["qq_number"] for profile in profiles if profile["qq_number"]]
    if len(qq_numbers) != len(set(qq_numbers)):
        raise ValidationError("玩家资料 QQ 号不能重复")
    return profiles


def normalize_private_contacts(
    source: Any,
    machines: dict[str, dict[str, Any]],
    private_profiles: list[dict[str, Any]],
) -> list[dict[str, str]]:
    if not isinstance(source, list):
        raise ValidationError("private_player_contacts 必须是数组")
    if len(source) > MAX_PRIVATE_CONTACTS:
        raise ValidationError("QQ 绑定数量超过限制")

    registrations = {
        registration["registration_id"]: registration
        for machine in machines.values()
        for registration in all_machine_registrations(machine)
    }
    profiles = {profile["profile_id"]: profile for profile in private_profiles}
    contacts: list[dict[str, str]] = []
    for value in source:
        if not isinstance(value, dict):
            raise ValidationError("private_player_contacts 包含无效绑定")
        registration_id = read_public_id(value, "registration_id")
        profile_id = read_uuid(value, "profile_id")
        qq_number = value.get("qq_number")
        if not isinstance(qq_number, str) or QQ_NUMBER_PATTERN.fullmatch(qq_number) is None:
            raise ValidationError("qq_number 必须是 5 至 12 位数字")
        registration = registrations.get(registration_id)
        if registration is None:
            raise ValidationError("QQ 绑定引用了不存在的登记")
        if registration["registration_type"] != "PLAYER_PROFILE":
            raise ValidationError("临时登记不能包含 QQ 绑定")
        profile = profiles.get(profile_id)
        if profile is None or profile["qq_number"] != qq_number:
            raise ValidationError("QQ 绑定与玩家资料不一致")
        contacts.append(
            {
                "registration_id": registration_id,
                "profile_id": profile_id,
                "qq_number": qq_number,
            }
        )

    registration_ids = [contact["registration_id"] for contact in contacts]
    player_ids = [contact["profile_id"] for contact in contacts]
    if len(registration_ids) != len(set(registration_ids)):
        raise ValidationError("QQ 绑定的登记编号不能重复")
    if len(player_ids) != len(set(player_ids)):
        raise ValidationError("QQ 绑定的玩家编号不能重复")
    return contacts


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


def normalize_public_business_hours(source: Any) -> dict[str, Any]:
    if source is None:
        return {
            "enabled": False,
            "outside": False,
            "closing_soon": False,
            "closing_grace": False,
            "closes_at": None,
            "registration_closes_at": None,
        }
    if not isinstance(source, dict):
        raise ValidationError("business_hours 必须是对象")
    allowed_fields = {
        "enabled",
        "outside",
        "closing_soon",
        "closing_grace",
        "closes_at",
        "registration_closes_at",
    }
    if set(source) - allowed_fields:
        raise ValidationError("business_hours 包含不支持的字段")
    values = {
        "enabled": source.get("enabled", False),
        "outside": source.get("outside", False),
        "closing_soon": source.get("closing_soon", False),
        "closing_grace": source.get("closing_grace", False),
    }
    if any(type(value) is not bool for value in values.values()):
        raise ValidationError("business_hours 状态必须是布尔值")
    closes_at = source.get("closes_at")
    if closes_at is not None and (type(closes_at) is not int or closes_at < 1):
        raise ValidationError("business_hours.closes_at 数值无效")
    registration_closes_at = source.get("registration_closes_at")
    if registration_closes_at is not None and (
        type(registration_closes_at) is not int or registration_closes_at < 1
    ):
        raise ValidationError("business_hours.registration_closes_at 数值无效")
    if not values["enabled"]:
        return {
            "enabled": False,
            "outside": False,
            "closing_soon": False,
            "closing_grace": False,
            "closes_at": None,
            "registration_closes_at": None,
        }
    if values["outside"] and values["closing_soon"]:
        raise ValidationError("非营业时间不能同时处于闭店提醒状态")
    if values["closing_grace"] and not values["outside"]:
        raise ValidationError("营业时间内不能处于闭店收尾状态")
    if values["closing_grace"] != (registration_closes_at is not None):
        raise ValidationError("闭店收尾状态与登记关闭时间不一致")
    return {
        **values,
        "closes_at": closes_at,
        "registration_closes_at": registration_closes_at,
    }


def normalize_public_queue_rules(source: Any) -> dict[str, bool]:
    if source is None:
        return {
            "allow_defer_one_round": True,
            "allow_temporary_leave": True,
            "allow_online_registration": True,
        }
    if not isinstance(source, dict):
        raise ValidationError("queue_rules 必须是对象")
    allow_online_registration = source.get("allow_online_registration", True)
    if type(allow_online_registration) is not bool:
        raise ValidationError("allow_online_registration 必须是布尔值")
    return {
        "allow_defer_one_round": read_boolean(source, "allow_defer_one_round"),
        "allow_temporary_leave": read_boolean(source, "allow_temporary_leave"),
        "allow_online_registration": allow_online_registration,
    }


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
    operation_source = source.get("operation_source", "ON_SITE_TERMINAL")
    if not isinstance(operation_source, str) or operation_source not in OPERATION_SOURCES:
        raise ValidationError("公开事件操作来源无效")
    return {
        "event_id": read_uuid(source, "event_id"),
        "occurred_at": read_integer(source, "occurred_at", minimum=1),
        "machine_id": machine_id,
        "type": read_choice(source, "type", PUBLIC_EVENT_TYPES),
        "title": read_string(source, "title", maximum_length=120),
        "detail": read_string(source, "detail", maximum_length=2_000),
        "operation_source": operation_source,
        "registration_ids": normalized_registration_ids,
    }


def normalize_machine(
    machine_id: str, source: Any, *, allow_custom_name: bool = False
) -> dict[str, Any]:
    if not isinstance(source, dict):
        raise ValidationError(f"机台 {machine_id} 必须是对象")

    operational = read_boolean(source, "operational")
    stop_reason = read_optional_choice(source, "stop_reason", STOP_REASONS)
    stop_reason_detail = read_optional_string(
        source, "stop_reason_detail", MAX_STOP_REASON_DETAIL_CHARACTERS
    )
    if operational and stop_reason is not None:
        raise ValidationError(f"正常使用的机台 {machine_id} 不能包含停止原因")
    if not operational and stop_reason is None:
        raise ValidationError(f"停止使用的机台 {machine_id} 必须包含停止原因")
    if stop_reason != "OTHER" and stop_reason_detail is not None:
        raise ValidationError(f"机台 {machine_id} 仅能为其他原因补充说明")

    playing = normalize_registration_list(source.get("playing"), f"机台 {machine_id} 游玩位置")
    if len(playing) > 2:
        raise ValidationError(f"机台 {machine_id} 游玩位置不能超过 2 个登记")
    if any(registration["online_registration_pending_check_in"] for registration in playing):
        raise ValidationError(f"机台 {machine_id} 的待签到登记不能处于游玩位置")
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

    playing_started_at = read_optional_integer(
        source, "playing_started_at", minimum=1
    )
    new_registration_estimated_wait_minutes = read_optional_integer(
        source,
        "new_registration_estimated_wait_minutes",
        minimum=0,
        maximum=24 * 60,
    )
    if not operational:
        playing_started_at = None
        new_registration_estimated_wait_minutes = None
        waiting_positions = [
            {**position, "estimated_wait_minutes": None}
            for position in waiting_positions
        ]

    return {
        "id": machine_id,
        "name": normalize_machine_name(machine_id, source, allow_custom_name),
        "operational": operational,
        "stop_reason": stop_reason,
        "stop_reason_detail": stop_reason_detail,
        "stopped_at": read_optional_integer(source, "stopped_at", minimum=1),
        "playing_started_at": playing_started_at,
        "registration_count": registration_count,
        "waiting_position_count": len(waiting_positions),
        "new_registration_estimated_wait_minutes": new_registration_estimated_wait_minutes,
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
    deferred_once = read_boolean(source, "deferred_once")
    if deferred_once and temporarily_away:
        raise ValidationError("登记不能同时处于暂缓一轮和暂时离开状态")
    no_show_count = read_integer(source, "no_show_count", minimum=0, maximum=10_000)
    last_no_show_action_was_defer = read_boolean(
        source, "last_no_show_action_was_defer"
    )
    if no_show_count == 0 and last_no_show_action_was_defer:
        raise ValidationError("没有未到场记录时不能包含上次处理方式")
    online_registration_pending_check_in = source.get(
        "online_registration_pending_check_in", False
    )
    if type(online_registration_pending_check_in) is not bool:
        raise ValidationError("online_registration_pending_check_in 必须是布尔值")
    registration_type = read_choice(source, "registration_type", REGISTRATION_TYPES)
    if online_registration_pending_check_in and registration_type != "PLAYER_PROFILE":
        raise ValidationError("待签到的线上登记必须关联玩家资料")
    if online_registration_pending_check_in and (deferred_once or temporarily_away):
        raise ValidationError("待签到的线上登记不能同时暂缓或暂时离开")

    return {
        "registration_id": read_public_id(source, "registration_id"),
        "display_id": read_string(source, "display_id", maximum_length=18),
        "preference": read_choice(source, "preference", PREFERENCES),
        "deferred_once": deferred_once,
        "temporarily_away": temporarily_away,
        "temporary_away_skipped_turns": temporary_away_skipped_turns,
        "fixed_pair": fixed_pair,
        "fixed_pair_id": fixed_pair_id,
        "no_show_count": no_show_count,
        "last_no_show_action_was_defer": last_no_show_action_was_defer,
        "online_registration_pending_check_in": online_registration_pending_check_in,
        "registration_type": registration_type,
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
    absence_states = {
        (
            registration["deferred_once"],
            registration["temporarily_away"],
            registration["temporary_away_skipped_turns"],
            registration["online_registration_pending_check_in"],
        )
        for registration in fixed_registrations
    }
    if len(absence_states) != 1:
        raise ValidationError(f"{label}的固定组合可用状态不一致")


def all_machine_registrations(machine: dict[str, Any]):
    yield from machine["playing"]
    for position in machine["waiting_positions"]:
        yield from position["registrations"]


def attach_public_registration_contacts(
    machines: dict[str, dict[str, Any]], contacts: list[dict[str, str]]
) -> None:
    qq_by_registration_id = {
        contact["registration_id"]: contact["qq_number"] for contact in contacts
    }
    for machine in machines.values():
        for registration in all_machine_registrations(machine):
            registration["qq_number"] = qq_by_registration_id.get(
                registration["registration_id"]
            )


def read_string(source: dict[str, Any], key: str, maximum_length: int) -> str:
    value = source.get(key)
    if not isinstance(value, str):
        raise ValidationError(f"{key} 必须是文本")
    value = value.strip()
    if not value or len(value) > maximum_length:
        raise ValidationError(f"{key} 长度无效")
    return value


def read_optional_string(
    source: dict[str, Any], key: str, maximum_length: int
) -> str | None:
    value = source.get(key)
    if value is None:
        return None
    if not isinstance(value, str):
        raise ValidationError(f"{key} 必须是文本")
    value = value.strip()
    if not value:
        return None
    if len(value) > maximum_length or not value.isprintable():
        raise ValidationError(f"{key} 长度或内容无效")
    return value


def read_qq_number(source: dict[str, Any], key: str) -> str:
    value = source.get(key)
    if not isinstance(value, str) or QQ_NUMBER_PATTERN.fullmatch(value) is None:
        raise ValidationError(f"{key} 必须是 5 至 12 位 QQ 号")
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
