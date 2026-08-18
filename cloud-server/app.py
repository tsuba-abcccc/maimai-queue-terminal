import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Any
from urllib.parse import urlencode
from uuid import UUID, uuid4

from flask import Flask, current_app, jsonify, make_response, request


PUBLIC_SCHEMA_VERSION = 8
SUPPORTED_SCHEMA_VERSIONS = {1, 2, 3, 4, 5, 6, 7, 8}
MAX_PAYLOAD_BYTES = 1024 * 1024
MAX_REGISTRATIONS_PER_MACHINE = 20
MAX_MACHINE_COUNT = 10
MAX_PLANNED_ROUND_MINUTES = 120
MAX_ESTIMATED_WAIT_MINUTES = (
    MAX_REGISTRATIONS_PER_MACHINE * MAX_PLANNED_ROUND_MINUTES
)
MAX_EVENT_REGISTRATION_IDS = MAX_REGISTRATIONS_PER_MACHINE * MAX_MACHINE_COUNT
MAX_PLAYER_PROFILES = 500
MAX_EVENTS_PER_SNAPSHOT = 200
MAX_PRIVATE_CONTACTS = (
    MAX_REGISTRATIONS_PER_MACHINE * MAX_MACHINE_COUNT + MAX_EVENTS_PER_SNAPSHOT * 2
)
MAX_STORED_EVENTS_PER_QUEUE = 2_000
MAX_LOG_PAGE_SIZE = 100
MAX_STOP_REASON_DETAIL_CHARACTERS = 40
PUBLIC_ID_PATTERN = re.compile(r"^[0-9a-f]{24}$")
MACHINE_INTERNAL_ID_PATTERN = re.compile(r"^[0-9a-f]{32}$")
QQ_NUMBER_PATTERN = re.compile(r"^[0-9]{5,12}$")
PLAYER_PUBLIC_ID_PATTERN = re.compile(r"^[0-9]{6}$")
MOBILE_SESSION_TOKEN_PATTERN = re.compile(r"^[A-Za-z0-9_-]{32,128}$")
PLAYER_ACCOUNT_SESSION_COOKIE = "maimai_q_session"
PLAYER_ACCOUNT_PASSWORD_MIN_LENGTH = 8
PLAYER_ACCOUNT_PASSWORD_MAX_LENGTH = 128
PLAYER_ACCOUNT_SESSION_TOKEN_BYTES = 32
PLAYER_ACCOUNT_BINDING_TOKEN_BYTES = 32
PLAYER_ACCOUNT_SCRYPT_N = 2**14
PLAYER_ACCOUNT_SCRYPT_R = 8
PLAYER_ACCOUNT_SCRYPT_P = 1
SEMANTIC_VERSION_PATTERN = re.compile(
    r"^v?(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
    r"(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$"
)
PREFERENCES = {"SOLO", "OPEN_TO_JOIN"}
STOP_REASONS = {"NOT_POWERED_ON", "NETWORK_DISCONNECTED", "MAINTENANCE", "OTHER"}
REGISTRATION_TYPES = {"TEMPORARY", "PLAYER_PROFILE"}
PLAYER_GENDERS = {"MALE", "FEMALE", "UNDISCLOSED"}
PROFILE_PREFERENCES = {"SOLO", "OPEN_TO_JOIN", "ASK_EVERY_TIME"}
QQ_VISIBILITIES = {"TERMINAL_ONLY", "PUBLIC_WEBSITE"}
NOTIFICATION_FIELDS = (
    "notification_enabled",
    "notify_queue_changes",
    "notify_playing_position",
    "notify_online_check_in",
    "notify_absence",
    "notify_machine_status",
)
BOT_PROFILE_UPDATE_FIELDS = (
    "nickname",
    "gender",
    "default_preference",
    "qq_visibility",
    *NOTIFICATION_FIELDS,
    "setup_version",
)
PROFILE_UPDATE_COMMAND = "UPDATE_PLAYER_PROFILE"
QUEUE_OPERATION_COMMAND = "QUEUE_OPERATION"
MOBILE_REGISTRATION_COMMAND = "MOBILE_DEVICE_REGISTRATION"
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
RESULT_SOURCE_SYNC_DISABLED = "SYNC_DISABLED"
COMMAND_TIMEOUT_DETAIL = "终端未在有效时间内处理这次修改，请重新提交。"
BOT_DISABLED_DETAIL = "QQ Bot 联动已关闭，这次修改没有执行。"
SYNC_DISABLED_DETAIL = "与服务端同步已关闭，这次操作没有执行。"
MOBILE_SESSION_INVALIDATED_DETAIL = "这次移动设备登记已经失效，请在终端重新打开。"
TEST_SYNC_ENDED_DETAIL = "测试同步已经结束，这次修改没有执行，请重新提交。"
TAKEOVER_QUEUE_CONTEXT_CHANGED_DETAIL = (
    "现场终端已经接管，原队列或机台配置发生变化，这次排队操作没有执行，"
    "请重新查询后再提交。"
)
TERMINAL_INSTANCE_CONFLICT_DETAIL = "另一份终端实例正在同步，请关闭重复打开的应用后重试。"
APPLIED_JOIN_SYNC_GUARD_SECONDS = 30
SYNC_MODES = {"test", "takeover"}
MACHINE_NAMES = {
    "A": "左侧·机台 A",
    "B": "右侧·机台 B",
    "C": "中间左侧·机台 C",
    "D": "中间右侧·机台 D",
    "E": "第 5 台·机台 E",
    "F": "第 6 台·机台 F",
    "G": "第 7 台·机台 G",
    "H": "第 8 台·机台 H",
    "I": "第 9 台·机台 I",
    "J": "第 10 台·机台 J",
}
DEFAULT_MACHINE_GROUP_ID = "00000000000000000000000000000001"
MAX_MACHINE_GROUP_NAME_CHARACTERS = 12
MAX_MACHINE_REMARK_CHARACTERS = 8
MAX_MACHINE_TYPE_CHARACTERS = 24
MAX_MACHINE_SERVER_CHARACTERS = 24
MAX_GAME_VERSION_CHARACTERS = 40
MAX_VENUE_NAME_CHARACTERS = 40
MAX_TERMINAL_NAME_CHARACTERS = 24
VENUE_CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
VENUE_CODE_LENGTH = 8
# Only remove horizontal spacing around the middle dot.  A broad ``\s*``
# would also consume a line break, joining otherwise separate log lines.
MIDDLE_DOT_SPACING_PATTERN = re.compile(
    r"[^\S\r\n\u2028\u2029]*·[^\S\r\n\u2028\u2029]*"
)
MACHINE_GAME_TYPES = {
    "MAIMAI_DX",
    "CHUNITHM",
    "ONGEKI",
    "DANCE_CUBE",
    "TAIKO_NO_TATSUJIN",
    "OTHER",
}
SERVER_CONFIGURABLE_GAME_TYPES = {"MAIMAI_DX", "CHUNITHM", "ONGEKI"}
MACHINE_SERVERS = {
    "CHINA",
    "INTERNATIONAL",
    "JAPAN",
    "DABING",
    "RINNET",
    "OTHER",
    "HIDDEN",
}
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
    "ONLINE_REGISTRATION_ADDED",
    "ONLINE_CHECK_IN_COMPLETED",
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
PUBLIC_NOTIFICATION_CATEGORIES = {
    "QUEUE_CHANGES",
    "PLAYING_POSITION",
    "ONLINE_CHECK_IN",
    "ABSENCE",
    "MACHINE_STATUS",
}
OPERATION_SOURCES = {
    "ON_SITE_TERMINAL",
    "QQ_BOT",
    "SYSTEM_AUTOMATIC",
    "WEBSITE_REMOTE",
    "MOBILE_DEVICE",
}

REQUIRED_DATABASE_COLUMNS = {
    "queue_snapshot": {"instance_id", "instance_generation", "venue_id"},
    "venue": {
        "venue_id",
        "venue_code",
        "profile_scope_id",
        "display_name",
        "business_hours_json",
        "created_at",
        "updated_at",
    },
    "registered_terminal": {
        "terminal_id",
        "venue_id",
        "display_name",
        "role",
        "created_at",
        "updated_at",
        "last_seen_at",
    },
    "player_profile": {
        "public_player_id",
        "web_account_bound",
        "terminal_editing_allowed",
        "visited_venues_public",
        "web_profile_revision",
    },
    "player_account": {
        "account_id",
        "profile_scope_id",
        "profile_id",
        "password_salt",
        "password_hash",
        "created_at",
        "updated_at",
        "password_changed_at",
    },
    "player_web_session": {
        "session_id",
        "account_id",
        "token_hash",
        "csrf_hash",
        "created_at",
        "last_seen_at",
        "expires_at",
        "revoked_at",
    },
    "player_binding_session": {
        "binding_id",
        "profile_scope_id",
        "profile_id",
        "token_hash",
        "created_by_terminal",
        "created_at",
        "expires_at",
        "consumed_at",
        "invalidated_at",
    },
    "player_auth_limit": {
        "action",
        "subject_hash",
        "window_started_at",
        "failure_count",
        "blocked_until",
    },
    "player_public_id_alias": {
        "profile_scope_id",
        "public_player_id",
        "canonical_profile_id",
        "created_at",
    },
    "terminal_command": {
        "claimed_at",
        "claimed_terminal",
        "claimed_instance",
        "result_registration_id",
        "result_source",
    },
    "queue_event": {
        "operation_source",
        "notification_categories",
        "machine_stable_id",
        "machine_name",
    },
    "queue_event_recipient": {"stored_at"},
    "mobile_registration_session": {"machine_stable_id"},
    "service_identity": {
        "bot_version",
        "bot_version_updated_at",
        "website_version",
        "website_version_updated_at",
    },
}


class ValidationError(ValueError):
    def __init__(
        self,
        message: str,
        *,
        code: str | None = None,
        field: str | None = None,
        details: dict[str, Any] | None = None,
    ):
        super().__init__(message)
        self.code = code
        self.field = field
        self.details = details or {}


def validation_error_payload(error: ValidationError) -> dict[str, Any]:
    payload: dict[str, Any] = {"ok": False, "error": str(error)}
    if error.code is not None:
        payload["code"] = error.code
    if error.field is not None:
        payload["field"] = error.field
    if error.details:
        payload["details"] = error.details
    return payload


def deterministic_player_public_id_start(
    profile_scope_id: str, profile_id: str
) -> int:
    digest = hashlib.sha256(
        f"{profile_scope_id}:{profile_id}".encode("utf-8")
    ).digest()
    return int.from_bytes(digest[:8], "big") % 1_000_000


def allocate_player_public_id(
    connection: sqlite3.Connection,
    *,
    profile_scope_id: str,
    profile_id: str,
) -> str:
    occupied = {
        row[0]
        for row in connection.execute(
            """
            SELECT public_player_id FROM player_profile
            WHERE device_id = ? AND public_player_id IS NOT NULL
            UNION
            SELECT public_player_id FROM player_public_id_alias
            WHERE profile_scope_id = ?
            """,
            (profile_scope_id, profile_scope_id),
        ).fetchall()
    }
    start = deterministic_player_public_id_start(profile_scope_id, profile_id)
    for offset in range(1_000_000):
        candidate = f"{(start + offset) % 1_000_000:06d}"
        if candidate not in occupied:
            return candidate
    raise RuntimeError("玩家编号空间已经用尽")


def public_player_id_owner(
    connection: sqlite3.Connection,
    *,
    profile_scope_id: str,
    public_player_id: str,
) -> str | None:
    row = connection.execute(
        """
        SELECT profile_id FROM player_profile
        WHERE device_id = ? AND public_player_id = ?
        UNION ALL
        SELECT canonical_profile_id FROM player_public_id_alias
        WHERE profile_scope_id = ? AND public_player_id = ?
        LIMIT 1
        """,
        (
            profile_scope_id,
            public_player_id,
            profile_scope_id,
            public_player_id,
        ),
    ).fetchone()
    return row[0] if row is not None else None


def preserve_player_public_id_alias(
    connection: sqlite3.Connection,
    *,
    profile_scope_id: str,
    public_player_id: str | None,
    canonical_profile_id: str,
    created_at: int,
    previous_profile_id: str | None = None,
) -> None:
    if public_player_id is None:
        return
    owner = public_player_id_owner(
        connection,
        profile_scope_id=profile_scope_id,
        public_player_id=public_player_id,
    )
    if owner not in (None, canonical_profile_id, previous_profile_id):
        raise RuntimeError("玩家编号已经属于另一份玩家资料")
    connection.execute(
        """
        INSERT INTO player_public_id_alias
            (profile_scope_id, public_player_id, canonical_profile_id, created_at)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(profile_scope_id, public_player_id) DO UPDATE SET
            canonical_profile_id = excluded.canonical_profile_id
        """,
        (
            profile_scope_id,
            public_player_id,
            canonical_profile_id,
            created_at,
        ),
    )


def read_player_public_id_aliases(
    connection: sqlite3.Connection,
    *,
    profile_scope_id: str,
) -> dict[str, list[str]]:
    aliases: dict[str, list[str]] = {}
    for public_player_id, canonical_profile_id in connection.execute(
        """
        SELECT public_player_id, canonical_profile_id
        FROM player_public_id_alias
        WHERE profile_scope_id = ?
        ORDER BY public_player_id
        """,
        (profile_scope_id,),
    ).fetchall():
        aliases.setdefault(canonical_profile_id, []).append(public_player_id)
    return aliases


def normalize_existing_player_public_ids(connection: sqlite3.Connection) -> None:
    rows = connection.execute(
        """
        SELECT device_id, profile_id, public_player_id
        FROM player_profile
        ORDER BY device_id, profile_id
        """
    ).fetchall()
    occupied_by_scope: dict[str, set[str]] = {}
    for profile_scope_id, public_player_id in connection.execute(
        """
        SELECT profile_scope_id, public_player_id
        FROM player_public_id_alias
        WHERE public_player_id IS NOT NULL
        """
    ).fetchall():
        if PLAYER_PUBLIC_ID_PATTERN.fullmatch(public_player_id or ""):
            occupied_by_scope.setdefault(profile_scope_id, set()).add(public_player_id)
    for profile_scope_id, profile_id, public_player_id in rows:
        occupied = occupied_by_scope.setdefault(profile_scope_id, set())
        normalized = (
            public_player_id
            if isinstance(public_player_id, str)
            and PLAYER_PUBLIC_ID_PATTERN.fullmatch(public_player_id)
            and public_player_id not in occupied
            else None
        )
        if normalized is None:
            start = deterministic_player_public_id_start(profile_scope_id, profile_id)
            for offset in range(1_000_000):
                candidate = f"{(start + offset) % 1_000_000:06d}"
                if candidate not in occupied:
                    normalized = candidate
                    break
        if normalized is None:
            raise RuntimeError("玩家编号空间已经用尽")
        occupied.add(normalized)
        if normalized != public_player_id:
            connection.execute(
                """
                UPDATE player_profile SET public_player_id = ?
                WHERE device_id = ? AND profile_id = ?
                """,
                (normalized, profile_scope_id, profile_id),
            )


def create_app(config: dict[str, Any] | None = None) -> Flask:
    app = Flask(__name__)
    # A self-hosted/public build must never silently emit the maintainer's
    # origin.  Deployment files provide this value explicitly; an omitted
    # value leaves CORS disabled until the operator configures their own site.
    configured_cors_origin = os.getenv("QUEUE_CORS_ORIGIN", "").strip()
    configured_public_site_url = os.getenv("QUEUE_PUBLIC_SITE_URL", "").strip()
    if not configured_public_site_url:
        configured_public_site_url = (
            f"{configured_cors_origin.rstrip('/')}/queue-status"
            if configured_cors_origin
            else ""
        )
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
        COMMAND_CLAIM_LEASE_SECONDS=int(
            os.getenv("QUEUE_COMMAND_CLAIM_LEASE_SECONDS", "15")
        ),
        COMMAND_RETENTION_SECONDS=int(
            os.getenv("QUEUE_COMMAND_RETENTION_SECONDS", "2592000")
        ),
        EVENT_RECIPIENT_RETENTION_SECONDS=int(
            os.getenv("QUEUE_EVENT_RECIPIENT_RETENTION_SECONDS", "2592000")
        ),
        MOBILE_SESSION_TTL_SECONDS=int(
            os.getenv("QUEUE_MOBILE_SESSION_TTL_SECONDS", "600")
        ),
        MOBILE_SESSION_RETENTION_SECONDS=int(
            os.getenv("QUEUE_MOBILE_SESSION_RETENTION_SECONDS", "86400")
        ),
        PLAYER_ACCOUNT_SESSION_TTL_SECONDS=int(
            os.getenv("QUEUE_PLAYER_SESSION_TTL_SECONDS", "2592000")
        ),
        PLAYER_BINDING_SESSION_TTL_SECONDS=int(
            os.getenv("QUEUE_PLAYER_BINDING_TTL_SECONDS", "600")
        ),
        PLAYER_AUTH_LIMIT_WINDOW_SECONDS=int(
            os.getenv("QUEUE_PLAYER_AUTH_LIMIT_WINDOW_SECONDS", "900")
        ),
        PLAYER_AUTH_LIMIT_BLOCK_SECONDS=int(
            os.getenv("QUEUE_PLAYER_AUTH_LIMIT_BLOCK_SECONDS", "900")
        ),
        PLAYER_AUTH_LIMIT_FAILURES=int(
            os.getenv("QUEUE_PLAYER_AUTH_LIMIT_FAILURES", "5")
        ),
        PLAYER_ACCOUNT_COOKIE_SECURE=os.getenv(
            "QUEUE_PLAYER_COOKIE_SECURE", "true"
        ).strip().lower() not in {"0", "false", "no"},
        PLAYER_ACCOUNT_SITE_URL=os.getenv(
            "QUEUE_PLAYER_ACCOUNT_SITE_URL", ""
        ).strip().rstrip("/"),
        CORS_ORIGIN=configured_cors_origin,
        PUBLIC_SITE_URL=configured_public_site_url.rstrip("/"),
        LATEST_TERMINAL_VERSION=os.getenv(
            "QUEUE_LATEST_TERMINAL_VERSION", "0.12.1"
        ),
        LATEST_WEBSITE_VERSION=os.getenv(
            "QUEUE_LATEST_WEBSITE_VERSION", "0.12.1"
        ),
        LATEST_BOT_VERSION=os.getenv("QUEUE_LATEST_BOT_VERSION", "0.3.13"),
        MAX_CONTENT_LENGTH=MAX_PAYLOAD_BYTES,
        JSON_AS_ASCII=False,
    )
    if config:
        app.config.update(config)

    initialize_database(
        app.config["DATABASE_PATH"],
        profile_scope_id=app.config["PROFILE_SCOPE_ID"],
    )
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


def generate_venue_code(connection: sqlite3.Connection) -> str:
    for _ in range(128):
        candidate = "".join(
            secrets.choice(VENUE_CODE_ALPHABET) for _ in range(VENUE_CODE_LENGTH)
        )
        if connection.execute(
            "SELECT 1 FROM venue WHERE venue_code = ?", (candidate,)
        ).fetchone() is None:
            return candidate
    raise RuntimeError("无法生成唯一机厅编号")


def initialize_database(database_path: str, *, profile_scope_id: str = "default") -> None:
    path = Path(database_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(path, timeout=10)
    try:
        connection.execute("PRAGMA journal_mode = WAL")
        connection.execute("PRAGMA secure_delete = ON")
        # Gunicorn workers import the app concurrently. Serialize schema inspection and
        # ALTER TABLE statements so two fresh workers cannot add the same column.
        connection.execute("BEGIN IMMEDIATE")
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS venue (
                venue_id TEXT PRIMARY KEY,
                venue_code TEXT NOT NULL UNIQUE,
                profile_scope_id TEXT NOT NULL UNIQUE,
                display_name TEXT,
                business_hours_json TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """
        )
        venue_columns = {
            row[1] for row in connection.execute("PRAGMA table_info(venue)")
        }
        if "business_hours_json" not in venue_columns:
            connection.execute(
                "ALTER TABLE venue ADD COLUMN business_hours_json TEXT"
            )
        active_venue = connection.execute(
            "SELECT venue_id FROM venue WHERE profile_scope_id = ?",
            (profile_scope_id,),
        ).fetchone()
        if active_venue is None:
            now = int(time.time())
            connection.execute(
                """
                INSERT INTO venue
                    (venue_id, venue_code, profile_scope_id, display_name,
                     created_at, updated_at)
                VALUES (?, ?, ?, NULL, ?, ?)
                """,
                (
                    str(uuid4()),
                    generate_venue_code(connection),
                    profile_scope_id,
                    now,
                    now,
                ),
            )
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS registered_terminal (
                terminal_id TEXT PRIMARY KEY,
                venue_id TEXT NOT NULL,
                display_name TEXT,
                role TEXT NOT NULL DEFAULT 'AUTHORITATIVE',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                last_seen_at INTEGER,
                FOREIGN KEY(venue_id) REFERENCES venue(venue_id)
            )
            """
        )
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS queue_snapshot (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                queue_id TEXT NOT NULL,
                revision INTEGER NOT NULL,
                payload TEXT NOT NULL,
                device_id TEXT NOT NULL,
                instance_id TEXT NOT NULL,
                instance_generation INTEGER NOT NULL,
                venue_id TEXT,
                received_at INTEGER NOT NULL
            )
            """
        )
        queue_snapshot_columns = {
            row[1] for row in connection.execute("PRAGMA table_info(queue_snapshot)")
        }
        for column_name, declaration in (
            ("instance_id", "TEXT NOT NULL DEFAULT ''"),
            ("instance_generation", "INTEGER NOT NULL DEFAULT 0"),
            ("venue_id", "TEXT"),
        ):
            if column_name not in queue_snapshot_columns:
                connection.execute(
                    f"ALTER TABLE queue_snapshot ADD COLUMN {column_name} {declaration}"
                )
        connection.execute(
            """
            UPDATE queue_snapshot
            SET instance_id = device_id
            WHERE instance_id = ''
            """
        )
        connection.execute(
            """
            UPDATE queue_snapshot
            SET venue_id = (
                SELECT venue_id FROM venue WHERE profile_scope_id = ?
            )
            WHERE venue_id IS NULL
            """,
            (profile_scope_id,),
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
                result_registration_id TEXT,
                claimed_at INTEGER,
                claimed_terminal TEXT,
                claimed_instance TEXT,
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
            ("claimed_instance", "TEXT"),
            ("result_registration_id", "TEXT"),
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
                qq_visibility TEXT NOT NULL DEFAULT 'TERMINAL_ONLY',
                notification_enabled INTEGER NOT NULL DEFAULT 1,
                notify_queue_changes INTEGER NOT NULL DEFAULT 1,
                notify_playing_position INTEGER NOT NULL DEFAULT 0,
                notify_online_check_in INTEGER NOT NULL DEFAULT 1,
                notify_absence INTEGER NOT NULL DEFAULT 1,
                notify_machine_status INTEGER NOT NULL DEFAULT 0,
                setup_version INTEGER NOT NULL DEFAULT 0,
                profile_revision INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL,
                profile_updated_at INTEGER NOT NULL,
                public_player_id TEXT,
                web_account_bound INTEGER NOT NULL DEFAULT 0,
                terminal_editing_allowed INTEGER NOT NULL DEFAULT 1,
                visited_venues_public INTEGER NOT NULL DEFAULT 1,
                web_profile_revision INTEGER NOT NULL DEFAULT 0,
                received_at INTEGER NOT NULL,
                PRIMARY KEY(device_id, profile_id)
            )
            """
        )
        player_profile_columns = {
            row[1] for row in connection.execute("PRAGMA table_info(player_profile)")
        }
        for column_name, declaration in (
            ("qq_visibility", "TEXT NOT NULL DEFAULT 'TERMINAL_ONLY'"),
            ("notification_enabled", "INTEGER NOT NULL DEFAULT 1"),
            ("notify_queue_changes", "INTEGER NOT NULL DEFAULT 1"),
            ("notify_playing_position", "INTEGER NOT NULL DEFAULT 0"),
            ("notify_online_check_in", "INTEGER NOT NULL DEFAULT 1"),
            ("notify_absence", "INTEGER NOT NULL DEFAULT 1"),
            ("notify_machine_status", "INTEGER NOT NULL DEFAULT 0"),
            ("setup_version", "INTEGER NOT NULL DEFAULT 0"),
            ("profile_revision", "INTEGER NOT NULL DEFAULT 1"),
            ("public_player_id", "TEXT"),
            ("web_account_bound", "INTEGER NOT NULL DEFAULT 0"),
            ("terminal_editing_allowed", "INTEGER NOT NULL DEFAULT 1"),
            ("visited_venues_public", "INTEGER NOT NULL DEFAULT 1"),
            ("web_profile_revision", "INTEGER NOT NULL DEFAULT 0"),
        ):
            if column_name not in player_profile_columns:
                connection.execute(
                    f"ALTER TABLE player_profile ADD COLUMN {column_name} {declaration}"
                )
        connection.execute(
            """
            CREATE INDEX IF NOT EXISTS player_profile_qq
            ON player_profile(device_id, qq_number)
            """
        )
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS player_public_id_alias (
                profile_scope_id TEXT NOT NULL,
                public_player_id TEXT NOT NULL,
                canonical_profile_id TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                PRIMARY KEY(profile_scope_id, public_player_id)
            )
            """
        )
        normalize_existing_player_public_ids(connection)
        connection.execute(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS player_profile_unique_public_id
            ON player_profile(device_id, public_player_id)
            WHERE public_player_id IS NOT NULL
            """
        )
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS current_player_profile (
                profile_scope_id TEXT NOT NULL,
                profile_id TEXT NOT NULL,
                PRIMARY KEY(profile_scope_id, profile_id)
            )
            """
        )
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS player_account (
                account_id TEXT PRIMARY KEY,
                profile_scope_id TEXT NOT NULL,
                profile_id TEXT NOT NULL,
                password_salt BLOB NOT NULL,
                password_hash BLOB NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                password_changed_at INTEGER NOT NULL,
                UNIQUE(profile_scope_id, profile_id)
            )
            """
        )
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS player_web_session (
                session_id TEXT PRIMARY KEY,
                account_id TEXT NOT NULL,
                token_hash TEXT NOT NULL UNIQUE,
                csrf_hash TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                last_seen_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                revoked_at INTEGER,
                FOREIGN KEY(account_id) REFERENCES player_account(account_id)
            )
            """
        )
        connection.execute(
            """
            CREATE INDEX IF NOT EXISTS player_web_session_account
            ON player_web_session(account_id, expires_at)
            """
        )
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS player_binding_session (
                binding_id TEXT PRIMARY KEY,
                profile_scope_id TEXT NOT NULL,
                profile_id TEXT NOT NULL,
                token_hash TEXT NOT NULL UNIQUE,
                created_by_terminal TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                consumed_at INTEGER,
                invalidated_at INTEGER
            )
            """
        )
        connection.execute(
            """
            CREATE INDEX IF NOT EXISTS player_binding_session_profile
            ON player_binding_session(profile_scope_id, profile_id, expires_at)
            """
        )
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS player_auth_limit (
                action TEXT NOT NULL,
                subject_hash TEXT NOT NULL,
                window_started_at INTEGER NOT NULL,
                failure_count INTEGER NOT NULL,
                blocked_until INTEGER,
                PRIMARY KEY(action, subject_hash)
            )
            """
        )
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS service_identity (
                profile_scope_id TEXT PRIMARY KEY,
                bot_qq TEXT NOT NULL,
                bot_version TEXT,
                bot_version_updated_at INTEGER,
                website_version TEXT,
                website_version_updated_at INTEGER,
                updated_at INTEGER NOT NULL
            )
            """
        )
        service_identity_columns = {
            row[1] for row in connection.execute("PRAGMA table_info(service_identity)")
        }
        for column_name, declaration in (
            ("bot_version", "TEXT"),
            ("bot_version_updated_at", "INTEGER"),
            ("website_version", "TEXT"),
            ("website_version_updated_at", "INTEGER"),
        ):
            if column_name not in service_identity_columns:
                connection.execute(
                    f"ALTER TABLE service_identity ADD COLUMN {column_name} {declaration}"
                )
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS mobile_registration_session (
                session_id TEXT PRIMARY KEY,
                session_token TEXT NOT NULL UNIQUE,
                queue_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                machine_id TEXT NOT NULL,
                machine_stable_id TEXT,
                status TEXT NOT NULL,
                command_id TEXT,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                submitted_at INTEGER,
                UNIQUE(command_id)
            )
            """
        )
        mobile_registration_session_columns = {
            row[1]
            for row in connection.execute(
                "PRAGMA table_info(mobile_registration_session)"
            )
        }
        if "machine_stable_id" not in mobile_registration_session_columns:
            connection.execute(
                "ALTER TABLE mobile_registration_session "
                "ADD COLUMN machine_stable_id TEXT"
            )
        connection.execute(
            """
            CREATE INDEX IF NOT EXISTS mobile_registration_session_expiry
            ON mobile_registration_session(expires_at, submitted_at)
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
                machine_stable_id TEXT,
                machine_name TEXT,
                event_type TEXT NOT NULL,
                title TEXT NOT NULL,
                detail TEXT NOT NULL,
                operation_source TEXT NOT NULL DEFAULT 'ON_SITE_TERMINAL',
                notification_categories TEXT NOT NULL DEFAULT '[]',
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
        if "notification_categories" not in queue_event_columns:
            connection.execute(
                """
                ALTER TABLE queue_event
                ADD COLUMN notification_categories TEXT NOT NULL DEFAULT '[]'
                """
            )
        if "machine_stable_id" not in queue_event_columns:
            connection.execute(
                """
                ALTER TABLE queue_event
                ADD COLUMN machine_stable_id TEXT
                """
            )
        if "machine_name" not in queue_event_columns:
            connection.execute(
                """
                ALTER TABLE queue_event
                ADD COLUMN machine_name TEXT
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
            "X-Queue-Venue-ID, X-CSRF-Token, "
            "X-Queue-Sync-Mode, X-Terminal-Instance-ID, "
            "X-Terminal-Instance-Generation"
        )
        if origin:
            response.headers["Access-Control-Allow-Credentials"] = "true"
        response.headers["Cache-Control"] = "no-store, max-age=0"
        response.headers["Pragma"] = "no-cache"
        response.headers["Vary"] = "Origin"
        return response

    @app.errorhandler(413)
    def payload_too_large(_error):
        return jsonify({"ok": False, "error": "队列数据超过大小限制"}), 413

    @app.get("/healthz")
    def health():
        try:
            with open_database() as connection:
                connection.execute("SELECT 1").fetchone()
                for table_name, required_columns in REQUIRED_DATABASE_COLUMNS.items():
                    actual_columns = {
                        row[1]
                        for row in connection.execute(
                            f"PRAGMA table_info({table_name})"
                        )
                    }
                    if not required_columns <= actual_columns:
                        return jsonify(
                            {
                                "status": "error",
                                "service": "maimai-queue-status",
                                "error": "database_schema_not_ready",
                            }
                        ), 503
        except sqlite3.Error:
            return jsonify(
                {
                    "status": "error",
                    "service": "maimai-queue-status",
                    "error": "database_unavailable",
                }
            ), 503
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

    @app.get("/api/queue-versions")
    def queue_versions():
        return read_queue_versions()

    @app.route("/api/queue-terminal/installation", methods=["GET", "POST"])
    def queue_terminal_installation():
        authorization_error = authorize_terminal()
        if authorization_error is not None:
            return authorization_error
        return read_or_update_terminal_installation()

    @app.route("/api/queue-terminal/venue-settings", methods=["GET", "PUT"])
    def queue_terminal_venue_settings():
        authorization_error = authorize_terminal()
        if authorization_error is not None:
            return authorization_error
        venue_error = authorize_terminal_venue()
        if venue_error is not None:
            return venue_error
        return read_or_update_venue_settings()

    @app.post("/api/queue-terminal/player-bindings")
    def queue_terminal_create_player_binding():
        authorization_error = authorize_terminal()
        if authorization_error is not None:
            return authorization_error
        venue_error = authorize_terminal_venue()
        if venue_error is not None:
            return venue_error
        return create_player_binding_session()

    @app.get("/api/player-account/bindings/<binding_token>")
    def player_account_binding(binding_token: str):
        return read_player_binding_session(binding_token)

    @app.post("/api/player-account/bindings/<binding_token>/complete")
    def player_account_complete_binding(binding_token: str):
        return complete_player_binding_session(binding_token)

    @app.post("/api/player-account/login")
    def player_account_login():
        return login_player_account()

    @app.get("/api/player-account")
    def player_account_current():
        return read_current_player_account()

    @app.patch("/api/player-account/profile")
    def player_account_update_profile():
        return update_current_player_account_profile()

    @app.post("/api/player-account/password")
    def player_account_update_password():
        return update_current_player_account_password()

    @app.get("/api/player-account/queue")
    def player_account_queue():
        return read_current_player_account_queue()

    @app.post("/api/player-account/queue-commands")
    def player_account_queue_command():
        return create_player_account_queue_operation_command()

    @app.post("/api/player-account/logout")
    def player_account_logout():
        return logout_player_account()

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

    @app.post("/api/queue-bot/identity")
    def queue_bot_identity():
        authorization_error = authorize_bot()
        if authorization_error is not None:
            return authorization_error
        return update_bot_identity()

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
        venue_error = authorize_terminal_venue()
        if venue_error is not None:
            return venue_error
        return read_terminal_commands()

    @app.get("/api/queue-terminal/profiles")
    def queue_terminal_profiles():
        authorization_error = authorize_terminal()
        if authorization_error is not None:
            return authorization_error
        venue_error = authorize_terminal_venue()
        if venue_error is not None:
            return venue_error
        return read_synced_profiles(allow_qq_filter=False)

    @app.post("/api/queue-terminal/mobile-registration-sessions")
    def queue_terminal_create_mobile_registration_session():
        authorization_error = authorize_terminal()
        if authorization_error is not None:
            return authorization_error
        venue_error = authorize_terminal_venue()
        if venue_error is not None:
            return venue_error
        return create_mobile_registration_session()

    @app.get("/api/queue-mobile/sessions/<session_token>")
    def queue_mobile_registration_session(session_token: str):
        return read_mobile_registration_session(session_token)

    @app.post("/api/queue-mobile/sessions/<session_token>/submit")
    def queue_mobile_registration_submit(session_token: str):
        return submit_mobile_registration_session(session_token)

    @app.get("/api/queue-mobile/sessions/<session_token>/result")
    def queue_mobile_registration_result(session_token: str):
        return read_mobile_registration_result(session_token)

    @app.post("/api/queue-terminal/commands/<command_id>/result")
    def queue_terminal_command_result(command_id: str):
        authorization_error = authorize_terminal()
        if authorization_error is not None:
            return authorization_error
        venue_error = authorize_terminal_venue()
        if venue_error is not None:
            return venue_error
        return complete_terminal_command(command_id)


def player_account_token_hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def invalidate_player_account_for_profile(
    connection: sqlite3.Connection,
    *,
    profile_scope_id: str,
    profile_id: str,
    now: int,
) -> None:
    """Invalidate stale web credentials when a profile is no longer bound.

    A profile can be imported from another terminal or database snapshot with
    ``web_account_bound`` cleared while the account tables still contain the
    old credential. Revoke every session and pending binding before removing
    the account row so the profile can be bound again without an orphaned
    login path.
    """
    account_rows = connection.execute(
        """
        SELECT account_id FROM player_account
        WHERE profile_scope_id = ? AND profile_id = ?
        """,
        (profile_scope_id, profile_id),
    ).fetchall()
    account_ids = [row["account_id"] for row in account_rows]
    if account_ids:
        connection.executemany(
            """
            UPDATE player_web_session
            SET revoked_at = COALESCE(revoked_at, ?)
            WHERE account_id = ?
            """,
            [(now, account_id) for account_id in account_ids],
        )
    connection.execute(
        """
        UPDATE player_binding_session
        SET invalidated_at = COALESCE(invalidated_at, ?)
        WHERE profile_scope_id = ? AND profile_id = ?
          AND consumed_at IS NULL
        """,
        (now, profile_scope_id, profile_id),
    )
    connection.execute(
        """
        DELETE FROM player_account
        WHERE profile_scope_id = ? AND profile_id = ?
        """,
        (profile_scope_id, profile_id),
    )


def validate_player_account_password(value: Any) -> str:
    if not isinstance(value, str):
        raise ValidationError("请输入账户密码。", code="PASSWORD_REQUIRED", field="password")
    if len(value) < PLAYER_ACCOUNT_PASSWORD_MIN_LENGTH:
        raise ValidationError(
            f"密码至少需要 {PLAYER_ACCOUNT_PASSWORD_MIN_LENGTH} 个字符。",
            code="PASSWORD_TOO_SHORT",
            field="password",
        )
    if len(value) > PLAYER_ACCOUNT_PASSWORD_MAX_LENGTH or len(value.encode("utf-8")) > 512:
        raise ValidationError(
            f"密码不能超过 {PLAYER_ACCOUNT_PASSWORD_MAX_LENGTH} 个字符。",
            code="PASSWORD_TOO_LONG",
            field="password",
        )
    if value.isspace():
        raise ValidationError("密码不能只包含空格。", code="PASSWORD_INVALID", field="password")
    return value


def hash_player_account_password(password: str, salt: bytes) -> bytes:
    return hashlib.scrypt(
        password.encode("utf-8"),
        salt=salt,
        n=PLAYER_ACCOUNT_SCRYPT_N,
        r=PLAYER_ACCOUNT_SCRYPT_R,
        p=PLAYER_ACCOUNT_SCRYPT_P,
        dklen=32,
    )


def player_auth_subject(action: str, value: str) -> str:
    return hashlib.sha256(f"{action}\0{value}".encode("utf-8")).hexdigest()


def player_auth_limit_error(
    connection: sqlite3.Connection,
    *,
    action: str,
    subject_hash: str,
    now: int,
):
    row = connection.execute(
        """
        SELECT blocked_until FROM player_auth_limit
        WHERE action = ? AND subject_hash = ?
        """,
        (action, subject_hash),
    ).fetchone()
    if row is None or row["blocked_until"] is None or row["blocked_until"] <= now:
        return None
    return jsonify(
        {
            "ok": False,
            "code": "AUTH_RATE_LIMITED",
            "error": "尝试次数过多，请稍后再试。",
            "retry_after": row["blocked_until"] - now,
        }
    ), 429


def record_player_auth_failure(
    connection: sqlite3.Connection,
    *,
    action: str,
    subject_hash: str,
    now: int,
) -> None:
    window_seconds = max(60, current_app.config["PLAYER_AUTH_LIMIT_WINDOW_SECONDS"])
    block_seconds = max(60, current_app.config["PLAYER_AUTH_LIMIT_BLOCK_SECONDS"])
    failure_limit = max(2, current_app.config["PLAYER_AUTH_LIMIT_FAILURES"])
    row = connection.execute(
        """
        SELECT window_started_at, failure_count, blocked_until
        FROM player_auth_limit WHERE action = ? AND subject_hash = ?
        """,
        (action, subject_hash),
    ).fetchone()
    if row is None or row["window_started_at"] <= now - window_seconds:
        window_started_at = now
        failure_count = 1
    else:
        window_started_at = row["window_started_at"]
        failure_count = row["failure_count"] + 1
    blocked_until = now + block_seconds if failure_count >= failure_limit else None
    connection.execute(
        """
        INSERT INTO player_auth_limit
            (action, subject_hash, window_started_at, failure_count, blocked_until)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT(action, subject_hash) DO UPDATE SET
            window_started_at = excluded.window_started_at,
            failure_count = excluded.failure_count,
            blocked_until = excluded.blocked_until
        """,
        (action, subject_hash, window_started_at, failure_count, blocked_until),
    )


def clear_player_auth_failures(
    connection: sqlite3.Connection, *, action: str, subject_hash: str
) -> None:
    connection.execute(
        "DELETE FROM player_auth_limit WHERE action = ? AND subject_hash = ?",
        (action, subject_hash),
    )


def player_account_cookie_options() -> dict[str, Any]:
    return {
        "secure": bool(current_app.config["PLAYER_ACCOUNT_COOKIE_SECURE"]),
        "samesite": "Lax",
        "path": "/",
    }


def set_player_account_cookies(
    response,
    *,
    session_token: str,
    csrf_token: str,
    max_age: int,
) -> None:
    options = player_account_cookie_options()
    response.set_cookie(
        PLAYER_ACCOUNT_SESSION_COOKIE,
        session_token,
        max_age=max_age,
        httponly=True,
        **options,
    )
    response.set_cookie(
        f"{PLAYER_ACCOUNT_SESSION_COOKIE}_csrf",
        csrf_token,
        max_age=max_age,
        httponly=False,
        **options,
    )


def clear_player_account_cookies(response) -> None:
    options = player_account_cookie_options()
    response.delete_cookie(
        PLAYER_ACCOUNT_SESSION_COOKIE,
        httponly=True,
        **options,
    )
    response.delete_cookie(
        f"{PLAYER_ACCOUNT_SESSION_COOKIE}_csrf",
        httponly=False,
        **options,
    )


def create_player_web_session(
    connection: sqlite3.Connection,
    *,
    account_id: str,
    now: int,
) -> tuple[str, str, int]:
    session_token = secrets.token_urlsafe(PLAYER_ACCOUNT_SESSION_TOKEN_BYTES)
    csrf_token = secrets.token_urlsafe(PLAYER_ACCOUNT_SESSION_TOKEN_BYTES)
    ttl_seconds = max(300, current_app.config["PLAYER_ACCOUNT_SESSION_TTL_SECONDS"])
    expires_at = now + ttl_seconds
    connection.execute(
        """
        INSERT INTO player_web_session
            (session_id, account_id, token_hash, csrf_hash, created_at,
             last_seen_at, expires_at, revoked_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, NULL)
        """,
        (
            str(uuid4()),
            account_id,
            player_account_token_hash(session_token),
            player_account_token_hash(csrf_token),
            now,
            now,
            expires_at,
        ),
    )
    return session_token, csrf_token, ttl_seconds


def current_player_account_session(
    connection: sqlite3.Connection,
) -> tuple[sqlite3.Row, sqlite3.Row] | None:
    session_token = request.cookies.get(PLAYER_ACCOUNT_SESSION_COOKIE, "")
    if not session_token or len(session_token) > 256:
        return None
    now = int(time.time())
    session = connection.execute(
        """
        SELECT * FROM player_web_session
        WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > ?
        """,
        (player_account_token_hash(session_token), now),
    ).fetchone()
    if session is None:
        return None
    account = connection.execute(
        """
        SELECT account.*, profile.nickname, profile.qq_number,
               profile.public_player_id, profile.gender,
               profile.default_preference, profile.qq_visibility,
               profile.notification_enabled, profile.notify_queue_changes,
               profile.notify_playing_position, profile.notify_online_check_in,
               profile.notify_absence, profile.notify_machine_status,
               profile.setup_version, profile.profile_revision,
               profile.web_account_bound, profile.terminal_editing_allowed,
               profile.visited_venues_public, profile.web_profile_revision,
               profile.profile_updated_at, venue.venue_id, venue.venue_code,
               venue.display_name AS venue_name
        FROM player_account AS account
        JOIN player_profile AS profile
          ON profile.device_id = account.profile_scope_id
         AND profile.profile_id = account.profile_id
         AND profile.web_account_bound = 1
        JOIN venue ON venue.profile_scope_id = account.profile_scope_id
        WHERE account.account_id = ?
        """,
        (session["account_id"],),
    ).fetchone()
    if account is None:
        return None
    if session["last_seen_at"] <= now - 300:
        connection.execute(
            "UPDATE player_web_session SET last_seen_at = ? WHERE session_id = ?",
            (now, session["session_id"]),
        )
    return session, account


def require_player_account_csrf(session: sqlite3.Row):
    header_token = request.headers.get("X-CSRF-Token", "")
    cookie_token = request.cookies.get(f"{PLAYER_ACCOUNT_SESSION_COOKIE}_csrf", "")
    if (
        not header_token
        or not cookie_token
        or not hmac.compare_digest(header_token, cookie_token)
        or not hmac.compare_digest(
            player_account_token_hash(header_token), session["csrf_hash"]
        )
    ):
        return jsonify(
            {
                "ok": False,
                "code": "CSRF_TOKEN_INVALID",
                "error": "页面验证信息已经失效，请刷新页面后重试。",
            }
        ), 403
    return None


def serialize_player_account(account: sqlite3.Row) -> dict[str, Any]:
    return {
        "account_id": account["account_id"],
        "profile": {
            "profile_id": account["profile_id"],
            "public_player_id": account["public_player_id"],
            "nickname": account["nickname"],
            "qq_number": account["qq_number"],
            "gender": account["gender"],
            "default_preference": account["default_preference"],
            "qq_visibility": account["qq_visibility"],
            "notification_enabled": bool(account["notification_enabled"]),
            "notify_queue_changes": bool(account["notify_queue_changes"]),
            "notify_playing_position": bool(account["notify_playing_position"]),
            "notify_online_check_in": bool(account["notify_online_check_in"]),
            "notify_absence": bool(account["notify_absence"]),
            "notify_machine_status": bool(account["notify_machine_status"]),
            "setup_complete": account["setup_version"] > 0,
            "profile_revision": account["profile_revision"],
            "web_account_bound": bool(account["web_account_bound"]),
            "terminal_editing_allowed": bool(account["terminal_editing_allowed"]),
            "visited_venues_public": bool(account["visited_venues_public"]),
            "web_profile_revision": account["web_profile_revision"],
            "updated_at": account["profile_updated_at"],
        },
        "venue": {
            "id": account["venue_id"],
            "code": account["venue_code"],
            "name": account["venue_name"],
        },
    }


def create_player_binding_session():
    source = request.get_json(silent=True)
    if not isinstance(source, dict):
        return jsonify({"ok": False, "error": "请求内容必须是 JSON 对象"}), 400
    try:
        profile_id = str(UUID(source.get("profile_id", "")))
    except (TypeError, ValueError):
        return jsonify({"ok": False, "error": "玩家资料编号无效"}), 400
    site_url = current_app.config["PLAYER_ACCOUNT_SITE_URL"] or current_app.config[
        "PUBLIC_SITE_URL"
    ]
    if not site_url:
        return jsonify(
            {
                "ok": False,
                "code": "PLAYER_ACCOUNT_SITE_NOT_CONFIGURED",
                "error": "服务端尚未配置玩家账户页面，请联系管理员。",
            }
        ), 503
    device_id = request.headers.get("X-Device-ID", "").strip()
    try:
        instance_id, instance_generation = read_terminal_instance_identity(device_id)
    except ValidationError as error:
        return jsonify(validation_error_payload(error)), 400
    now = int(time.time())
    with open_database() as connection:
        connection.execute("BEGIN IMMEDIATE")
        venue = read_active_venue(connection)
        snapshot = connection.execute(
            """
            SELECT device_id, instance_id, instance_generation, payload
            FROM queue_snapshot WHERE id = 1
            """
        ).fetchone()
        if not terminal_instance_matches(
            snapshot, device_id, instance_id, instance_generation
        ):
            connection.rollback()
            return jsonify(
                {
                    "ok": False,
                    "code": "STALE_TERMINAL_INSTANCE",
                    "error": TERMINAL_INSTANCE_CONFLICT_DETAIL,
                }
            ), 409
        snapshot_payload = json.loads(snapshot["payload"])
        if snapshot_payload.get("test_data", False):
            connection.rollback()
            return jsonify(
                {
                    "ok": False,
                    "code": "TEST_DATA_ACCOUNT_BINDING_UNAVAILABLE",
                    "error": "当前数据是测试数据，不能绑定长期网页账户。",
                }
            ), 409
        profile = connection.execute(
            """
            SELECT profile_id, public_player_id, nickname, qq_number, setup_version,
                   web_account_bound
            FROM player_profile
            WHERE device_id = ? AND profile_id = ?
              AND profile_id IN (
                  SELECT profile_id FROM current_player_profile
                  WHERE profile_scope_id = ?
              )
            """,
            (venue["profile_scope_id"], profile_id, venue["profile_scope_id"]),
        ).fetchone()
        if profile is None:
            connection.rollback()
            return jsonify({"ok": False, "error": "没有找到当前玩家资料"}), 404
        if profile["setup_version"] <= 0 or profile["qq_number"] is None:
            connection.rollback()
            return jsonify(
                {
                    "ok": False,
                    "code": "PLAYER_PROFILE_INCOMPLETE",
                    "error": "请先在现场终端补全玩家资料，再绑定网页账户。",
                }
            ), 409
        account = connection.execute(
            """
            SELECT account_id FROM player_account
            WHERE profile_scope_id = ? AND profile_id = ?
            """,
            (venue["profile_scope_id"], profile_id),
        ).fetchone()
        if account is not None and profile["web_account_bound"]:
            connection.rollback()
            return jsonify(
                {
                    "ok": False,
                    "code": "PLAYER_ACCOUNT_ALREADY_BOUND",
                    "error": "这份玩家资料已经绑定网页账户，请直接登录；资料和密码请在网页玩家资料中管理。",
                }
            ), 409
        if account is not None:
            # An imported profile may have lost its binding flag while the
            # account row survived. Remove that orphan before issuing a new
            # binding, otherwise completion could create a session for an
            # account id that the upsert would silently replace.
            invalidate_player_account_for_profile(
                connection,
                profile_scope_id=venue["profile_scope_id"],
                profile_id=profile_id,
                now=now,
            )
        binding_token = secrets.token_urlsafe(PLAYER_ACCOUNT_BINDING_TOKEN_BYTES)
        binding_id = str(uuid4())
        expires_at = now + max(
            60, current_app.config["PLAYER_BINDING_SESSION_TTL_SECONDS"]
        )
        connection.execute(
            """
            UPDATE player_binding_session SET invalidated_at = ?
            WHERE profile_scope_id = ? AND profile_id = ?
              AND consumed_at IS NULL AND invalidated_at IS NULL AND expires_at > ?
            """,
            (now, venue["profile_scope_id"], profile_id, now),
        )
        connection.execute(
            """
            INSERT INTO player_binding_session
                (binding_id, profile_scope_id, profile_id, token_hash,
                 created_by_terminal, created_at, expires_at, consumed_at,
                 invalidated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL)
            """,
            (
                binding_id,
                venue["profile_scope_id"],
                profile_id,
                player_account_token_hash(binding_token),
                device_id,
                now,
                expires_at,
            ),
        )
        connection.commit()
    separator = "&" if "?" in site_url else "?"
    binding_url = (
        f"{site_url}{separator}{urlencode({'account_binding': binding_token})}"
        if site_url
        else None
    )
    return jsonify(
        {
            "binding_id": binding_id,
            "binding_token": binding_token,
            "binding_url": binding_url,
            "expires_at": expires_at * 1000,
            "profile": {
                "profile_id": profile["profile_id"],
                "public_player_id": profile["public_player_id"],
                "nickname": profile["nickname"],
                "qq_number": profile["qq_number"],
            },
        }
    ), 201


def read_player_binding_row(
    connection: sqlite3.Connection, binding_token: str
) -> sqlite3.Row | None:
    if not MOBILE_SESSION_TOKEN_PATTERN.fullmatch(binding_token):
        return None
    return connection.execute(
        """
        SELECT binding.*, profile.public_player_id, profile.nickname,
               profile.qq_number, profile.setup_version, venue.venue_id,
               venue.venue_code, venue.display_name AS venue_name,
               account.account_id
        FROM player_binding_session AS binding
        JOIN player_profile AS profile
          ON profile.device_id = binding.profile_scope_id
         AND profile.profile_id = binding.profile_id
        JOIN venue ON venue.profile_scope_id = binding.profile_scope_id
        LEFT JOIN player_account AS account
          ON account.profile_scope_id = binding.profile_scope_id
         AND account.profile_id = binding.profile_id
         AND profile.web_account_bound = 1
        WHERE binding.token_hash = ?
        """,
        (player_account_token_hash(binding_token),),
    ).fetchone()


def player_binding_state_error(binding: sqlite3.Row | None, now: int):
    if binding is None:
        return jsonify({"ok": False, "error": "绑定页面无效，请在终端重新打开。"}), 404
    if binding["consumed_at"] is not None:
        return jsonify(
            {
                "ok": False,
                "code": "PLAYER_BINDING_USED",
                "error": "这次绑定已经完成，请直接登录。",
            }
        ), 409
    if binding["invalidated_at"] is not None or binding["expires_at"] <= now:
        return jsonify(
            {
                "ok": False,
                "code": "PLAYER_BINDING_EXPIRED",
                "error": "绑定页面已经失效，请在现场终端重新生成。",
            }
        ), 410
    if binding["account_id"] is not None:
        return jsonify(
            {
                "ok": False,
                "code": "PLAYER_ACCOUNT_ALREADY_BOUND",
                "error": "这份玩家资料已经绑定网页账户，请直接登录；资料和密码请在网页玩家资料中管理。",
            }
        ), 409
    if binding["setup_version"] <= 0 or binding["qq_number"] is None:
        return jsonify(
            {
                "ok": False,
                "code": "PLAYER_PROFILE_INCOMPLETE",
                "error": "玩家资料尚未补全，请先回到现场终端完成资料设置。",
            }
        ), 409
    return None


def read_player_binding_session(binding_token: str):
    now = int(time.time())
    with open_database() as connection:
        binding = read_player_binding_row(connection, binding_token)
    state_error = player_binding_state_error(binding, now)
    if state_error is not None:
        return state_error
    return jsonify(
        {
            "binding_id": binding["binding_id"],
            "expires_at": binding["expires_at"] * 1000,
            "profile": {
                "profile_id": binding["profile_id"],
                "public_player_id": binding["public_player_id"],
                "nickname": binding["nickname"],
                "qq_number": binding["qq_number"],
            },
            "venue": {
                "id": binding["venue_id"],
                "code": binding["venue_code"],
                "name": binding["venue_name"],
            },
        }
    )


def complete_player_binding_session(binding_token: str):
    source = request.get_json(silent=True)
    if not isinstance(source, dict):
        return jsonify({"ok": False, "error": "请求内容必须是 JSON 对象"}), 400
    try:
        password = validate_player_account_password(source.get("password"))
    except ValidationError as error:
        return jsonify(validation_error_payload(error)), 400
    now = int(time.time())
    subject_hash = player_auth_subject("binding", binding_token)
    with open_database() as connection:
        connection.execute("BEGIN IMMEDIATE")
        limit_error = player_auth_limit_error(
            connection,
            action="binding",
            subject_hash=subject_hash,
            now=now,
        )
        if limit_error is not None:
            connection.commit()
            return limit_error
        binding = read_player_binding_row(connection, binding_token)
        state_error = player_binding_state_error(binding, now)
        if state_error is not None:
            record_player_auth_failure(
                connection,
                action="binding",
                subject_hash=subject_hash,
                now=now,
            )
            connection.commit()
            return state_error
        salt = secrets.token_bytes(16)
        password_hash = hash_player_account_password(password, salt)
        account_id = binding["account_id"] or str(uuid4())
        connection.execute(
            """
            INSERT INTO player_account
                (account_id, profile_scope_id, profile_id, password_salt,
                 password_hash, created_at, updated_at, password_changed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(profile_scope_id, profile_id) DO UPDATE SET
                password_salt = excluded.password_salt,
                password_hash = excluded.password_hash,
                updated_at = excluded.updated_at,
                password_changed_at = excluded.password_changed_at
            """,
            (
                account_id,
                binding["profile_scope_id"],
                binding["profile_id"],
                salt,
                password_hash,
                now,
                now,
                now,
            ),
        )
        connection.execute(
            "UPDATE player_web_session SET revoked_at = ? WHERE account_id = ? AND revoked_at IS NULL",
            (now, account_id),
        )
        if binding["account_id"] is None:
            connection.execute(
                """
                UPDATE player_profile
                SET web_account_bound = 1,
                    terminal_editing_allowed = 0,
                    visited_venues_public = 1,
                    qq_visibility = 'PUBLIC_WEBSITE',
                    web_profile_revision = web_profile_revision + 1,
                    profile_revision = profile_revision + 1,
                    profile_updated_at = ?
                WHERE device_id = ? AND profile_id = ?
                """,
                (
                    now * 1000,
                    binding["profile_scope_id"],
                    binding["profile_id"],
                ),
            )
        connection.execute(
            "UPDATE player_binding_session SET consumed_at = ? WHERE binding_id = ?",
            (now, binding["binding_id"]),
        )
        session_token, csrf_token, ttl_seconds = create_player_web_session(
            connection, account_id=account_id, now=now
        )
        clear_player_auth_failures(
            connection, action="binding", subject_hash=subject_hash
        )
        connection.commit()
        session_account = current_player_account_session_for_id(connection, account_id)
    response = make_response(
        jsonify(
            {
                "ok": True,
                "account": serialize_player_account(session_account),
                "csrf_token": csrf_token,
            }
        )
    )
    set_player_account_cookies(
        response,
        session_token=session_token,
        csrf_token=csrf_token,
        max_age=ttl_seconds,
    )
    return response, 201


def current_player_account_session_for_id(
    connection: sqlite3.Connection, account_id: str
) -> sqlite3.Row:
    return connection.execute(
        """
        SELECT account.*, profile.nickname, profile.qq_number,
               profile.public_player_id, profile.gender,
               profile.default_preference, profile.qq_visibility,
               profile.notification_enabled, profile.notify_queue_changes,
               profile.notify_playing_position, profile.notify_online_check_in,
               profile.notify_absence, profile.notify_machine_status,
               profile.setup_version, profile.profile_revision,
               profile.web_account_bound, profile.terminal_editing_allowed,
               profile.visited_venues_public, profile.web_profile_revision,
               profile.profile_updated_at, venue.venue_id, venue.venue_code,
               venue.display_name AS venue_name
        FROM player_account AS account
        JOIN player_profile AS profile
          ON profile.device_id = account.profile_scope_id
         AND profile.profile_id = account.profile_id
         AND profile.web_account_bound = 1
        JOIN venue ON venue.profile_scope_id = account.profile_scope_id
        WHERE account.account_id = ?
        """,
        (account_id,),
    ).fetchone()


def login_player_account():
    source = request.get_json(silent=True)
    if not isinstance(source, dict):
        return jsonify({"ok": False, "error": "请求内容必须是 JSON 对象"}), 400
    qq_number = str(source.get("qq", "")).strip()
    password = source.get("password")
    if QQ_NUMBER_PATTERN.fullmatch(qq_number) is None or not isinstance(password, str):
        return jsonify(
            {
                "ok": False,
                "code": "ACCOUNT_LOGIN_FAILED",
                "error": "QQ 或密码不正确。",
            }
        ), 401
    now = int(time.time())
    subject_hash = player_auth_subject("login", qq_number)
    with open_database() as connection:
        connection.execute("BEGIN IMMEDIATE")
        limit_error = player_auth_limit_error(
            connection, action="login", subject_hash=subject_hash, now=now
        )
        if limit_error is not None:
            connection.commit()
            return limit_error
        account = connection.execute(
            """
            SELECT account.* FROM player_account AS account
            JOIN player_profile AS profile
              ON profile.device_id = account.profile_scope_id
             AND profile.profile_id = account.profile_id
             AND profile.web_account_bound = 1
            JOIN current_player_profile AS current_profile
              ON current_profile.profile_scope_id = account.profile_scope_id
             AND current_profile.profile_id = account.profile_id
            JOIN venue ON venue.profile_scope_id = account.profile_scope_id
            WHERE profile.qq_number = ?
            """,
            (qq_number,),
        ).fetchone()
        supplied_hash = (
            hash_player_account_password(password, bytes(account["password_salt"]))
            if account is not None
            else hash_player_account_password(password, b"\0" * 16)
        )
        if account is None or not hmac.compare_digest(
            supplied_hash, bytes(account["password_hash"])
        ):
            record_player_auth_failure(
                connection,
                action="login",
                subject_hash=subject_hash,
                now=now,
            )
            connection.commit()
            return jsonify(
                {
                    "ok": False,
                    "code": "ACCOUNT_LOGIN_FAILED",
                    "error": "QQ 或密码不正确。",
                }
            ), 401
        clear_player_auth_failures(
            connection, action="login", subject_hash=subject_hash
        )
        session_token, csrf_token, ttl_seconds = create_player_web_session(
            connection, account_id=account["account_id"], now=now
        )
        full_account = current_player_account_session_for_id(
            connection, account["account_id"]
        )
        connection.commit()
    response = make_response(
        jsonify(
            {
                "ok": True,
                "account": serialize_player_account(full_account),
                "csrf_token": csrf_token,
            }
        )
    )
    set_player_account_cookies(
        response,
        session_token=session_token,
        csrf_token=csrf_token,
        max_age=ttl_seconds,
    )
    return response


def read_current_player_account():
    with open_database() as connection:
        connection.execute("BEGIN IMMEDIATE")
        current = current_player_account_session(connection)
        if current is None:
            connection.commit()
            response = make_response(
                jsonify(
                    {
                        "ok": False,
                        "code": "ACCOUNT_LOGIN_REQUIRED",
                        "error": "请先登录玩家账户。",
                    }
                ),
                401,
            )
            clear_player_account_cookies(response)
            return response
        _, account = current
        csrf_token = request.cookies.get(f"{PLAYER_ACCOUNT_SESSION_COOKIE}_csrf", "")
        connection.commit()
    return jsonify(
        {
            "ok": True,
            "account": serialize_player_account(account),
            "csrf_token": csrf_token,
        }
    )


def update_current_player_account_profile():
    source = request.get_json(silent=True)
    if not isinstance(source, dict):
        return jsonify({"ok": False, "error": "请求内容必须是 JSON 对象"}), 400
    allowed_fields = {
        "expected_profile_revision",
        "nickname",
        "gender",
        "default_preference",
        "qq_number",
        "qq_visibility",
        *NOTIFICATION_FIELDS,
        "terminal_editing_allowed",
        "visited_venues_public",
        "current_password",
    }
    if not set(source) <= allowed_fields:
        return jsonify({"ok": False, "error": "请求包含不支持的玩家资料字段"}), 400
    editable_fields = set(source) - {"expected_profile_revision", "current_password"}
    if not editable_fields:
        return jsonify({"ok": False, "error": "没有需要保存的玩家资料字段"}), 400
    try:
        expected_revision = read_integer(
            source, "expected_profile_revision", minimum=1, maximum=2**63 - 1
        )
        requested: dict[str, Any] = {}
        if "nickname" in source:
            requested["nickname"] = read_string(source, "nickname", maximum_length=18)
        if "gender" in source:
            requested["gender"] = read_choice(source, "gender", PLAYER_GENDERS)
        if "default_preference" in source:
            requested["default_preference"] = read_choice(
                source, "default_preference", PROFILE_PREFERENCES
            )
        if "qq_number" in source:
            requested["qq_number"] = read_qq_number(source, "qq_number")
        if "qq_visibility" in source:
            requested["qq_visibility"] = read_choice(
                source, "qq_visibility", QQ_VISIBILITIES
            )
        for field in NOTIFICATION_FIELDS:
            if field in source:
                requested[field] = read_boolean(source, field)
        for field in ("terminal_editing_allowed", "visited_venues_public"):
            if field in source:
                requested[field] = read_boolean(source, field)
    except ValidationError as error:
        return jsonify(validation_error_payload(error)), 400

    now = int(time.time())
    with open_database() as connection:
        connection.execute("BEGIN IMMEDIATE")
        current = current_player_account_session(connection)
        if current is None:
            connection.commit()
            return jsonify(
                {
                    "ok": False,
                    "code": "ACCOUNT_LOGIN_REQUIRED",
                    "error": "请先登录玩家账户。",
                }
            ), 401
        session, account = current
        csrf_error = require_player_account_csrf(session)
        if csrf_error is not None:
            connection.rollback()
            return csrf_error
        if account["profile_revision"] != expected_revision:
            connection.rollback()
            return jsonify(
                {
                    "ok": False,
                    "code": "PLAYER_PROFILE_CHANGED",
                    "error": "玩家资料已经在其他端更新，请刷新后重试。",
                }
            ), 409

        qq_changed = (
            "qq_number" in requested
            and requested["qq_number"] != account["qq_number"]
        )
        if qq_changed:
            subject_hash = player_auth_subject("qq-change", account["account_id"])
            limit_error = player_auth_limit_error(
                connection,
                action="qq-change",
                subject_hash=subject_hash,
                now=now,
            )
            if limit_error is not None:
                connection.commit()
                return limit_error
            current_password = source.get("current_password")
            if not isinstance(current_password, str) or not hmac.compare_digest(
                hash_player_account_password(
                    current_password, bytes(account["password_salt"])
                ),
                bytes(account["password_hash"]),
            ):
                record_player_auth_failure(
                    connection,
                    action="qq-change",
                    subject_hash=subject_hash,
                    now=now,
                )
                connection.commit()
                return jsonify(
                    {
                        "ok": False,
                        "code": "CURRENT_PASSWORD_INVALID",
                        "field": "current_password",
                        "error": "当前密码不正确，QQ 没有修改。",
                    }
                ), 403
            clear_player_auth_failures(
                connection,
                action="qq-change",
                subject_hash=subject_hash,
            )
            conflict = connection.execute(
                """
                SELECT 1 FROM player_profile
                WHERE device_id = ? AND profile_id != ? AND qq_number = ?
                """,
                (
                    account["profile_scope_id"],
                    account["profile_id"],
                    requested["qq_number"],
                ),
            ).fetchone()
            if conflict is not None:
                connection.commit()
                return jsonify(
                    {
                        "ok": False,
                        "code": "QQ_ALREADY_USED",
                        "field": "qq_number",
                        "error": "这个 QQ 已经关联其他玩家资料。",
                    }
                ), 409

        nickname = requested.get("nickname")
        if nickname is not None and nickname.casefold() != account["nickname"].casefold():
            profile_conflict = connection.execute(
                """
                SELECT 1 FROM player_profile
                WHERE device_id = ? AND profile_id != ? AND lower(nickname) = lower(?)
                """,
                (account["profile_scope_id"], account["profile_id"], nickname),
            ).fetchone()
            queue_conflict = False
            snapshot_row = connection.execute(
                "SELECT device_id, queue_id, payload FROM queue_snapshot WHERE id = 1"
            ).fetchone()
            if snapshot_row is not None:
                snapshot_payload = json.loads(snapshot_row["payload"])
                queue_storage_id = queue_storage_id_for_snapshot(
                    snapshot_row["queue_id"], snapshot_payload, snapshot_row["device_id"]
                )
                for machine in snapshot_payload.get("machines", {}).values():
                    for registration in all_machine_registrations(machine):
                        if str(registration.get("display_id", "")).casefold() != nickname.casefold():
                            continue
                        contact = connection.execute(
                            """
                            SELECT player_id FROM queue_private_contact
                            WHERE queue_id = ? AND registration_id = ?
                            """,
                            (queue_storage_id, registration.get("registration_id")),
                        ).fetchone()
                        if contact is None or contact["player_id"] != account["profile_id"]:
                            queue_conflict = True
                            break
                    if queue_conflict:
                        break
            if profile_conflict is not None or queue_conflict:
                connection.rollback()
                return jsonify(
                    {
                        "ok": False,
                        "code": "NICKNAME_ALREADY_USED",
                        "field": "nickname",
                        "error": "这个昵称已经用于其他玩家资料或当前登记。",
                    }
                ), 409

        update_values = {
            "nickname": account["nickname"],
            "gender": account["gender"],
            "default_preference": account["default_preference"],
            "qq_number": account["qq_number"],
            "qq_visibility": account["qq_visibility"],
            "notification_enabled": bool(account["notification_enabled"]),
            "notify_queue_changes": bool(account["notify_queue_changes"]),
            "notify_playing_position": bool(account["notify_playing_position"]),
            "notify_online_check_in": bool(account["notify_online_check_in"]),
            "notify_absence": bool(account["notify_absence"]),
            "notify_machine_status": bool(account["notify_machine_status"]),
            "terminal_editing_allowed": bool(account["terminal_editing_allowed"]),
            "visited_venues_public": bool(account["visited_venues_public"]),
            **requested,
        }
        changed = any(update_values[field] != account[field] for field in update_values)
        if changed:
            updated_at = max(now * 1000, account["profile_updated_at"] + 1)
            connection.execute(
                """
                UPDATE player_profile SET
                    nickname = ?, gender = ?, default_preference = ?,
                    qq_number = ?, qq_visibility = ?,
                    notification_enabled = ?, notify_queue_changes = ?,
                    notify_playing_position = ?, notify_online_check_in = ?,
                    notify_absence = ?, notify_machine_status = ?,
                    terminal_editing_allowed = ?, visited_venues_public = ?,
                    web_profile_revision = web_profile_revision + 1,
                    profile_revision = profile_revision + 1,
                    profile_updated_at = ?
                WHERE device_id = ? AND profile_id = ?
                """,
                (
                    update_values["nickname"],
                    update_values["gender"],
                    update_values["default_preference"],
                    update_values["qq_number"],
                    update_values["qq_visibility"],
                    int(update_values["notification_enabled"]),
                    int(update_values["notify_queue_changes"]),
                    int(update_values["notify_playing_position"]),
                    int(update_values["notify_online_check_in"]),
                    int(update_values["notify_absence"]),
                    int(update_values["notify_machine_status"]),
                    int(update_values["terminal_editing_allowed"]),
                    int(update_values["visited_venues_public"]),
                    updated_at,
                    account["profile_scope_id"],
                    account["profile_id"],
                ),
            )
        updated_account = current_player_account_session_for_id(
            connection, account["account_id"]
        )
        connection.commit()
    return jsonify(
        {
            "ok": True,
            "changed": changed,
            "account": serialize_player_account(updated_account),
            "sync_status": "WAITING_FOR_TERMINAL" if changed else "CURRENT",
        }
    )


def update_current_player_account_password():
    source = request.get_json(silent=True)
    if not isinstance(source, dict):
        return jsonify({"ok": False, "error": "请求内容必须是 JSON 对象"}), 400
    current_password = source.get("current_password")
    try:
        new_password = validate_player_account_password(source.get("new_password"))
        confirmation = validate_player_account_password(
            source.get("new_password_confirmation")
        )
    except ValidationError as error:
        return jsonify(validation_error_payload(error)), 400
    if new_password != confirmation:
        return jsonify(
            {
                "ok": False,
                "code": "PASSWORD_CONFIRMATION_MISMATCH",
                "field": "new_password_confirmation",
                "error": "两次输入的新密码不一致。",
            }
        ), 400
    if not isinstance(current_password, str):
        return jsonify(
            {
                "ok": False,
                "code": "CURRENT_PASSWORD_REQUIRED",
                "field": "current_password",
                "error": "请输入当前账户密码。",
            }
        ), 400
    now = int(time.time())
    with open_database() as connection:
        connection.execute("BEGIN IMMEDIATE")
        current = current_player_account_session(connection)
        if current is None:
            connection.commit()
            return jsonify(
                {
                    "ok": False,
                    "code": "ACCOUNT_LOGIN_REQUIRED",
                    "error": "请先登录玩家账户。",
                }
            ), 401
        session, account = current
        csrf_error = require_player_account_csrf(session)
        if csrf_error is not None:
            connection.rollback()
            return csrf_error
        subject_hash = player_auth_subject("password-change", account["account_id"])
        limit_error = player_auth_limit_error(
            connection,
            action="password-change",
            subject_hash=subject_hash,
            now=now,
        )
        if limit_error is not None:
            connection.commit()
            return limit_error
        supplied_hash = hash_player_account_password(
            current_password, bytes(account["password_salt"])
        )
        if not hmac.compare_digest(supplied_hash, bytes(account["password_hash"])):
            record_player_auth_failure(
                connection,
                action="password-change",
                subject_hash=subject_hash,
                now=now,
            )
            connection.commit()
            return jsonify(
                {
                    "ok": False,
                    "code": "CURRENT_PASSWORD_INVALID",
                    "field": "current_password",
                    "error": "当前密码不正确，密码没有修改。",
                }
            ), 403
        clear_player_auth_failures(
            connection,
            action="password-change",
            subject_hash=subject_hash,
        )
        if hmac.compare_digest(
            hash_player_account_password(
                new_password, bytes(account["password_salt"])
            ),
            bytes(account["password_hash"]),
        ):
            connection.commit()
            return jsonify(
                {
                    "ok": False,
                    "code": "PASSWORD_UNCHANGED",
                    "error": "新密码不能与当前密码相同。",
                }
            ), 400
        salt = secrets.token_bytes(16)
        password_hash = hash_player_account_password(new_password, salt)
        connection.execute(
            """
            UPDATE player_account
            SET password_salt = ?, password_hash = ?,
                password_changed_at = ?, updated_at = ?
            WHERE account_id = ?
            """,
            (salt, password_hash, now, now, account["account_id"]),
        )
        connection.execute(
            """
            UPDATE player_web_session
            SET revoked_at = ?
            WHERE account_id = ? AND session_id != ? AND revoked_at IS NULL
            """,
            (now, account["account_id"], session["session_id"]),
        )
        updated_account = current_player_account_session_for_id(
            connection, account["account_id"]
        )
        connection.commit()
    return jsonify(
        {
            "ok": True,
            "account": serialize_player_account(updated_account),
            "message": "密码已修改，其他设备上的网页登录已退出。",
        }
    )


def read_current_player_account_queue():
    with open_database() as connection:
        connection.execute("BEGIN IMMEDIATE")
        current = current_player_account_session(connection)
        if current is None:
            connection.commit()
            return jsonify(
                {
                    "ok": False,
                    "code": "ACCOUNT_LOGIN_REQUIRED",
                    "error": "请先登录玩家账户。",
                }
            ), 401
        _, account = current
        snapshot_row = connection.execute(
            """
            SELECT queue_id, revision, payload, device_id, received_at
            FROM queue_snapshot WHERE id = 1
            """
        ).fetchone()
        if snapshot_row is None:
            connection.commit()
            return jsonify(
                {
                    "ok": True,
                    "queue": None,
                    "registrations": [],
                    "error": "现场终端尚未同步排队状态。",
                }
            )
        snapshot = json.loads(snapshot_row["payload"])
        queue_storage_id, _ = snapshot_storage_context(snapshot_row, snapshot)
        contexts = find_profile_registration_contexts(
            connection,
            queue_storage_id,
            snapshot,
            account["profile_id"],
        )
        connection.commit()

    now = int(time.time())
    terminal_online = (
        max(0, now - snapshot_row["received_at"])
        <= current_app.config["ONLINE_TIMEOUT_SECONDS"]
    )
    remote_actions = bool(
        terminal_online and snapshot.get("website_remote_enabled", False)
    )
    machines = {
        machine_id: serialize_remote_machine(machine_id, machine)
        for machine_id, machine in snapshot.get("machines", {}).items()
    }
    registrations = []
    for context in contexts:
        registration = context["registration"]
        machine = snapshot.get("machines", {}).get(context["machine_id"], {})
        registrations.append(
            {
                "registration_id": registration["registration_id"],
                "display_id": registration["display_id"],
                "machine_id": context["machine_id"],
                "machine_stable_id": context["machine_stable_id"],
                "machine_name": context["machine_name"],
                "machine_operational": context["machine_operational"],
                "position": context["position"],
                "position_index": context["position_index"],
                "estimated_wait_minutes": context["estimated_wait_minutes"],
                "preference": registration["preference"],
                "fixed_pair": registration["fixed_pair"],
                "fixed_pair_id": registration["fixed_pair_id"],
                "deferred_once": registration["deferred_once"],
                "temporarily_away": registration["temporarily_away"],
                "temporary_away_skipped_turns": registration[
                    "temporary_away_skipped_turns"
                ],
                "online_registration_pending_check_in": registration.get(
                    "online_registration_pending_check_in", False
                ),
                "machine_capacity": machine_capacity(machine),
            }
        )
    return jsonify(
        {
            "ok": True,
            "queue": {
                "queue_id": snapshot_row["queue_id"],
                "revision": snapshot_row["revision"],
                "machine_configuration_revision": snapshot.get(
                    "machine_configuration_revision", 1
                ),
                "received_at": snapshot_row["received_at"] * 1000,
                "terminal_online": terminal_online,
                "remote_actions": remote_actions,
                "queue_rules": snapshot.get("queue_rules")
                or normalize_public_queue_rules(None),
                "machines": list(machines.values()),
            },
            "registrations": registrations,
        }
    )


def create_player_account_queue_operation_command():
    source = request.get_json(silent=True)
    if not isinstance(source, dict):
        return jsonify({"ok": False, "error": "请求内容必须是 JSON 对象"}), 400
    allowed_fields = {
        "request_id",
        "operation",
        "target_machine_id",
        "preference",
        "expected_queue_id",
        "expected_registration_id",
        "expected_machine_id",
        "expected_position",
        "expected_fixed_pair_id",
        "expected_absence_status",
        "expected_temporary_away_skipped_turns",
        "expected_pending_check_in",
        "expected_machine_configuration_revision",
        "expected_machine_stable_id",
        "expected_target_machine_stable_id",
    }
    if set(source) - allowed_fields:
        return jsonify(
            {"ok": False, "error": "请求包含不支持的个人排队操作字段"}
        ), 400
    if source.get("operation") == "JOIN_QUEUE":
        return jsonify(
            {"ok": False, "error": "加入排队请使用网站上的“加入排队”入口。"}
        ), 400
    with open_database() as connection:
        connection.execute("BEGIN IMMEDIATE")
        current = current_player_account_session(connection)
        if current is None:
            connection.commit()
            return jsonify(
                {
                    "ok": False,
                    "code": "ACCOUNT_LOGIN_REQUIRED",
                    "error": "请先登录玩家账户。",
                }
            ), 401
        session, account = current
        csrf_error = require_player_account_csrf(session)
        if csrf_error is not None:
            connection.rollback()
            return csrf_error
        actor_qq = account["qq_number"]
        actor_profile_id = account["profile_id"]
        connection.commit()
    return create_queue_operation_command(
        {**source, "actor_qq": actor_qq},
        "WEBSITE_REMOTE",
        actor_profile_id=actor_profile_id,
    )


def logout_player_account():
    with open_database() as connection:
        connection.execute("BEGIN IMMEDIATE")
        current = current_player_account_session(connection)
        if current is not None:
            session, _ = current
            csrf_error = require_player_account_csrf(session)
            if csrf_error is not None:
                connection.rollback()
                return csrf_error
            connection.execute(
                "UPDATE player_web_session SET revoked_at = ? WHERE session_id = ?",
                (int(time.time()), session["session_id"]),
            )
        connection.commit()
    response = make_response(jsonify({"ok": True}))
    clear_player_account_cookies(response)
    return response


def publish_snapshot():
    authorization_error = authorize_terminal()
    if authorization_error is not None:
        return authorization_error

    schema_header = request.headers.get("X-Queue-Schema-Version", "").strip()
    invalid_schema_header = False
    try:
        requested_schema_version = int(schema_header) if schema_header else None
    except ValueError:
        # Do not silently treat a malformed declaration as a legacy client.
        # That would let a broken/new client skip the schema-8 venue checks.
        requested_schema_version = None
        invalid_schema_header = bool(schema_header)
    if invalid_schema_header:
        return jsonify(
            {
                "ok": False,
                "code": "SCHEMA_VERSION_INVALID",
                "error": "请求头中的队列协议版本无效。",
            }
        ), 400
    if (
        requested_schema_version is not None
        and requested_schema_version not in SUPPORTED_SCHEMA_VERSIONS
    ):
        return jsonify(
            {
                "ok": False,
                "code": "SCHEMA_VERSION_UNSUPPORTED",
                "error": "请求头中的队列协议版本暂不支持。",
            }
        ), 400
    device_id = request.headers.get("X-Device-ID", "").strip()
    if not device_id or len(device_id) > 128:
        return jsonify({"ok": False, "error": "终端编号无效"}), 400
    try:
        instance_id, instance_generation = read_terminal_instance_identity(device_id)
    except ValidationError as error:
        return jsonify({"ok": False, "error": str(error)}), 400

    requested_sync_mode = request.headers.get("X-Queue-Sync-Mode", "").strip().lower()
    if requested_sync_mode and requested_sync_mode not in SYNC_MODES:
        return jsonify({"ok": False, "error": "同步方式无效"}), 400

    payload = request.get_json(silent=True)
    if not isinstance(payload, dict):
        return jsonify({"ok": False, "error": "请求内容必须是 JSON 对象"}), 400

    try:
        payload_schema_version = read_integer(payload, "schema_version", minimum=1)
    except ValidationError as error:
        return jsonify(validation_error_payload(error)), 400
    # Legacy clients did not consistently send a header while migrating
    # between schema revisions. Keep that compatibility path intact, but do
    # not allow either side to disguise a schema-8 request as legacy.
    modern_schema_declared = (
        payload_schema_version >= 8 or requested_schema_version is not None and requested_schema_version >= 8
    )
    if payload_schema_version >= 8 and requested_schema_version is None:
        return jsonify(
            {
                "ok": False,
                "code": "SCHEMA_VERSION_REQUIRED",
                "error": "新版终端同步必须明确声明队列协议版本。",
            }
        ), 400
    if modern_schema_declared and requested_schema_version != payload_schema_version:
        return jsonify(
            {
                "ok": False,
                "code": "SCHEMA_VERSION_MISMATCH",
                "error": "请求头与请求内容中的队列协议版本不一致。",
            }
        ), 400
    if payload_schema_version >= 8 and requested_schema_version >= 8:
        # The header check above runs before parsing the body so that an
        # unverified terminal cannot reach normalization or mutate state.
        venue_error = authorize_terminal_venue()
        if venue_error is not None:
            return venue_error
        # Check the raw venue envelope before normalizing the rest of the
        # snapshot. A stale schema-8 client must receive the identity boundary
        # error even when it also omitted newer fields; otherwise a generic
        # payload error would hide the reason synchronization is paused.
        submitted_venue = payload.get("venue")
        submitted_venue_id = (
            submitted_venue.get("id")
            if isinstance(submitted_venue, dict)
            else None
        )
        try:
            submitted_venue_id = str(UUID(submitted_venue_id))
        except (TypeError, ValueError):
            return jsonify(
                {
                    "ok": False,
                    "code": "VENUE_ID_REQUIRED",
                    "error": "新版终端同步必须包含已核对的机厅 ID，本次同步没有执行。",
                }
            ), 409
        header_venue_id = request.headers.get("X-Queue-Venue-ID", "").strip()
        if submitted_venue_id != header_venue_id:
            return jsonify(
                {
                    "ok": False,
                    "code": "VENUE_MISMATCH",
                    "error": "提交的机厅与当前服务端不一致，本次同步没有执行。",
                }
            ), 409

    try:
        normalized = normalize_snapshot(payload, device_id)
    except ValidationError as error:
        return jsonify(validation_error_payload(error)), 400

    events = normalized.pop("recent_events", [])
    private_contacts = normalized.pop("private_player_contacts", [])
    private_profiles = normalized.pop("private_player_profiles", None)
    queue_id = normalized["queue_id"]
    revision = normalized["revision"]
    website_remote_enabled = normalized["website_remote_enabled"]
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
        venue = read_active_venue(connection)
        submitted_venue = normalized.get("venue")
        submitted_venue_id = (
            submitted_venue.get("id")
            if isinstance(submitted_venue, dict)
            else None
        )
        if payload_schema_version >= 8 and not submitted_venue_id:
            connection.rollback()
            return jsonify(
                {
                    "ok": False,
                    "code": "VENUE_ID_REQUIRED",
                    "error": "新版终端同步必须包含已核对的机厅 ID，本次同步没有执行。",
                }
            ), 409
        if submitted_venue_id is not None and submitted_venue_id != venue["venue_id"]:
            connection.rollback()
            return jsonify(
                {
                    "ok": False,
                    "code": "VENUE_MISMATCH",
                    "error": (
                        "终端绑定的机厅与当前服务器不一致。为防止覆盖其他机厅的队列，"
                        "本次同步没有执行，请在设置中核对服务器与机厅。"
                    ),
                }
            ), 409
        normalized["venue"] = serialize_venue(venue)
        try:
            upsert_registered_terminal(
                connection,
                terminal_id=device_id,
                venue_id=venue["venue_id"],
                display_name=normalized.get("terminal", {}).get("name"),
                seen_at=now,
            )
        except ValidationError as error:
            connection.rollback()
            return jsonify(validation_error_payload(error)), 409
        current = connection.execute(
            """
            SELECT queue_id, revision, payload, device_id, instance_id,
                   instance_generation, venue_id, received_at
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
        current_instance_changed = bool(
            current
            and current["device_id"] == device_id
            and current["instance_id"] != instance_id
        )

        if current is None:
            # There is no existing queue to displace, so the first authenticated terminal can
            # establish the official snapshot without a takeover choice.
            incoming_is_test = requested_sync_mode == "test"
        elif current["device_id"] == device_id:
            if instance_generation < current["instance_generation"]:
                connection.rollback()
                return jsonify(
                    {
                        "ok": False,
                        "code": "STALE_TERMINAL_INSTANCE",
                        "error": TERMINAL_INSTANCE_CONFLICT_DETAIL,
                    }
                ), 409
            if (
                current_instance_changed
                and instance_generation == current["instance_generation"]
                and current_is_online
            ):
                connection.rollback()
                return jsonify(
                    {
                        "ok": False,
                        "code": "TERMINAL_INSTANCE_CONFLICT",
                        "error": TERMINAL_INSTANCE_CONFLICT_DETAIL,
                    }
                ), 409
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
        queue_storage_id = queue_storage_id_for_snapshot(
            queue_id, normalized, device_id
        )
        profile_scope_id = profile_scope_id_for_snapshot(normalized, device_id)
        current_queue_storage_id = (
            queue_storage_id_for_snapshot(
                current["queue_id"], current_payload, current["device_id"]
            )
            if current is not None
            else None
        )
        if current_is_test and not incoming_is_test:
            # Releases before 0.10.1 stored test retirement markers under the public
            # queue id. The returning official terminal is the source of truth.
            connection.execute(
                "DELETE FROM retired_queue WHERE queue_id = ?",
                (queue_storage_id,),
            )
        serialized = json.dumps(normalized, ensure_ascii=False, separators=(",", ":"))
        previous_queue_contacts = {}
        if (
            current is not None
            and current["queue_id"] != queue_id
            and current_is_test == incoming_is_test
        ):
            previous_queue_contacts = {
                row["registration_id"]: row
                for row in connection.execute(
                    """
                    SELECT registration_id, player_id, qq_number
                    FROM queue_private_contact
                    WHERE queue_id = ?
                    """,
                    (current_queue_storage_id,),
                ).fetchall()
            }
        retired = connection.execute(
            "SELECT 1 FROM retired_queue WHERE queue_id = ?", (queue_storage_id,)
        ).fetchone()

        if retired is not None:
            connection.rollback()
            return jsonify({"ok": False, "error": "此队列批次已经结束"}), 409

        if current is not None and current_queue_storage_id == queue_storage_id:
            if revision < current["revision"]:
                connection.rollback()
                return jsonify({"ok": False, "error": "队列版本早于服务器版本"}), 409
        elif (
            current is not None
            and current["device_id"] == device_id
            and current_is_test == incoming_is_test
        ):
            connection.execute(
                "INSERT OR IGNORE INTO retired_queue (queue_id, retired_at) VALUES (?, ?)",
                (current_queue_storage_id, now),
            )

        connection.execute(
            """
            INSERT INTO queue_snapshot
                (id, queue_id, revision, payload, device_id, instance_id,
                 instance_generation, venue_id, received_at)
            VALUES
                (1, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                queue_id = excluded.queue_id,
                revision = excluded.revision,
                payload = excluded.payload,
                device_id = excluded.device_id,
                instance_id = excluded.instance_id,
                instance_generation = excluded.instance_generation,
                venue_id = excluded.venue_id,
                received_at = excluded.received_at
            """,
            (
                queue_id,
                revision,
                serialized,
                device_id,
                instance_id,
                instance_generation,
                venue["venue_id"],
                now,
            ),
        )
        data_scope_changed = bool(
            current is not None and current_is_test != incoming_is_test
        )
        if data_scope_changed:
            connection.execute(
                """
                UPDATE mobile_registration_session
                SET status = 'INVALIDATED'
                WHERE status = 'OPEN' AND device_id = ?
                """,
                (current["device_id"],),
            )
            if current["device_id"] == device_id:
                connection.execute(
                    """
                    UPDATE terminal_command
                    SET status = 'REJECTED', completed_at = ?, result_detail = ?,
                        result_source = ?
                    WHERE status = 'PENDING' AND device_id = ?
                      AND (claimed_at IS NULL OR ?)
                    """,
                    (
                        now,
                        TEST_SYNC_ENDED_DETAIL,
                        RESULT_SOURCE_SERVER_MIGRATION,
                        device_id,
                        int(current_is_test and not incoming_is_test),
                    ),
                )
        if current_instance_changed:
            if takeover_changes_queue_context(current_payload, normalized):
                connection.execute(
                    """
                    UPDATE terminal_command
                    SET status = 'REJECTED', completed_at = ?, result_detail = ?,
                        result_source = ?
                    WHERE status = 'PENDING' AND device_id = ?
                      AND command_type IN (?, ?)
                    """,
                    (
                        now,
                        TAKEOVER_QUEUE_CONTEXT_CHANGED_DETAIL,
                        RESULT_SOURCE_SERVER_MIGRATION,
                        device_id,
                        QUEUE_OPERATION_COMMAND,
                        MOBILE_REGISTRATION_COMMAND,
                    ),
                )
            connection.execute(
                """
                UPDATE terminal_command
                SET claimed_at = NULL, claimed_terminal = NULL, claimed_instance = NULL
                WHERE status = 'PENDING' AND device_id = ?
                  AND claimed_instance IS NOT NULL AND claimed_instance != ?
                """,
                (device_id, instance_id),
            )
        if (
            current is not None
            and current_queue_storage_id != queue_storage_id
            and current_is_test == incoming_is_test
        ):
            connection.execute(
                "DELETE FROM queue_private_contact WHERE queue_id = ?",
                (current_queue_storage_id,),
            )
        stored_contacts = {
            row["registration_id"]: row
            for row in connection.execute(
                """
                SELECT registration_id, player_id, qq_number
                FROM queue_private_contact
                WHERE queue_id = ?
                """,
                (queue_storage_id,),
            ).fetchall()
        }
        incoming_contacts = {
            contact["registration_id"]: {
                "registration_id": contact["registration_id"],
                "player_id": contact["profile_id"],
                "qq_number": contact["qq_number"],
            }
            for contact in private_contacts
        }
        all_contacts = dict(stored_contacts)
        all_contacts.update(incoming_contacts)
        event_contacts = {
            registration_id: contact
            for registration_id, contact in all_contacts.items()
            if registration_id not in current_registration_ids
            or registration_id in current_contact_ids
        }

        # A snapshot may contain an old registration for a recent event and a new
        # current registration belonging to the same profile. Keep both in
        # event_contacts, but persist at most one contact per profile. Current
        # registrations take priority; otherwise retain the historical contact
        # so a later event can still be routed to its original recipient.
        contacts_to_store = {
            contact["player_id"]: contact
            for registration_id, contact in stored_contacts.items()
            if registration_id not in current_registration_ids
            or registration_id in current_contact_ids
        }
        for contact in private_contacts:
            registration_id = contact["registration_id"]
            if registration_id not in current_registration_ids:
                normalized_contact = incoming_contacts[registration_id]
                contacts_to_store[normalized_contact["player_id"]] = normalized_contact
        for contact in private_contacts:
            registration_id = contact["registration_id"]
            if registration_id in current_registration_ids:
                normalized_contact = incoming_contacts[registration_id]
                contacts_to_store[normalized_contact["player_id"]] = normalized_contact
        connection.execute(
            "DELETE FROM queue_private_contact WHERE queue_id = ?",
            (queue_storage_id,),
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
                    queue_storage_id,
                    contact["registration_id"],
                    contact["player_id"],
                    contact["qq_number"],
                    now,
                )
                for contact in contacts_to_store.values()
            ],
        )
        if private_profiles is not None:
            replace_current_player_profile_ids(
                connection,
                profile_scope_id=profile_scope_id,
                profile_ids={profile["profile_id"] for profile in private_profiles},
            )
            upsert_player_profiles(
                connection,
                profile_scope_id=profile_scope_id,
                terminal_device_id=device_id,
                profiles=private_profiles,
                received_at=now,
            )
        if not website_remote_enabled:
            connection.execute(
                """
                UPDATE terminal_command
                SET status = 'REJECTED', completed_at = ?,
                    result_detail = ?, result_source = ?
                WHERE status = 'PENDING' AND claimed_at IS NULL AND device_id = ? AND (
                    command_type = ?
                    OR json_extract(payload, '$.operation_source') = 'WEBSITE_REMOTE'
                )
                """,
                (
                    now,
                    SYNC_DISABLED_DETAIL,
                    RESULT_SOURCE_SYNC_DISABLED,
                    device_id,
                    MOBILE_REGISTRATION_COMMAND,
                ),
            )
            connection.execute(
                """
                UPDATE mobile_registration_session
                SET status = 'INVALIDATED'
                WHERE status = 'OPEN' AND device_id = ?
                """,
                (device_id,),
            )
        if not onebot_sync_enabled:
            connection.execute(
                "DELETE FROM queue_event_recipient WHERE queue_id = ?",
                (queue_storage_id,),
            )
            connection.execute(
                """
                UPDATE terminal_command
                SET status = 'REJECTED', completed_at = ?,
                    result_detail = ?, result_source = ?
                WHERE status = 'PENDING' AND claimed_at IS NULL AND device_id = ? AND (
                    command_type = ?
                    OR json_extract(payload, '$.operation_source') = 'QQ_BOT'
                )
                """,
                (
                    now,
                    BOT_DISABLED_DETAIL,
                    RESULT_SOURCE_BOT_DISABLED,
                    device_id,
                    PROFILE_UPDATE_COMMAND,
                ),
            )
        notification_settings = {
            row["profile_id"]: row
            for row in connection.execute(
                """
                SELECT profile_id, notification_enabled, notify_queue_changes,
                       notify_playing_position, notify_online_check_in,
                       notify_absence, notify_machine_status, setup_version
                FROM player_profile WHERE device_id = ?
                """,
                (profile_scope_id,),
            ).fetchall()
        }
        for event in sorted(events, key=lambda value: value["occurred_at"]):
            event_registration_ids = (
                list(previous_queue_contacts)
                if event["type"] == "QUEUE_RESET" and previous_queue_contacts
                else event["registration_ids"]
            )
            inserted_event = connection.execute(
                """
                INSERT OR IGNORE INTO queue_event
                    (queue_id, event_id, occurred_at, machine_id,
                     machine_stable_id, machine_name, event_type,
                     title, detail, operation_source, notification_categories,
                     registration_ids)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    queue_storage_id,
                    event["event_id"],
                    event["occurred_at"],
                    event["machine_id"],
                    event["machine_stable_id"],
                    event["machine_name"],
                    event["type"],
                    event["title"],
                    event["detail"],
                    event["operation_source"],
                    json.dumps(event["notification_categories"], separators=(",", ":")),
                    json.dumps(event_registration_ids, separators=(",", ":")),
                ),
            )
            is_new_event = inserted_event.rowcount == 1
            if not is_new_event and (
                event["machine_stable_id"] is not None
                or event["machine_name"] is not None
            ):
                connection.execute(
                    """
                    UPDATE queue_event
                    SET machine_stable_id = COALESCE(machine_stable_id, ?),
                        machine_name = COALESCE(machine_name, ?)
                    WHERE queue_id = ? AND event_id = ?
                    """,
                    (
                        event["machine_stable_id"],
                        event["machine_name"],
                        queue_storage_id,
                        event["event_id"],
                    ),
                )
            if is_new_event and onebot_sync_enabled:
                if event["type"] == "QUEUE_RESET" and previous_queue_contacts:
                    recipient_contacts = previous_queue_contacts.values()
                else:
                    recipient_contacts = (
                        event_contacts[registration_id]
                        for registration_id in event_registration_ids
                        if registration_id in event_contacts
                    )
                recipient_contacts = [
                    contact
                    for contact in recipient_contacts
                    if profile_allows_event_notification(
                        notification_settings.get(contact["player_id"]),
                        event["type"],
                        event["notification_categories"],
                    )
                ]
                connection.executemany(
                    """
                    INSERT INTO queue_event_recipient
                        (queue_id, event_id, registration_id, profile_id, qq_number,
                         stored_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    [
                        (
                            queue_storage_id,
                            event["event_id"],
                            contact["registration_id"],
                            contact["player_id"],
                            contact["qq_number"],
                            now,
                        )
                        for contact in recipient_contacts
                    ],
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
            (queue_storage_id, queue_storage_id, MAX_STORED_EVENTS_PER_QUEUE),
        )
        connection.execute(
            """
            DELETE FROM queue_event_recipient
            WHERE queue_id = ? AND event_id NOT IN (
                SELECT event_id FROM queue_event WHERE queue_id = ?
            )
            """,
            (queue_storage_id, queue_storage_id),
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
                if takeover_changes_queue_context(current_payload, normalized):
                    connection.execute(
                        """
                        UPDATE terminal_command
                        SET status = 'REJECTED', completed_at = ?, result_detail = ?,
                            result_source = ?
                        WHERE status = 'PENDING' AND command_type IN (?, ?)
                        """,
                        (
                            now,
                            TAKEOVER_QUEUE_CONTEXT_CHANGED_DETAIL,
                            RESULT_SOURCE_SERVER_MIGRATION,
                            QUEUE_OPERATION_COMMAND,
                            MOBILE_REGISTRATION_COMMAND,
                        ),
                    )
                connection.execute(
                    """
                    UPDATE terminal_command
                    SET device_id = ?, claimed_at = NULL, claimed_terminal = NULL,
                        claimed_instance = NULL
                    WHERE status = 'PENDING'
                    """,
                    (device_id,),
                )
        connection.commit()

    return "", 204


def applied_bot_profile_update_matches(
    connection: sqlite3.Connection,
    *,
    terminal_device_id: str,
    current: sqlite3.Row,
    incoming: dict[str, Any],
) -> bool:
    if incoming["profile_revision"] != current["profile_revision"] + 1:
        return False
    rows = connection.execute(
        """
        SELECT payload FROM terminal_command
        WHERE device_id = ? AND command_type = ? AND status = 'APPLIED'
          AND json_extract(payload, '$.profile_id') = ?
          AND json_extract(payload, '$.expected_profile_revision') = ?
        ORDER BY completed_at DESC
        LIMIT 5
        """,
        (
            terminal_device_id,
            PROFILE_UPDATE_COMMAND,
            incoming["profile_id"],
            current["profile_revision"],
        ),
    ).fetchall()
    for row in rows:
        payload = json.loads(row["payload"])
        if all(incoming.get(field) == payload.get(field) for field in BOT_PROFILE_UPDATE_FIELDS):
            return True
    return False


def upsert_player_profiles(
    connection: sqlite3.Connection,
    *,
    profile_scope_id: str,
    terminal_device_id: str,
    profiles: list[dict[str, Any]],
    received_at: int,
) -> None:
    for profile in profiles:
        current = connection.execute(
            """
            SELECT profile_revision, profile_updated_at, public_player_id,
                   nickname, gender, default_preference, qq_number,
                   usage_count, last_used_at, qq_visibility,
                   notification_enabled, notify_queue_changes,
                   notify_playing_position, notify_online_check_in,
                   notify_absence, notify_machine_status, setup_version,
                   web_account_bound, terminal_editing_allowed,
                   visited_venues_public, web_profile_revision
            FROM player_profile
            WHERE device_id = ? AND profile_id = ?
            """,
            (profile_scope_id, profile["profile_id"]),
        ).fetchone()
        requested_public_player_id = profile.get("public_player_id")
        current_public_player_id = (
            current["public_player_id"] if current is not None else None
        )
        public_player_id = current_public_player_id
        if public_player_id is None and requested_public_player_id is not None:
            requested_owner = public_player_id_owner(
                connection,
                profile_scope_id=profile_scope_id,
                public_player_id=requested_public_player_id,
            )
            # An identifier preserved in the alias table must remain historical,
            # even when it points at this same canonical profile. Otherwise a
            # stale terminal could silently promote an old identifier back to
            # the profile's current public identifier.
            if requested_owner is None:
                public_player_id = requested_public_player_id
        if public_player_id is None:
            public_player_id = allocate_player_public_id(
                connection,
                profile_scope_id=profile_scope_id,
                profile_id=profile["profile_id"],
            )
        if current is not None:
            protected_values_changed = False
            if current["web_account_bound"]:
                incoming_web_revision = profile.get("web_profile_revision", 0)
                authorized_bot_update = applied_bot_profile_update_matches(
                    connection,
                    terminal_device_id=terminal_device_id,
                    current=current,
                    incoming=profile,
                )
                protect_profile_fields = (
                    incoming_web_revision < current["web_profile_revision"]
                    or (
                        not bool(current["terminal_editing_allowed"])
                        and not authorized_bot_update
                    )
                )
                protected_values_changed = any(
                    profile.get(field) != current[field]
                    for field in (
                        "qq_number",
                        "terminal_editing_allowed",
                        "visited_venues_public",
                        "web_profile_revision",
                    )
                )
                profile = {
                    **profile,
                    "qq_number": current["qq_number"],
                    "web_account_bound": True,
                    "terminal_editing_allowed": bool(
                        current["terminal_editing_allowed"]
                    ),
                    "visited_venues_public": bool(current["visited_venues_public"]),
                    "web_profile_revision": current["web_profile_revision"],
                    "usage_count": max(profile["usage_count"], current["usage_count"]),
                    "last_used_at": max(
                        filter(
                            lambda value: value is not None,
                            (profile["last_used_at"], current["last_used_at"]),
                        ),
                        default=None,
                    ),
                }
                if protect_profile_fields:
                    protected_fields = (
                        "nickname",
                        "gender",
                        "default_preference",
                        "qq_visibility",
                        "notification_enabled",
                        "notify_queue_changes",
                        "notify_playing_position",
                        "notify_online_check_in",
                        "notify_absence",
                        "notify_machine_status",
                        "setup_version",
                    )
                    protected_values_changed = protected_values_changed or any(
                        profile.get(field) != current[field]
                        for field in protected_fields
                    )
                    profile = {
                        **profile,
                        **{field: current[field] for field in protected_fields},
                    }
            else:
                profile = {
                    **profile,
                    "web_account_bound": False,
                    "terminal_editing_allowed": True,
                    "visited_venues_public": True,
                    "web_profile_revision": 0,
                }
                invalidate_player_account_for_profile(
                    connection,
                    profile_scope_id=profile_scope_id,
                    profile_id=profile["profile_id"],
                    now=max(received_at, int(time.time())),
                )
            connection.execute(
                """
                UPDATE player_profile SET received_at = ?
                WHERE device_id = ? AND profile_id = ?
                """,
                (received_at, profile_scope_id, profile["profile_id"]),
            )
            if (
                current_public_player_id is None
                and public_player_id is not None
                and not profile["legacy_revision"]
                and profile["profile_revision"] == current["profile_revision"]
            ):
                connection.execute(
                    """
                    UPDATE player_profile
                    SET public_player_id = ?
                    WHERE device_id = ? AND profile_id = ?
                    """,
                    (public_player_id, profile_scope_id, profile["profile_id"]),
                )
            if profile["legacy_revision"]:
                if profile["updated_at"] <= current["profile_updated_at"]:
                    continue
                profile = {
                    **profile,
                    "profile_revision": current["profile_revision"] + 1,
                }
            elif profile["profile_revision"] <= current["profile_revision"]:
                operational_values_changed = (
                    profile["usage_count"] > current["usage_count"]
                    or (
                        profile["last_used_at"] is not None
                        and (
                            current["last_used_at"] is None
                            or profile["last_used_at"] > current["last_used_at"]
                        )
                    )
                )
                if not operational_values_changed:
                    continue
                profile = {
                    **profile,
                    "profile_revision": current["profile_revision"] + 1,
                }
            elif protected_values_changed:
                profile = {
                    **profile,
                    "profile_revision": max(
                        profile["profile_revision"], current["profile_revision"]
                    ) + 1,
                }
        else:
            detached_from_another_server = bool(
                profile["web_account_bound"]
                or not profile["terminal_editing_allowed"]
                or not profile["visited_venues_public"]
                or profile["web_profile_revision"] > 0
            )
            profile = {
                **profile,
                "web_account_bound": False,
                "terminal_editing_allowed": True,
                "visited_venues_public": True,
                "web_profile_revision": 0,
            }
            if detached_from_another_server:
                # A web account belongs to the server that created it. Advance
                # the profile revision so the terminal accepts this server's
                # unbound state instead of retaining a same-revision flag from
                # its previous endpoint.
                profile = {
                    **profile,
                    "profile_revision": profile["profile_revision"] + 1,
                    "updated_at": max(profile["updated_at"], received_at * 1000),
                }
            invalidate_player_account_for_profile(
                connection,
                profile_scope_id=profile_scope_id,
                profile_id=profile["profile_id"],
                now=max(received_at, int(time.time())),
            )

        qq_number = profile["qq_number"]
        if qq_number is not None:
            conflicting = connection.execute(
                """
                SELECT profile_id, profile_revision FROM player_profile
                WHERE device_id = ? AND profile_id != ? AND qq_number = ?
                """,
                (profile_scope_id, profile["profile_id"], qq_number),
            ).fetchone()
            conflicting_is_historical_alias = bool(
                conflicting is not None
                and stored_profile_is_historical_alias(
                    connection,
                    profile_scope_id=profile_scope_id,
                    source_profile_id=conflicting["profile_id"],
                    canonical_profile_id=profile["profile_id"],
                )
            )
            if (
                conflicting is not None
                and conflicting["profile_revision"] >= profile["profile_revision"]
                and not conflicting_is_historical_alias
            ):
                continue
            if conflicting_is_historical_alias:
                conflicting_public_id = connection.execute(
                    """
                    SELECT public_player_id FROM player_profile
                    WHERE device_id = ? AND profile_id = ?
                    """,
                    (profile_scope_id, conflicting["profile_id"]),
                ).fetchone()["public_player_id"]
                invalidate_player_account_for_profile(
                    connection,
                    profile_scope_id=profile_scope_id,
                    profile_id=conflicting["profile_id"],
                    now=max(received_at, int(time.time())),
                )
                connection.execute(
                    """
                    DELETE FROM player_profile
                    WHERE device_id = ? AND profile_id = ?
                    """,
                    (profile_scope_id, conflicting["profile_id"]),
                )
                preserve_player_public_id_alias(
                    connection,
                    profile_scope_id=profile_scope_id,
                    public_player_id=conflicting_public_id,
                    canonical_profile_id=profile["profile_id"],
                    created_at=received_at,
                    previous_profile_id=conflicting["profile_id"],
                )
            elif conflicting is not None:
                invalidate_player_account_for_profile(
                    connection,
                    profile_scope_id=profile_scope_id,
                    profile_id=conflicting["profile_id"],
                    now=max(received_at, int(time.time())),
                )
                connection.execute(
                    """
                    UPDATE player_profile
                    SET qq_number = NULL,
                        web_account_bound = 0,
                        terminal_editing_allowed = 1,
                        visited_venues_public = 1,
                        web_profile_revision = 0,
                        profile_revision = profile_revision + 1
                    WHERE device_id = ? AND profile_id != ? AND qq_number = ?
                    """,
                    (profile_scope_id, profile["profile_id"], qq_number),
                )

        connection.execute(
            """
            INSERT INTO player_profile
                (device_id, profile_id, nickname, gender, default_preference,
                 qq_number, usage_count, last_used_at, qq_visibility,
                 notification_enabled, notify_queue_changes,
                 notify_playing_position, notify_online_check_in,
                 notify_absence, notify_machine_status, setup_version,
                 profile_revision, created_at, profile_updated_at,
                 public_player_id, web_account_bound, terminal_editing_allowed,
                 visited_venues_public, web_profile_revision, received_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(device_id, profile_id) DO UPDATE SET
                nickname = excluded.nickname,
                gender = excluded.gender,
                default_preference = excluded.default_preference,
                qq_number = excluded.qq_number,
                usage_count = excluded.usage_count,
                last_used_at = excluded.last_used_at,
                qq_visibility = excluded.qq_visibility,
                notification_enabled = excluded.notification_enabled,
                notify_queue_changes = excluded.notify_queue_changes,
                notify_playing_position = excluded.notify_playing_position,
                notify_online_check_in = excluded.notify_online_check_in,
                notify_absence = excluded.notify_absence,
                notify_machine_status = excluded.notify_machine_status,
                setup_version = excluded.setup_version,
                profile_revision = excluded.profile_revision,
                created_at = excluded.created_at,
                profile_updated_at = excluded.profile_updated_at,
                public_player_id = COALESCE(
                    player_profile.public_player_id,
                    excluded.public_player_id
                ),
                web_account_bound = excluded.web_account_bound,
                terminal_editing_allowed = excluded.terminal_editing_allowed,
                visited_venues_public = excluded.visited_venues_public,
                web_profile_revision = excluded.web_profile_revision,
                received_at = excluded.received_at
            """,
            (
                profile_scope_id,
                profile["profile_id"],
                profile["nickname"],
                profile["gender"],
                profile["default_preference"],
                qq_number,
                profile["usage_count"],
                profile["last_used_at"],
                profile["qq_visibility"],
                int(profile["notification_enabled"]),
                int(profile["notify_queue_changes"]),
                int(profile["notify_playing_position"]),
                int(profile["notify_online_check_in"]),
                int(profile["notify_absence"]),
                int(profile["notify_machine_status"]),
                profile["setup_version"],
                profile["profile_revision"],
                profile["created_at"],
                profile["updated_at"],
                public_player_id,
                int(profile["web_account_bound"]),
                int(profile["terminal_editing_allowed"]),
                int(profile["visited_venues_public"]),
                profile["web_profile_revision"],
                received_at,
            ),
        )


def replace_current_player_profile_ids(
    connection: sqlite3.Connection,
    *,
    profile_scope_id: str,
    profile_ids: set[str],
) -> None:
    connection.execute(
        "DELETE FROM current_player_profile WHERE profile_scope_id = ?",
        (profile_scope_id,),
    )
    connection.executemany(
        """
        INSERT INTO current_player_profile(profile_scope_id, profile_id)
        VALUES (?, ?)
        """,
        [(profile_scope_id, profile_id) for profile_id in sorted(profile_ids)],
    )


def read_current_player_profile_ids(
    connection: sqlite3.Connection, profile_scope_id: str
) -> set[str]:
    return {
        row["profile_id"]
        for row in connection.execute(
            """
            SELECT profile_id FROM current_player_profile
            WHERE profile_scope_id = ?
            """,
            (profile_scope_id,),
        ).fetchall()
    }


def read_active_venue(connection: sqlite3.Connection) -> sqlite3.Row:
    venue = connection.execute(
        """
        SELECT venue_id, venue_code, profile_scope_id, display_name,
               business_hours_json,
               created_at, updated_at
        FROM venue WHERE profile_scope_id = ?
        """,
        (current_app.config["PROFILE_SCOPE_ID"],),
    ).fetchone()
    if venue is None:
        raise RuntimeError("当前服务实例缺少机厅身份")
    return venue


def read_stored_venue_business_hours(venue: sqlite3.Row) -> dict[str, Any] | None:
    source = venue["business_hours_json"]
    if not source:
        return None
    try:
        return normalize_venue_business_hours(json.loads(source))
    except (TypeError, ValueError, json.JSONDecodeError, ValidationError):
        # A malformed legacy value must never make the venue or queue endpoint
        # unavailable. Treat it as unconfigured and let the next terminal save
        # replace it with a validated schedule.
        return None


def read_or_update_venue_settings():
    source = request.get_json(silent=True) if request.method == "PUT" else None
    business_hours = None
    if request.method == "PUT":
        if not isinstance(source, dict) or set(source) != {"business_hours"}:
            return jsonify(
                {"ok": False, "error": "请求内容必须只包含 business_hours"}
            ), 400
        try:
            business_hours = normalize_venue_business_hours(source["business_hours"])
        except ValidationError as error:
            return jsonify(validation_error_payload(error)), 400

    now = int(time.time())
    with open_database() as connection:
        connection.execute("BEGIN IMMEDIATE")
        venue = read_active_venue(connection)
        if request.method == "PUT":
            connection.execute(
                """
                UPDATE venue
                SET business_hours_json = ?, updated_at = ?
                WHERE venue_id = ?
                """,
                (
                    json.dumps(
                        business_hours,
                        ensure_ascii=False,
                        separators=(",", ":"),
                    ),
                    now,
                    venue["venue_id"],
                ),
            )
            venue = read_active_venue(connection)
        connection.commit()
    return jsonify(
        {
            "ok": True,
            "venue": serialize_venue(venue),
            "business_hours": read_stored_venue_business_hours(venue),
        }
    )


def serialize_venue(venue: sqlite3.Row) -> dict[str, Any]:
    return {
        "id": venue["venue_id"],
        "code": venue["venue_code"],
        "name": venue["display_name"],
        "registered": True,
    }


def upsert_registered_terminal(
    connection: sqlite3.Connection,
    *,
    terminal_id: str,
    venue_id: str,
    display_name: str | None,
    seen_at: int,
) -> None:
    normalized_name = display_name.strip() if isinstance(display_name, str) else None
    if normalized_name == "":
        normalized_name = None
    existing = connection.execute(
        "SELECT venue_id FROM registered_terminal WHERE terminal_id = ?",
        (terminal_id,),
    ).fetchone()
    if existing is not None and existing["venue_id"] != venue_id:
        raise ValidationError(
            "此终端已经绑定到另一机厅，不能自动更改归属。",
            code="VENUE_MISMATCH",
        )
    connection.execute(
        """
        INSERT INTO registered_terminal
            (terminal_id, venue_id, display_name, role, created_at, updated_at,
             last_seen_at)
        VALUES (?, ?, ?, 'AUTHORITATIVE', ?, ?, ?)
        ON CONFLICT(terminal_id) DO UPDATE SET
            display_name = COALESCE(excluded.display_name, registered_terminal.display_name),
            updated_at = excluded.updated_at,
            last_seen_at = excluded.last_seen_at
        """,
        (
            terminal_id,
            venue_id,
            normalized_name,
            seen_at,
            seen_at,
            seen_at,
        ),
    )


def read_or_update_terminal_installation():
    terminal_id = request.headers.get("X-Device-ID", "").strip()
    if not terminal_id or len(terminal_id) > 128:
        return jsonify({"ok": False, "error": "终端编号无效"}), 400
    source = request.get_json(silent=True) if request.method == "POST" else None
    if request.method == "POST":
        if not isinstance(source, dict):
            return jsonify({"ok": False, "error": "请求内容必须是 JSON 对象"}), 400
        if set(source) - {"venue_name", "terminal_name", "venue_id"}:
            return jsonify({"ok": False, "error": "请求包含不支持的安装信息字段"}), 400
        venue_name = source.get("venue_name")
        terminal_name = source.get("terminal_name")
        submitted_venue_id = source.get("venue_id")
        if venue_name is not None and (
            not isinstance(venue_name, str)
            or not venue_name.strip()
            or len(venue_name.strip()) > MAX_VENUE_NAME_CHARACTERS
        ):
            return jsonify({"ok": False, "error": "机厅名称必须为 1 至 40 个字符"}), 400
        if terminal_name is not None and (
            not isinstance(terminal_name, str)
            or not terminal_name.strip()
            or len(terminal_name.strip()) > MAX_TERMINAL_NAME_CHARACTERS
        ):
            return jsonify({"ok": False, "error": "终端名称必须为 1 至 24 个字符"}), 400
        if submitted_venue_id is not None:
            try:
                submitted_venue_id = str(UUID(submitted_venue_id))
            except (TypeError, ValueError):
                return jsonify({"ok": False, "error": "机厅 ID 无效"}), 400

    now = int(time.time())
    with open_database() as connection:
        connection.execute("BEGIN IMMEDIATE")
        venue = read_active_venue(connection)
        if request.method == "POST" and submitted_venue_id is not None and (
            submitted_venue_id != venue["venue_id"]
        ):
            connection.rollback()
            return jsonify(
                {
                    "ok": False,
                    "code": "VENUE_MISMATCH",
                    "error": "当前服务器属于另一机厅，请核对服务器地址后再继续。",
                }
            ), 409
        if request.method == "POST" and venue_name is not None:
            connection.execute(
                "UPDATE venue SET display_name = ?, updated_at = ? WHERE venue_id = ?",
                (venue_name.strip(), now, venue["venue_id"]),
            )
            venue = read_active_venue(connection)
        try:
            upsert_registered_terminal(
                connection,
                terminal_id=terminal_id,
                venue_id=venue["venue_id"],
                display_name=terminal_name if request.method == "POST" else None,
                seen_at=now,
            )
        except ValidationError as error:
            connection.rollback()
            return jsonify(validation_error_payload(error)), 409
        terminal = connection.execute(
            """
            SELECT terminal_id, display_name, role, created_at, updated_at,
                   last_seen_at
            FROM registered_terminal WHERE terminal_id = ?
            """,
            (terminal_id,),
        ).fetchone()
        connection.commit()
    return jsonify(
        {
            "ok": True,
            "venue": serialize_venue(venue),
            "terminal": {
                "id": terminal["terminal_id"],
                "name": terminal["display_name"],
                "role": terminal["role"],
                "last_seen_at": optional_epoch_millis(terminal["last_seen_at"]),
            },
            "capabilities": {
                "schema_version": PUBLIC_SCHEMA_VERSION,
                "multiple_venues": False,
                "multiple_terminals": False,
            },
        }
    )


def stored_profile_is_historical_alias(
    connection: sqlite3.Connection,
    *,
    profile_scope_id: str,
    source_profile_id: str,
    canonical_profile_id: str,
) -> bool:
    current_profile_ids = read_current_player_profile_ids(
        connection, profile_scope_id
    )
    if (
        canonical_profile_id not in current_profile_ids
        or source_profile_id in current_profile_ids
    ):
        return False
    profiles = connection.execute(
        """
        SELECT profile_id, nickname, gender, default_preference, qq_number,
               setup_version
        FROM player_profile
        WHERE device_id = ? AND profile_id IN (?, ?)
        """,
        (profile_scope_id, source_profile_id, canonical_profile_id),
    ).fetchall()
    profiles_by_id = {profile["profile_id"]: profile for profile in profiles}
    source = profiles_by_id.get(source_profile_id)
    canonical = profiles_by_id.get(canonical_profile_id)
    return bool(
        source is not None
        and canonical is not None
        and are_legacy_profile_aliases(source, canonical)
    )


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


def profile_scope_id_for_snapshot(
    snapshot: dict[str, Any] | None,
    device_id: str,
) -> str:
    base_scope = current_app.config["PROFILE_SCOPE_ID"]
    if snapshot and snapshot.get("test_data", False):
        return f"{base_scope}:test:{device_id}"
    return base_scope


def queue_storage_id_for_snapshot(
    queue_id: str,
    snapshot: dict[str, Any] | None,
    device_id: str,
) -> str:
    if snapshot and snapshot.get("test_data", False):
        return f"test:{device_id}:{queue_id}"
    return queue_id


def snapshot_storage_context(
    snapshot_row: sqlite3.Row,
    snapshot: dict[str, Any] | None = None,
) -> tuple[str, str]:
    resolved_snapshot = snapshot or json.loads(snapshot_row["payload"])
    return (
        queue_storage_id_for_snapshot(
            snapshot_row["queue_id"], resolved_snapshot, snapshot_row["device_id"]
        ),
        profile_scope_id_for_snapshot(resolved_snapshot, snapshot_row["device_id"]),
    )


def active_profile_scope_id(connection: sqlite3.Connection) -> str:
    snapshot_row = connection.execute(
        "SELECT queue_id, device_id, payload FROM queue_snapshot WHERE id = 1"
    ).fetchone()
    if snapshot_row is None:
        return current_app.config["PROFILE_SCOPE_ID"]
    return snapshot_storage_context(snapshot_row)[1]


def read_snapshot():
    with open_database() as connection:
        row = connection.execute(
            "SELECT payload, device_id, venue_id, received_at FROM queue_snapshot WHERE id = 1"
        ).fetchone()
        venue = read_active_venue(connection)
    if row is None:
        return jsonify({"ok": False, "error": "排队终端暂未同步"}), 404

    payload = compact_public_middle_dots(json.loads(row["payload"]))
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
    # Old schema snapshots did not carry venue information. Public readers
    # still receive the active venue without claiming that the old terminal
    # completed the new installation flow.
    payload["venue"] = serialize_venue(venue)
    payload["capabilities"] = public_capabilities(payload, terminal_online)
    return jsonify(payload)


def read_queue_versions():
    with open_database() as connection:
        snapshot_row = connection.execute(
            "SELECT payload, received_at FROM queue_snapshot WHERE id = 1"
        ).fetchone()
        identity = connection.execute(
            """
            SELECT bot_version, bot_version_updated_at,
                   website_version, website_version_updated_at
            FROM service_identity WHERE profile_scope_id = ?
            """,
            (current_app.config["PROFILE_SCOPE_ID"],),
        ).fetchone()

    terminal_version = None
    terminal_updated_at = None
    if snapshot_row is not None:
        payload = json.loads(snapshot_row["payload"])
        terminal = payload.get("terminal")
        if isinstance(terminal, dict):
            terminal_version = terminal.get("app_version")
        terminal_updated_at = snapshot_row["received_at"]

    return jsonify(
        {
            "checked_at": int(time.time()) * 1000,
            "components": {
                "terminal": build_version_component(
                    "现场终端",
                    terminal_version,
                    current_app.config.get("LATEST_TERMINAL_VERSION"),
                    terminal_updated_at,
                ),
                "website": build_version_component(
                    "队列网站",
                    identity["website_version"] if identity is not None else None,
                    current_app.config.get("LATEST_WEBSITE_VERSION"),
                    identity["website_version_updated_at"]
                    if identity is not None
                    else None,
                ),
                "bot": build_version_component(
                    "QQ Bot",
                    identity["bot_version"] if identity is not None else None,
                    current_app.config.get("LATEST_BOT_VERSION"),
                    identity["bot_version_updated_at"] if identity is not None else None,
                ),
            },
        }
    )


def build_version_component(
    name: str,
    current_version: Any,
    latest_version: Any,
    updated_at: Any,
) -> dict[str, Any]:
    normalized_current = normalize_semantic_version(current_version)
    normalized_latest = normalize_semantic_version(latest_version)
    if normalized_current is None or normalized_latest is None:
        status = "UNKNOWN"
    else:
        comparison = compare_semantic_versions(
            normalized_current[1], normalized_latest[1]
        )
        status = (
            "LATEST"
            if comparison == 0
            else "UPDATE_AVAILABLE"
            if comparison < 0
            else "AHEAD"
        )
    return {
        "name": name,
        "current_version": normalized_current[0] if normalized_current else None,
        "latest_version": normalized_latest[0] if normalized_latest else None,
        "status": status,
        "updated_at": optional_epoch_millis(updated_at),
    }


def normalize_semantic_version(
    source: Any,
) -> tuple[str, tuple[int, int, int, tuple[str, ...] | None]] | None:
    if not isinstance(source, str):
        return None
    value = source.strip()
    if not value or len(value) > 32:
        return None
    match = SEMANTIC_VERSION_PATTERN.fullmatch(value)
    if match is None:
        return None
    prerelease_text = match.group(4)
    prerelease = tuple(prerelease_text.split(".")) if prerelease_text else None
    if prerelease and any(
        identifier.isdigit()
        and len(identifier) > 1
        and identifier.startswith("0")
        for identifier in prerelease
    ):
        return None
    canonical = value[1:] if value.startswith("v") else value
    return (
        canonical,
        (int(match.group(1)), int(match.group(2)), int(match.group(3)), prerelease),
    )


def compare_semantic_versions(
    left: tuple[int, int, int, tuple[str, ...] | None],
    right: tuple[int, int, int, tuple[str, ...] | None],
) -> int:
    if left[:3] != right[:3]:
        return -1 if left[:3] < right[:3] else 1
    left_prerelease = left[3]
    right_prerelease = right[3]
    if left_prerelease == right_prerelease:
        return 0
    if left_prerelease is None:
        return 1
    if right_prerelease is None:
        return -1
    for left_identifier, right_identifier in zip(left_prerelease, right_prerelease):
        if left_identifier == right_identifier:
            continue
        left_numeric = left_identifier.isdigit()
        right_numeric = right_identifier.isdigit()
        if left_numeric and right_numeric:
            return -1 if int(left_identifier) < int(right_identifier) else 1
        if left_numeric != right_numeric:
            return -1 if left_numeric else 1
        return -1 if left_identifier < right_identifier else 1
    return -1 if len(left_prerelease) < len(right_prerelease) else 1


def optional_epoch_millis(value: Any) -> int | None:
    return value * 1000 if isinstance(value, int) and value >= 0 else None


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
        current = connection.execute(
            "SELECT queue_id, device_id, payload FROM queue_snapshot WHERE id = 1"
        ).fetchone()
        if not queue_id:
            if current is None:
                return jsonify({"ok": False, "error": "排队终端暂未同步"}), 404
            queue_id = current["queue_id"]
        else:
            try:
                queue_id = str(UUID(queue_id))
            except ValueError:
                return jsonify({"ok": False, "error": "queue_id 必须是 UUID"}), 400

        queue_storage_id = (
            snapshot_storage_context(current)[0]
            if current is not None and current["queue_id"] == queue_id
            else queue_id
        )

        query = """
            SELECT id, event_id, occurred_at, machine_id, machine_stable_id,
                   machine_name, event_type,
                   title, detail, operation_source, notification_categories,
                   registration_ids
            FROM queue_event
            WHERE queue_id = ?
        """
        parameters: list[Any] = [queue_storage_id]
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
            "machine_stable_id": row["machine_stable_id"],
            "machine_name": compact_middle_dots(row["machine_name"]),
            "type": row["event_type"],
            "title": compact_middle_dots(row["title"]),
            "detail": compact_middle_dots(row["detail"]),
            "operation_source": row["operation_source"],
            "notification_categories": stored_event_notification_categories(row),
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
            "SELECT queue_id, revision, payload, device_id, received_at "
            "FROM queue_snapshot WHERE id = 1"
        ).fetchone()
        if snapshot_row is None:
            return jsonify({"ok": False, "error": "排队终端暂未同步"}), 404
        snapshot = json.loads(snapshot_row["payload"])
        queue_storage_id, profile_scope_id = snapshot_storage_context(
            snapshot_row, snapshot
        )
        query = """
            SELECT contact.registration_id, contact.player_id,
                   CASE WHEN profile.profile_id IS NOT NULL
                        THEN profile.qq_number ELSE contact.qq_number END AS qq_number,
                   contact.updated_at
            FROM queue_private_contact AS contact
            LEFT JOIN player_profile AS profile
              ON profile.device_id = ? AND profile.profile_id = contact.player_id
            WHERE contact.queue_id = ?
        """
        parameters: list[Any] = [profile_scope_id, queue_storage_id]
        if qq_number:
            query += " AND CASE WHEN profile.profile_id IS NOT NULL"
            query += " THEN profile.qq_number ELSE contact.qq_number END = ?"
            parameters.append(qq_number)
        query += " ORDER BY contact.registration_id"
        contacts = connection.execute(query, parameters).fetchall()

    last_seen_seconds = max(0, int(time.time()) - snapshot_row["received_at"])
    terminal_online = last_seen_seconds <= current_app.config["ONLINE_TIMEOUT_SECONDS"]
    bot_remote_enabled = terminal_online and snapshot.get("onebot_sync_enabled", True)
    online_registration = bool(
        bot_remote_enabled
        and snapshot.get("registration_open", True)
        and online_registration_allowed(snapshot)
    )
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
                "machine_stable_id": context["machine_stable_id"],
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
                ]
                + (
                    [context["common_play_preview"]["display_id"]]
                    if context.get("common_play_preview") is not None
                    else []
                ),
                "common_play_preview_display_id": (
                    context["common_play_preview"]["display_id"]
                    if context.get("common_play_preview") is not None
                    else None
                ),
                "preference": context["registration"]["preference"],
                "fixed_pair": context["registration"]["fixed_pair"],
                "fixed_pair_id": context["registration"]["fixed_pair_id"],
                "registration_type": context["registration"]["registration_type"],
                "created_at": context["registration"]["created_at"],
                "online_check_in_started_at": context["registration"][
                    "online_check_in_started_at"
                ],
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
            "machine_configuration_revision": snapshot.get(
                "machine_configuration_revision", 1
            ),
            "revision": snapshot_row["revision"],
            "received_at": snapshot_row["received_at"] * 1000,
            "registration_open": snapshot.get("registration_open", True),
            "test_data": bool(snapshot.get("test_data", False)),
            "business_hours": snapshot.get("business_hours")
            or normalize_public_business_hours(None),
            "queue_rules": snapshot.get("queue_rules")
            or normalize_public_queue_rules(None),
            "terminal": {
                "online": terminal_online,
                "last_seen_seconds": last_seen_seconds,
            },
            "players": players,
            "capabilities": {
                "read_players": True,
                # Existing-registration operations remain available while the
                # terminal is online and QQ Bot linkage is enabled.  The
                # registration-open switch only controls creating a new online
                # registration; conflating the two made Bot menus advertise
                # actions that the server would reject (and hide valid leave /
                # absence operations when registration was closed).
                "remote_actions": bot_remote_enabled,
                "online_registration": online_registration,
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
            "SELECT queue_id, revision, payload, device_id "
            "FROM queue_snapshot WHERE id = 1"
        ).fetchone()
        if snapshot_row is None:
            connection.commit()
            return jsonify({"ok": False, "error": "排队终端暂未同步"}), 404
        snapshot = json.loads(snapshot_row["payload"])
        queue_storage_id, _ = snapshot_storage_context(snapshot_row, snapshot)
        recipient_rows = connection.execute(
            """
            SELECT event_id, registration_id, profile_id, qq_number
            FROM queue_event_recipient
            WHERE queue_id = ?
            """,
            (queue_storage_id,),
        ).fetchall()
        event_rows = connection.execute(
            """
            SELECT id, event_id, occurred_at, machine_id, machine_stable_id,
                   machine_name, event_type,
                   title, detail, operation_source, notification_categories,
                   registration_ids
            FROM queue_event
            WHERE queue_id = ? AND id > ?
            ORDER BY id ASC
            LIMIT ?
            """,
            (queue_storage_id, after, MAX_STORED_EVENTS_PER_QUEUE),
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
                "machine_stable_id": row["machine_stable_id"],
                "machine_name": compact_middle_dots(row["machine_name"]),
                "type": row["event_type"],
                "title": compact_middle_dots(row["title"]),
                "detail": compact_middle_dots(row["detail"]),
                "operation_source": row["operation_source"],
                "notification_categories": stored_event_notification_categories(row),
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
            "notification_scope_id": queue_storage_id,
            "revision": snapshot_row["revision"],
            "test_data": bool(snapshot.get("test_data", False)),
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


def update_bot_identity():
    source = request.get_json(silent=True)
    allowed_fields = {"bot_qq", "bot_version", "website_version"}
    if (
        not isinstance(source, dict)
        or "bot_qq" not in source
        or not set(source) <= allowed_fields
    ):
        return jsonify(
            {
                "ok": False,
                "error": "请求内容必须包含 bot_qq，且不能包含未知字段",
            }
        ), 400
    try:
        bot_qq = read_qq_number(source, "bot_qq")
        bot_version = read_optional_semantic_version(source, "bot_version")
        website_version = read_optional_semantic_version(source, "website_version")
    except ValidationError as error:
        return jsonify({"ok": False, "error": str(error)}), 400
    now = int(time.time())
    with open_database() as connection:
        connection.execute(
            """
            INSERT INTO service_identity (
                profile_scope_id, bot_qq, bot_version, bot_version_updated_at,
                website_version, website_version_updated_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(profile_scope_id) DO UPDATE SET
                bot_qq = excluded.bot_qq,
                bot_version = excluded.bot_version,
                bot_version_updated_at = excluded.bot_version_updated_at,
                website_version = CASE
                    WHEN excluded.website_version IS NOT NULL THEN excluded.website_version
                    ELSE service_identity.website_version
                END,
                website_version_updated_at = CASE
                    WHEN excluded.website_version IS NOT NULL THEN excluded.website_version_updated_at
                    ELSE service_identity.website_version_updated_at
                END,
                updated_at = excluded.updated_at
            """,
            (
                current_app.config["PROFILE_SCOPE_ID"],
                bot_qq,
                bot_version,
                now if bot_version is not None else None,
                website_version,
                now if website_version is not None else None,
                now,
            ),
        )
        identity = connection.execute(
            """
            SELECT bot_version, bot_version_updated_at,
                   website_version, website_version_updated_at
            FROM service_identity WHERE profile_scope_id = ?
            """,
            (current_app.config["PROFILE_SCOPE_ID"],),
        ).fetchone()
        connection.commit()
    return jsonify(
        {
            "ok": True,
            "bot_qq": bot_qq,
            "bot_version": identity["bot_version"],
            "bot_version_updated_at": optional_epoch_millis(
                identity["bot_version_updated_at"]
            ),
            "website_version": identity["website_version"],
            "website_version_updated_at": optional_epoch_millis(
                identity["website_version_updated_at"]
            ),
            "updated_at": now * 1000,
        }
    )


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
            """
            SELECT device_id, queue_id, revision, payload, received_at
            FROM queue_snapshot WHERE id = 1
            """
        ).fetchone()
        if snapshot_row is None:
            return jsonify({"ok": False, "error": "排队终端暂未同步"}), 404
        profile_scope_id = snapshot_storage_context(snapshot_row)[1]
        rows = connection.execute(
            """
            SELECT profile_id, nickname, gender, default_preference, qq_number,
                   usage_count, last_used_at, qq_visibility,
                   notification_enabled, notify_queue_changes,
                   notify_playing_position, notify_online_check_in,
                   notify_absence, notify_machine_status, setup_version,
                   profile_revision, created_at, profile_updated_at,
                   public_player_id, web_account_bound,
                   terminal_editing_allowed, visited_venues_public,
                   web_profile_revision, received_at
            FROM player_profile
            WHERE device_id = ?
            ORDER BY nickname, profile_id
            """,
            (profile_scope_id,),
        ).fetchall()
        current_profile_ids = read_current_player_profile_ids(
            connection, profile_scope_id
        )
        profile_aliases = find_mobile_profile_aliases(
            rows, current_profile_ids=current_profile_ids
        )
        public_player_id_aliases = read_player_public_id_aliases(
            connection, profile_scope_id=profile_scope_id
        )
        canonical_rows = [
            row for row in rows if row["profile_id"] not in profile_aliases
        ]
        if qq_number:
            matching_profile_ids = {
                profile_aliases.get(row["profile_id"], row["profile_id"])
                for row in rows
                if row["qq_number"] == qq_number
            }
            canonical_rows = [
                row
                for row in canonical_rows
                if row["profile_id"] in matching_profile_ids
            ]
        identity = connection.execute(
            """
            SELECT bot_qq FROM service_identity WHERE profile_scope_id = ?
            """,
            (current_app.config["PROFILE_SCOPE_ID"],),
        ).fetchone()

    return jsonify(
        {
            "queue_id": snapshot_row["queue_id"],
            "revision": snapshot_row["revision"],
            "bot_qq": identity["bot_qq"] if identity is not None else None,
            "profile_aliases": profile_aliases,
            "profiles": [
                {
                    "profile_id": row["profile_id"],
                    "public_player_id": row["public_player_id"],
                    "public_player_id_aliases": public_player_id_aliases.get(
                        row["profile_id"], []
                    ),
                    "nickname": row["nickname"],
                    "gender": row["gender"],
                    "default_preference": row["default_preference"],
                    "qq_number": row["qq_number"],
                    "usage_count": row["usage_count"],
                    "last_used_at": row["last_used_at"],
                    "qq_visibility": row["qq_visibility"],
                    "notification_enabled": bool(row["notification_enabled"]),
                    "notify_queue_changes": bool(row["notify_queue_changes"]),
                    "notify_playing_position": bool(row["notify_playing_position"]),
                    "notify_online_check_in": bool(row["notify_online_check_in"]),
                    "notify_absence": bool(row["notify_absence"]),
                    "notify_machine_status": bool(row["notify_machine_status"]),
                    "web_account_bound": bool(row["web_account_bound"]),
                    "terminal_editing_allowed": bool(row["terminal_editing_allowed"]),
                    "visited_venues_public": bool(row["visited_venues_public"]),
                    "web_profile_revision": row["web_profile_revision"],
                    "setup_version": row["setup_version"],
                    "profile_revision": row["profile_revision"],
                    "created_at": row["created_at"],
                    "updated_at": row["profile_updated_at"],
                    "synced_at": row["received_at"] * 1000,
                }
                for row in canonical_rows
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
            "SELECT queue_id, device_id, payload, received_at "
            "FROM queue_snapshot WHERE id = 1"
        ).fetchone()
        if snapshot_row is None:
            return jsonify({"ok": False, "error": "排队终端暂未同步"}), 404
        snapshot = json.loads(snapshot_row["payload"])
        if not snapshot_is_online(snapshot_row):
            return jsonify(
                {"ok": False, "error": "现场终端暂时离线，暂不能使用线上登记"}
            ), 503
        if not snapshot.get("website_remote_enabled", False):
            return jsonify(
                {"ok": False, "error": "现场终端已关闭与服务端同步，暂不能使用线上登记"}
            ), 503
        if snapshot_in_closing_grace(snapshot):
            return jsonify(
                {"ok": False, "error": "闭店收尾期间不再接收新的排队登记"}
            ), 409
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
        if profile["qq_number"] != qq_number:
            return profile_qq_sync_pending_response()
        queue_storage_id, _ = snapshot_storage_context(snapshot_row, snapshot)
        registrations = find_qq_registration_contexts(
            connection, queue_storage_id, snapshot, qq_number
        )

    terminal_online = snapshot_is_online(snapshot_row)
    return jsonify(
        {
            "queue_id": snapshot_row["queue_id"],
            "machine_configuration_revision": snapshot.get(
                "machine_configuration_revision", 1
            ),
            "profile": serialize_player_profile(profile),
            "existing_registration": serialize_registration_context(registrations[0])
            if registrations
            else None,
            "registration_open": snapshot.get("registration_open", True),
            "business_hours": snapshot.get("business_hours")
            or normalize_public_business_hours(None),
            "terminal": {"online": terminal_online},
            "machine_groups": snapshot.get("machine_groups")
            or [{"id": DEFAULT_MACHINE_GROUP_ID, "name": "分组 1"}],
            "default_machine_group_id": snapshot.get(
                "default_machine_group_id", DEFAULT_MACHINE_GROUP_ID
            ),
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
    allowed_fields = {
        "request_id",
        "qq",
        "machine_id",
        "preference",
        "expected_queue_id",
        "expected_machine_configuration_revision",
        "expected_machine_stable_id",
    }
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
        "expected_queue_id",
        "expected_registration_id",
        "expected_machine_id",
        "expected_position",
        "expected_fixed_pair_id",
        "expected_absence_status",
        "expected_temporary_away_skipped_turns",
        "expected_pending_check_in",
        "expected_machine_configuration_revision",
        "expected_machine_stable_id",
        "expected_target_machine_stable_id",
    }
    if set(source) - allowed_fields:
        return jsonify({"ok": False, "error": "请求包含不支持的排队操作字段"}), 400
    return create_queue_operation_command(source, "QQ_BOT")


def recently_applied_join_waiting_for_snapshot(
    connection: sqlite3.Connection,
    *,
    device_id: str,
    queue_storage_id: str,
    actor_qq: str,
    profile_id: str,
    snapshot_received_at: int,
    now: int,
) -> bool:
    row = connection.execute(
        """
        SELECT 1 FROM terminal_command
        WHERE status = 'APPLIED' AND device_id = ?
          AND completed_at IS NOT NULL AND completed_at > ?
          AND completed_at >= ?
          AND (
              (command_type = ? AND json_extract(payload, '$.operation') = 'JOIN_QUEUE')
              OR command_type = ?
          )
          AND COALESCE(
              json_extract(payload, '$._queue_storage_id'),
              CASE WHEN command_type = ?
                   THEN json_extract(payload, '$.queue_id') END
          ) = ?
          AND (
              json_extract(payload, '$.actor_qq') = ?
              OR json_extract(payload, '$.profile_id') = ?
              OR json_extract(payload, '$.profile.profile_id') = ?
          )
        LIMIT 1
        """,
        (
            device_id,
            now - APPLIED_JOIN_SYNC_GUARD_SECONDS,
            snapshot_received_at,
            QUEUE_OPERATION_COMMAND,
            MOBILE_REGISTRATION_COMMAND,
            QUEUE_OPERATION_COMMAND,
            queue_storage_id,
            actor_qq,
            profile_id,
            profile_id,
        ),
    ).fetchone()
    return row is not None


def player_join_syncing_response():
    return jsonify(
        {
            "ok": False,
            "code": "PLAYER_OPERATION_SYNCING",
            "error": "上一份登记已由终端保存，正在同步最新队列，请稍后刷新。",
        }
    ), 409


def create_queue_operation_command(
    source: dict[str, Any],
    operation_source: str,
    *,
    actor_profile_id: str | None = None,
):
    try:
        command_id = read_uuid(source, "request_id")
        actor_qq = read_qq_number(source, "actor_qq")
        operation = read_choice(source, "operation", QUEUE_OPERATIONS)
        machine_id = read_optional_machine_id(source, "machine_id")
        target_machine_id = read_optional_machine_id(source, "target_machine_id")
        preference = read_optional_choice(source, "preference", PREFERENCES)
        expected_field_names = {
            "expected_queue_id",
            "expected_registration_id",
            "expected_machine_id",
            "expected_position",
            "expected_fixed_pair_id",
            "expected_absence_status",
            "expected_temporary_away_skipped_turns",
            "expected_pending_check_in",
        }
        expected_configuration_revision_field = (
            "expected_machine_configuration_revision"
        )
        expected_machine_stable_id = (
            read_machine_internal_id(source, "expected_machine_stable_id")
            if "expected_machine_stable_id" in source
            else None
        )
        expected_target_machine_stable_id = (
            read_machine_internal_id(source, "expected_target_machine_stable_id")
            if "expected_target_machine_stable_id" in source
            else None
        )
        expected_join_field_names = {
            "expected_queue_id",
            expected_configuration_revision_field,
        }
        expected_context = None
        expected_join_context = None
        supplied_expected_fields: set[str] = set()
        if operation == "JOIN_QUEUE":
            supplied_join_fields = expected_join_field_names.intersection(source)
            registration_detail_fields = expected_field_names - {"expected_queue_id"}
            if registration_detail_fields.intersection(source):
                raise ValidationError("加入排队不接受登记确认状态")
            if supplied_join_fields and supplied_join_fields != expected_join_field_names:
                raise ValidationError("加入排队确认字段不完整")
            if supplied_join_fields:
                expected_join_context = {
                    "queue_id": read_uuid(source, "expected_queue_id"),
                    "machine_configuration_revision": read_integer(
                        source,
                        "expected_machine_configuration_revision",
                        minimum=1,
                        maximum=2**63 - 1,
                    ),
                    "machine_stable_id": expected_machine_stable_id,
                }
            elif expected_machine_stable_id is not None:
                raise ValidationError("加入排队确认字段不完整")
            if expected_target_machine_stable_id is not None:
                raise ValidationError("加入排队不接受目标机台确认字段")
        else:
            supplied_expected_fields = expected_field_names.intersection(source)
            if (
                supplied_expected_fields
                and supplied_expected_fields != expected_field_names
            ):
                raise ValidationError("确认状态字段不完整")
            if (
                expected_configuration_revision_field in source
                and supplied_expected_fields != expected_field_names
            ):
                raise ValidationError("确认状态字段不完整")
            if (
                (expected_machine_stable_id is not None or
                 expected_target_machine_stable_id is not None)
                and supplied_expected_fields != expected_field_names
            ):
                raise ValidationError("确认状态字段不完整")
        if operation != "JOIN_QUEUE" and supplied_expected_fields:
            expected_context = {
                "queue_id": read_uuid(source, "expected_queue_id"),
                "registration_id": read_public_id(source, "expected_registration_id"),
                "machine_id": read_optional_machine_id(source, "expected_machine_id"),
                "position": read_choice(source, "expected_position", {"PLAYING", "WAITING"}),
                "fixed_pair_id": read_optional_public_id(
                    source, "expected_fixed_pair_id"
                ),
                "absence_status": read_choice(
                    source,
                    "expected_absence_status",
                    {"NONE", "DEFER_ONE_ROUND", "TEMPORARILY_AWAY"},
                ),
                "temporary_away_skipped_turns": read_integer(
                    source,
                    "expected_temporary_away_skipped_turns",
                    minimum=0,
                    maximum=3,
                ),
                "pending_check_in": read_boolean(source, "expected_pending_check_in"),
                "machine_configuration_revision": read_integer(
                    source,
                    expected_configuration_revision_field,
                    minimum=1,
                    maximum=2**63 - 1,
                )
                if expected_configuration_revision_field in source
                else None,
                "machine_stable_id": expected_machine_stable_id,
                "target_machine_stable_id": expected_target_machine_stable_id,
            }
            if expected_context["machine_id"] is None:
                raise ValidationError("expected_machine_id 不能为空")
            if (
                expected_target_machine_stable_id is not None
                and operation != "TRANSFER_MACHINE"
            ):
                raise ValidationError("只有切换机台可以提交目标机台确认字段")
    except ValidationError as error:
        return jsonify({"ok": False, "error": str(error)}), 400

    request_identity = {
        "operation_source": operation_source,
        "actor_qq": actor_qq,
        "operation": operation,
        "machine_id": machine_id,
        "target_machine_id": target_machine_id,
        "preference": preference,
        "expected_context": expected_context,
        "expected_join_context": expected_join_context,
    }
    if actor_profile_id is not None:
        request_identity["actor_profile_id"] = actor_profile_id

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
        queue_storage_id, _ = snapshot_storage_context(snapshot_row, snapshot)
        if expected_join_context is not None and (
            expected_join_context["queue_id"] != snapshot_row["queue_id"]
            or expected_join_context["machine_configuration_revision"]
            != snapshot.get("machine_configuration_revision", 1)
        ):
            return jsonify(
                {
                    "ok": False,
                    "code": "QUEUE_CONTEXT_CHANGED",
                    "error": "现场队列或机台配置已更新，请重新查询玩家资料后再提交。",
                }
            ), 409
        if (
            expected_join_context is not None
            and expected_join_context["machine_stable_id"] is not None
        ):
            selected_machine = snapshot.get("machines", {}).get(machine_id or "")
            selected_machine_stable_id = (
                selected_machine.get("stable_id")
                if isinstance(selected_machine, dict)
                else None
            )
            if selected_machine_stable_id != expected_join_context["machine_stable_id"]:
                return jsonify(
                    {
                        "ok": False,
                        "code": "QUEUE_CONTEXT_CHANGED",
                        "error": "所选机台已经变化，请重新查询玩家资料后再提交。",
                    }
                ), 409
        if (
            expected_context is not None
            and expected_context["machine_configuration_revision"] is not None
            and expected_context["machine_configuration_revision"]
            != snapshot.get("machine_configuration_revision", 1)
        ):
            return jsonify(
                {
                    "ok": False,
                    "code": "QUEUE_CONTEXT_CHANGED",
                    "error": "现场机台配置已更新，请重新查询排队状态后再操作。",
                }
            ), 409
        availability_error = remote_operation_availability_error(
            snapshot_row, snapshot, operation_source, operation
        )
        if availability_error is not None:
            detail, status_code = availability_error
            return jsonify({"ok": False, "error": detail}), status_code

        profile = (
            find_player_profile_by_id(connection, actor_profile_id)
            if actor_profile_id is not None
            else find_player_profile_by_qq(connection, actor_qq)
        )
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
        if profile["qq_number"] != actor_qq:
            return profile_qq_sync_pending_response()
        registration_contexts = (
            find_profile_registration_contexts(
                connection,
                queue_storage_id,
                snapshot,
                actor_profile_id,
            )
            if actor_profile_id is not None
            else find_qq_registration_contexts(
                connection,
                queue_storage_id,
                snapshot,
                actor_qq,
            )
        )
        if (
            operation == "JOIN_QUEUE"
            and not registration_contexts
            and recently_applied_join_waiting_for_snapshot(
                connection,
                device_id=snapshot_row["device_id"],
                queue_storage_id=queue_storage_id,
                actor_qq=actor_qq,
                profile_id=profile["profile_id"],
                snapshot_received_at=snapshot_row["received_at"],
                now=now,
            )
        ):
            return player_join_syncing_response()

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
            expected_context=expected_context,
            registration_contexts=registration_contexts,
            request_identity=request_identity,
        )
        if validation_error is not None:
            detail, status_code = validation_error
            return jsonify({"ok": False, "error": detail}), status_code
        # The public queue UUID can intentionally be reused by a temporary test
        # snapshot. Keep server-side duplicate guards scoped to the actual data
        # set without exposing this internal identifier to clients.
        desired["_queue_storage_id"] = queue_storage_id
        if actor_profile_id is not None:
            desired["profile_identity_verified"] = True

        pending = connection.execute(
            """
            SELECT 1 FROM terminal_command
            WHERE status = 'PENDING' AND device_id = ? AND (
                json_extract(payload, '$.actor_qq') = ?
                OR json_extract(payload, '$.profile_id') = ?
            )
            """,
            (snapshot_row["device_id"], actor_qq, profile["profile_id"]),
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
    expected_context: dict[str, Any] | None,
    registration_contexts: list[dict[str, Any]],
    request_identity: dict[str, Any],
):
    payload = {
        "queue_id": queue_id,
        "machine_configuration_revision": snapshot.get(
            "machine_configuration_revision", 1
        ),
        "profile_id": profile["profile_id"],
        "actor_qq": actor_qq,
        "operation": operation,
        "operation_source": operation_source,
        "_request": request_identity,
    }

    if operation == "JOIN_QUEUE":
        if expected_context is not None:
            return None, ("加入排队不接受登记确认状态", 400)
        if snapshot_in_closing_grace(snapshot):
            return None, ("闭店收尾期间不再接收新的排队登记", 409)
        if not online_registration_allowed(snapshot):
            return None, ("现场规则暂不允许线上登记", 409)
        if registration_contexts:
            return None, ("你已经有一份正在排队的登记，不能重复加入", 409)
        if not snapshot.get("registration_open", True):
            return None, ("现场当前没有使用登记排队，暂不能线上加入排队", 409)
        machine = snapshot.get("machines", {}).get(machine_id or "")
        if machine is None:
            return None, ("请选择有效的排队机台", 400)
        payload["machine_stable_id"] = machine.get(
            "stable_id"
        ) or default_machine_stable_id(machine_id or "")
        if not machine.get("operational", False):
            return None, (f"{machine['name']}已停止使用，暂不能加入", 409)
        if machine.get("registration_count", 0) >= MAX_REGISTRATIONS_PER_MACHINE:
            return None, (f"{machine['name']}的登记已满，请选择其他机台", 409)
        if machine_capacity(machine) == 1:
            resolved_preference = "SOLO"
        else:
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
    if expected_context is not None:
        current_absence_status = (
            "DEFER_ONE_ROUND"
            if registration.get("deferred_once", False)
            else "TEMPORARILY_AWAY"
            if registration.get("temporarily_away", False)
            else "NONE"
        )
        if (
            expected_context["queue_id"] != queue_id
            or expected_context["registration_id"] != registration.get("registration_id")
        ):
            return None, ("确认期间排队批次或登记已经变化，请重新查询后再操作", 409)
        if (
            expected_context["machine_id"] != context["machine_id"]
            or expected_context["position"] != context["position"]
        ):
            return None, ("确认期间登记所在机台或位置已经变化，请重新查询后再操作", 409)
        if (
            expected_context["fixed_pair_id"]
            != registration.get("fixed_pair_id")
            or expected_context["absence_status"] != current_absence_status
            or expected_context["temporary_away_skipped_turns"]
            != registration.get("temporary_away_skipped_turns", 0)
            or expected_context["pending_check_in"]
            != registration.get("online_registration_pending_check_in", False)
        ):
            return None, ("确认期间登记状态已经变化，请重新查询后再操作", 409)
    source_machine = snapshot.get("machines", {}).get(context["machine_id"])
    if source_machine is None or not source_machine.get("operational", False):
        return None, ("登记所在机台已停止使用，恢复正常使用后才能操作", 409)
    source_machine_stable_id = source_machine.get(
        "stable_id"
    ) or default_machine_stable_id(context["machine_id"])
    if (
        expected_context is not None
        and expected_context["machine_stable_id"] is not None
        and expected_context["machine_stable_id"] != source_machine_stable_id
    ):
        return None, ("确认期间登记所在机台已经变化，请重新查询后再操作", 409)
    payload["machine_stable_id"] = source_machine_stable_id
    pending_check_in = registration.get("online_registration_pending_check_in", False)
    if pending_check_in and operation != "LEAVE_QUEUE":
        return None, ("线上登记完成现场签到后，才能进行这项操作", 409)

    queue_rules = snapshot.get("queue_rules") or normalize_public_queue_rules(None)
    if operation == "DEFER_ONE_ROUND":
        if not queue_rules["allow_defer_one_round"]:
            return None, ("系统规则不允许暂缓一次", 409)
    elif operation == "TEMPORARILY_LEAVE":
        if not queue_rules["allow_temporary_leave"]:
            return None, ("系统规则不允许暂时离开", 409)
    elif operation in {"CANCEL_DEFER_ONE_ROUND", "CANCEL_TEMPORARY_LEAVE"}:
        # The public snapshot may lag behind the terminal by one publish cycle.
        # The terminal owns the current absence state and performs the final check.
        pass
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
        target_machine_stable_id = target_machine.get(
            "stable_id"
        ) or default_machine_stable_id(target_machine_id or "")
        if (
            expected_context is not None
            and expected_context["target_machine_stable_id"] is not None
            and expected_context["target_machine_stable_id"]
            != target_machine_stable_id
        ):
            return None, ("确认期间要转入的机台已经变化，请重新选择", 409)
        if machine_capacity(target_machine) == 1 and registration.get("fixed_pair"):
            return None, (f"{target_machine['name']}仅能容纳一人游玩，请先释放固定组合", 409)
        payload["target_machine_id"] = target_machine_id
        payload["target_machine_stable_id"] = target_machine_stable_id
    elif operation == "CHANGE_PLAY_PREFERENCE":
        if machine_capacity(source_machine) == 1:
            return None, (f"{source_machine['name']}仅能容纳一人游玩，不能修改游玩偏好", 409)
        if preference not in PREFERENCES:
            return None, ("请选择本次游玩偏好", 400)
        payload["preference"] = preference
    elif operation != "LEAVE_QUEUE":
        return None, ("不支持这项排队操作", 400)

    payload.update(
        {
            "registration_id": registration["registration_id"],
            "machine_id": context["machine_id"],
            "expected_position": context["position"],
            "expected_fixed_pair_id": registration.get("fixed_pair_id"),
            "expected_absence_status": (
                "DEFER_ONE_ROUND"
                if registration.get("deferred_once", False)
                else "TEMPORARILY_AWAY"
                if registration.get("temporarily_away", False)
                else "NONE"
            ),
            "expected_temporary_away_skipped_turns": registration.get(
                "temporary_away_skipped_turns", 0
            ),
            "expected_pending_check_in": pending_check_in,
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
    request_identity = payload.get("_request")
    account_profile_id = (
        request_identity.get("actor_profile_id")
        if isinstance(request_identity, dict)
        else None
    )
    if account_profile_id:
        # Account queue actions are private. Public online-join commands do
        # not carry actor_profile_id and remain readable without a login.
        with open_database() as connection:
            current = current_player_account_session(connection)
        if current is None or current[1]["profile_id"] != account_profile_id:
            return jsonify({"ok": False, "error": "没有找到这条命令"}), 404
    command = serialize_command(row)
    command.pop("payload", None)
    return jsonify(command)


def cleanup_mobile_registration_sessions(
    connection: sqlite3.Connection, now: int
) -> None:
    retention = max(1, current_app.config["MOBILE_SESSION_RETENTION_SECONDS"])
    connection.execute(
        """
        DELETE FROM mobile_registration_session
        WHERE COALESCE(submitted_at, expires_at) <= ?
        """,
        (now - retention,),
    )


def create_mobile_registration_session():
    source = request.get_json(silent=True)
    required_fields = {"request_id", "queue_id", "machine_id"}
    allowed_fields = required_fields | {"machine_stable_id"}
    if (
        not isinstance(source, dict)
        or not required_fields <= set(source)
        or not set(source) <= allowed_fields
    ):
        return jsonify({"ok": False, "error": "移动设备登记会话参数不完整"}), 400
    try:
        session_id = read_uuid(source, "request_id")
        queue_id = read_uuid(source, "queue_id")
        machine_id = read_optional_machine_id(source, "machine_id")
        if machine_id is None:
            raise ValidationError("machine_id 机台编号无效")
        machine_stable_id = (
            read_machine_internal_id(source, "machine_stable_id")
            if "machine_stable_id" in source
            else None
        )
    except ValidationError as error:
        return jsonify({"ok": False, "error": str(error)}), 400

    if not current_app.config.get("PUBLIC_SITE_URL", "").strip():
        return jsonify(
            {
                "ok": False,
                "error": "服务端尚未配置公开网站地址，暂不能生成移动设备登记二维码",
            }
        ), 503

    device_id = request.headers.get("X-Device-ID", "").strip()
    now = int(time.time())
    expires_at = now + max(60, current_app.config["MOBILE_SESSION_TTL_SECONDS"])
    with open_database() as connection:
        connection.execute("BEGIN IMMEDIATE")
        cleanup_mobile_registration_sessions(connection, now)
        existing = connection.execute(
            "SELECT * FROM mobile_registration_session WHERE session_id = ?",
            (session_id,),
        ).fetchone()
        if existing is not None:
            if (
                existing["queue_id"] != queue_id
                or existing["device_id"] != device_id
                or existing["machine_id"] != machine_id
                or (
                    machine_stable_id is not None
                    and existing["machine_stable_id"] != machine_stable_id
                )
            ):
                return jsonify({"ok": False, "error": "request_id 已用于其他登记会话"}), 409
            connection.commit()
            return jsonify(serialize_mobile_session(existing)), 200

        snapshot_row = connection.execute(
            "SELECT queue_id, device_id, payload, received_at FROM queue_snapshot WHERE id = 1"
        ).fetchone()
        if snapshot_row is None:
            return jsonify({"ok": False, "error": "排队终端暂未同步"}), 404
        if not snapshot_is_online(snapshot_row):
            return jsonify(
                {"ok": False, "error": "现场终端暂时离线，暂不能使用移动设备登记"}
            ), 503
        if snapshot_row["device_id"] != device_id:
            return jsonify({"ok": False, "error": "当前终端不是正在同步的终端"}), 409
        if snapshot_row["queue_id"] != queue_id:
            return jsonify({"ok": False, "error": "排队批次已经变化，请重新打开登记页面"}), 409
        snapshot = json.loads(snapshot_row["payload"])
        if not snapshot.get("website_remote_enabled", False):
            return jsonify({"ok": False, "error": "与服务端同步已关闭，暂不能使用移动设备登记"}), 409
        if snapshot_in_closing_grace(snapshot):
            return jsonify({"ok": False, "error": "闭店收尾期间不再接收新的排队登记"}), 409
        if not online_registration_allowed(snapshot):
            return jsonify({"ok": False, "error": "现场规则暂不允许线上登记"}), 409
        if not snapshot.get("registration_open", True):
            return jsonify({"ok": False, "error": "现场当前没有使用登记排队"}), 409
        machine = snapshot.get("machines", {}).get(machine_id)
        if machine is None:
            return jsonify({"ok": False, "error": "所选机台不存在"}), 404
        current_machine_stable_id = machine.get("stable_id") or default_machine_stable_id(
            machine_id
        )
        if (
            machine_stable_id is not None
            and machine_stable_id != current_machine_stable_id
        ):
            return jsonify({"ok": False, "error": "所选机台已经变化，请重新打开登记页面"}), 409
        if not machine.get("operational", False):
            return jsonify({"ok": False, "error": f"{machine['name']}已停止使用"}), 409
        if machine.get("registration_count", 0) >= MAX_REGISTRATIONS_PER_MACHINE:
            return jsonify({"ok": False, "error": f"{machine['name']}的登记已满"}), 409

        session_token = secrets.token_urlsafe(32)
        connection.execute(
            """
            INSERT INTO mobile_registration_session
                (session_id, session_token, queue_id, device_id, machine_id,
                 machine_stable_id, status, created_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?, ?)
            """,
            (
                session_id,
                session_token,
                queue_id,
                device_id,
                machine_id,
                current_machine_stable_id,
                now,
                expires_at,
            ),
        )
        connection.commit()
        created = connection.execute(
            "SELECT * FROM mobile_registration_session WHERE session_id = ?",
            (session_id,),
        ).fetchone()
    return jsonify(serialize_mobile_session(created)), 201


def serialize_mobile_session(row: sqlite3.Row) -> dict[str, Any]:
    token = row["session_token"]
    return {
        "session_id": row["session_id"],
        "session_token": token,
        "queue_id": row["queue_id"],
        "machine_id": row["machine_id"],
        "machine_stable_id": row["machine_stable_id"],
        "status": row["status"],
        "command_id": row["command_id"],
        "created_at": row["created_at"] * 1000,
        "expires_at": row["expires_at"] * 1000,
        "registration_url": (
            f"{current_app.config['PUBLIC_SITE_URL']}?mobile_registration={token}"
        ),
    }


def find_mobile_session(connection: sqlite3.Connection, session_token: str):
    if MOBILE_SESSION_TOKEN_PATTERN.fullmatch(session_token) is None:
        return None
    return connection.execute(
        "SELECT * FROM mobile_registration_session WHERE session_token = ?",
        (session_token,),
    ).fetchone()


def validate_open_mobile_session(
    connection: sqlite3.Connection,
    session: sqlite3.Row,
    now: int,
):
    if session["status"] == "INVALIDATED":
        return (
            MOBILE_SESSION_INVALIDATED_DETAIL,
            409,
            "SESSION_INVALIDATED",
        )
    if session["status"] != "OPEN":
        return "这次移动设备登记已经提交", 409, "SESSION_SUBMITTED"
    if session["expires_at"] <= now:
        return "移动设备登记二维码已过期，请在终端重新打开", 410, "SESSION_EXPIRED"
    snapshot_row = connection.execute(
        "SELECT queue_id, device_id, payload, received_at FROM queue_snapshot WHERE id = 1"
    ).fetchone()
    if snapshot_row is None:
        return "排队终端暂未同步", 404, "TERMINAL_NOT_READY"
    if (
        snapshot_row["queue_id"] != session["queue_id"]
        or snapshot_row["device_id"] != session["device_id"]
    ):
        return "这次移动设备登记已经失效，请在终端重新打开", 409, "SESSION_STALE"
    if not snapshot_is_online(snapshot_row):
        return "现场终端暂时离线，请稍后重试", 503, "TERMINAL_OFFLINE"
    snapshot = json.loads(snapshot_row["payload"])
    if not snapshot.get("website_remote_enabled", False):
        return "与服务端同步已关闭，暂不能使用移动设备登记", 503, "WEBSITE_SYNC_DISABLED"
    if snapshot_in_closing_grace(snapshot):
        return "闭店收尾期间不再接收新的排队登记", 409, "REGISTRATION_CLOSED"
    if not online_registration_allowed(snapshot):
        return "现场规则暂不允许线上登记", 409, "ONLINE_REGISTRATION_DISABLED"
    if not snapshot.get("registration_open", True):
        return "现场当前没有使用登记排队", 409, "REGISTRATION_CLOSED"
    machine = snapshot.get("machines", {}).get(session["machine_id"])
    if machine is None:
        return "目标机台已经不存在", 409, "MACHINE_NOT_FOUND"
    current_machine_stable_id = machine.get("stable_id") or default_machine_stable_id(
        session["machine_id"]
    )
    if (
        session["machine_stable_id"] is not None
        and session["machine_stable_id"] != current_machine_stable_id
    ):
        return "目标机台已经变化，请在终端重新打开", 409, "MACHINE_CHANGED"
    if not machine.get("operational", False):
        return f"{machine['name']}已停止使用", 409, "MACHINE_STOPPED"
    if machine.get("registration_count", 0) >= MAX_REGISTRATIONS_PER_MACHINE:
        return f"{machine['name']}的登记已满", 409, "MACHINE_FULL"
    return snapshot_row, snapshot, machine


def read_mobile_registration_session(session_token: str):
    query = request.args.get("q", "").strip()
    if len(query) > 32:
        return jsonify({"ok": False, "error": "搜索内容过长"}), 400
    now = int(time.time())
    with open_database() as connection:
        connection.execute("BEGIN IMMEDIATE")
        cleanup_mobile_registration_sessions(connection, now)
        session = find_mobile_session(connection, session_token)
        if session is None:
            connection.commit()
            return jsonify({"ok": False, "error": "没有找到这次移动设备登记"}), 404
        validation = validate_open_mobile_session(connection, session, now)
        if isinstance(validation[0], str):
            detail, status_code, code = validation
            connection.commit()
            return jsonify({"ok": False, "code": code, "error": detail}), status_code
        snapshot_row, snapshot, machine = validation
        _, profile_scope_id = snapshot_storage_context(snapshot_row, snapshot)
        profiles = connection.execute(
            """
            SELECT profile_id, nickname, gender, default_preference, qq_number,
                   usage_count, last_used_at, qq_visibility,
                   notification_enabled, notify_queue_changes,
                   notify_playing_position, notify_online_check_in,
                   notify_absence, notify_machine_status, setup_version,
                   profile_revision, public_player_id, received_at
            FROM player_profile
            WHERE device_id = ?
            ORDER BY usage_count DESC, COALESCE(last_used_at, 0) DESC,
                     nickname, profile_id
            """,
            (profile_scope_id,),
        ).fetchall()
        current_profile_ids = read_current_player_profile_ids(
            connection, profile_scope_id
        )
        profile_aliases = find_mobile_profile_aliases(
            profiles, current_profile_ids=current_profile_ids
        )
        public_player_id_aliases = read_player_public_id_aliases(
            connection, profile_scope_id=profile_scope_id
        )
        canonical_profiles = [
            profile
            for profile in profiles
            if profile["profile_id"] not in profile_aliases
        ]
        if query:
            folded_query = query.casefold()
            matching_profile_ids = {
                profile_aliases.get(profile["profile_id"], profile["profile_id"])
                for profile in profiles
                if folded_query in profile["nickname"].casefold()
                or (profile["qq_number"] and query in profile["qq_number"])
                or (
                    profile["public_player_id"]
                    and query in profile["public_player_id"]
                )
                or any(
                    query in public_player_id
                    for public_player_id in public_player_id_aliases.get(
                        profile_aliases.get(
                            profile["profile_id"], profile["profile_id"]
                        ),
                        [],
                    )
                )
            }
            canonical_profiles = [
                profile
                for profile in canonical_profiles
                if profile["profile_id"] in matching_profile_ids
            ]
        profiles = [
            profile
            for profile in canonical_profiles
        ]
        identity = connection.execute(
            "SELECT bot_qq FROM service_identity WHERE profile_scope_id = ?",
            (profile_scope_id,),
        ).fetchone()
        connection.commit()
    return jsonify(
        {
            "session": {
                "session_id": session["session_id"],
                "queue_id": session["queue_id"],
                "machine_id": session["machine_id"],
                "machine_name": machine["name"],
                "machine_configuration_revision": snapshot.get(
                    "machine_configuration_revision", 1
                ),
                "machine_configuration": machine.get("configuration")
                or default_machine_configuration(machine),
                "expires_at": session["expires_at"] * 1000,
            },
            "profiles": [
                serialize_mobile_profile(
                    row,
                    public_player_id_aliases=public_player_id_aliases.get(
                        row["profile_id"], []
                    ),
                )
                for row in profiles
            ],
            "profile_aliases": profile_aliases,
            "bot_qq": identity["bot_qq"] if identity is not None else None,
            "capabilities": {
                "create_profile": True,
                "complete_profile": True,
                "edit_complete_profile": False,
                "terminal_is_source_of_truth": True,
            },
        }
    )


def serialize_mobile_profile(
    profile: sqlite3.Row,
    *,
    public_player_id_aliases: list[str] | None = None,
) -> dict[str, Any]:
    qq_is_public = profile["qq_visibility"] == "PUBLIC_WEBSITE"
    return {
        "profile_id": profile["profile_id"],
        "public_player_id": profile["public_player_id"],
        "public_player_id_aliases": public_player_id_aliases or [],
        "nickname": profile["nickname"],
        "gender": profile["gender"],
        "default_preference": profile["default_preference"],
        "qq_number": profile["qq_number"] if qq_is_public else None,
        "qq_present": bool(profile["qq_number"]),
        "qq_public": qq_is_public,
        "notification_enabled": bool(profile["notification_enabled"]),
        "notify_queue_changes": bool(profile["notify_queue_changes"]),
        "notify_playing_position": bool(profile["notify_playing_position"]),
        "notify_online_check_in": bool(profile["notify_online_check_in"]),
        "notify_absence": bool(profile["notify_absence"]),
        "notify_machine_status": bool(profile["notify_machine_status"]),
        "usage_count": profile["usage_count"],
        "last_used_at": profile["last_used_at"],
        "setup_complete": bool(profile["setup_version"] >= 1 and profile["qq_number"]),
        "profile_revision": profile["profile_revision"],
    }


def are_legacy_profile_aliases(
    first: sqlite3.Row, second: sqlite3.Row
) -> bool:
    return bool(
        first["profile_id"] != second["profile_id"]
        and first["nickname"].casefold() == second["nickname"].casefold()
        and first["gender"] == second["gender"]
        and first["default_preference"] == second["default_preference"]
        and not (first["qq_number"] and second["qq_number"])
        and (
            (not first["qq_number"] and first["setup_version"] < 1)
            or (not second["qq_number"] and second["setup_version"] < 1)
        )
    )


def find_mobile_profile_aliases(
    profiles: list[sqlite3.Row],
    *,
    current_profile_ids: set[str] | None = None,
) -> dict[str, str]:
    by_identity: dict[tuple[str, str, str], list[sqlite3.Row]] = {}
    for profile in profiles:
        identity = (
            profile["nickname"].casefold(),
            profile["gender"],
            profile["default_preference"],
        )
        by_identity.setdefault(identity, []).append(profile)

    aliases: dict[str, str] = {}
    for matching_profiles in by_identity.values():
        contact_profiles = [profile for profile in matching_profiles if profile["qq_number"]]
        if len(contact_profiles) > 1:
            continue
        if current_profile_ids is not None:
            current_profiles = [
                profile
                for profile in matching_profiles
                if profile["profile_id"] in current_profile_ids
            ]
            if len(current_profiles) != 1:
                continue
            canonical = current_profiles[0]
        elif len(contact_profiles) == 1:
            canonical = contact_profiles[0]
        else:
            continue
        for candidate in matching_profiles:
            if are_legacy_profile_aliases(candidate, canonical):
                aliases[candidate["profile_id"]] = canonical["profile_id"]
    return aliases


def normalize_mobile_profile_settings(
    source: Any,
    *,
    require_identity_fields: bool,
    existing_qq: str | None = None,
) -> dict[str, Any]:
    if not isinstance(source, dict):
        raise ValidationError("玩家资料设置不完整")
    required = {"qq_visibility", *NOTIFICATION_FIELDS}
    if require_identity_fields:
        required.update({"nickname", "gender", "default_preference", "qq_number"})
    elif existing_qq is None:
        required.add("qq_number")
    if set(source) != required:
        raise ValidationError("请确认并保存全部玩家资料设置")

    settings = {
        "qq_visibility": read_choice(source, "qq_visibility", QQ_VISIBILITIES),
    }
    for key in NOTIFICATION_FIELDS:
        settings[key] = read_boolean(source, key)
    if require_identity_fields:
        settings.update(
            {
                "nickname": read_string(source, "nickname", maximum_length=18),
                "gender": read_choice(source, "gender", PLAYER_GENDERS),
                "default_preference": read_choice(
                    source, "default_preference", PROFILE_PREFERENCES
                ),
            }
        )
    qq_number = existing_qq
    if "qq_number" in source:
        qq_number = read_qq_number(source, "qq_number")
    settings["qq_number"] = qq_number
    settings["setup_version"] = 1
    return settings


def resolve_mobile_registration_preference(
    default_preference: str, requested_preference: Any
) -> str:
    if default_preference == "ASK_EVERY_TIME":
        if requested_preference not in PREFERENCES:
            raise ValidationError("请选择本次游玩偏好")
        return requested_preference
    if requested_preference is not None and requested_preference != default_preference:
        raise ValidationError("玩家资料的默认游玩偏好已经变化，请重新确认")
    return default_preference


def submit_mobile_registration_session(session_token: str):
    source = request.get_json(silent=True)
    if not isinstance(source, dict):
        return jsonify({"ok": False, "error": "请求内容必须是 JSON 对象"}), 400
    allowed_fields = {
        "request_id",
        "profile_id",
        "expected_profile_revision",
        "profile_completion",
        "new_profile",
        "preference",
    }
    if set(source) - allowed_fields:
        return jsonify({"ok": False, "error": "请求包含不支持的移动设备登记字段"}), 400
    try:
        command_id = read_uuid(source, "request_id")
    except ValidationError as error:
        return jsonify({"ok": False, "error": str(error)}), 400
    profile_id_value = source.get("profile_id")
    new_profile_source = source.get("new_profile")
    if (profile_id_value is None) == (new_profile_source is None):
        return jsonify({"ok": False, "error": "请选择一份玩家资料或新建资料"}), 400

    now = int(time.time())
    with open_database() as connection:
        connection.execute("BEGIN IMMEDIATE")
        cleanup_mobile_registration_sessions(connection, now)
        expire_pending_commands(connection, now)
        session = find_mobile_session(connection, session_token)
        if session is None:
            connection.commit()
            return jsonify({"ok": False, "error": "没有找到这次移动设备登记"}), 404
        if session["status"] == "SUBMITTED":
            if session["command_id"] != command_id:
                connection.commit()
                return jsonify({"ok": False, "error": "这次移动设备登记已经提交"}), 409
            command = connection.execute(
                "SELECT * FROM terminal_command WHERE command_id = ?",
                (command_id,),
            ).fetchone()
            if command is None:
                connection.commit()
                return jsonify(
                    {
                        "ok": False,
                        "code": "SESSION_COMMAND_UNAVAILABLE",
                        "error": "这次移动设备登记的处理记录已失效，请重新打开登记页面。",
                    }
                ), 409
            connection.commit()
            return jsonify(serialize_mobile_command_result(command, session)), 200
        validation = validate_open_mobile_session(connection, session, now)
        if isinstance(validation[0], str):
            detail, status_code, code = validation
            connection.commit()
            return jsonify({"ok": False, "code": code, "error": detail}), status_code
        snapshot_row, snapshot, machine = validation
        single_player_machine = machine_capacity(machine) == 1

        # request_id is the command's global primary key, not just a key within
        # this mobile session. Reject a collision before doing any profile work
        # so a stale/reused client request can never turn into an HTTP 500 on
        # the INSERT below.
        existing_command = connection.execute(
            "SELECT 1 FROM terminal_command WHERE command_id = ?",
            (command_id,),
        ).fetchone()
        if existing_command is not None:
            return jsonify(
                {"ok": False, "code": "REQUEST_ID_CONFLICT", "error": "request_id 已用于其他命令"}
            ), 409

        try:
            queue_storage_id, profile_scope_id = snapshot_storage_context(
                snapshot_row, snapshot
            )
            if profile_id_value is not None:
                profile_id = read_uuid(source, "profile_id")
                profile = connection.execute(
                    """
                    SELECT profile_id, nickname, gender, default_preference, qq_number,
                           qq_visibility, notification_enabled, notify_queue_changes,
                           notify_playing_position, notify_online_check_in,
                           notify_absence, notify_machine_status, setup_version,
                           profile_revision, received_at
                    FROM player_profile
                    WHERE device_id = ? AND profile_id = ?
                    """,
                    (profile_scope_id, profile_id),
                ).fetchone()
                if profile is None:
                    return jsonify(
                        {
                            "ok": False,
                            "code": "PROFILE_NOT_FOUND",
                            "error": "这份玩家资料已经不存在",
                        }
                    ), 404
                expected_revision = read_integer(
                    source,
                    "expected_profile_revision",
                    minimum=1,
                    maximum=2**63 - 1,
                )
                if expected_revision != profile["profile_revision"]:
                    return jsonify(
                        {
                            "ok": False,
                            "code": "PROFILE_UPDATED",
                            "error": "玩家资料已经更新，请重新选择后再提交",
                        }
                    ), 409
                completion_source = source.get("profile_completion")
                profile_is_complete = bool(
                    profile["setup_version"] >= 1 and profile["qq_number"]
                )
                if profile_is_complete:
                    if completion_source is not None:
                        raise ValidationError("完整玩家资料不能在此页面编辑")
                    profile_settings = None
                    actor_qq = profile["qq_number"]
                else:
                    profile_settings = normalize_mobile_profile_settings(
                        completion_source,
                        require_identity_fields=False,
                        existing_qq=profile["qq_number"],
                    )
                    actor_qq = profile_settings["qq_number"]
                resolved_preference = (
                    "SOLO"
                    if single_player_machine
                    else resolve_mobile_registration_preference(
                        profile["default_preference"], source.get("preference")
                    )
                )
                nickname = profile["nickname"]
                command_profile = {
                    "mode": "EXISTING",
                    "profile_id": profile_id,
                    "expected_profile_revision": expected_revision,
                    "completion": profile_settings,
                }
            else:
                if source.get("profile_completion") is not None:
                    raise ValidationError("新建资料不能同时提交补全资料内容")
                if source.get("expected_profile_revision") is not None:
                    raise ValidationError("新建资料不能包含旧资料版本")
                profile_settings = normalize_mobile_profile_settings(
                    new_profile_source,
                    require_identity_fields=True,
                )
                actor_qq = profile_settings["qq_number"]
                nickname = profile_settings["nickname"]
                resolved_preference = (
                    "SOLO"
                    if single_player_machine
                    else resolve_mobile_registration_preference(
                        profile_settings["default_preference"], source.get("preference")
                    )
                )
                profile_id = str(uuid4())
                command_profile = {
                    "mode": "NEW",
                    "profile_id": profile_id,
                    "profile": profile_settings,
                }
        except (ValidationError, ValueError) as error:
            return jsonify(
                {
                    "ok": False,
                    "code": "PROFILE_SETTINGS_INVALID",
                    "error": str(error) or "玩家资料编号无效",
                }
            ), 400

        duplicates = connection.execute(
            """
            SELECT profile_id, nickname, gender, default_preference, qq_number,
                   setup_version, received_at
            FROM player_profile
            WHERE device_id = ? AND profile_id != ?
              AND (lower(nickname) = lower(?) OR qq_number = ?)
            """,
            (profile_scope_id, profile_id, nickname, actor_qq),
        ).fetchall()
        current_profile_ids = read_current_player_profile_ids(
            connection, profile_scope_id
        )
        duplicate = next(
            (
                candidate
                for candidate in duplicates
                if not (
                    profile_id_value is not None
                    and profile["profile_id"] in current_profile_ids
                    and candidate["profile_id"] not in current_profile_ids
                    and are_legacy_profile_aliases(candidate, profile)
                )
            ),
            None,
        )
        if duplicate is not None:
            duplicate_qq = duplicate["qq_number"] == actor_qq
            return jsonify(
                {
                    "ok": False,
                    "code": (
                        "QQ_ALREADY_USED"
                        if duplicate_qq
                        else "NICKNAME_ALREADY_USED"
                    ),
                    "error": (
                        "这个 QQ 已经关联其他玩家资料"
                        if duplicate_qq
                        else "这个昵称已经用于其他玩家资料"
                    ),
                }
            ), 409

        active_registration_ids = {
            row["registration_id"]
            for row in connection.execute(
                """
                SELECT registration_id FROM queue_private_contact
                WHERE queue_id = ? AND (player_id = ? OR qq_number = ?)
                """,
                (queue_storage_id, profile_id, actor_qq),
            ).fetchall()
        }
        current_registration_ids = set(index_snapshot_registrations(snapshot))
        if active_registration_ids & current_registration_ids:
            return jsonify(
                {
                    "ok": False,
                    "code": "PLAYER_ALREADY_REGISTERED",
                    "error": "这名玩家已经有一份正在排队的登记",
                }
            ), 409
        if any(
            registration["display_id"].casefold() == nickname.casefold()
            for machine in snapshot.get("machines", {}).values()
            for registration in all_machine_registrations(machine)
        ):
            return jsonify(
                {
                    "ok": False,
                    "code": "NICKNAME_IN_QUEUE",
                    "error": "这个昵称已经用于当前队列中的其他登记",
                }
            ), 409
        if recently_applied_join_waiting_for_snapshot(
            connection,
            device_id=snapshot_row["device_id"],
            queue_storage_id=queue_storage_id,
            actor_qq=actor_qq,
            profile_id=profile_id,
            snapshot_received_at=snapshot_row["received_at"],
            now=now,
        ):
            return player_join_syncing_response()
        pending = connection.execute(
            """
            SELECT 1 FROM terminal_command
            WHERE status = 'PENDING' AND device_id = ? AND (
                json_extract(payload, '$.actor_qq') = ?
                OR json_extract(payload, '$.profile.profile_id') = ?
                OR json_extract(payload, '$.profile_id') = ?
            )
            """,
            (snapshot_row["device_id"], actor_qq, profile_id, profile_id),
        ).fetchone()
        if pending is not None:
            return jsonify(
                {
                    "ok": False,
                    "code": "PLAYER_OPERATION_PENDING",
                    "error": "这名玩家已有一项操作等待终端处理",
                }
            ), 409

        payload = {
            "_queue_storage_id": queue_storage_id,
            "queue_id": session["queue_id"],
            "machine_configuration_revision": snapshot.get(
                "machine_configuration_revision", 1
            ),
            "machine_id": session["machine_id"],
            "machine_stable_id": session["machine_stable_id"],
            "actor_qq": actor_qq,
            "preference": resolved_preference,
            "operation_source": "MOBILE_DEVICE",
            "session_id": session["session_id"],
            "profile": command_profile,
        }
        connection.execute(
            """
            INSERT INTO terminal_command
                (command_id, device_id, command_type, payload, status, created_at)
            VALUES (?, ?, ?, ?, 'PENDING', ?)
            """,
            (
                command_id,
                snapshot_row["device_id"],
                MOBILE_REGISTRATION_COMMAND,
                json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
                now,
            ),
        )
        connection.execute(
            """
            UPDATE mobile_registration_session
            SET status = 'SUBMITTED', command_id = ?, submitted_at = ?
            WHERE session_id = ? AND status = 'OPEN'
            """,
            (command_id, now, session["session_id"]),
        )
        connection.commit()
        command = connection.execute(
            "SELECT * FROM terminal_command WHERE command_id = ?", (command_id,)
        ).fetchone()
        submitted = connection.execute(
            "SELECT * FROM mobile_registration_session WHERE session_id = ?",
            (session["session_id"],),
        ).fetchone()
    return jsonify(serialize_mobile_command_result(command, submitted)), 202


def serialize_mobile_command_result(
    command: sqlite3.Row | None, session: sqlite3.Row
) -> dict[str, Any]:
    profile_id = None
    if command is not None:
        try:
            payload = json.loads(command["payload"])
            profile_id = payload.get("profile", {}).get("profile_id")
        except (TypeError, ValueError, AttributeError):
            profile_id = None
    return {
        "session_id": session["session_id"],
        "command_id": session["command_id"],
        "status": command["status"] if command is not None else "UNAVAILABLE",
        "profile_id": profile_id,
        "created_at": command["created_at"] * 1000 if command is not None else None,
        "completed_at": (
            command["completed_at"] * 1000
            if command is not None and command["completed_at"]
            else None
        ),
        "result_detail": command["result_detail"] if command is not None else None,
    }


def read_mobile_registration_result(session_token: str):
    now = int(time.time())
    with open_database() as connection:
        connection.execute("BEGIN IMMEDIATE")
        cleanup_mobile_registration_sessions(connection, now)
        expire_pending_commands(connection, now)
        session = find_mobile_session(connection, session_token)
        if session is None:
            connection.commit()
            return jsonify({"ok": False, "error": "没有找到这次移动设备登记"}), 404
        if session["status"] == "INVALIDATED":
            connection.commit()
            return jsonify(
                {
                    "ok": False,
                    "code": "SESSION_INVALIDATED",
                    "error": MOBILE_SESSION_INVALIDATED_DETAIL,
                }
            ), 409
        if session["status"] != "SUBMITTED" or not session["command_id"]:
            connection.commit()
            if session["expires_at"] <= now:
                return jsonify(
                    {
                        "ok": False,
                        "code": "SESSION_EXPIRED",
                        "error": "移动设备登记二维码已过期，请在终端重新打开",
                    }
                ), 410
            return jsonify({"status": "OPEN", "session_id": session["session_id"]})
        command = connection.execute(
            "SELECT * FROM terminal_command WHERE command_id = ?",
            (session["command_id"],),
        ).fetchone()
        connection.commit()
    return jsonify(serialize_mobile_command_result(command, session))


def find_player_profile_by_id(
    connection: sqlite3.Connection, profile_id: str
):
    profile_scope_id = active_profile_scope_id(connection)
    return connection.execute(
        """
        SELECT profile_id, nickname, gender, default_preference, qq_number,
               usage_count, last_used_at, qq_visibility,
               notification_enabled, notify_queue_changes,
               notify_playing_position, notify_online_check_in,
               notify_absence, notify_machine_status, setup_version,
               profile_revision, created_at, profile_updated_at,
               public_player_id, received_at
        FROM player_profile
        WHERE device_id = ? AND profile_id = ?
        """,
        (profile_scope_id, profile_id),
    ).fetchone()


def find_player_profile_by_qq(connection: sqlite3.Connection, qq_number: str):
    profile_scope_id = active_profile_scope_id(connection)
    profiles = connection.execute(
        """
        SELECT profile_id, nickname, gender, default_preference, qq_number,
               usage_count, last_used_at, qq_visibility,
               notification_enabled, notify_queue_changes,
               notify_playing_position, notify_online_check_in,
               notify_absence, notify_machine_status, setup_version,
               profile_revision, created_at, profile_updated_at,
               public_player_id, received_at
        FROM player_profile
        WHERE device_id = ?
        """,
        (profile_scope_id,),
    ).fetchall()
    matching_profile = next(
        (profile for profile in profiles if profile["qq_number"] == qq_number),
        None,
    )
    if matching_profile is None:
        return None
    aliases = find_mobile_profile_aliases(
        profiles,
        current_profile_ids=read_current_player_profile_ids(
            connection, profile_scope_id
        ),
    )
    canonical_id = aliases.get(
        matching_profile["profile_id"], matching_profile["profile_id"]
    )
    return next(
        (profile for profile in profiles if profile["profile_id"] == canonical_id),
        None,
    )


def profile_qq_sync_pending_response():
    return jsonify(
        {
            "ok": False,
            "code": "PROFILE_SYNC_PENDING",
            "error": (
                "这份玩家资料尚未在现场终端完成 QQ 关联。"
                "请先在现场终端的玩家资料库补全并保存 QQ，再使用线上登记。"
            ),
        }
    ), 409


def serialize_player_profile(profile: sqlite3.Row) -> dict[str, Any]:
    return {
        "profile_id": profile["profile_id"],
        "public_player_id": profile["public_player_id"],
        "nickname": profile["nickname"],
        "gender": profile["gender"],
        "default_preference": profile["default_preference"],
        "qq_number": profile["qq_number"],
        "usage_count": profile["usage_count"],
        "last_used_at": profile["last_used_at"],
        "qq_visibility": profile["qq_visibility"],
        "notification_enabled": bool(profile["notification_enabled"]),
        "notify_queue_changes": bool(profile["notify_queue_changes"]),
        "notify_playing_position": bool(profile["notify_playing_position"]),
        "notify_online_check_in": bool(profile["notify_online_check_in"]),
        "notify_absence": bool(profile["notify_absence"]),
        "notify_machine_status": bool(profile["notify_machine_status"]),
        "setup_version": profile["setup_version"],
        "profile_revision": profile["profile_revision"],
        "created_at": profile["created_at"],
        "updated_at": profile["profile_updated_at"],
    }


def find_qq_registration_contexts(
    connection: sqlite3.Connection,
    queue_id: str,
    snapshot: dict[str, Any],
    qq_number: str,
) -> list[dict[str, Any]]:
    profile_scope_id = active_profile_scope_id(connection)
    registration_ids = {
        row["registration_id"]
        for row in connection.execute(
            """
            SELECT contact.registration_id
            FROM queue_private_contact AS contact
            LEFT JOIN player_profile AS profile
              ON profile.device_id = ? AND profile.profile_id = contact.player_id
            WHERE contact.queue_id = ?
              AND CASE WHEN profile.profile_id IS NOT NULL
                       THEN profile.qq_number ELSE contact.qq_number END = ?
            """,
            (profile_scope_id, queue_id, qq_number),
        ).fetchall()
    }
    indexed = index_snapshot_registrations(snapshot)
    return [indexed[value] for value in registration_ids if value in indexed]


def find_profile_registration_contexts(
    connection: sqlite3.Connection,
    queue_id: str,
    snapshot: dict[str, Any],
    profile_id: str,
) -> list[dict[str, Any]]:
    registration_ids = {
        row["registration_id"]
        for row in connection.execute(
            """
            SELECT registration_id FROM queue_private_contact
            WHERE queue_id = ? AND player_id = ?
            """,
            (queue_id, profile_id),
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
        "stable_id": machine.get("stable_id") or default_machine_stable_id(machine_id),
        "group_id": machine.get("group_id") or DEFAULT_MACHINE_GROUP_ID,
        "name": machine["name"],
        "operational": machine["operational"],
        "registration_count": machine.get("registration_count", 0),
        "estimated_wait_minutes": machine.get(
            "new_registration_estimated_wait_minutes"
        ),
        "configuration": machine.get("configuration")
        or default_machine_configuration(machine),
        "capacity": machine_capacity(machine),
        "available": unavailable_reason is None,
        "unavailable_reason": unavailable_reason,
    }


def default_machine_configuration(machine: dict[str, Any]) -> dict[str, Any]:
    return {
        "remark": machine.get("remark") or compact_middle_dots(
            machine.get("name", "")
        ).split("·", 1)[0],
        "game_type": "MAIMAI_DX",
        "custom_game_type": None,
        "server": "HIDDEN",
        "custom_server": None,
        "game_version": None,
        "game_version_visible": False,
        "capacity": 2,
        "solo_round_minutes": 12,
        "shared_round_minutes": 15,
    }


def machine_capacity(machine: dict[str, Any]) -> int:
    configuration = machine.get("configuration")
    if isinstance(configuration, dict) and configuration.get("capacity") in {1, 2}:
        return configuration["capacity"]
    return 2


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
        return "现场终端已关闭与服务端同步，暂不能在线操作", 503
    if operation_source == "QQ_BOT" and not snapshot.get("onebot_sync_enabled", True):
        return "现场终端已关闭 QQ Bot 联动", 503
    if operation == "JOIN_QUEUE" and snapshot_in_closing_grace(snapshot):
        return "闭店收尾期间不再接收新的排队登记", 409
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

    allowed_update_fields = {
        "nickname",
        "gender",
        "default_preference",
        "qq_visibility",
        "notification_enabled",
        "notify_queue_changes",
        "notify_playing_position",
        "notify_online_check_in",
        "notify_absence",
        "notify_machine_status",
    }
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
        if "qq_visibility" in source:
            requested_update["qq_visibility"] = read_choice(
                source, "qq_visibility", QQ_VISIBILITIES
            )
        for key in (
            "notification_enabled",
            "notify_queue_changes",
            "notify_playing_position",
            "notify_online_check_in",
            "notify_absence",
            "notify_machine_status",
        ):
            if key in source:
                requested_update[key] = read_boolean(source, key)
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
            "SELECT device_id, payload, received_at FROM queue_snapshot WHERE id = 1"
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

        if not snapshot_is_online(snapshot):
            return jsonify(
                {"ok": False, "error": "现场终端暂时离线，暂不能修改玩家资料"}
            ), 503
        snapshot_payload = json.loads(snapshot["payload"])
        if snapshot_payload.get("onebot_sync_enabled", True) is False:
            return jsonify({"ok": False, "error": "QQ Bot 联动已关闭"}), 503

        profile_scope_id = active_profile_scope_id(connection)
        profile = connection.execute(
            """
            SELECT profile_id, nickname, gender, default_preference, qq_number,
                   qq_visibility, notification_enabled, notify_queue_changes,
                   notify_playing_position, notify_online_check_in,
                   notify_absence, notify_machine_status, setup_version,
                   profile_revision, profile_updated_at
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
            "expected_profile_revision": profile["profile_revision"],
            "nickname": requested_update.get("nickname", profile["nickname"]),
            "gender": requested_update.get("gender", profile["gender"]),
            "default_preference": requested_update.get(
                "default_preference", profile["default_preference"]
            ),
            "qq_visibility": requested_update.get(
                "qq_visibility", profile["qq_visibility"]
            ),
            "notification_enabled": requested_update.get(
                "notification_enabled", bool(profile["notification_enabled"])
            ),
            "notify_queue_changes": requested_update.get(
                "notify_queue_changes", bool(profile["notify_queue_changes"])
            ),
            "notify_playing_position": requested_update.get(
                "notify_playing_position", bool(profile["notify_playing_position"])
            ),
            "notify_online_check_in": requested_update.get(
                "notify_online_check_in", bool(profile["notify_online_check_in"])
            ),
            "notify_absence": requested_update.get(
                "notify_absence", bool(profile["notify_absence"])
            ),
            "notify_machine_status": requested_update.get(
                "notify_machine_status", bool(profile["notify_machine_status"])
            ),
            "setup_version": profile["setup_version"],
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
    try:
        instance_id, instance_generation = read_terminal_instance_identity(device_id)
    except ValidationError as error:
        return jsonify({"ok": False, "error": str(error)}), 400
    with open_database() as connection:
        connection.execute("BEGIN IMMEDIATE")
        now = int(time.time())
        expire_pending_commands(connection, now)
        current_terminal = connection.execute(
            """
            SELECT device_id, instance_id, instance_generation
            FROM queue_snapshot WHERE id = 1
            """
        ).fetchone()
        if not terminal_instance_matches(
            current_terminal,
            device_id,
            instance_id,
            instance_generation,
        ):
            connection.rollback()
            return jsonify(
                {
                    "ok": False,
                    "code": "STALE_TERMINAL_INSTANCE",
                    "error": TERMINAL_INSTANCE_CONFLICT_DETAIL,
                }
            ), 409
        lease_seconds = max(1, current_app.config["COMMAND_CLAIM_LEASE_SECONDS"])
        command_ids = [
            row["command_id"]
            for row in connection.execute(
                """
                SELECT command_id FROM terminal_command
                WHERE device_id = ? AND status = 'PENDING'
                  AND (
                      claimed_at IS NULL OR claimed_instance IS NULL
                      OR claimed_at <= ?
                  )
                ORDER BY created_at, command_id
                LIMIT 20
                """,
                (device_id, now - lease_seconds),
            ).fetchall()
        ]
        rows = []
        if command_ids:
            placeholders = ",".join("?" for _ in command_ids)
            connection.execute(
                f"""
                UPDATE terminal_command
                SET claimed_at = ?, claimed_terminal = ?, claimed_instance = ?
                WHERE command_id IN ({placeholders})
                  AND device_id = ? AND status = 'PENDING'
                  AND (
                      claimed_at IS NULL OR claimed_instance IS NULL
                      OR claimed_at <= ?
                  )
                """,
                (
                    now,
                    device_id,
                    instance_id,
                    *command_ids,
                    device_id,
                    now - lease_seconds,
                ),
            )
            rows = connection.execute(
                f"""
                SELECT * FROM terminal_command
                WHERE command_id IN ({placeholders})
                  AND device_id = ? AND status = 'PENDING'
                  AND claimed_terminal = ? AND claimed_instance = ?
                  AND claimed_at = ?
                ORDER BY created_at, command_id
                """,
                (*command_ids, device_id, device_id, instance_id, now),
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
    result_registration_id = source.get("result_registration_id")
    if result_registration_id is not None:
        if (
            status != "APPLIED"
            or not isinstance(result_registration_id, str)
            or PUBLIC_ID_PATTERN.fullmatch(result_registration_id) is None
        ):
            return jsonify({"ok": False, "error": "命令结果中的登记编号无效"}), 400
    device_id = request.headers.get("X-Device-ID", "").strip()
    try:
        instance_id, instance_generation = read_terminal_instance_identity(device_id)
    except ValidationError as error:
        return jsonify({"ok": False, "error": str(error)}), 400

    with open_database() as connection:
        connection.execute("BEGIN IMMEDIATE")
        expire_pending_commands(connection, int(time.time()))
        row = connection.execute(
            "SELECT * FROM terminal_command WHERE command_id = ?", (command_id,)
        ).fetchone()
        if row is None:
            return jsonify({"ok": False, "error": "没有找到这条命令"}), 404
        if row["device_id"] != device_id:
            connection.rollback()
            return jsonify({"ok": False, "error": "此命令不属于当前终端"}), 403
        current_terminal = connection.execute(
            """
            SELECT device_id, instance_id, instance_generation
            FROM queue_snapshot WHERE id = 1
            """
        ).fetchone()
        if not terminal_instance_matches(
            current_terminal,
            device_id,
            instance_id,
            instance_generation,
        ):
            connection.rollback()
            return jsonify(
                {
                    "ok": False,
                    "code": "STALE_TERMINAL_INSTANCE",
                    "error": TERMINAL_INSTANCE_CONFLICT_DETAIL,
                }
            ), 409
        late_timeout_result = (
            row["status"] == "REJECTED"
            and row["result_source"] == RESULT_SOURCE_SERVER_TIMEOUT
            and row["claimed_at"] is not None
            and row["claimed_terminal"] == device_id
            and row["claimed_instance"] == instance_id
        )
        result_can_be_written = row["status"] == "PENDING" or late_timeout_result
        if result_can_be_written and row["claimed_instance"] != instance_id:
            connection.rollback()
            return jsonify(
                {
                    "ok": False,
                    "code": "COMMAND_NOT_CLAIMED",
                    "error": "此命令未由当前终端实例领取，请等待终端重新获取。",
                }
            ), 409
        if result_can_be_written:
            connection.execute(
                """
                UPDATE terminal_command
                SET status = ?, completed_at = ?, result_detail = ?,
                    result_registration_id = ?, result_source = ?
                WHERE command_id = ? AND device_id = ?
                  AND (
                      status = 'PENDING'
                      OR (
                          status = 'REJECTED' AND result_source = ?
                          AND claimed_at IS NOT NULL AND claimed_terminal = ?
                          AND claimed_instance = ?
                      )
                  )
                """,
                (
                    status,
                    int(time.time()),
                    detail or None,
                    result_registration_id,
                    RESULT_SOURCE_TERMINAL,
                    command_id,
                    device_id,
                    RESULT_SOURCE_SERVER_TIMEOUT,
                    device_id,
                    instance_id,
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
    payload = json.loads(row["payload"])
    payload.pop("_queue_storage_id", None)
    return {
        "command_id": row["command_id"],
        "type": row["command_type"],
        "payload": payload,
        "status": row["status"],
        "created_at": row["created_at"] * 1000,
        "claimed_at": row["claimed_at"] * 1000 if row["claimed_at"] else None,
        "claimed_terminal": row["claimed_terminal"],
        "claimed_instance": row["claimed_instance"],
        "completed_at": row["completed_at"] * 1000 if row["completed_at"] else None,
        "result_source": row["result_source"],
        "result_detail": row["result_detail"],
        "result_registration_id": row["result_registration_id"],
    }


def profile_allows_event_notification(
    settings: sqlite3.Row | None,
    event_type: str,
    notification_categories: list[str] | None = None,
) -> bool:
    if (
        settings is None
        or not settings["notification_enabled"]
    ):
        return False
    categories = notification_categories or [notification_category_for_event_type(event_type)]
    return any(bool(settings[notification_field_for_category(category)]) for category in categories)


def notification_category_for_event_type(event_type: str) -> str:
    if event_type == "PLAYING_CHANGED":
        return "PLAYING_POSITION"
    if event_type in {
        "ONLINE_REGISTRATION_ADDED",
        "ONLINE_CHECK_IN_COMPLETED",
        "ONLINE_CHECK_IN_TIMED_OUT",
        "ONLINE_CHECK_IN_MISSED",
    }:
        return "ONLINE_CHECK_IN"
    if event_type in {
        "NO_SHOW_DEFERRED",
        "NO_SHOW_MOVED_TO_TAIL",
        "NO_SHOW_REMOVED",
        "TEMPORARY_AWAY_EXPIRED",
        "ABSENCE_CHANGED",
    }:
        return "ABSENCE"
    if event_type in {
        "MACHINE_STOPPED",
        "MACHINE_RESTORED",
        "REGISTRATION_OPENED",
        "REGISTRATION_CLOSED",
    }:
        return "MACHINE_STATUS"
    return "QUEUE_CHANGES"


def notification_field_for_category(category: str) -> str:
    return {
        "QUEUE_CHANGES": "notify_queue_changes",
        "PLAYING_POSITION": "notify_playing_position",
        "ONLINE_CHECK_IN": "notify_online_check_in",
        "ABSENCE": "notify_absence",
        "MACHINE_STATUS": "notify_machine_status",
    }[category]


def stored_event_notification_categories(row: sqlite3.Row) -> list[str]:
    try:
        categories = json.loads(row["notification_categories"])
    except (TypeError, ValueError, json.JSONDecodeError):
        categories = []
    valid = [
        category
        for category in categories
        if category in PUBLIC_NOTIFICATION_CATEGORIES
    ] if isinstance(categories, list) else []
    return valid or [notification_category_for_event_type(row["event_type"])]


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
            "machine_stable_id": machine.get("stable_id")
            or default_machine_stable_id(machine_id),
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
                    "common_play_preview": waiting_position.get(
                        "common_play_preview"
                    ),
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
    remote_actions = bool(
        snapshot
        and terminal_online
        and snapshot.get("website_remote_enabled", False)
    )
    online_registration = bool(
        remote_actions
        and snapshot.get("registration_open", True)
        and online_registration_allowed(snapshot)
    )
    return {
        "public_logs": True,
        "local_self_marking": True,
        "registration_qq": True,
        "remote_actions": remote_actions,
        "online_registration": online_registration,
        "transport": "polling",
    }


def online_registration_allowed(snapshot: dict[str, Any]) -> bool:
    queue_rules = snapshot.get("queue_rules")
    rule_allows = not isinstance(queue_rules, dict) or queue_rules.get(
        "allow_online_registration", True
    ) is not False
    return rule_allows and not snapshot_in_closing_grace(snapshot)


def snapshot_in_closing_grace(snapshot: dict[str, Any]) -> bool:
    business_hours = snapshot.get("business_hours")
    return isinstance(business_hours, dict) and business_hours.get(
        "closing_grace", False
    ) is True


def takeover_changes_queue_context(
    current_snapshot: dict[str, Any] | None,
    incoming_snapshot: dict[str, Any],
) -> bool:
    if not isinstance(current_snapshot, dict):
        return True
    if current_snapshot.get("queue_id") != incoming_snapshot.get("queue_id"):
        return True
    return snapshot_machine_identity(current_snapshot) != snapshot_machine_identity(
        incoming_snapshot
    )


def snapshot_machine_identity(snapshot: dict[str, Any]) -> dict[str, str]:
    machines = snapshot.get("machines")
    if not isinstance(machines, dict):
        return {}
    return {
        machine_id: (
            machine.get("stable_id")
            if isinstance(machine, dict) and isinstance(machine.get("stable_id"), str)
            else default_machine_stable_id(machine_id)
        )
        for machine_id, machine in machines.items()
    }


def read_terminal_instance_identity(device_id: str) -> tuple[str, int]:
    instance_id = request.headers.get("X-Terminal-Instance-ID", "").strip()
    generation_source = request.headers.get(
        "X-Terminal-Instance-Generation", ""
    ).strip()
    if not instance_id and not generation_source:
        # Terminals from before runtime-instance coordination use their stable device ID.
        return device_id, 0
    if not instance_id or not generation_source:
        raise ValidationError("终端运行实例信息不完整")
    try:
        instance_id = str(UUID(instance_id))
    except ValueError as error:
        raise ValidationError("终端运行实例编号无效") from error
    try:
        instance_generation = int(generation_source)
    except ValueError as error:
        raise ValidationError("终端运行实例代次无效") from error
    if instance_generation < 1 or instance_generation > 2**63 - 1:
        raise ValidationError("终端运行实例代次无效")
    return instance_id, instance_generation


def terminal_instance_matches(
    snapshot: sqlite3.Row | None,
    device_id: str,
    instance_id: str,
    instance_generation: int,
) -> bool:
    return bool(
        snapshot is not None
        and snapshot["device_id"] == device_id
        and snapshot["instance_id"] == instance_id
        and snapshot["instance_generation"] == instance_generation
    )


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


def authorize_terminal_venue():
    """Bind schema 8 private terminal operations to the verified venue.

    Schema 1-7 clients predate venue identity and remain supported by the
    single-venue compatibility path. A schema 8 client must provide the active
    venue ID so replacing a service behind the same URL cannot expose another
    venue's private profiles or commands during the next identity probe window.
    """
    schema_header = request.headers.get("X-Queue-Schema-Version", "").strip()
    try:
        schema_version = int(schema_header)
    except ValueError:
        return jsonify({"ok": False, "error": "队列协议版本无效"}), 400
    if schema_version not in SUPPORTED_SCHEMA_VERSIONS:
        return jsonify({"ok": False, "error": "不支持的队列协议版本"}), 400
    if schema_version < 8:
        return None

    submitted_venue_id = request.headers.get("X-Queue-Venue-ID", "").strip()
    try:
        submitted_venue_id = str(UUID(submitted_venue_id))
    except (TypeError, ValueError):
        return jsonify(
            {
                "ok": False,
                "code": "VENUE_ID_REQUIRED",
                "error": "终端尚未提供已核对的机厅 ID，私有资料和远程操作已暂停。",
            }
        ), 409
    with open_database() as connection:
        venue = read_active_venue(connection)
    if submitted_venue_id != venue["venue_id"]:
        return jsonify(
            {
                "ok": False,
                "code": "VENUE_MISMATCH",
                "error": (
                    "终端绑定的机厅与当前服务器不一致。为防止读取或执行其他机厅的数据，"
                    "私有资料和远程操作已暂停。"
                ),
            }
        ), 409
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
    machine_configuration_revision = (
        read_integer(
            payload,
            "machine_configuration_revision",
            minimum=1,
            maximum=2**63 - 1,
        )
        if schema_version >= 6
        else 1
    )
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
    app_version = read_optional_string(terminal, "app_version", maximum_length=32)
    terminal_name = (
        read_optional_string(terminal, "name", maximum_length=MAX_TERMINAL_NAME_CHARACTERS)
        if schema_version >= 8
        else None
    )
    venue = normalize_submitted_venue(payload.get("venue")) if schema_version >= 8 else None

    machines_source = payload.get("machines")
    if not isinstance(machines_source, dict):
        raise ValidationError("machines 必须是对象")
    configured_machine_ids = list(MACHINE_NAMES)[: len(machines_source)]
    if (
        not 1 <= len(machines_source) <= MAX_MACHINE_COUNT
        or set(machines_source) != set(configured_machine_ids)
    ):
        raise ValidationError("机台必须按 A 至 J 的顺序连续配置 1 至 10 台")

    machine_groups, default_machine_group_id = normalize_machine_groups(
        payload,
        configured_machine_ids=configured_machine_ids,
        schema_version=schema_version,
    )
    valid_machine_group_ids = {group["id"] for group in machine_groups}

    machines = {
        machine_id: normalize_machine(
            machine_id,
            machines_source[machine_id],
            allow_custom_name=schema_version >= 2,
            schema_version=schema_version,
            valid_machine_group_ids=valid_machine_group_ids,
        )
        for machine_id in configured_machine_ids
    }
    stable_machine_ids = [machine["stable_id"] for machine in machines.values()]
    if len(stable_machine_ids) != len(set(stable_machine_ids)):
        raise ValidationError("机台稳定标识不能重复")
    used_machine_group_ids = {machine["group_id"] for machine in machines.values()}
    if used_machine_group_ids != valid_machine_group_ids:
        raise ValidationError("每个机台分组都必须至少包含一台机台")
    registration_ids = [
        registration["registration_id"]
        for machine in machines.values()
        for registration in all_machine_registrations(machine)
    ]
    if len(registration_ids) != len(set(registration_ids)):
        raise ValidationError("登记公开编号不能重复")

    recent_events = normalize_public_events(payload.get("recent_events", []))
    private_player_profiles = (
        normalize_private_profiles(
            payload.get("private_player_profiles", []),
            schema_version=schema_version,
        )
        if schema_version >= 3
        else None
    )
    private_player_contacts = normalize_private_contacts(
        payload.get("private_player_contacts", []) if schema_version >= 3 else [],
        machines,
        private_player_profiles or [],
        recent_events,
    )
    attach_public_registration_contacts(machines, private_player_contacts)

    return {
        "schema_version": PUBLIC_SCHEMA_VERSION,
        "queue_id": queue_id,
        "revision": revision,
        "machine_configuration_revision": machine_configuration_revision,
        "captured_at": captured_at,
        "registration_open": registration_open,
        "website_remote_enabled": website_remote_enabled,
        "onebot_sync_enabled": onebot_sync_enabled,
        "queue_rules": queue_rules,
        "business_hours": business_hours,
        "terminal": {
            "id": device_id,
            "name": terminal_name,
            "online": True,
            "app_version": app_version,
            "last_seen_at": captured_at,
        },
        "venue": venue,
        "machine_groups": machine_groups,
        "default_machine_group_id": default_machine_group_id,
        "machines": machines,
        "recent_events": recent_events,
        "private_player_contacts": private_player_contacts,
        "private_player_profiles": private_player_profiles,
    }


def normalize_private_profiles(
    source: Any, *, schema_version: int
) -> list[dict[str, Any]]:
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
        if schema_version >= 5:
            qq_visibility = read_choice(value, "qq_visibility", QQ_VISIBILITIES)
            notification_enabled = read_boolean(value, "notification_enabled")
            notify_queue_changes = read_boolean(value, "notify_queue_changes")
            notify_playing_position = read_boolean(value, "notify_playing_position")
            notify_online_check_in = read_boolean(value, "notify_online_check_in")
            notify_absence = read_boolean(value, "notify_absence")
            notify_machine_status = read_boolean(value, "notify_machine_status")
            setup_version = read_integer(
                value, "setup_version", minimum=0, maximum=2**31 - 1
            )
            profile_revision = read_integer(
                value, "profile_revision", minimum=1, maximum=2**63 - 1
            )
        else:
            qq_visibility = "TERMINAL_ONLY"
            notification_enabled = True
            notify_queue_changes = True
            notify_playing_position = False
            notify_online_check_in = True
            notify_absence = True
            notify_machine_status = False
            setup_version = 0
            profile_revision = 1
        public_player_id = (
            read_optional_player_public_id(value, "public_player_id")
            if schema_version >= 8
            else None
        )
        if schema_version >= 8:
            web_account_bound = (
                read_boolean(value, "web_account_bound")
                if "web_account_bound" in value else False
            )
            terminal_editing_allowed = (
                read_boolean(value, "terminal_editing_allowed")
                if "terminal_editing_allowed" in value else True
            )
            visited_venues_public = (
                read_boolean(value, "visited_venues_public")
                if "visited_venues_public" in value else True
            )
            web_profile_revision = (
                read_integer(
                    value, "web_profile_revision", minimum=0, maximum=2**63 - 1
                )
                if "web_profile_revision" in value else 0
            )
        else:
            web_account_bound = False
            terminal_editing_allowed = True
            visited_venues_public = True
            web_profile_revision = 0
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
                "qq_visibility": qq_visibility,
                "notification_enabled": notification_enabled,
                "notify_queue_changes": notify_queue_changes,
                "notify_playing_position": notify_playing_position,
                "notify_online_check_in": notify_online_check_in,
                "notify_absence": notify_absence,
                "notify_machine_status": notify_machine_status,
                "setup_version": setup_version,
                "profile_revision": profile_revision,
                "public_player_id": public_player_id,
                "web_account_bound": web_account_bound,
                "terminal_editing_allowed": terminal_editing_allowed,
                "visited_venues_public": visited_venues_public,
                "web_profile_revision": web_profile_revision,
                "legacy_revision": schema_version < 5,
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


def normalize_submitted_venue(source: Any) -> dict[str, Any] | None:
    if source is None:
        return None
    if not isinstance(source, dict) or set(source) != {"id"}:
        raise ValidationError("venue 必须只包含机厅 ID")
    return {"id": read_uuid(source, "id")}


def read_optional_player_public_id(source: dict[str, Any], key: str) -> str | None:
    value = source.get(key)
    if value is None:
        return None
    if not isinstance(value, str) or PLAYER_PUBLIC_ID_PATTERN.fullmatch(value) is None:
        raise ValidationError("玩家编号必须是六位数字")
    return value


def normalize_private_contacts(
    source: Any,
    machines: dict[str, dict[str, Any]],
    private_profiles: list[dict[str, Any]],
    recent_events: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    if not isinstance(source, list):
        raise ValidationError("private_player_contacts 必须是数组")
    if len(source) > MAX_PRIVATE_CONTACTS:
        raise ValidationError("QQ 绑定数量超过限制")

    registrations = {
        registration["registration_id"]: registration
        for machine in machines.values()
        for registration in all_machine_registrations(machine)
    }
    event_registration_ids = {
        registration_id
        for event in recent_events
        for registration_id in event["registration_ids"]
    }
    profiles = {profile["profile_id"]: profile for profile in private_profiles}
    contacts: list[dict[str, Any]] = []
    for value in source:
        if not isinstance(value, dict):
            raise ValidationError("private_player_contacts 包含无效绑定")
        registration_id = read_public_id(value, "registration_id")
        profile_id = read_uuid(value, "profile_id")
        qq_number = value.get("qq_number")
        if not isinstance(qq_number, str) or QQ_NUMBER_PATTERN.fullmatch(qq_number) is None:
            raise ValidationError("qq_number 必须是 5 至 12 位数字")
        registration = registrations.get(registration_id)
        if registration is None and registration_id not in event_registration_ids:
            raise ValidationError("QQ 绑定必须引用当前登记或最近事件中的登记")
        if registration is not None and registration["registration_type"] != "PLAYER_PROFILE":
            raise ValidationError("临时登记不能包含 QQ 绑定")
        profile = profiles.get(profile_id)
        if profile is None or profile["qq_number"] != qq_number:
            raise ValidationError("QQ 绑定与玩家资料不一致")
        contacts.append(
            {
                "registration_id": registration_id,
                "profile_id": profile_id,
                "qq_number": qq_number,
                "public_qq": profile["qq_visibility"] == "PUBLIC_WEBSITE",
            }
        )

    registration_ids = [contact["registration_id"] for contact in contacts]
    if len(registration_ids) != len(set(registration_ids)):
        raise ValidationError("QQ 绑定的登记编号不能重复")
    current_profile_ids = [
        contact["profile_id"]
        for contact in contacts
        if contact["registration_id"] in registrations
    ]
    if len(current_profile_ids) != len(set(current_profile_ids)):
        raise ValidationError("同一玩家资料不能关联多份当前登记")
    return contacts


def normalize_public_events(source: Any) -> list[dict[str, Any]]:
    if not isinstance(source, list):
        raise ValidationError(
            "最近事件列表无效：recent_events 必须是数组。",
            code="invalid_recent_events",
            field="recent_events",
        )
    if len(source) > MAX_EVENTS_PER_SNAPSHOT:
        raise ValidationError(
            f"最近事件列表无效：最多只能上传 {MAX_EVENTS_PER_SNAPSHOT} 条事件。",
            code="too_many_recent_events",
            field="recent_events",
        )

    events = []
    first_index_by_event_id: dict[str, int] = {}
    for index, value in enumerate(source):
        try:
            event = normalize_public_event(value)
            previous_index = first_index_by_event_id.get(event["event_id"])
            if previous_index is not None:
                raise ValidationError(
                    f"事件编号重复：与最近事件第 {previous_index + 1} 条使用了同一编号。",
                    code="duplicate_recent_event_id",
                    field="event_id",
                )
            first_index_by_event_id[event["event_id"]] = index
            events.append(event)
        except ValidationError as error:
            raise contextualize_public_event_error(error, index, value) from error
    return events


def contextualize_public_event_error(
    error: ValidationError,
    index: int,
    source: Any,
) -> ValidationError:
    field = f"recent_events[{index}]"
    if error.field:
        field = f"{field}.{error.field}"

    title = None
    event_id = None
    if isinstance(source, dict):
        raw_title = source.get("title")
        if isinstance(raw_title, str):
            normalized_title = raw_title.strip()
            if normalized_title and normalized_title.isprintable():
                title = normalized_title[:120]
        raw_event_id = source.get("event_id")
        if isinstance(raw_event_id, str):
            normalized_event_id = raw_event_id.strip()
            if normalized_event_id and normalized_event_id.isprintable():
                event_id = normalized_event_id[:64]

    label = f"最近事件第 {index + 1} 条"
    if title is not None:
        label += f"《{title}》"
    message_lines = [f"{label}的{error}", f"字段：{field}"]
    if event_id is not None:
        message_lines.append(f"事件编号：{event_id}")

    details: dict[str, Any] = {
        "event_index": index,
        "event_number": index + 1,
    }
    if title is not None:
        details["event_title"] = title
    if event_id is not None:
        details["event_id"] = event_id
    return ValidationError(
        "\n".join(message_lines),
        code=error.code or "invalid_recent_event",
        field=field,
        details=details,
    )


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


def normalize_venue_business_hours(source: Any) -> dict[str, Any]:
    """Validate the venue-owned weekly schedule, not its computed live state."""
    if not isinstance(source, dict):
        raise ValidationError("营业时间配置必须是对象")
    allowed_fields = {"enabled", "use_weekly_schedule", "default_hours", "weekly_hours"}
    if set(source) - allowed_fields:
        raise ValidationError("营业时间配置包含不支持的字段")
    enabled = source.get("enabled", False)
    use_weekly = source.get("use_weekly_schedule", False)
    if type(enabled) is not bool or type(use_weekly) is not bool:
        raise ValidationError("营业时间开关必须是布尔值")

    def normalize_daily(value: Any, field: str) -> dict[str, int]:
        if not isinstance(value, dict) or set(value) - {"opening_minutes", "closing_minutes"}:
            raise ValidationError(f"{field} 必须包含开店和闭店分钟数")
        opening = value.get("opening_minutes", 600)
        closing = value.get("closing_minutes", 1320)
        if (
            type(opening) is not int
            or type(closing) is not int
            or not 0 <= opening < 1440
            or not 0 <= closing < 1440
        ):
            raise ValidationError(f"{field} 的时间必须是 0 至 1439 分钟")
        return {"opening_minutes": opening, "closing_minutes": closing}

    default_hours = normalize_daily(source.get("default_hours", {}), "default_hours")
    weekly_source = source.get("weekly_hours", {})
    if not isinstance(weekly_source, dict):
        raise ValidationError("weekly_hours 必须是对象")
    valid_days = {
        "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY",
        "FRIDAY", "SATURDAY", "SUNDAY",
    }
    if set(weekly_source) - valid_days:
        raise ValidationError("weekly_hours 包含无效的星期")
    weekly_hours = {
        day: normalize_daily(weekly_source.get(day, default_hours), f"weekly_hours.{day}")
        for day in sorted(valid_days)
    }
    return {
        "enabled": enabled,
        "use_weekly_schedule": use_weekly,
        "default_hours": default_hours,
        "weekly_hours": weekly_hours,
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
        raise ValidationError(
            "内容无效：每条事件都必须是对象。",
            code="invalid_recent_event",
        )
    machine_id = source.get("machine_id")
    if machine_id is not None and machine_id not in MACHINE_NAMES:
        raise ValidationError(
            "机台编号无效：必须为空或使用 A 至 J 中已配置的编号。",
            code="invalid_recent_event_machine_id",
            field="machine_id",
        )
    machine_stable_id = source.get("machine_stable_id")
    if machine_stable_id is not None and (
        not isinstance(machine_stable_id, str)
        or MACHINE_INTERNAL_ID_PATTERN.fullmatch(machine_stable_id) is None
    ):
        raise ValidationError(
            "机台稳定标识无效：必须为空或使用 32 位小写十六进制标识。",
            code="invalid_recent_event_machine_stable_id",
            field="machine_stable_id",
        )

    raw_machine_name = source.get("machine_name")
    if (
        machine_id is None
        and machine_stable_id is None
        and raw_machine_name == "null"
    ):
        # Terminal 0.10.0/0.10.1 could deserialize a JSON null as the literal
        # string "null" after a restart.  Accept only that exact historical
        # shape; all other machine identity attached to a system event remains
        # invalid.
        machine_name = None
    else:
        try:
            machine_name = read_optional_string(source, "machine_name", 120)
        except ValidationError as error:
            raise ValidationError(
                f"机台名称无效：{error}。",
                code="invalid_recent_event_machine_name",
                field="machine_name",
            ) from error
    machine_name = compact_middle_dots(machine_name)
    if machine_id is None and (machine_stable_id is not None or machine_name is not None):
        if machine_stable_id is not None:
            identity_label = "机台稳定标识"
            identity_field = "machine_stable_id"
        else:
            identity_label = "机台名称"
            identity_field = "machine_name"
        raise ValidationError(
            f"{identity_label}无效：系统事件不应关联单一机台。",
            code="system_event_has_machine_identity",
            field=identity_field,
        )
    registration_ids = source.get("registration_ids")
    if (
        not isinstance(registration_ids, list)
        or len(registration_ids) > MAX_EVENT_REGISTRATION_IDS
    ):
        raise ValidationError(
            f"登记编号列表无效：必须是数组且不能超过 {MAX_EVENT_REGISTRATION_IDS} 项。",
            code="invalid_recent_event_registration_ids",
            field="registration_ids",
        )
    normalized_registration_ids = []
    first_registration_index_by_id: dict[str, int] = {}
    for registration_index, value in enumerate(registration_ids):
        try:
            registration_id = read_public_id(
                {"registration_id": value}, "registration_id"
            )
        except ValidationError as error:
            raise ValidationError(
                "登记编号无效：必须使用 24 位小写十六进制公开编号。",
                code="invalid_recent_event_registration_id",
                field=f"registration_ids[{registration_index}]",
            ) from error
        previous_index = first_registration_index_by_id.get(registration_id)
        if previous_index is not None:
            raise ValidationError(
                f"登记编号重复：与列表第 {previous_index + 1} 项相同。",
                code="duplicate_recent_event_registration_id",
                field=f"registration_ids[{registration_index}]",
            )
        first_registration_index_by_id[registration_id] = registration_index
        normalized_registration_ids.append(registration_id)
    operation_source = source.get("operation_source", "ON_SITE_TERMINAL")
    if not isinstance(operation_source, str) or operation_source not in OPERATION_SOURCES:
        raise ValidationError(
            "操作来源无效：终端上传了不支持的来源类型。",
            code="invalid_recent_event_operation_source",
            field="operation_source",
        )
    try:
        event_type = read_choice(source, "type", PUBLIC_EVENT_TYPES)
    except ValidationError as error:
        raise ValidationError(
            "事件类型无效：终端上传了不支持的事件类型。",
            code="invalid_recent_event_type",
            field="type",
        ) from error
    categories_source = source.get("notification_categories")
    if categories_source is None:
        notification_categories = [notification_category_for_event_type(event_type)]
    else:
        if not isinstance(categories_source, list) or not 1 <= len(categories_source) <= 5:
            raise ValidationError(
                "通知类别无效：必须包含 1 至 5 个类别。",
                code="invalid_recent_event_notification_categories",
                field="notification_categories",
            )
        if any(
            not isinstance(category, str)
            or category not in PUBLIC_NOTIFICATION_CATEGORIES
            for category in categories_source
        ):
            invalid_index = next(
                index
                for index, category in enumerate(categories_source)
                if not isinstance(category, str)
                or category not in PUBLIC_NOTIFICATION_CATEGORIES
            )
            raise ValidationError(
                "通知类别无效：终端上传了不支持的类别。",
                code="invalid_recent_event_notification_category",
                field=f"notification_categories[{invalid_index}]",
            )
        if len(categories_source) != len(set(categories_source)):
            seen_categories: set[str] = set()
            duplicate_index = 0
            for index, category in enumerate(categories_source):
                if category in seen_categories:
                    duplicate_index = index
                    break
                seen_categories.add(category)
            raise ValidationError(
                "通知类别重复：同一类别只能填写一次。",
                code="duplicate_recent_event_notification_category",
                field=f"notification_categories[{duplicate_index}]",
            )
        notification_categories = categories_source
    try:
        event_id = read_uuid(source, "event_id")
    except ValidationError as error:
        raise ValidationError(
            "事件编号无效：必须使用 UUID。",
            code="invalid_recent_event_id",
            field="event_id",
        ) from error
    try:
        occurred_at = read_integer(source, "occurred_at", minimum=1)
    except ValidationError as error:
        raise ValidationError(
            "发生时间无效：必须是正整数时间戳。",
            code="invalid_recent_event_occurred_at",
            field="occurred_at",
        ) from error
    try:
        title = compact_middle_dots(read_string(source, "title", maximum_length=120))
    except ValidationError as error:
        raise ValidationError(
            "标题无效：必须是 1 至 120 个字符的文本。",
            code="invalid_recent_event_title",
            field="title",
        ) from error
    try:
        detail = compact_middle_dots(read_string(source, "detail", maximum_length=2_000))
    except ValidationError as error:
        raise ValidationError(
            "说明无效：必须是 1 至 2000 个字符的文本。",
            code="invalid_recent_event_detail",
            field="detail",
        ) from error
    return {
        "event_id": event_id,
        "occurred_at": occurred_at,
        "machine_id": machine_id,
        "machine_stable_id": machine_stable_id,
        "machine_name": machine_name,
        "type": event_type,
        "title": title,
        "detail": detail,
        "operation_source": operation_source,
        "notification_categories": notification_categories,
        "registration_ids": normalized_registration_ids,
    }


def normalize_machine_groups(
    payload: dict[str, Any],
    *,
    configured_machine_ids: list[str],
    schema_version: int,
) -> tuple[list[dict[str, str]], str]:
    if schema_version < 7:
        return (
            [{"id": DEFAULT_MACHINE_GROUP_ID, "name": "分组 1"}],
            DEFAULT_MACHINE_GROUP_ID,
        )

    source = payload.get("machine_groups")
    if not isinstance(source, list) or not 1 <= len(source) <= len(configured_machine_ids):
        raise ValidationError("机台分组数量必须为 1 至当前机台数量")
    groups: list[dict[str, str]] = []
    for index, item in enumerate(source):
        if not isinstance(item, dict) or set(item) != {"id", "name"}:
            raise ValidationError(f"第 {index + 1} 个机台分组字段不完整")
        group_id = read_machine_internal_id(item, "id")
        name = read_string(item, "name", MAX_MACHINE_GROUP_NAME_CHARACTERS)
        if not name.isprintable():
            raise ValidationError(f"第 {index + 1} 个机台分组名称内容无效")
        groups.append({"id": group_id, "name": name})
    group_ids = [group["id"] for group in groups]
    if len(group_ids) != len(set(group_ids)):
        raise ValidationError("机台分组标识不能重复")
    default_group_id = read_machine_internal_id(payload, "default_machine_group_id")
    if default_group_id not in set(group_ids):
        raise ValidationError("默认机台分组不存在")
    return groups, default_group_id


def default_machine_stable_id(machine_id: str) -> str:
    return f"{list(MACHINE_NAMES).index(machine_id) + 1:032x}"


def normalize_machine(
    machine_id: str,
    source: Any,
    *,
    allow_custom_name: bool = False,
    schema_version: int = 1,
    valid_machine_group_ids: set[str] | None = None,
) -> dict[str, Any]:
    if not isinstance(source, dict):
        raise ValidationError(f"机台 {machine_id} 必须是对象")

    name = normalize_machine_name(machine_id, source, allow_custom_name)
    configuration = normalize_machine_configuration(
        machine_id,
        source,
        name=name,
        schema_version=schema_version,
    )
    stable_id = (
        read_machine_internal_id(source, "stable_id")
        if schema_version >= 7
        else default_machine_stable_id(machine_id)
    )
    group_id = (
        read_machine_internal_id(source, "group_id")
        if schema_version >= 7
        else DEFAULT_MACHINE_GROUP_ID
    )
    if valid_machine_group_ids is not None and group_id not in valid_machine_group_ids:
        raise ValidationError(f"机台 {machine_id} 引用了不存在的机台分组")
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
    if configuration["capacity"] == 1 and len(playing) > 1:
        raise ValidationError(f"机台 {machine_id} 的游玩容量为 1，游玩位置不能超过 1 个登记")

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

    registrations_by_id = {
        registration["registration_id"]: registration
        for registration in playing
    }
    registrations_by_id.update(
        {
            registration["registration_id"]: registration
            for position in waiting_positions
            for registration in position["registrations"]
        }
    )
    for position in waiting_positions:
        preview = position["common_play_preview"]
        if preview is None:
            continue
        previewed = registrations_by_id.get(preview["registration_id"])
        if previewed is None:
            raise ValidationError(f"机台 {machine_id} 的共同游玩预览登记不存在")
        position_registration_ids = {
            registration["registration_id"]
            for registration in position["registrations"]
        }
        if preview["registration_id"] in position_registration_ids:
            raise ValidationError(f"机台 {machine_id} 的共同游玩预览不能重复当前位置登记")
        if previewed["display_id"] != preview["display_id"]:
            raise ValidationError(f"机台 {machine_id} 的共同游玩预览昵称不一致")
        if (
            len(position["registrations"]) != 1
            or position["registrations"][0]["preference"] != "OPEN_TO_JOIN"
            or position["registrations"][0]["fixed_pair"]
            or previewed["preference"] != "OPEN_TO_JOIN"
            or previewed["fixed_pair"]
        ):
            raise ValidationError(f"机台 {machine_id} 的共同游玩预览与游玩偏好不一致")

    if configuration["capacity"] == 1:
        registrations = [
            *playing,
            *[
                registration
                for position in waiting_positions
                for registration in position["registrations"]
            ],
        ]
        if any(
            registration["preference"] != "SOLO" or registration["fixed_pair"]
            for registration in registrations
        ):
            raise ValidationError(
                f"机台 {machine_id} 的游玩容量为 1，登记必须使用单人游玩且不能组成固定组合"
            )
        if any(len(position["registrations"]) != 1 for position in waiting_positions):
            raise ValidationError(f"机台 {machine_id} 的游玩容量为 1，等待位置只能包含 1 个登记")
        if any(position["common_play_preview"] is not None for position in waiting_positions):
            raise ValidationError(f"机台 {machine_id} 的游玩容量为 1，不能包含共同游玩预览")

    playing_started_at = read_optional_integer(
        source, "playing_started_at", minimum=1
    )
    new_registration_estimated_wait_minutes = read_optional_integer(
        source,
        "new_registration_estimated_wait_minutes",
        minimum=0,
        maximum=MAX_ESTIMATED_WAIT_MINUTES,
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
        "stable_id": stable_id,
        "group_id": group_id,
        "name": name,
        "remark": configuration["remark"],
        "configuration": configuration,
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


def normalize_machine_configuration(
    machine_id: str,
    source: dict[str, Any],
    *,
    name: str,
    schema_version: int,
) -> dict[str, Any]:
    suffix = f"·机台 {machine_id}"
    remark = name[: -len(suffix)] if name.endswith(suffix) else name
    if schema_version < 6:
        return {
            "remark": remark,
            "game_type": "MAIMAI_DX",
            "custom_game_type": None,
            "server": "HIDDEN",
            "custom_server": None,
            "game_version": None,
            "game_version_visible": False,
            "capacity": 2,
            "solo_round_minutes": 12,
            "shared_round_minutes": 15,
        }

    submitted_remark = read_string(
        source,
        "remark",
        maximum_length=MAX_MACHINE_REMARK_CHARACTERS,
    )
    if submitted_remark != remark:
        raise ValidationError(f"机台 {machine_id} 的备注与名称不一致")
    configuration = source.get("configuration")
    if not isinstance(configuration, dict):
        raise ValidationError(f"机台 {machine_id} 的 configuration 必须是对象")
    allowed_fields = {
        "game_type",
        "custom_game_type",
        "server",
        "custom_server",
        "game_version",
        "game_version_visible",
        "capacity",
        "solo_round_minutes",
        "shared_round_minutes",
    }
    if set(configuration) != allowed_fields:
        raise ValidationError(f"机台 {machine_id} 的 configuration 字段不完整")

    game_type = read_choice(configuration, "game_type", MACHINE_GAME_TYPES)
    custom_game_type = read_optional_string(
        configuration,
        "custom_game_type",
        MAX_MACHINE_TYPE_CHARACTERS,
    )
    if game_type == "OTHER" and custom_game_type is None:
        raise ValidationError(f"机台 {machine_id} 选择其他游戏类型时必须填写名称")
    if game_type != "OTHER" and custom_game_type is not None:
        raise ValidationError(f"机台 {machine_id} 仅能为其他游戏类型填写自定义名称")

    server = read_choice(configuration, "server", MACHINE_SERVERS)
    custom_server = read_optional_string(
        configuration,
        "custom_server",
        MAX_MACHINE_SERVER_CHARACTERS,
    )
    if game_type not in SERVER_CONFIGURABLE_GAME_TYPES and server != "HIDDEN":
        raise ValidationError(f"机台 {machine_id} 的游戏类型不支持配置服务器")
    if server == "OTHER" and custom_server is None:
        raise ValidationError(f"机台 {machine_id} 选择其他服务器时必须填写名称")
    if server != "OTHER" and custom_server is not None:
        raise ValidationError(f"机台 {machine_id} 仅能为其他服务器填写自定义名称")

    game_version = read_optional_string(
        configuration,
        "game_version",
        MAX_GAME_VERSION_CHARACTERS,
    )
    game_version_visible = read_boolean(configuration, "game_version_visible")
    if game_version_visible and game_version is None:
        raise ValidationError(f"机台 {machine_id} 显示游戏版本前必须填写版本")
    capacity = read_integer(configuration, "capacity", minimum=1, maximum=2)
    return {
        "remark": remark,
        "game_type": game_type,
        "custom_game_type": custom_game_type,
        "server": server,
        "custom_server": custom_server,
        "game_version": game_version if game_version_visible else None,
        "game_version_visible": game_version_visible,
        "capacity": capacity,
        "solo_round_minutes": read_integer(
            configuration,
            "solo_round_minutes",
            minimum=1,
            maximum=MAX_PLANNED_ROUND_MINUTES,
        ),
        "shared_round_minutes": read_integer(
            configuration,
            "shared_round_minutes",
            minimum=1,
            maximum=MAX_PLANNED_ROUND_MINUTES,
        ),
    }


def normalize_machine_name(
    machine_id: str, source: dict[str, Any], allow_custom_name: bool
) -> str:
    if not allow_custom_name:
        return MACHINE_NAMES[machine_id]

    suffix = f"·机台 {machine_id}"
    legacy_suffix = f" · 机台 {machine_id}"
    name = read_string(
        source,
        "name",
        maximum_length=MAX_MACHINE_REMARK_CHARACTERS + len(legacy_suffix),
    )
    name = compact_middle_dots(name)
    if not name.endswith(suffix):
        raise ValidationError(f"机台 {machine_id} 名称必须以“{suffix.strip()}”结尾")
    remark = name[: -len(suffix)]
    if not remark or remark != remark.strip() or len(remark) > MAX_MACHINE_REMARK_CHARACTERS:
        raise ValidationError(f"机台 {machine_id} 备注必须为 1 至 8 个字符")
    return f"{remark}{suffix}"


def compact_middle_dots(value: Any) -> Any:
    if not isinstance(value, str):
        return value
    return MIDDLE_DOT_SPACING_PATTERN.sub("·", value)


def compact_public_middle_dots(value: Any) -> Any:
    if isinstance(value, dict):
        return {key: compact_public_middle_dots(item) for key, item in value.items()}
    if isinstance(value, list):
        return [compact_public_middle_dots(item) for item in value]
    return compact_middle_dots(value)


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
            source,
            "estimated_wait_minutes",
            minimum=0,
            maximum=MAX_ESTIMATED_WAIT_MINUTES,
        ),
        "registrations": registrations,
        "common_play_preview": normalize_common_play_preview(
            source.get("common_play_preview")
        ),
    }


def normalize_common_play_preview(source: Any) -> dict[str, str] | None:
    if source is None:
        return None
    if not isinstance(source, dict):
        raise ValidationError("共同游玩预览必须是对象或 null")
    return {
        "registration_id": read_public_id(source, "registration_id"),
        "display_id": read_string(source, "display_id", maximum_length=18),
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
        raise ValidationError("登记不能同时处于暂缓一次和暂时离开状态")
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
        raise ValidationError("待签到的线上登记不能同时暂缓一次或暂时离开")
    created_at = read_integer(source, "created_at", minimum=1)
    online_check_in_started_at = source.get("online_check_in_started_at", created_at)
    if type(online_check_in_started_at) is not int or online_check_in_started_at < 1:
        raise ValidationError("online_check_in_started_at 数值无效")

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
        "created_at": created_at,
        "online_check_in_started_at": online_check_in_started_at,
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
    machines: dict[str, dict[str, Any]], contacts: list[dict[str, Any]]
) -> None:
    qq_by_registration_id = {
        contact["registration_id"]: contact["qq_number"]
        for contact in contacts
        if contact["public_qq"]
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


def read_optional_semantic_version(source: dict[str, Any], key: str) -> str | None:
    value = source.get(key)
    if value is None:
        return None
    normalized = normalize_semantic_version(value)
    if normalized is None:
        raise ValidationError(f"{key} 必须是有效的语义版本号")
    return normalized[0]


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


def read_machine_internal_id(source: dict[str, Any], key: str) -> str:
    value = source.get(key)
    if not isinstance(value, str) or MACHINE_INTERNAL_ID_PATTERN.fullmatch(value) is None:
        raise ValidationError(f"{key} 机台内部标识无效")
    return value


def read_optional_public_id(source: dict[str, Any], key: str) -> str | None:
    if source.get(key) is None:
        return None
    return read_public_id(source, key)


app = create_app()


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.getenv("PORT", "8080")), debug=False)
