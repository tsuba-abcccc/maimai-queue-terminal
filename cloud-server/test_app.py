import copy
import sqlite3
import tempfile
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from threading import Barrier
from uuid import UUID

from app import create_app


class QueueStatusApiTest(unittest.TestCase):
    profile_id = "00000000-0000-0000-0000-000000000901"
    sync_token = "s" * 32
    bot_token = "b" * 32

    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.database_path = str(Path(self.temporary_directory.name) / "queue.db")
        self.app = create_app(
            {
                "TESTING": True,
                "DATABASE_PATH": self.database_path,
                "SYNC_TOKEN": self.sync_token,
                "BOT_TOKEN": self.bot_token,
                "ALLOWED_DEVICE_ID": "terminal-1",
                "ONLINE_TIMEOUT_SECONDS": 90,
                "EVENT_RECIPIENT_RETENTION_SECONDS": 30 * 24 * 60 * 60,
            }
        )
        self.client = self.app.test_client()
        self.headers = {
            "Authorization": f"Bearer {self.sync_token}",
            "X-Device-ID": "terminal-1",
            "X-Queue-Schema-Version": "1",
        }
        self.bot_headers = {"Authorization": f"Bearer {self.bot_token}"}

    def tearDown(self):
        self.temporary_directory.cleanup()

    def test_requires_terminal_authentication(self):
        response = self.client.post("/api/queue-status", json=self.snapshot())

        self.assertEqual(401, response.status_code)

    def test_invalid_token_configuration_fails_closed_but_health_stays_available(self):
        self.app.config["SYNC_TOKEN"] = "s" * 31
        short_sync = self.client.post(
            "/api/queue-status",
            json=self.snapshot(),
            headers={**self.headers, "Authorization": f"Bearer {'s' * 31}"},
        )
        health = self.client.get("/healthz")

        self.app.config["SYNC_TOKEN"] = self.sync_token
        self.app.config["BOT_TOKEN"] = "b" * 31
        terminal_with_invalid_bot = self.client.post(
            "/api/queue-status", json=self.snapshot(), headers=self.headers
        )
        short_bot = self.client.get(
            "/api/queue-bot/profiles",
            headers={"Authorization": f"Bearer {'b' * 31}"},
        )
        self.app.config["BOT_TOKEN"] = ""
        missing_bot = self.client.get(
            "/api/queue-bot/profiles",
            headers={"Authorization": "Bearer any-value"},
        )
        self.app.config["SYNC_TOKEN"] = ""
        self.app.config["BOT_TOKEN"] = self.bot_token
        bot_with_missing_sync = self.client.get(
            "/api/queue-bot/profiles", headers=self.bot_headers
        )
        missing_sync = self.client.post(
            "/api/queue-status",
            json=self.snapshot(revision=5),
            headers={"Authorization": "Bearer any-value", "X-Device-ID": "terminal-1"},
        )

        self.assertEqual(503, short_sync.status_code)
        self.assertEqual(
            {"ok": False, "error": "服务器鉴权配置无效"}, short_sync.get_json()
        )
        self.assertNotIn("s" * 31, short_sync.get_data(as_text=True))
        self.assertEqual(200, health.status_code)
        self.assertEqual(204, terminal_with_invalid_bot.status_code)
        self.assertEqual(503, short_bot.status_code)
        self.assertEqual(503, missing_bot.status_code)
        self.assertEqual(
            {"ok": False, "error": "服务器鉴权配置无效"}, missing_bot.get_json()
        )
        self.assertEqual(200, bot_with_missing_sync.status_code)
        self.assertEqual(503, missing_sync.status_code)

    def test_identical_terminal_and_bot_tokens_disable_both_private_roles(self):
        shared_token = "x" * 32
        self.app.config["SYNC_TOKEN"] = shared_token
        self.app.config["BOT_TOKEN"] = shared_token

        terminal_response = self.client.post(
            "/api/queue-status",
            json=self.snapshot(),
            headers={**self.headers, "Authorization": f"Bearer {shared_token}"},
        )
        bot_response = self.client.get(
            "/api/queue-bot/profiles",
            headers={"Authorization": f"Bearer {shared_token}"},
        )

        self.assertEqual(503, terminal_response.status_code)
        self.assertEqual(503, bot_response.status_code)
        self.assertEqual(terminal_response.get_json(), bot_response.get_json())
        self.assertNotIn(shared_token, terminal_response.get_data(as_text=True))

    def test_token_minimum_uses_utf8_byte_length(self):
        multibyte_token = "é" * 16
        self.assertEqual(32, len(multibyte_token.encode("utf-8")))
        self.app.config["SYNC_TOKEN"] = multibyte_token

        response = self.client.post(
            "/api/queue-status",
            json=self.snapshot(),
            headers={**self.headers, "Authorization": f"Bearer {multibyte_token}"},
        )

        self.assertEqual(204, response.status_code)

    def test_migrates_command_claim_and_event_recipient_retention_columns(self):
        legacy_database_path = str(
            Path(self.temporary_directory.name) / "legacy-queue.db"
        )
        connection = sqlite3.connect(legacy_database_path)
        try:
            connection.executescript(
                """
                CREATE TABLE terminal_command (
                    command_id TEXT PRIMARY KEY,
                    device_id TEXT NOT NULL,
                    command_type TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    status TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    completed_at INTEGER,
                    result_detail TEXT
                );
                CREATE TABLE queue_event_recipient (
                    queue_id TEXT NOT NULL,
                    event_id TEXT NOT NULL,
                    registration_id TEXT NOT NULL,
                    profile_id TEXT NOT NULL,
                    qq_number TEXT NOT NULL,
                    PRIMARY KEY(queue_id, event_id, registration_id)
                );
                CREATE TABLE queue_event (
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
                );
                """
            )
            connection.execute(
                """
                INSERT INTO terminal_command
                    (command_id, device_id, command_type, payload, status,
                     created_at, completed_at, result_detail)
                VALUES (?, ?, ?, ?, 'REJECTED', ?, ?, ?)
                """,
                (
                    "00000000-0000-0000-0000-000000000700",
                    "terminal-1",
                    "UPDATE_PLAYER_PROFILE",
                    '{"profile_id":"00000000-0000-0000-0000-000000000901"}',
                    100,
                    200,
                    "终端未在有效时间内处理这次修改，请重新提交。",
                ),
            )
            connection.execute(
                """
                INSERT INTO queue_event
                    (queue_id, event_id, occurred_at, machine_id, event_type,
                     title, detail, registration_ids)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    "00000000-0000-0000-0000-000000000001",
                    "00000000-0000-0000-0000-000000000101",
                    100,
                    "A",
                    "OTHER",
                    "旧事件",
                    "旧事件说明。",
                    "[]",
                ),
            )
            connection.execute(
                """
                INSERT INTO queue_event_recipient
                    (queue_id, event_id, registration_id, profile_id, qq_number)
                VALUES (?, ?, ?, ?, ?)
                """,
                (
                    "00000000-0000-0000-0000-000000000001",
                    "00000000-0000-0000-0000-000000000101",
                    "a" * 24,
                    self.profile_id,
                    "12345678",
                ),
            )
            connection.commit()
        finally:
            connection.close()

        create_app(
            {
                "TESTING": True,
                "DATABASE_PATH": legacy_database_path,
                "SYNC_TOKEN": self.sync_token,
                "BOT_TOKEN": self.bot_token,
            }
        )
        connection = sqlite3.connect(legacy_database_path)
        try:
            command_columns = {
                row[1] for row in connection.execute("PRAGMA table_info(terminal_command)")
            }
            recipient_columns = {
                row[1]
                for row in connection.execute("PRAGMA table_info(queue_event_recipient)")
            }
            event_columns = {
                row[1] for row in connection.execute("PRAGMA table_info(queue_event)")
            }
            result_source = connection.execute(
                "SELECT result_source FROM terminal_command"
            ).fetchone()[0]
            stored_at = connection.execute(
                "SELECT stored_at FROM queue_event_recipient"
            ).fetchone()[0]
            operation_source = connection.execute(
                "SELECT operation_source FROM queue_event"
            ).fetchone()[0]
        finally:
            connection.close()

        self.assertTrue({"claimed_at", "claimed_terminal", "result_source"} <= command_columns)
        self.assertIn("stored_at", recipient_columns)
        self.assertIn("operation_source", event_columns)
        self.assertEqual("SERVER_TIMEOUT", result_source)
        self.assertEqual("ON_SITE_TERMINAL", operation_source)
        self.assertGreater(stored_at, 0)

    def test_publishes_public_snapshot_and_drops_unknown_private_fields(self):
        snapshot = self.snapshot()
        snapshot["qq_number"] = "123456789"
        snapshot["machines"]["A"]["playing"][0]["phone_number"] = "13800138000"
        snapshot["machines"]["A"]["playing"][0]["player_profile_id"] = "private-id"

        publish = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )
        read = self.client.get("/api/queue-status")
        serialized = read.get_data(as_text=True)

        self.assertEqual(204, publish.status_code)
        self.assertEqual(200, read.status_code)
        self.assertNotIn("123456789", serialized)
        self.assertNotIn("13800138000", serialized)
        self.assertNotIn("private-id", serialized)
        self.assertEqual("公开昵称", read.get_json()["machines"]["A"]["playing"][0]["display_id"])
        self.assertTrue(read.get_json()["terminal"]["online"])

    def test_preserves_temporary_away_state_and_rejects_invalid_count(self):
        snapshot = self.snapshot()
        registration = snapshot["machines"]["A"]["playing"][0]
        registration["temporarily_away"] = True
        registration["temporary_away_skipped_turns"] = 2

        publish = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )
        stored = self.client.get("/api/queue-status").get_json()

        self.assertEqual(204, publish.status_code)
        stored_registration = stored["machines"]["A"]["playing"][0]
        self.assertTrue(stored_registration["temporarily_away"])
        self.assertEqual(2, stored_registration["temporary_away_skipped_turns"])

        invalid = self.snapshot(revision=5)
        invalid["machines"]["A"]["playing"][0]["temporary_away_skipped_turns"] = 1
        rejected = self.client.post(
            "/api/queue-status", json=invalid, headers=self.headers
        )
        self.assertEqual(400, rejected.status_code)

        conflicting = self.snapshot(revision=6)
        conflicting_registration = conflicting["machines"]["A"]["playing"][0]
        conflicting_registration["deferred_once"] = True
        conflicting_registration["temporarily_away"] = True
        rejected_conflict = self.client.post(
            "/api/queue-status", json=conflicting, headers=self.headers
        )
        self.assertEqual(400, rejected_conflict.status_code)

        stale_no_show_action = self.snapshot(revision=7)
        stale_no_show_action["machines"]["A"]["playing"][0][
            "last_no_show_action_was_defer"
        ] = True
        rejected_stale_action = self.client.post(
            "/api/queue-status", json=stale_no_show_action, headers=self.headers
        )
        self.assertEqual(400, rejected_stale_action.status_code)

    def test_stores_public_events_once_and_exposes_paginated_logs(self):
        first = self.snapshot(revision=4)
        first["schema_version"] = 2
        first["recent_events"] = [
            self.event(
                "00000000-0000-0000-0000-000000000101",
                "NO_SHOW_MOVED_TO_TAIL",
                1_000_100,
            )
        ]
        second = self.snapshot(revision=5)
        second["schema_version"] = 2
        second["recent_events"] = [
            self.event(
                "00000000-0000-0000-0000-000000000102",
                "ABSENCE_CHANGED",
                1_000_200,
            ),
            first["recent_events"][0],
        ]
        second["recent_events"][0]["operation_source"] = "QQ_BOT"

        self.assertEqual(
            204,
            self.client.post("/api/queue-status", json=first, headers=self.headers).status_code,
        )
        self.assertEqual(
            204,
            self.client.post("/api/queue-status", json=second, headers=self.headers).status_code,
        )
        first_page = self.client.get("/api/queue-logs?limit=1").get_json()
        second_page = self.client.get(
            f"/api/queue-logs?limit=1&before={first_page['next_cursor']}"
        ).get_json()

        self.assertEqual("ABSENCE_CHANGED", first_page["logs"][0]["type"])
        self.assertEqual("QQ_BOT", first_page["logs"][0]["operation_source"])
        self.assertEqual("NO_SHOW_MOVED_TO_TAIL", second_page["logs"][0]["type"])
        self.assertEqual("ON_SITE_TERMINAL", second_page["logs"][0]["operation_source"])
        self.assertIsNone(second_page["next_cursor"])
        self.assertTrue(first_page["capabilities"]["public_logs"])

        connection = sqlite3.connect(self.database_path)
        try:
            event_count = connection.execute("SELECT COUNT(*) FROM queue_event").fetchone()[0]
        finally:
            connection.close()
        self.assertEqual(2, event_count)

    def test_public_events_drop_unknown_private_fields(self):
        snapshot = self.snapshot()
        snapshot["schema_version"] = 2
        event = self.event(
            "00000000-0000-0000-0000-000000000103",
            "REGISTRATION_UPDATED",
            1_000_300,
        )
        event["phone_number"] = "13800138000"
        event["qq_number"] = "12345678"
        snapshot["recent_events"] = [event]

        self.assertEqual(
            204,
            self.client.post("/api/queue-status", json=snapshot, headers=self.headers).status_code,
        )
        serialized = self.client.get("/api/queue-logs").get_data(as_text=True)

        self.assertNotIn("13800138000", serialized)
        self.assertNotIn("12345678", serialized)

    def test_public_qq_visibility_exposes_only_the_allowed_active_contact(self):
        snapshot = self.snapshot()
        snapshot["schema_version"] = 5
        registration = snapshot["machines"]["A"]["playing"][0]
        companion = self.registration("b" * 24, "同行玩家")
        for fixed_registration in (registration, companion):
            fixed_registration["preference"] = "OPEN_TO_JOIN"
            fixed_registration["fixed_pair"] = True
            fixed_registration["fixed_pair_id"] = "f" * 24
        snapshot["machines"]["A"]["playing"].append(companion)
        public_profile = self.player_profile()
        public_profile["qq_visibility"] = "PUBLIC_WEBSITE"
        snapshot["private_player_profiles"] = [public_profile]
        snapshot["private_player_contacts"] = [
            {
                "registration_id": registration["registration_id"],
                "profile_id": self.profile_id,
                "qq_number": "12345678",
            }
        ]

        published = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )
        public_response = self.client.get("/api/queue-status")
        unauthenticated = self.client.get("/api/queue-bot/players")
        wrong_role = self.client.get(
            "/api/queue-bot/players",
            headers={"Authorization": f"Bearer {self.sync_token}"},
        )
        bot_response = self.client.post(
            "/api/queue-bot/players", json={"qq": "12345678"}, headers=self.bot_headers
        )

        self.assertEqual(204, published.status_code)
        self.assertEqual(5, public_response.get_json()["schema_version"])
        public_registration = public_response.get_json()["machines"]["A"]["playing"][0]
        public_companion = public_response.get_json()["machines"]["A"]["playing"][1]
        self.assertEqual("12345678", public_registration["qq_number"])
        self.assertIsNone(public_companion["qq_number"])
        self.assertNotIn("private_player_contacts", public_response.get_data(as_text=True))
        self.assertNotIn("private_player_profiles", public_response.get_data(as_text=True))
        self.assertEqual(401, unauthenticated.status_code)
        self.assertEqual(401, wrong_role.status_code)
        self.assertEqual(200, bot_response.status_code)
        body = bot_response.get_json()
        self.assertEqual(snapshot["queue_id"], body["queue_id"])
        self.assertTrue(body["terminal"]["online"])
        self.assertLessEqual(body["terminal"]["last_seen_seconds"], 1)
        self.assertEqual(1, len(body["players"]))
        player = body["players"][0]
        self.assertEqual("12345678", player["qq_number"])
        self.assertEqual("公开昵称", player["display_id"])
        self.assertEqual("A", player["machine_id"])
        self.assertEqual("左侧 · 机台 A", player["machine_name"])
        self.assertTrue(player["machine_operational"])
        self.assertIsNone(player["machine_stop_reason"])
        self.assertIsNone(player["machine_stop_reason_detail"])
        self.assertEqual(900_000, player["playing_started_at"])
        self.assertEqual("PLAYING", player["position"])
        self.assertEqual(0, player["estimated_wait_minutes"])
        self.assertEqual(["同行玩家"], player["co_player_display_ids"])
        self.assertEqual("OPEN_TO_JOIN", player["preference"])
        self.assertTrue(player["fixed_pair"])
        self.assertEqual("PLAYER_PROFILE", player["registration_type"])
        self.assertIsNone(player["last_played_at"])
        profiles = self.client.post(
            "/api/queue-bot/profiles", json={"qq": "12345678"}, headers=self.bot_headers
        ).get_json()["profiles"]
        self.assertEqual(1, len(profiles))
        self.assertEqual(self.profile_id, profiles[0]["profile_id"])
        self.assertEqual("公开昵称", profiles[0]["nickname"])
        legacy_query = self.client.get(
            "/api/queue-bot/players?qq=12345678", headers=self.bot_headers
        )
        self.assertEqual(400, legacy_query.status_code)

    def test_stopped_machine_suppresses_playing_timer_and_all_wait_estimates(self):
        snapshot = self.snapshot()
        snapshot["schema_version"] = 3
        playing_registration = snapshot["machines"]["A"]["playing"][0]
        waiting_registration = self.registration("b" * 24, "等待玩家")
        machine = snapshot["machines"]["A"]
        machine.update(
            {
                "operational": False,
                "stop_reason": "NETWORK_DISCONNECTED",
                "stopped_at": 950_000,
                "registration_count": 2,
                "waiting_position_count": 1,
                "waiting_positions": [
                    {
                        "position_id": "c" * 24,
                        "fixed_pair": False,
                        "estimated_wait_minutes": 12,
                        "registrations": [waiting_registration],
                    }
                ],
            }
        )
        snapshot["private_player_profiles"] = [self.player_profile()]
        snapshot["private_player_contacts"] = [
            {
                "registration_id": playing_registration["registration_id"],
                "profile_id": self.profile_id,
                "qq_number": "12345678",
            }
        ]

        published = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )
        public_machine = self.client.get("/api/queue-status").get_json()["machines"]["A"]
        bot_player = self.client.post(
            "/api/queue-bot/players",
            json={"qq": "12345678"},
            headers=self.bot_headers,
        ).get_json()["players"][0]

        self.assertEqual(204, published.status_code)
        self.assertIsNone(public_machine["playing_started_at"])
        self.assertIsNone(
            public_machine["waiting_positions"][0]["estimated_wait_minutes"]
        )
        self.assertFalse(bot_player["machine_operational"])
        self.assertIsNone(bot_player["playing_started_at"])
        self.assertIsNone(bot_player["estimated_wait_minutes"])

    def test_exposes_only_computed_business_hours_state(self):
        snapshot = self.snapshot()
        snapshot["schema_version"] = 3
        snapshot["onebot_sync_enabled"] = True
        snapshot["business_hours"] = {
            "enabled": True,
            "outside": False,
            "closing_soon": True,
            "closing_grace": False,
            "closes_at": 1_060_000,
            "registration_closes_at": None,
        }

        published = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )
        public_snapshot = self.client.get("/api/queue-status").get_json()

        self.assertEqual(204, published.status_code)
        self.assertTrue(public_snapshot["onebot_sync_enabled"])
        self.assertEqual(snapshot["business_hours"], public_snapshot["business_hours"])
        self.assertNotIn("weekly_hours", public_snapshot["business_hours"])
        self.assertNotIn("opening_minutes", public_snapshot["business_hours"])
        bot_snapshot = self.client.post(
            "/api/queue-bot/players", json={}, headers=self.bot_headers
        ).get_json()
        self.assertEqual(snapshot["registration_open"], bot_snapshot["registration_open"])
        self.assertEqual(snapshot["business_hours"], bot_snapshot["business_hours"])

        closing = copy.deepcopy(snapshot)
        closing["revision"] = 5
        closing["business_hours"] = {
            "enabled": True,
            "outside": True,
            "closing_soon": False,
            "closing_grace": True,
            "closes_at": None,
            "registration_closes_at": 2_200_000,
        }
        accepted_closing = self.client.post(
            "/api/queue-status", json=closing, headers=self.headers
        )
        self.assertEqual(204, accepted_closing.status_code)
        closing_bot_snapshot = self.client.post(
            "/api/queue-bot/players", json={}, headers=self.bot_headers
        ).get_json()
        self.assertEqual(closing["business_hours"], closing_bot_snapshot["business_hours"])
        self.assertEqual(
            closing["business_hours"],
            self.client.get("/api/queue-status").get_json()["business_hours"],
        )

        invalid = copy.deepcopy(snapshot)
        invalid["revision"] = 6
        invalid["business_hours"]["weekly_hours"] = {}
        rejected = self.client.post(
            "/api/queue-status", json=invalid, headers=self.headers
        )
        self.assertEqual(400, rejected.status_code)

    def test_disabling_bot_link_invalidates_commands_and_drops_notification_backlog(self):
        registration_id = self.snapshot()["machines"]["A"]["playing"][0][
            "registration_id"
        ]
        first_event = self.event(
            "00000000-0000-0000-0000-000000000301",
            "PLAYING_CHANGED",
            1_000_100,
        )
        first_event["operation_source"] = "ON_SITE_TERMINAL"
        enabled = self.snapshot(revision=4)
        enabled["schema_version"] = 3
        enabled["onebot_sync_enabled"] = True
        enabled["private_player_profiles"] = [self.player_profile()]
        enabled["private_player_contacts"] = [
            {
                "registration_id": registration_id,
                "profile_id": self.profile_id,
                "qq_number": "12345678",
            }
        ]
        enabled["recent_events"] = [first_event]
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=enabled, headers=self.headers
            ).status_code,
        )

        command_id = "00000000-0000-0000-0000-000000000302"
        created = self.client.patch(
            f"/api/queue-bot/profiles/{self.profile_id}",
            json={
                "request_id": command_id,
                "actor_qq": "12345678",
                "nickname": "联动关闭前的修改",
            },
            headers=self.bot_headers,
        )
        self.assertEqual(202, created.status_code)

        disabled_event = self.event(
            "00000000-0000-0000-0000-000000000303",
            "REGISTRATION_UPDATED",
            1_000_200,
        )
        disabled_event["operation_source"] = "SYSTEM_AUTOMATIC"
        disabled = copy.deepcopy(enabled)
        disabled["revision"] = 5
        disabled["onebot_sync_enabled"] = False
        disabled["recent_events"] = [first_event, disabled_event]
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=disabled, headers=self.headers
            ).status_code,
        )

        blocked_responses = [
            self.client.get("/api/queue-bot/players", headers=self.bot_headers),
            self.client.post(
                "/api/queue-bot/events",
                json={"qq": "12345678", "after": 0},
                headers=self.bot_headers,
            ),
            self.client.post(
                "/api/queue-bot/profiles",
                json={"qq": "12345678"},
                headers=self.bot_headers,
            ),
            self.client.patch(
                f"/api/queue-bot/profiles/{self.profile_id}",
                json={
                    "request_id": "00000000-0000-0000-0000-000000000304",
                    "actor_qq": "12345678",
                    "nickname": "不应执行",
                },
                headers=self.bot_headers,
            ),
            self.client.get(
                f"/api/queue-bot/commands/{command_id}",
                headers=self.bot_headers,
            ),
        ]
        self.assertTrue(all(response.status_code == 503 for response in blocked_responses))
        self.assertTrue(
            all(
                response.get_json()["error"] == "QQ Bot 联动已关闭"
                for response in blocked_responses
            )
        )
        self.assertEqual(
            [],
            self.client.get(
                "/api/queue-terminal/commands", headers=self.headers
            ).get_json()["commands"],
        )

        connection = sqlite3.connect(self.database_path)
        try:
            recipient_count = connection.execute(
                "SELECT COUNT(*) FROM queue_event_recipient"
            ).fetchone()[0]
            profile_count = connection.execute(
                "SELECT COUNT(*) FROM player_profile"
            ).fetchone()[0]
            command = connection.execute(
                """
                SELECT status, result_source, result_detail
                FROM terminal_command WHERE command_id = ?
                """,
                (command_id,),
            ).fetchone()
        finally:
            connection.close()
        self.assertEqual(0, recipient_count)
        self.assertEqual(1, profile_count)
        self.assertEqual(
            ("REJECTED", "BOT_DISABLED", "QQ Bot 联动已关闭，这次修改没有执行。"),
            command,
        )

        reenabled_event = self.event(
            "00000000-0000-0000-0000-000000000305",
            "ABSENCE_CHANGED",
            1_000_300,
        )
        reenabled_event["operation_source"] = "QQ_BOT"
        reenabled = copy.deepcopy(disabled)
        reenabled["revision"] = 6
        reenabled["onebot_sync_enabled"] = True
        reenabled["recent_events"] = [first_event, disabled_event, reenabled_event]
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=reenabled, headers=self.headers
            ).status_code,
        )

        notifications = self.client.post(
            "/api/queue-bot/events",
            json={"qq": "12345678", "after": 0},
            headers=self.bot_headers,
        ).get_json()["events"]
        preserved_profiles = self.client.post(
            "/api/queue-bot/profiles",
            json={"qq": "12345678"},
            headers=self.bot_headers,
        ).get_json()["profiles"]
        rejected_command = self.client.get(
            f"/api/queue-bot/commands/{command_id}", headers=self.bot_headers
        ).get_json()

        self.assertEqual([reenabled_event["event_id"]], [
            event["event_id"] for event in notifications
        ])
        self.assertEqual("QQ_BOT", notifications[0]["operation_source"])
        self.assertEqual(1, len(preserved_profiles))
        self.assertEqual("REJECTED", rejected_command["status"])
        self.assertEqual("BOT_DISABLED", rejected_command["result_source"])

    def test_bot_profile_update_is_applied_only_after_terminal_acknowledges_it(self):
        snapshot = self.snapshot(revision=4)
        snapshot["schema_version"] = 3
        snapshot["private_player_profiles"] = [self.player_profile()]
        snapshot["private_player_contacts"] = []
        self.assertEqual(
            204,
            self.client.post("/api/queue-status", json=snapshot, headers=self.headers).status_code,
        )
        command_id = "00000000-0000-0000-0000-000000000777"
        update = {
            "request_id": command_id,
            "actor_qq": "12345678",
            "nickname": "云端新昵称",
            "gender": "FEMALE",
            "default_preference": "SOLO",
        }

        created = self.client.patch(
            f"/api/queue-bot/profiles/{self.profile_id}",
            json=update,
            headers=self.bot_headers,
        )
        repeated = self.client.patch(
            f"/api/queue-bot/profiles/{self.profile_id}",
            json=update,
            headers=self.bot_headers,
        )
        commands = self.client.get(
            "/api/queue-terminal/commands", headers=self.headers
        ).get_json()["commands"]
        unchanged_profile = self.client.post(
            "/api/queue-bot/profiles", json={"qq": "12345678"}, headers=self.bot_headers
        ).get_json()["profiles"][0]

        self.assertEqual(202, created.status_code)
        self.assertEqual(200, repeated.status_code)
        self.assertEqual("PENDING", created.get_json()["status"])
        self.assertEqual(1, len(commands))
        self.assertEqual(command_id, commands[0]["command_id"])
        self.assertEqual(950_000, commands[0]["payload"]["expected_updated_at"])
        self.assertEqual("云端新昵称", commands[0]["payload"]["nickname"])
        self.assertEqual("公开昵称", unchanged_profile["nickname"])

        acknowledged = self.client.post(
            f"/api/queue-terminal/commands/{command_id}/result",
            json={"status": "APPLIED", "detail": "玩家资料已由终端更新。"},
            headers=self.headers,
        )
        command_status = self.client.get(
            f"/api/queue-bot/commands/{command_id}", headers=self.bot_headers
        )
        profile_after_ack = self.client.post(
            "/api/queue-bot/profiles",
            json={"qq": "12345678"},
            headers=self.bot_headers,
        ).get_json()["profiles"][0]

        self.assertEqual(200, acknowledged.status_code)
        self.assertEqual("APPLIED", command_status.get_json()["status"])
        self.assertEqual("公开昵称", profile_after_ack["nickname"])
        self.assertEqual([], self.client.get(
            "/api/queue-terminal/commands", headers=self.headers
        ).get_json()["commands"])

        updated_snapshot = self.snapshot(revision=5)
        updated_snapshot["schema_version"] = 3
        updated_profile = self.player_profile()
        updated_profile.update(
            {
                "nickname": "云端新昵称",
                "gender": "FEMALE",
                "default_preference": "SOLO",
                "updated_at": 1_100_000,
            }
        )
        updated_snapshot["private_player_profiles"] = [updated_profile]
        updated_snapshot["private_player_contacts"] = []
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=updated_snapshot, headers=self.headers
            ).status_code,
        )
        synced_profile = self.client.post(
            "/api/queue-bot/profiles", json={"qq": "12345678"}, headers=self.bot_headers
        ).get_json()["profiles"][0]
        repeated_after_profile_changed = self.client.patch(
            f"/api/queue-bot/profiles/{self.profile_id}",
            json=update,
            headers=self.bot_headers,
        )
        self.assertEqual("云端新昵称", synced_profile["nickname"])
        self.assertEqual("FEMALE", synced_profile["gender"])
        self.assertEqual("SOLO", synced_profile["default_preference"])
        self.assertEqual(200, repeated_after_profile_changed.status_code)
        self.assertEqual(command_id, repeated_after_profile_changed.get_json()["command_id"])

    def test_concurrent_profile_updates_create_only_one_pending_command(self):
        snapshot = self.snapshot(revision=4)
        snapshot["schema_version"] = 3
        snapshot["private_player_profiles"] = [self.player_profile()]
        snapshot["private_player_contacts"] = []
        self.client.post("/api/queue-status", json=snapshot, headers=self.headers)
        barrier = Barrier(2)

        def submit(command_id: str):
            with self.app.test_client() as client:
                barrier.wait(timeout=5)
                response = client.patch(
                    f"/api/queue-bot/profiles/{self.profile_id}",
                    json={
                        "request_id": command_id,
                        "actor_qq": "12345678",
                        "nickname": "并发昵称",
                    },
                    headers=self.bot_headers,
                )
                return response.status_code

        with ThreadPoolExecutor(max_workers=2) as executor:
            statuses = sorted(executor.map(submit, [
                "00000000-0000-0000-0000-000000000784",
                "00000000-0000-0000-0000-000000000785",
            ]))
        connection = sqlite3.connect(self.database_path)
        try:
            pending_count = connection.execute(
                "SELECT COUNT(*) FROM terminal_command WHERE status = 'PENDING'"
            ).fetchone()[0]
        finally:
            connection.close()

        self.assertEqual([202, 409], statuses)
        self.assertEqual(1, pending_count)

    def test_command_result_is_first_writer_wins(self):
        snapshot = self.snapshot(revision=4)
        snapshot["schema_version"] = 3
        snapshot["private_player_profiles"] = [self.player_profile()]
        snapshot["private_player_contacts"] = []
        self.client.post("/api/queue-status", json=snapshot, headers=self.headers)
        command_id = "00000000-0000-0000-0000-000000000780"
        self.client.patch(
            f"/api/queue-bot/profiles/{self.profile_id}",
            json={
                "request_id": command_id,
                "actor_qq": "12345678",
                "nickname": "先确认的昵称",
            },
            headers=self.bot_headers,
        )

        first = self.client.post(
            f"/api/queue-terminal/commands/{command_id}/result",
            json={"status": "APPLIED", "detail": "第一次回执"},
            headers=self.headers,
        )
        second = self.client.post(
            f"/api/queue-terminal/commands/{command_id}/result",
            json={"status": "REJECTED", "detail": "迟到的相反回执"},
            headers=self.headers,
        )

        self.assertEqual("APPLIED", first.get_json()["status"])
        self.assertEqual("APPLIED", second.get_json()["status"])
        self.assertEqual("第一次回执", second.get_json()["result_detail"])

    def test_pending_command_expires_and_allows_a_new_request(self):
        self.app.config["COMMAND_TIMEOUT_SECONDS"] = 1
        snapshot = self.snapshot(revision=4)
        snapshot["schema_version"] = 3
        snapshot["private_player_profiles"] = [self.player_profile()]
        snapshot["private_player_contacts"] = []
        self.client.post("/api/queue-status", json=snapshot, headers=self.headers)
        command_id = "00000000-0000-0000-0000-000000000781"
        self.client.patch(
            f"/api/queue-bot/profiles/{self.profile_id}",
            json={
                "request_id": command_id,
                "actor_qq": "12345678",
                "nickname": "已经超时的修改",
            },
            headers=self.bot_headers,
        )
        connection = sqlite3.connect(self.database_path)
        try:
            connection.execute(
                "UPDATE terminal_command SET created_at = ? WHERE command_id = ?",
                (int(time.time()) - 2, command_id),
            )
            connection.commit()
        finally:
            connection.close()

        expired = self.client.get(
            f"/api/queue-bot/commands/{command_id}", headers=self.bot_headers
        )
        replacement = self.client.patch(
            f"/api/queue-bot/profiles/{self.profile_id}",
            json={
                "request_id": "00000000-0000-0000-0000-000000000782",
                "actor_qq": "12345678",
                "nickname": "重新提交的修改",
            },
            headers=self.bot_headers,
        )

        self.assertEqual("REJECTED", expired.get_json()["status"])
        self.assertIn("有效时间", expired.get_json()["result_detail"])
        self.assertEqual(202, replacement.status_code)

    def test_claimed_command_late_applied_result_corrects_server_timeout(self):
        self.app.config["COMMAND_TIMEOUT_SECONDS"] = 1
        snapshot = self.snapshot(revision=4)
        snapshot["schema_version"] = 3
        snapshot["private_player_profiles"] = [self.player_profile()]
        snapshot["private_player_contacts"] = []
        self.client.post("/api/queue-status", json=snapshot, headers=self.headers)
        command_id = "00000000-0000-0000-0000-000000000786"
        self.client.patch(
            f"/api/queue-bot/profiles/{self.profile_id}",
            json={
                "request_id": command_id,
                "actor_qq": "12345678",
                "nickname": "断网期间已写入",
            },
            headers=self.bot_headers,
        )
        claimed = self.client.get(
            "/api/queue-terminal/commands", headers=self.headers
        ).get_json()["commands"][0]
        self.assertIsNotNone(claimed["claimed_at"])
        self.assertEqual("terminal-1", claimed["claimed_terminal"])

        connection = sqlite3.connect(self.database_path)
        try:
            connection.execute(
                "UPDATE terminal_command SET created_at = ? WHERE command_id = ?",
                (int(time.time()) - 2, command_id),
            )
            connection.commit()
        finally:
            connection.close()
        expired = self.client.get(
            f"/api/queue-bot/commands/{command_id}", headers=self.bot_headers
        ).get_json()
        self.assertEqual("REJECTED", expired["status"])
        self.assertEqual("SERVER_TIMEOUT", expired["result_source"])

        late_applied = self.client.post(
            f"/api/queue-terminal/commands/{command_id}/result",
            json={"status": "APPLIED", "detail": "资料已在断网前写入本机。"},
            headers=self.headers,
        ).get_json()
        contrary_replay = self.client.post(
            f"/api/queue-terminal/commands/{command_id}/result",
            json={"status": "REJECTED", "detail": "迟到的相反结果"},
            headers=self.headers,
        ).get_json()

        self.assertEqual("APPLIED", late_applied["status"])
        self.assertEqual("TERMINAL", late_applied["result_source"])
        self.assertEqual("APPLIED", contrary_replay["status"])
        self.assertEqual("资料已在断网前写入本机。", contrary_replay["result_detail"])

    def test_unclaimed_timeout_cannot_be_overridden_by_terminal_result(self):
        self.app.config["COMMAND_TIMEOUT_SECONDS"] = 1
        snapshot = self.snapshot(revision=4)
        snapshot["schema_version"] = 3
        snapshot["private_player_profiles"] = [self.player_profile()]
        snapshot["private_player_contacts"] = []
        self.client.post("/api/queue-status", json=snapshot, headers=self.headers)
        command_id = "00000000-0000-0000-0000-000000000787"
        self.client.patch(
            f"/api/queue-bot/profiles/{self.profile_id}",
            json={
                "request_id": command_id,
                "actor_qq": "12345678",
                "nickname": "从未领取的命令",
            },
            headers=self.bot_headers,
        )
        connection = sqlite3.connect(self.database_path)
        try:
            connection.execute(
                "UPDATE terminal_command SET created_at = ? WHERE command_id = ?",
                (int(time.time()) - 2, command_id),
            )
            connection.commit()
        finally:
            connection.close()

        late_result = self.client.post(
            f"/api/queue-terminal/commands/{command_id}/result",
            json={"status": "APPLIED", "detail": "不应接受"},
            headers=self.headers,
        ).get_json()

        self.assertEqual("REJECTED", late_result["status"])
        self.assertEqual("SERVER_TIMEOUT", late_result["result_source"])

    def test_previous_terminal_cannot_correct_timeout_after_takeover(self):
        self.app.config["ALLOWED_DEVICE_ID"] = ""
        self.app.config["PRIMARY_DEVICE_ID"] = "terminal-2"
        self.app.config["COMMAND_TIMEOUT_SECONDS"] = 1
        snapshot = self.snapshot(revision=4)
        snapshot["schema_version"] = 3
        snapshot["private_player_profiles"] = [self.player_profile()]
        snapshot["private_player_contacts"] = []
        self.client.post("/api/queue-status", json=snapshot, headers=self.headers)
        command_id = "00000000-0000-0000-0000-000000000788"
        self.client.patch(
            f"/api/queue-bot/profiles/{self.profile_id}",
            json={
                "request_id": command_id,
                "actor_qq": "12345678",
                "nickname": "旧终端领取的修改",
            },
            headers=self.bot_headers,
        )
        self.client.get("/api/queue-terminal/commands", headers=self.headers)
        connection = sqlite3.connect(self.database_path)
        try:
            connection.execute(
                "UPDATE terminal_command SET created_at = ? WHERE command_id = ?",
                (int(time.time()) - 2, command_id),
            )
            connection.commit()
        finally:
            connection.close()
        self.client.get(
            f"/api/queue-bot/commands/{command_id}", headers=self.bot_headers
        )

        terminal_two_headers = {**self.headers, "X-Device-ID": "terminal-2"}
        takeover_snapshot = copy.deepcopy(snapshot)
        takeover_snapshot["revision"] = 5
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status",
                json=takeover_snapshot,
                headers=terminal_two_headers,
            ).status_code,
        )
        old_terminal_result = self.client.post(
            f"/api/queue-terminal/commands/{command_id}/result",
            json={"status": "APPLIED", "detail": "旧终端迟到的回执"},
            headers=self.headers,
        ).get_json()

        self.assertEqual("REJECTED", old_terminal_result["status"])
        self.assertEqual("SERVER_TIMEOUT", old_terminal_result["result_source"])

    def test_terminal_takeover_reassigns_pending_commands(self):
        self.app.config["ALLOWED_DEVICE_ID"] = ""
        self.app.config["PRIMARY_DEVICE_ID"] = "terminal-2"
        snapshot = self.snapshot(revision=4)
        snapshot["schema_version"] = 3
        snapshot["private_player_profiles"] = [self.player_profile()]
        snapshot["private_player_contacts"] = []
        self.client.post("/api/queue-status", json=snapshot, headers=self.headers)
        command_id = "00000000-0000-0000-0000-000000000783"
        self.client.patch(
            f"/api/queue-bot/profiles/{self.profile_id}",
            json={
                "request_id": command_id,
                "actor_qq": "12345678",
                "nickname": "接管后的修改",
            },
            headers=self.bot_headers,
        )
        terminal_two_headers = {
            **self.headers,
            "X-Device-ID": "terminal-2",
        }
        takeover_snapshot = self.snapshot(revision=5)
        takeover_snapshot["schema_version"] = 3
        takeover_snapshot["private_player_profiles"] = [self.player_profile()]
        takeover_snapshot["private_player_contacts"] = []

        takeover = self.client.post(
            "/api/queue-status", json=takeover_snapshot, headers=terminal_two_headers
        )
        old_commands = self.client.get(
            "/api/queue-terminal/commands", headers=self.headers
        ).get_json()["commands"]
        new_commands = self.client.get(
            "/api/queue-terminal/commands", headers=terminal_two_headers
        ).get_json()["commands"]

        self.assertEqual(204, takeover.status_code)
        self.assertEqual([], old_commands)
        self.assertEqual([command_id], [item["command_id"] for item in new_commands])

    def test_bot_cannot_update_another_qq_or_change_the_identity_field(self):
        snapshot = self.snapshot(revision=4)
        snapshot["schema_version"] = 3
        snapshot["private_player_profiles"] = [self.player_profile()]
        snapshot["private_player_contacts"] = []
        self.client.post("/api/queue-status", json=snapshot, headers=self.headers)

        wrong_actor = self.client.patch(
            f"/api/queue-bot/profiles/{self.profile_id}",
            json={
                "request_id": "00000000-0000-0000-0000-000000000778",
                "actor_qq": "87654321",
                "nickname": "越权昵称",
            },
            headers=self.bot_headers,
        )
        identity_change = self.client.patch(
            f"/api/queue-bot/profiles/{self.profile_id}",
            json={
                "request_id": "00000000-0000-0000-0000-000000000779",
                "actor_qq": "12345678",
                "qq_number": "87654321",
            },
            headers=self.bot_headers,
        )

        self.assertEqual(403, wrong_actor.status_code)
        self.assertEqual(400, identity_change.status_code)

    def test_private_qq_binding_routes_events_after_registration_leaves(self):
        first = self.snapshot(revision=4)
        first["schema_version"] = 5
        registration = first["machines"]["A"]["playing"][0]
        profile = self.player_profile()
        profile["notify_playing_position"] = True
        first["private_player_profiles"] = [profile]
        first["recent_events"] = [
            self.event(
                "00000000-0000-0000-0000-000000000199",
                "PLAYING_CHANGED",
                1_000_100,
            )
        ]
        first["private_player_contacts"] = [
            {
                "registration_id": registration["registration_id"],
                "profile_id": self.profile_id,
                "qq_number": "12345678",
            }
        ]
        second = self.snapshot(revision=5)
        second["schema_version"] = 5
        second["machines"]["A"] = self.machine(name="左侧 · 机台 A")
        second["private_player_profiles"] = [profile]
        second["private_player_contacts"] = []

        self.assertEqual(
            204,
            self.client.post("/api/queue-status", json=first, headers=self.headers).status_code,
        )
        self.assertEqual(
            204,
            self.client.post("/api/queue-status", json=second, headers=self.headers).status_code,
        )
        players = self.client.get(
            "/api/queue-bot/players", headers=self.bot_headers
        ).get_json()["players"]
        events_response = self.client.post(
            "/api/queue-bot/events",
            json={"qq": "12345678", "after": 0},
            headers=self.bot_headers,
        ).get_json()

        self.assertEqual([], players)
        self.assertEqual(1, len(events_response["events"]))
        event = events_response["events"][0]
        self.assertEqual("PLAYING_CHANGED", event["type"])
        self.assertEqual("12345678", event["affected_players"][0]["qq_number"])
        self.assertEqual(event["cursor"], events_response["next_cursor"])
        self.assertEqual(event["cursor"], events_response["latest_cursor"])

    def test_new_queue_reset_event_notifies_players_from_previous_batch(self):
        first = self.snapshot(revision=4)
        first["schema_version"] = 3
        registration = first["machines"]["A"]["playing"][0]
        first["private_player_profiles"] = [self.player_profile()]
        first["private_player_contacts"] = [
            {
                "registration_id": registration["registration_id"],
                "profile_id": self.profile_id,
                "qq_number": "12345678",
            }
        ]
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=first, headers=self.headers
            ).status_code,
        )

        reset_event_id = "00000000-0000-0000-0000-000000000195"
        second = self.snapshot(
            queue_id="00000000-0000-0000-0000-000000000002",
            revision=5,
        )
        second["schema_version"] = 3
        second["machines"]["A"] = self.machine(name="左侧 · 机台 A")
        second["private_player_profiles"] = [self.player_profile()]
        second["private_player_contacts"] = []
        reset_event = self.event(reset_event_id, "QUEUE_RESET", 1_000_200)
        reset_event.update(
            {
                "machine_id": None,
                "title": "开始新的队列",
                "detail": "未载入上次保存的 1 个登记，已从空队列开始。",
                "registration_ids": [],
            }
        )
        second["recent_events"] = [reset_event]
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=second, headers=self.headers
            ).status_code,
        )

        events_response = self.client.post(
            "/api/queue-bot/events",
            json={"qq": "12345678", "after": 0},
            headers=self.bot_headers,
        ).get_json()
        public_logs = self.client.get("/api/queue-logs").get_json()["logs"]

        self.assertEqual(second["queue_id"], events_response["queue_id"])
        self.assertEqual(
            [reset_event_id],
            [event["event_id"] for event in events_response["events"]],
        )
        self.assertEqual(
            "12345678",
            events_response["events"][0]["affected_players"][0]["qq_number"],
        )
        self.assertEqual(
            [registration["registration_id"]],
            public_logs[0]["registration_ids"],
        )

    def test_current_registration_cannot_inherit_a_stale_qq_binding(self):
        first = self.snapshot(revision=4)
        first["schema_version"] = 3
        registration = first["machines"]["A"]["playing"][0]
        first["private_player_profiles"] = [self.player_profile()]
        first["private_player_contacts"] = [
            {
                "registration_id": registration["registration_id"],
                "profile_id": self.profile_id,
                "qq_number": "12345678",
            }
        ]
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=first, headers=self.headers
            ).status_code,
        )

        second = copy.deepcopy(first)
        second["revision"] = 5
        second["private_player_contacts"] = []
        current_event = self.event(
            "00000000-0000-0000-0000-000000000198",
            "REGISTRATION_UPDATED",
            1_000_200,
        )
        second["recent_events"] = [current_event]
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=second, headers=self.headers
            ).status_code,
        )

        events = self.client.post(
            "/api/queue-bot/events",
            json={"qq": "12345678", "after": 0},
            headers=self.bot_headers,
        ).get_json()["events"]
        connection = sqlite3.connect(self.database_path)
        try:
            contact_count = connection.execute(
                "SELECT COUNT(*) FROM queue_private_contact"
            ).fetchone()[0]
        finally:
            connection.close()

        self.assertEqual([], events)
        self.assertEqual(0, contact_count)

    def test_profile_can_rejoin_same_queue_with_a_new_registration_id(self):
        first = self.snapshot(revision=4)
        first["schema_version"] = 3
        first_registration = first["machines"]["A"]["playing"][0]
        first["private_player_profiles"] = [self.player_profile()]
        first["private_player_contacts"] = [
            {
                "registration_id": first_registration["registration_id"],
                "profile_id": self.profile_id,
                "qq_number": "12345678",
            }
        ]
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=first, headers=self.headers
            ).status_code,
        )

        second = self.snapshot(revision=5)
        second["schema_version"] = 3
        second_registration = self.registration("c" * 24, "公开昵称")
        second["machines"]["A"]["playing"] = [second_registration]
        second["machines"]["A"]["registration_count"] = 1
        second["private_player_profiles"] = [self.player_profile()]
        second["private_player_contacts"] = [
            {
                "registration_id": second_registration["registration_id"],
                "profile_id": self.profile_id,
                "qq_number": "12345678",
            }
        ]
        removed_event = self.event(
            "00000000-0000-0000-0000-000000000195",
            "REGISTRATION_REMOVED",
            1_000_200,
        )
        removed_event["registration_ids"] = [first_registration["registration_id"]]
        second["recent_events"] = [removed_event]

        response = self.client.post(
            "/api/queue-status", json=second, headers=self.headers
        )
        players = self.client.post(
            "/api/queue-bot/players",
            json={"qq": "12345678"},
            headers=self.bot_headers,
        ).get_json()["players"]
        events = self.client.post(
            "/api/queue-bot/events",
            json={"qq": "12345678", "after": 0},
            headers=self.bot_headers,
        ).get_json()["events"]
        connection = sqlite3.connect(self.database_path)
        try:
            stored_registration_ids = [
                row[0]
                for row in connection.execute(
                    "SELECT registration_id FROM queue_private_contact"
                )
            ]
        finally:
            connection.close()

        self.assertEqual(204, response.status_code)
        self.assertEqual(
            [second_registration["registration_id"]],
            [player["registration_id"] for player in players],
        )
        self.assertEqual(
            [removed_event["event_id"]],
            [event["event_id"] for event in events],
        )
        self.assertEqual(
            first_registration["registration_id"],
            events[0]["affected_players"][0]["registration_id"],
        )
        self.assertEqual(
            [second_registration["registration_id"]], stored_registration_ids
        )

    def test_qq_filtered_events_do_not_expose_other_recipients(self):
        snapshot = self.snapshot(revision=4)
        snapshot["schema_version"] = 3
        second_registration = self.registration("b" * 24, "另一位玩家")
        snapshot["machines"]["A"]["playing"].append(second_registration)
        snapshot["machines"]["A"]["registration_count"] = 2
        second_profile = self.player_profile()
        second_profile.update(
            {
                "profile_id": "00000000-0000-0000-0000-000000000902",
                "nickname": "另一位玩家",
                "qq_number": "87654321",
            }
        )
        snapshot["private_player_profiles"] = [self.player_profile(), second_profile]
        snapshot["private_player_contacts"] = [
            {
                "registration_id": "a" * 24,
                "profile_id": self.profile_id,
                "qq_number": "12345678",
            },
            {
                "registration_id": second_registration["registration_id"],
                "profile_id": second_profile["profile_id"],
                "qq_number": "87654321",
            },
        ]
        event = self.event(
            "00000000-0000-0000-0000-000000000194",
            "REGISTRATION_UPDATED",
            1_000_100,
        )
        event["registration_ids"] = ["a" * 24, "b" * 24]
        snapshot["recent_events"] = [event]
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )

        filtered_event = self.client.post(
            "/api/queue-bot/events",
            json={"qq": "12345678", "after": 0},
            headers=self.bot_headers,
        ).get_json()["events"][0]
        all_event = self.client.get(
            "/api/queue-bot/events?after=0", headers=self.bot_headers
        ).get_json()["events"][0]

        self.assertEqual(
            ["12345678"],
            [player["qq_number"] for player in filtered_event["affected_players"]],
        )
        self.assertEqual(
            {"12345678", "87654321"},
            {player["qq_number"] for player in all_event["affected_players"]},
        )

    def test_event_recipient_is_fixed_when_the_event_is_stored(self):
        event_id = "00000000-0000-0000-0000-000000000198"
        first = self.snapshot(revision=4)
        first["schema_version"] = 3
        registration = first["machines"]["A"]["playing"][0]
        first["private_player_profiles"] = [self.player_profile()]
        first["private_player_contacts"] = [{
            "registration_id": registration["registration_id"],
            "profile_id": self.profile_id,
            "qq_number": "12345678",
        }]
        first["recent_events"] = [self.event(event_id, "REGISTRATION_UPDATED", 1_000_100)]
        self.assertEqual(
            204,
            self.client.post("/api/queue-status", json=first, headers=self.headers).status_code,
        )

        second = self.snapshot(revision=5)
        second["schema_version"] = 3
        changed_profile = self.player_profile()
        changed_profile["qq_number"] = "87654321"
        changed_profile["updated_at"] = 1_100_000
        second["private_player_profiles"] = [changed_profile]
        second["private_player_contacts"] = [{
            "registration_id": registration["registration_id"],
            "profile_id": self.profile_id,
            "qq_number": "87654321",
        }]
        second["recent_events"] = [self.event(event_id, "REGISTRATION_UPDATED", 1_000_100)]
        self.assertEqual(
            204,
            self.client.post("/api/queue-status", json=second, headers=self.headers).status_code,
        )

        old_owner_events = self.client.post(
            "/api/queue-bot/events",
            json={"qq": "12345678", "after": 0},
            headers=self.bot_headers,
        ).get_json()["events"]
        new_owner_events = self.client.post(
            "/api/queue-bot/events",
            json={"qq": "87654321", "after": 0},
            headers=self.bot_headers,
        ).get_json()["events"]

        self.assertEqual([event_id], [item["event_id"] for item in old_owner_events])
        self.assertEqual([], new_owner_events)

    def test_replayed_event_cannot_append_a_different_recipient(self):
        event_id = "00000000-0000-0000-0000-000000000197"
        first = self.snapshot(revision=4)
        first["schema_version"] = 3
        second_registration = self.registration("b" * 24, "另一位玩家")
        first["machines"]["A"]["playing"].append(second_registration)
        first["machines"]["A"]["registration_count"] = 2
        second_profile = self.player_profile()
        second_profile.update(
            {
                "profile_id": "00000000-0000-0000-0000-000000000902",
                "nickname": "另一位玩家",
                "qq_number": "87654321",
            }
        )
        first["private_player_profiles"] = [self.player_profile(), second_profile]
        first["private_player_contacts"] = [
            {
                "registration_id": "a" * 24,
                "profile_id": self.profile_id,
                "qq_number": "12345678",
            },
            {
                "registration_id": "b" * 24,
                "profile_id": second_profile["profile_id"],
                "qq_number": "87654321",
            },
        ]
        first_event = self.event(event_id, "REGISTRATION_UPDATED", 1_000_100)
        first_event["registration_ids"] = ["a" * 24]
        first["recent_events"] = [first_event]
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=first, headers=self.headers
            ).status_code,
        )

        replay = copy.deepcopy(first)
        replay["revision"] = 5
        replay["recent_events"][0]["registration_ids"] = ["b" * 24]
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=replay, headers=self.headers
            ).status_code,
        )
        second_owner_events = self.client.post(
            "/api/queue-bot/events",
            json={"qq": "87654321", "after": 0},
            headers=self.bot_headers,
        ).get_json()["events"]
        connection = sqlite3.connect(self.database_path)
        try:
            recipient_ids = [
                row[0]
                for row in connection.execute(
                    """
                    SELECT registration_id FROM queue_event_recipient
                    WHERE queue_id = ? AND event_id = ?
                    """,
                    (first["queue_id"], event_id),
                )
            ]
        finally:
            connection.close()

        self.assertEqual(["a" * 24], recipient_ids)
        self.assertEqual([], second_owner_events)

    def test_expired_event_recipients_are_removed_but_public_event_remains(self):
        self.app.config["EVENT_RECIPIENT_RETENTION_SECONDS"] = 1
        event_id = "00000000-0000-0000-0000-000000000196"
        snapshot = self.snapshot(revision=4)
        snapshot["schema_version"] = 3
        registration = snapshot["machines"]["A"]["playing"][0]
        snapshot["private_player_profiles"] = [self.player_profile()]
        snapshot["private_player_contacts"] = [
            {
                "registration_id": registration["registration_id"],
                "profile_id": self.profile_id,
                "qq_number": "12345678",
            }
        ]
        snapshot["recent_events"] = [
            self.event(event_id, "PLAYING_CHANGED", 1_000_100)
        ]
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )
        connection = sqlite3.connect(self.database_path)
        try:
            connection.execute(
                "UPDATE queue_event_recipient SET stored_at = ? WHERE event_id = ?",
                (int(time.time()) - 2, event_id),
            )
            connection.commit()
        finally:
            connection.close()

        filtered_events = self.client.post(
            "/api/queue-bot/events",
            json={"qq": "12345678", "after": 0},
            headers=self.bot_headers,
        ).get_json()["events"]
        all_bot_events = self.client.get(
            "/api/queue-bot/events?after=0", headers=self.bot_headers
        ).get_json()["events"]
        public_logs = self.client.get("/api/queue-logs").get_json()["logs"]
        connection = sqlite3.connect(self.database_path)
        try:
            recipient_count = connection.execute(
                "SELECT COUNT(*) FROM queue_event_recipient WHERE event_id = ?",
                (event_id,),
            ).fetchone()[0]
            event_count = connection.execute(
                "SELECT COUNT(*) FROM queue_event WHERE event_id = ?",
                (event_id,),
            ).fetchone()[0]
        finally:
            connection.close()

        self.assertEqual([], filtered_events)
        self.assertEqual([event_id], [item["event_id"] for item in all_bot_events])
        self.assertEqual([], all_bot_events[0]["affected_players"])
        self.assertEqual([event_id], [item["event_id"] for item in public_logs])
        self.assertEqual(0, recipient_count)
        self.assertEqual(1, event_count)

    def test_rejects_duplicate_profile_nickname_or_qq(self):
        duplicate_qq = self.snapshot(revision=4)
        duplicate_qq["schema_version"] = 3
        second_profile = self.player_profile()
        second_profile["profile_id"] = "00000000-0000-0000-0000-000000000902"
        second_profile["nickname"] = "另一位玩家"
        duplicate_qq["private_player_profiles"] = [self.player_profile(), second_profile]
        duplicate_qq["private_player_contacts"] = []

        duplicate_nickname = self.snapshot(revision=5)
        duplicate_nickname["schema_version"] = 3
        second_profile = self.player_profile()
        second_profile["profile_id"] = "00000000-0000-0000-0000-000000000903"
        second_profile["nickname"] = "公开昵称"
        second_profile["qq_number"] = "87654321"
        duplicate_nickname["private_player_profiles"] = [
            self.player_profile(),
            second_profile,
        ]
        duplicate_nickname["private_player_contacts"] = []

        self.assertEqual(
            400,
            self.client.post(
                "/api/queue-status", json=duplicate_qq, headers=self.headers
            ).status_code,
        )
        self.assertEqual(
            400,
            self.client.post(
                "/api/queue-status", json=duplicate_nickname, headers=self.headers
            ).status_code,
        )

    def test_new_queue_clears_previous_queue_qq_bindings(self):
        first = self.snapshot(
            queue_id="00000000-0000-0000-0000-000000000001", revision=4
        )
        first["schema_version"] = 3
        registration = first["machines"]["A"]["playing"][0]
        first["private_player_profiles"] = [self.player_profile()]
        first["private_player_contacts"] = [
            {
                "registration_id": registration["registration_id"],
                "profile_id": self.profile_id,
                "qq_number": "12345678",
            }
        ]
        second = self.snapshot(
            queue_id="00000000-0000-0000-0000-000000000002", revision=1
        )
        second["schema_version"] = 3
        second["private_player_profiles"] = []
        second["private_player_contacts"] = []

        self.assertEqual(
            204,
            self.client.post("/api/queue-status", json=first, headers=self.headers).status_code,
        )
        self.assertEqual(
            204,
            self.client.post("/api/queue-status", json=second, headers=self.headers).status_code,
        )
        connection = sqlite3.connect(self.database_path)
        try:
            contact_count = connection.execute(
                "SELECT COUNT(*) FROM queue_private_contact"
            ).fetchone()[0]
            profile_count = connection.execute(
                "SELECT COUNT(*) FROM player_profile"
            ).fetchone()[0]
        finally:
            connection.close()

        self.assertEqual(0, contact_count)
        self.assertEqual(1, profile_count)
        self.assertEqual(
            401, self.client.get("/api/queue-terminal/profiles").status_code
        )
        terminal_profiles = self.client.get(
            "/api/queue-terminal/profiles", headers=self.headers
        ).get_json()["profiles"]
        self.assertEqual(1, len(terminal_profiles))
        self.assertEqual(self.profile_id, terminal_profiles[0]["profile_id"])

    def test_rejects_invalid_or_temporary_registration_qq_bindings(self):
        invalid_qq = self.snapshot(revision=4)
        invalid_qq["schema_version"] = 3
        registration = invalid_qq["machines"]["A"]["playing"][0]
        invalid_qq["private_player_profiles"] = [self.player_profile()]
        invalid_qq["private_player_contacts"] = [
            {
                "registration_id": registration["registration_id"],
                "profile_id": self.profile_id,
                "qq_number": "not-a-qq",
            }
        ]
        temporary = self.snapshot(revision=5)
        temporary["schema_version"] = 3
        temporary_registration = temporary["machines"]["A"]["playing"][0]
        temporary_registration["registration_type"] = "TEMPORARY"
        temporary["private_player_profiles"] = [self.player_profile()]
        temporary["private_player_contacts"] = [
            {
                "registration_id": temporary_registration["registration_id"],
                "profile_id": self.profile_id,
                "qq_number": "12345678",
            }
        ]

        self.assertEqual(
            400,
            self.client.post(
                "/api/queue-status", json=invalid_qq, headers=self.headers
            ).status_code,
        )
        self.assertEqual(
            400,
            self.client.post(
                "/api/queue-status", json=temporary, headers=self.headers
            ).status_code,
        )

    def test_rejects_older_revision_of_same_queue(self):
        current = self.snapshot(revision=8)
        older = self.snapshot(revision=7)

        self.assertEqual(
            204,
            self.client.post("/api/queue-status", json=current, headers=self.headers).status_code,
        )
        response = self.client.post(
            "/api/queue-status", json=older, headers=self.headers
        )

        self.assertEqual(409, response.status_code)

    def test_rejects_a_retired_queue_after_new_queue_is_accepted(self):
        first = self.snapshot(queue_id="00000000-0000-0000-0000-000000000001")
        second = self.snapshot(queue_id="00000000-0000-0000-0000-000000000002")
        delayed_first = self.snapshot(
            queue_id="00000000-0000-0000-0000-000000000001", revision=99
        )

        self.assertEqual(
            204,
            self.client.post("/api/queue-status", json=first, headers=self.headers).status_code,
        )
        self.assertEqual(
            204,
            self.client.post("/api/queue-status", json=second, headers=self.headers).status_code,
        )
        response = self.client.post(
            "/api/queue-status", json=delayed_first, headers=self.headers
        )

        self.assertEqual(409, response.status_code)
        self.assertEqual(
            second["queue_id"], self.client.get("/api/queue-status").get_json()["queue_id"]
        )

    def test_marks_terminal_offline_using_server_receive_time(self):
        self.client.post("/api/queue-status", json=self.snapshot(), headers=self.headers)
        connection = sqlite3.connect(self.database_path)
        try:
            connection.execute(
                "UPDATE queue_snapshot SET received_at = ? WHERE id = 1",
                (int(time.time()) - 91,),
            )
            connection.commit()
        finally:
            connection.close()

        data = self.client.get("/api/queue-status").get_json()

        self.assertFalse(data["terminal"]["online"])
        self.assertGreaterEqual(data["terminal"]["last_seen_seconds"], 91)

    def test_same_revision_heartbeat_refreshes_terminal_presence(self):
        snapshot = self.snapshot(revision=8)
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )
        connection = sqlite3.connect(self.database_path)
        try:
            connection.execute(
                "UPDATE queue_snapshot SET received_at = ? WHERE id = 1",
                (int(time.time()) - 91,),
            )
            connection.commit()
        finally:
            connection.close()
        self.assertFalse(
            self.client.get("/api/queue-status").get_json()["terminal"]["online"]
        )

        heartbeat = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )
        current = self.client.get("/api/queue-status").get_json()

        self.assertEqual(204, heartbeat.status_code)
        self.assertEqual(8, current["revision"])
        self.assertTrue(current["terminal"]["online"])
        self.assertLessEqual(current["terminal"]["last_seen_seconds"], 1)

    def test_rejects_duplicate_registration_ids_across_machines(self):
        snapshot = self.snapshot()
        duplicate = copy.deepcopy(snapshot["machines"]["A"]["playing"][0])
        snapshot["machines"]["B"]["playing"] = [duplicate]
        snapshot["machines"]["B"]["registration_count"] = 1

        response = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )

        self.assertEqual(400, response.status_code)

    def test_secondary_terminal_must_choose_test_or_takeover_after_primary_goes_offline(self):
        self.app.config["ALLOWED_DEVICE_ID"] = ""
        self.app.config["PRIMARY_DEVICE_ID"] = "terminal-1"
        primary = self.snapshot(queue_id="00000000-0000-0000-0000-000000000001")
        secondary = self.snapshot(queue_id="00000000-0000-0000-0000-000000000002")

        self.assertEqual(
            204,
            self.client.post("/api/queue-status", json=primary, headers=self.headers).status_code,
        )
        secondary_headers = {**self.headers, "X-Device-ID": "terminal-2"}
        rejected = self.client.post(
            "/api/queue-status", json=secondary, headers=secondary_headers
        )

        self.assertEqual(409, rejected.status_code)

        connection = sqlite3.connect(self.database_path)
        try:
            connection.execute(
                "UPDATE queue_snapshot SET received_at = ? WHERE id = 1",
                (int(time.time()) - 91,),
            )
            connection.commit()
        finally:
            connection.close()

        choice_required = self.client.post(
            "/api/queue-status", json=secondary, headers=secondary_headers
        )
        self.assertEqual(409, choice_required.status_code)
        self.assertEqual("SYNC_MODE_REQUIRED", choice_required.get_json()["code"])
        self.assertEqual(1, choice_required.get_json()["current_registration_count"])
        self.assertEqual(1, choice_required.get_json()["local_registration_count"])

        test_started = self.client.post(
            "/api/queue-status",
            json=secondary,
            headers={**secondary_headers, "X-Queue-Sync-Mode": "test"},
        )
        self.assertEqual(204, test_started.status_code)
        self.assertTrue(self.client.get("/api/queue-status").get_json()["test_data"])

        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=primary, headers=self.headers
            ).status_code,
        )

        connection = sqlite3.connect(self.database_path)
        try:
            retired_queue_ids = {
                row[0] for row in connection.execute("SELECT queue_id FROM retired_queue")
            }
        finally:
            connection.close()
        current = self.client.get("/api/queue-status").get_json()

        self.assertEqual("terminal-1", current["terminal"]["id"])
        self.assertEqual(primary["queue_id"], current["queue_id"])
        self.assertFalse(current["test_data"])
        self.assertNotIn(primary["queue_id"], retired_queue_ids)
        self.assertNotIn(secondary["queue_id"], retired_queue_ids)

    def test_explicit_takeover_publishes_official_data_after_current_terminal_is_offline(self):
        self.app.config["ALLOWED_DEVICE_ID"] = ""
        self.app.config["PRIMARY_DEVICE_ID"] = "terminal-1"
        primary = self.snapshot(queue_id="00000000-0000-0000-0000-000000000011")
        secondary = self.snapshot(queue_id="00000000-0000-0000-0000-000000000012")
        self.client.post("/api/queue-status", json=primary, headers=self.headers)
        connection = sqlite3.connect(self.database_path)
        try:
            connection.execute(
                "UPDATE queue_snapshot SET received_at = ? WHERE id = 1",
                (int(time.time()) - 91,),
            )
            connection.commit()
        finally:
            connection.close()

        response = self.client.post(
            "/api/queue-status",
            json=secondary,
            headers={
                **self.headers,
                "X-Device-ID": "terminal-2",
                "X-Queue-Sync-Mode": "takeover",
            },
        )
        current = self.client.get("/api/queue-status").get_json()

        self.assertEqual(204, response.status_code)
        self.assertEqual("terminal-2", current["terminal"]["id"])
        self.assertFalse(current["test_data"])

    def test_primary_restore_rejects_only_commands_created_for_test_terminal(self):
        self.app.config["ALLOWED_DEVICE_ID"] = ""
        self.app.config["PRIMARY_DEVICE_ID"] = "terminal-1"
        primary = self.snapshot(queue_id="00000000-0000-0000-0000-000000000021")
        primary["schema_version"] = 3
        primary["private_player_profiles"] = [self.player_profile()]
        primary["private_player_contacts"] = []
        self.client.post("/api/queue-status", json=primary, headers=self.headers)
        connection = sqlite3.connect(self.database_path)
        try:
            connection.execute(
                "UPDATE queue_snapshot SET received_at = ? WHERE id = 1",
                (int(time.time()) - 91,),
            )
            connection.commit()
        finally:
            connection.close()

        test_snapshot = copy.deepcopy(primary)
        test_snapshot["queue_id"] = "00000000-0000-0000-0000-000000000022"
        secondary_headers = {
            **self.headers,
            "X-Device-ID": "terminal-2",
            "X-Queue-Sync-Mode": "test",
        }
        self.client.post(
            "/api/queue-status", json=test_snapshot, headers=secondary_headers
        )
        command_id = "00000000-0000-0000-0000-000000000784"
        created = self.client.patch(
            f"/api/queue-bot/profiles/{self.profile_id}",
            json={
                "request_id": command_id,
                "actor_qq": "12345678",
                "nickname": "测试资料修改",
            },
            headers=self.bot_headers,
        )
        self.assertEqual(202, created.status_code)

        primary["revision"] += 1
        restored = self.client.post(
            "/api/queue-status", json=primary, headers=self.headers
        )
        command = self.client.get(
            f"/api/queue-bot/commands/{command_id}", headers=self.bot_headers
        ).get_json()

        self.assertEqual(204, restored.status_code)
        self.assertEqual("REJECTED", command["status"])
        self.assertEqual("SERVER_MIGRATION", command["result_source"])
        self.assertIn("测试同步已经结束", command["result_detail"])

    def test_schema_v2_preserves_valid_machine_names(self):
        snapshot = self.snapshot()
        snapshot["schema_version"] = 2
        snapshot["machines"]["A"]["name"] = "入口侧 · 机台 A"
        snapshot["machines"]["B"]["name"] = "墙侧 · 机台 B"

        response = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )
        current = self.client.get("/api/queue-status").get_json()

        self.assertEqual(204, response.status_code)
        self.assertEqual("入口侧 · 机台 A", current["machines"]["A"]["name"])
        self.assertEqual("墙侧 · 机台 B", current["machines"]["B"]["name"])

    def test_schema_v1_keeps_default_machine_names(self):
        snapshot = self.snapshot()
        snapshot["machines"]["A"]["name"] = "入口侧 · 机台 A"

        response = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )
        current = self.client.get("/api/queue-status").get_json()

        self.assertEqual(204, response.status_code)
        self.assertEqual("左侧 · 机台 A", current["machines"]["A"]["name"])

    def test_schema_v2_rejects_mismatched_or_overlong_machine_names(self):
        mismatched = self.snapshot(revision=5)
        mismatched["schema_version"] = 2
        mismatched["machines"]["A"]["name"] = "入口侧 · 机台 B"
        overlong = self.snapshot(revision=6)
        overlong["schema_version"] = 2
        overlong["machines"]["A"]["name"] = "一二三四五六七八九 · 机台 A"

        mismatched_response = self.client.post(
            "/api/queue-status", json=mismatched, headers=self.headers
        )
        overlong_response = self.client.post(
            "/api/queue-status", json=overlong, headers=self.headers
        )

        self.assertEqual(400, mismatched_response.status_code)
        self.assertEqual(400, overlong_response.status_code)

    def test_preserves_maintenance_and_optional_other_stop_detail(self):
        maintenance = self.snapshot(revision=7)
        maintenance["machines"]["A"].update(
            operational=False,
            stop_reason="MAINTENANCE",
            stopped_at=1_000_000,
        )
        maintenance_response = self.client.post(
            "/api/queue-status", json=maintenance, headers=self.headers
        )
        maintenance_current = self.client.get("/api/queue-status").get_json()

        other = self.snapshot(revision=8)
        other["machines"]["A"].update(
            operational=False,
            stop_reason="OTHER",
            stop_reason_detail="  按钮失灵  ",
            stopped_at=1_000_100,
        )
        other_response = self.client.post(
            "/api/queue-status", json=other, headers=self.headers
        )
        other_current = self.client.get("/api/queue-status").get_json()

        invalid = self.snapshot(revision=9)
        invalid["machines"]["A"].update(
            operational=False,
            stop_reason="NETWORK_DISCONNECTED",
            stop_reason_detail="不应附加说明",
            stopped_at=1_000_200,
        )
        invalid_response = self.client.post(
            "/api/queue-status", json=invalid, headers=self.headers
        )

        self.assertEqual(204, maintenance_response.status_code)
        self.assertEqual("MAINTENANCE", maintenance_current["machines"]["A"]["stop_reason"])
        self.assertIsNone(maintenance_current["machines"]["A"]["stop_reason_detail"])
        self.assertEqual(204, other_response.status_code)
        self.assertEqual("按钮失灵", other_current["machines"]["A"]["stop_reason_detail"])
        self.assertEqual(400, invalid_response.status_code)

    def test_website_profile_lookup_and_join_are_terminal_confirmed_and_idempotent(self):
        snapshot = self.remote_ready_snapshot()
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )

        profile = self.client.post(
            "/api/queue-online/profile", json={"qq": "12345678"}
        )
        request_id = "00000000-0000-0000-0000-000000000401"
        request_body = {
            "request_id": request_id,
            "qq": "12345678",
            "machine_id": "A",
        }
        created = self.client.post("/api/queue-online/join", json=request_body)
        repeated = self.client.post("/api/queue-online/join", json=request_body)
        commands = self.client.get(
            "/api/queue-terminal/commands", headers=self.headers
        ).get_json()["commands"]

        self.assertEqual(200, profile.status_code)
        self.assertEqual("公开昵称", profile.get_json()["profile"]["nickname"])
        self.assertTrue(profile.get_json()["capabilities"]["online_registration"])
        self.assertEqual(202, created.status_code)
        self.assertEqual(200, repeated.status_code)
        self.assertEqual(request_id, repeated.get_json()["command_id"])
        self.assertEqual(1, len(commands))
        self.assertEqual("QUEUE_OPERATION", commands[0]["type"])
        self.assertEqual("JOIN_QUEUE", commands[0]["payload"]["operation"])
        self.assertEqual("WEBSITE_REMOTE", commands[0]["payload"]["operation_source"])

    def test_legacy_profile_can_join_online_before_completing_setup(self):
        snapshot = self.remote_ready_snapshot(revision=22)
        snapshot["private_player_profiles"][0]["setup_version"] = 0
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )

        profile = self.client.post(
            "/api/queue-online/profile", json={"qq": "12345678"}
        )
        joined = self.client.post(
            "/api/queue-online/join",
            json={
                "request_id": "00000000-0000-0000-0000-000000000420",
                "qq": "12345678",
                "machine_id": "A",
            },
        )

        self.assertEqual(200, profile.status_code)
        self.assertEqual(0, profile.get_json()["profile"]["setup_version"])
        self.assertEqual(202, joined.status_code)
        self.assertEqual("PENDING", joined.get_json()["status"])

    def test_online_registration_switch_blocks_new_joins_but_keeps_existing_management(self):
        snapshot = self.remote_ready_snapshot(
            with_registration=True,
            allow_online_registration=False,
        )
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )

        public_status = self.client.get("/api/queue-status").get_json()
        website_profile = self.client.post(
            "/api/queue-online/profile", json={"qq": "12345678"}
        )
        website_join = self.client.post(
            "/api/queue-online/join",
            json={
                "request_id": "00000000-0000-0000-0000-000000000421",
                "qq": "12345678",
                "machine_id": "A",
            },
        )
        bot_join = self.client.post(
            "/api/queue-bot/queue-commands",
            json={
                "request_id": "00000000-0000-0000-0000-000000000422",
                "actor_qq": "12345678",
                "operation": "JOIN_QUEUE",
                "machine_id": "A",
            },
            headers=self.bot_headers,
        )
        bot_players = self.client.post(
            "/api/queue-bot/players",
            json={"qq": "12345678"},
            headers=self.bot_headers,
        )
        bot_leave = self.client.post(
            "/api/queue-bot/queue-commands",
            json={
                "request_id": "00000000-0000-0000-0000-000000000423",
                "actor_qq": "12345678",
                "operation": "LEAVE_QUEUE",
                "machine_id": "A",
            },
            headers=self.bot_headers,
        )

        self.assertFalse(public_status["capabilities"]["online_registration"])
        self.assertEqual(503, website_profile.status_code)
        self.assertEqual("现场规则暂不允许线上登记", website_profile.get_json()["error"])
        self.assertEqual(503, website_join.status_code)
        self.assertEqual(503, bot_join.status_code)
        self.assertEqual(200, bot_players.status_code)
        self.assertEqual(202, bot_leave.status_code)

    def test_online_join_requires_a_current_preference_when_profile_asks_each_time(self):
        snapshot = self.remote_ready_snapshot(default_preference="ASK_EVERY_TIME")
        self.client.post("/api/queue-status", json=snapshot, headers=self.headers)

        missing = self.client.post(
            "/api/queue-online/join",
            json={
                "request_id": "00000000-0000-0000-0000-000000000402",
                "qq": "12345678",
                "machine_id": "A",
            },
        )
        selected = self.client.post(
            "/api/queue-online/join",
            json={
                "request_id": "00000000-0000-0000-0000-000000000403",
                "qq": "12345678",
                "machine_id": "A",
                "preference": "SOLO",
            },
        )

        self.assertEqual(400, missing.status_code)
        self.assertEqual("请选择本次游玩偏好", missing.get_json()["error"])
        self.assertEqual(202, selected.status_code)

    def test_pending_online_registration_only_allows_remote_leave(self):
        snapshot = self.remote_ready_snapshot(with_registration=True, pending_check_in=True)
        self.client.post("/api/queue-status", json=snapshot, headers=self.headers)

        deferred = self.client.post(
            "/api/queue-bot/queue-commands",
            json={
                "request_id": "00000000-0000-0000-0000-000000000404",
                "actor_qq": "12345678",
                "operation": "DEFER_ONE_ROUND",
            },
            headers=self.bot_headers,
        )
        left = self.client.post(
            "/api/queue-bot/queue-commands",
            json={
                "request_id": "00000000-0000-0000-0000-000000000405",
                "actor_qq": "12345678",
                "operation": "LEAVE_QUEUE",
            },
            headers=self.bot_headers,
        )

        self.assertEqual(409, deferred.status_code)
        self.assertIn("完成现场签到后", deferred.get_json()["error"])
        self.assertEqual(202, left.status_code)

    def test_disabling_onebot_does_not_reject_a_pending_website_join(self):
        enabled = self.remote_ready_snapshot(revision=4)
        self.client.post("/api/queue-status", json=enabled, headers=self.headers)
        command_id = "00000000-0000-0000-0000-000000000406"
        created = self.client.post(
            "/api/queue-online/join",
            json={
                "request_id": command_id,
                "qq": "12345678",
                "machine_id": "A",
            },
        )

        disabled = self.remote_ready_snapshot(revision=5)
        disabled["onebot_sync_enabled"] = False
        published = self.client.post(
            "/api/queue-status", json=disabled, headers=self.headers
        )
        pending = self.client.get(
            "/api/queue-terminal/commands", headers=self.headers
        ).get_json()["commands"]

        self.assertEqual(202, created.status_code)
        self.assertEqual(204, published.status_code)
        self.assertEqual([command_id], [item["command_id"] for item in pending])

    def test_rejects_pending_online_registration_in_playing_position(self):
        snapshot = self.remote_ready_snapshot(with_registration=True, pending_check_in=True)
        registration = snapshot["machines"]["A"]["waiting_positions"][0][
            "registrations"
        ][0]
        snapshot["machines"]["A"]["playing"] = [registration]
        snapshot["machines"]["A"]["playing_started_at"] = 900_000
        snapshot["machines"]["A"]["waiting_positions"] = []

        response = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )

        self.assertEqual(400, response.status_code)
        self.assertIn("待签到登记不能处于游玩位置", response.get_json()["error"])

    def test_profile_and_queue_commands_for_the_same_player_cannot_overlap(self):
        snapshot = self.remote_ready_snapshot()
        self.client.post("/api/queue-status", json=snapshot, headers=self.headers)
        queue_command = self.client.post(
            "/api/queue-online/join",
            json={
                "request_id": "00000000-0000-0000-0000-000000000407",
                "qq": "12345678",
                "machine_id": "A",
            },
        )
        profile_command = self.client.patch(
            f"/api/queue-bot/profiles/{self.profile_id}",
            json={
                "request_id": "00000000-0000-0000-0000-000000000408",
                "actor_qq": "12345678",
                "nickname": "不应并发更新",
            },
            headers=self.bot_headers,
        )

        self.assertEqual(202, queue_command.status_code)
        self.assertEqual(409, profile_command.status_code)
        self.assertEqual(
            "你已有一项操作正在等待终端处理",
            profile_command.get_json()["error"],
        )

    def test_bot_identity_and_mobile_registration_session_are_private_and_one_time(self):
        snapshot = self.remote_ready_snapshot(revision=20)
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )
        identity = self.client.post(
            "/api/queue-bot/identity",
            json={"bot_qq": "87654321"},
            headers=self.bot_headers,
        )
        self.assertEqual(200, identity.status_code)

        session_id = "00000000-0000-0000-0000-000000000820"
        created = self.client.post(
            "/api/queue-terminal/mobile-registration-sessions",
            json={
                "request_id": session_id,
                "queue_id": snapshot["queue_id"],
                "machine_id": "A",
            },
            headers=self.headers,
        )
        self.assertEqual(201, created.status_code)
        token = created.get_json()["session_token"]
        self.assertIn(
            "/queue-status?mobile_registration=",
            created.get_json()["registration_url"],
        )

        opened = self.client.get(f"/api/queue-mobile/sessions/{token}")
        self.assertEqual(200, opened.status_code)
        opened_payload = opened.get_json()
        self.assertEqual("87654321", opened_payload["bot_qq"])
        self.assertEqual(1, len(opened_payload["profiles"]))
        self.assertIsNone(opened_payload["profiles"][0]["qq_number"])
        self.assertTrue(opened_payload["profiles"][0]["qq_present"])
        self.assertFalse(opened_payload["profiles"][0]["qq_public"])

        command_id = "00000000-0000-0000-0000-000000000821"
        submission = {
            "request_id": command_id,
            "profile_id": self.profile_id,
            "expected_profile_revision": 3,
        }
        submitted = self.client.post(
            f"/api/queue-mobile/sessions/{token}/submit",
            json=submission,
        )
        self.assertEqual(202, submitted.status_code)
        repeated = self.client.post(
            f"/api/queue-mobile/sessions/{token}/submit",
            json=submission,
        )
        self.assertEqual(200, repeated.status_code)
        self.assertEqual(command_id, repeated.get_json()["command_id"])
        self.assertEqual("PENDING", repeated.get_json()["status"])
        commands = self.client.get(
            "/api/queue-terminal/commands", headers=self.headers
        ).get_json()["commands"]
        command = next(value for value in commands if value["command_id"] == command_id)
        self.assertEqual("MOBILE_DEVICE_REGISTRATION", command["type"])
        self.assertEqual("MOBILE_DEVICE", command["payload"]["operation_source"])
        self.assertNotIn("online_registration_pending_check_in", command["payload"])

        completed = self.client.post(
            f"/api/queue-terminal/commands/{command_id}/result",
            json={"status": "APPLIED", "detail": "已通过移动设备加入排队。"},
            headers=self.headers,
        )
        self.assertEqual(200, completed.status_code)
        result = self.client.get(
            f"/api/queue-mobile/sessions/{token}/result"
        ).get_json()
        self.assertEqual("APPLIED", result["status"])
        self.assertEqual(self.profile_id, result["profile_id"])

        reused = self.client.post(
            f"/api/queue-mobile/sessions/{token}/submit",
            json={
                "request_id": "00000000-0000-0000-0000-000000000822",
                "profile_id": self.profile_id,
                "expected_profile_revision": 3,
            },
        )
        self.assertEqual(409, reused.status_code)

    def test_mobile_registration_allows_rejoining_after_the_previous_registration_left(self):
        active_snapshot = self.remote_ready_snapshot(revision=20, with_registration=True)
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=active_snapshot, headers=self.headers
            ).status_code,
        )
        created = self.client.post(
            "/api/queue-terminal/mobile-registration-sessions",
            json={
                "request_id": "00000000-0000-0000-0000-000000000823",
                "queue_id": active_snapshot["queue_id"],
                "machine_id": "A",
            },
            headers=self.headers,
        ).get_json()
        token = created["session_token"]
        submission = {
            "request_id": "00000000-0000-0000-0000-000000000824",
            "profile_id": self.profile_id,
            "expected_profile_revision": 3,
        }

        while_active = self.client.post(
            f"/api/queue-mobile/sessions/{token}/submit", json=submission
        )
        self.assertEqual(409, while_active.status_code)
        self.assertEqual(
            "这名玩家已经有一份正在排队的登记",
            while_active.get_json()["error"],
        )
        self.assertEqual(
            "PLAYER_ALREADY_REGISTERED",
            while_active.get_json()["code"],
        )

        departed_snapshot = self.remote_ready_snapshot(revision=21)
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=departed_snapshot, headers=self.headers
            ).status_code,
        )
        connection = sqlite3.connect(self.database_path)
        try:
            retained_contact_count = connection.execute(
                "SELECT COUNT(*) FROM queue_private_contact WHERE queue_id = ?",
                (active_snapshot["queue_id"],),
            ).fetchone()[0]
        finally:
            connection.close()

        after_departure = self.client.post(
            f"/api/queue-mobile/sessions/{token}/submit", json=submission
        )
        self.assertEqual(1, retained_contact_count)
        self.assertEqual(202, after_departure.status_code)

    def test_mobile_registration_requires_explicit_completion_for_legacy_profiles(self):
        snapshot = self.remote_ready_snapshot(revision=21)
        legacy_profile = snapshot["private_player_profiles"][0]
        legacy_profile.update(
            setup_version=0,
            notification_enabled=True,
            notify_queue_changes=False,
            notify_playing_position=True,
            notify_online_check_in=False,
            notify_absence=True,
            notify_machine_status=True,
        )
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )
        created = self.client.post(
            "/api/queue-terminal/mobile-registration-sessions",
            json={
                "request_id": "00000000-0000-0000-0000-000000000830",
                "queue_id": snapshot["queue_id"],
                "machine_id": "A",
            },
            headers=self.headers,
        ).get_json()
        token = created["session_token"]
        opened_profile = self.client.get(
            f"/api/queue-mobile/sessions/{token}"
        ).get_json()["profiles"][0]
        self.assertFalse(opened_profile["notify_queue_changes"])
        self.assertTrue(opened_profile["notify_playing_position"])
        self.assertFalse(opened_profile["notify_online_check_in"])
        self.assertTrue(opened_profile["notify_absence"])
        self.assertTrue(opened_profile["notify_machine_status"])

        missing_completion = self.client.post(
            f"/api/queue-mobile/sessions/{token}/submit",
            json={
                "request_id": "00000000-0000-0000-0000-000000000831",
                "profile_id": self.profile_id,
                "expected_profile_revision": 3,
            },
        )
        self.assertEqual(400, missing_completion.status_code)

        submitted = self.client.post(
            f"/api/queue-mobile/sessions/{token}/submit",
            json={
                "request_id": "00000000-0000-0000-0000-000000000832",
                "profile_id": self.profile_id,
                "expected_profile_revision": 3,
                "profile_completion": {
                    "qq_visibility": "TERMINAL_ONLY",
                    "notification_enabled": True,
                    "notify_queue_changes": False,
                    "notify_playing_position": True,
                    "notify_online_check_in": False,
                    "notify_absence": True,
                    "notify_machine_status": True,
                },
            },
        )
        self.assertEqual(202, submitted.status_code)
        commands = self.client.get(
            "/api/queue-terminal/commands", headers=self.headers
        ).get_json()["commands"]
        completion = next(
            value for value in commands
            if value["command_id"] == "00000000-0000-0000-0000-000000000832"
        )["payload"]["profile"]["completion"]
        self.assertFalse(completion["notify_queue_changes"])
        self.assertTrue(completion["notify_playing_position"])
        self.assertFalse(completion["notify_online_check_in"])
        self.assertTrue(completion["notify_machine_status"])

    def test_mobile_registration_keeps_the_profile_used_by_the_current_terminal(self):
        stale_snapshot = self.remote_ready_snapshot(revision=21)
        stale_snapshot["private_player_profiles"][0]["setup_version"] = 0
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=stale_snapshot, headers=self.headers
            ).status_code,
        )
        alias_id = "00000000-0000-0000-0000-000000000902"
        connection = sqlite3.connect(self.database_path)
        try:
            connection.execute(
                "UPDATE player_profile SET received_at = 1 WHERE profile_id = ?",
                (self.profile_id,),
            )
            connection.commit()
        finally:
            connection.close()

        snapshot = self.remote_ready_snapshot(revision=22)
        current_profile = snapshot["private_player_profiles"][0]
        current_profile.update(
            profile_id=alias_id,
            qq_number=None,
            usage_count=37,
            setup_version=0,
            profile_revision=1,
            created_at=700_000,
            updated_at=850_000,
        )
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )

        created = self.client.post(
            "/api/queue-terminal/mobile-registration-sessions",
            json={
                "request_id": "00000000-0000-0000-0000-000000000833",
                "queue_id": snapshot["queue_id"],
                "machine_id": "A",
            },
            headers=self.headers,
        ).get_json()
        token = created["session_token"]
        opened = self.client.get(
            f"/api/queue-mobile/sessions/{token}"
        ).get_json()

        self.assertEqual(
            [alias_id],
            [profile["profile_id"] for profile in opened["profiles"]],
        )
        self.assertEqual(alias_id, opened["profile_aliases"][self.profile_id])
        synced_profiles = self.client.get(
            "/api/queue-terminal/profiles", headers=self.headers
        ).get_json()
        self.assertEqual(
            [alias_id],
            [profile["profile_id"] for profile in synced_profiles["profiles"]],
        )
        self.assertEqual(
            alias_id,
            synced_profiles["profile_aliases"][self.profile_id],
        )

        submitted = self.client.post(
            f"/api/queue-mobile/sessions/{token}/submit",
            json={
                "request_id": "00000000-0000-0000-0000-000000000834",
                "profile_id": alias_id,
                "expected_profile_revision": 1,
                "profile_completion": {
                    "qq_number": "12345678",
                    "qq_visibility": "TERMINAL_ONLY",
                    "notification_enabled": True,
                    "notify_queue_changes": True,
                    "notify_playing_position": False,
                    "notify_online_check_in": True,
                    "notify_absence": True,
                    "notify_machine_status": False,
                },
            },
        )

        self.assertEqual(202, submitted.status_code)
        commands = self.client.get(
            "/api/queue-terminal/commands", headers=self.headers
        ).get_json()["commands"]
        command = next(
            item
            for item in commands
            if item["command_id"] == "00000000-0000-0000-0000-000000000834"
        )
        self.assertEqual(alias_id, command["payload"]["profile"]["profile_id"])
        self.assertEqual(1, command["payload"]["profile"]["expected_profile_revision"])

    def test_mobile_registration_can_create_a_complete_player_profile(self):
        snapshot = self.remote_ready_snapshot(revision=22)
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )
        created = self.client.post(
            "/api/queue-terminal/mobile-registration-sessions",
            json={
                "request_id": "00000000-0000-0000-0000-000000000840",
                "queue_id": snapshot["queue_id"],
                "machine_id": "B",
            },
            headers=self.headers,
        ).get_json()

        command_id = "00000000-0000-0000-0000-000000000841"
        submitted = self.client.post(
            f"/api/queue-mobile/sessions/{created['session_token']}/submit",
            json={
                "request_id": command_id,
                "preference": "SOLO",
                "new_profile": {
                    "nickname": "移动新玩家",
                    "gender": "FEMALE",
                    "default_preference": "ASK_EVERY_TIME",
                    "qq_number": "87654321",
                    "qq_visibility": "PUBLIC_WEBSITE",
                    "notification_enabled": True,
                    "notify_queue_changes": True,
                    "notify_playing_position": False,
                    "notify_online_check_in": True,
                    "notify_absence": True,
                    "notify_machine_status": False,
                },
            },
        )
        self.assertEqual(202, submitted.status_code)

        commands = self.client.get(
            "/api/queue-terminal/commands", headers=self.headers
        ).get_json()["commands"]
        command = next(value for value in commands if value["command_id"] == command_id)
        payload = command["payload"]
        profile = payload["profile"]
        self.assertEqual("MOBILE_DEVICE_REGISTRATION", command["type"])
        self.assertEqual("MOBILE_DEVICE", payload["operation_source"])
        self.assertEqual("B", payload["machine_id"])
        self.assertEqual("87654321", payload["actor_qq"])
        self.assertEqual("SOLO", payload["preference"])
        self.assertEqual("NEW", profile["mode"])
        self.assertEqual("移动新玩家", profile["profile"]["nickname"])
        self.assertEqual("PUBLIC_WEBSITE", profile["profile"]["qq_visibility"])
        self.assertEqual(1, profile["profile"]["setup_version"])
        self.assertIsNone(profile.get("expected_profile_revision"))
        self.assertIsNone(profile.get("completion"))
        UUID(profile["profile_id"])

    def test_mobile_registration_rechecks_machine_state_before_submission(self):
        snapshot = self.remote_ready_snapshot(revision=23)
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )
        created = self.client.post(
            "/api/queue-terminal/mobile-registration-sessions",
            json={
                "request_id": "00000000-0000-0000-0000-000000000850",
                "queue_id": snapshot["queue_id"],
                "machine_id": "A",
            },
            headers=self.headers,
        ).get_json()

        stopped = copy.deepcopy(snapshot)
        stopped["revision"] += 1
        stopped["machines"]["A"].update(
            operational=False,
            stop_reason="MAINTENANCE",
            stopped_at=1_000_100,
        )
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=stopped, headers=self.headers
            ).status_code,
        )

        submitted = self.client.post(
            f"/api/queue-mobile/sessions/{created['session_token']}/submit",
            json={
                "request_id": "00000000-0000-0000-0000-000000000851",
                "profile_id": self.profile_id,
                "expected_profile_revision": 3,
            },
        )
        self.assertEqual(409, submitted.status_code)
        self.assertEqual("MACHINE_STOPPED", submitted.get_json()["code"])
        commands = self.client.get(
            "/api/queue-terminal/commands", headers=self.headers
        ).get_json()["commands"]
        self.assertFalse(any(
            command["command_id"] == "00000000-0000-0000-0000-000000000851"
            for command in commands
        ))

    def remote_ready_snapshot(
        self,
        revision=4,
        default_preference="OPEN_TO_JOIN",
        with_registration=False,
        pending_check_in=False,
        allow_online_registration=True,
    ):
        snapshot = self.snapshot(revision=revision)
        snapshot.update(
            schema_version=5,
            website_remote_enabled=True,
            onebot_sync_enabled=True,
            queue_rules={
                "allow_defer_one_round": True,
                "allow_temporary_leave": True,
                "allow_online_registration": allow_online_registration,
            },
        )
        profile = self.player_profile()
        profile["default_preference"] = default_preference
        snapshot["private_player_profiles"] = [profile]
        snapshot["private_player_contacts"] = []
        snapshot["machines"]["A"] = self.machine(name="左侧 · 机台 A")
        snapshot["machines"]["B"] = self.machine(name="右侧 · 机台 B")
        snapshot["machines"]["A"]["new_registration_estimated_wait_minutes"] = 0
        snapshot["machines"]["B"]["new_registration_estimated_wait_minutes"] = 0
        if with_registration:
            registration = self.registration("a" * 24, "公开昵称")
            registration["online_registration_pending_check_in"] = pending_check_in
            snapshot["machines"]["A"]["waiting_positions"] = [
                {
                    "index": 1,
                    "position_id": "b" * 24,
                    "fixed_pair": False,
                    "estimated_wait_minutes": None if pending_check_in else 0,
                    "registrations": [registration],
                }
            ]
            snapshot["machines"]["A"]["registration_count"] = 1
            snapshot["machines"]["A"]["waiting_position_count"] = 1
            snapshot["private_player_contacts"] = [
                {
                    "registration_id": registration["registration_id"],
                    "profile_id": self.profile_id,
                    "qq_number": "12345678",
                }
            ]
        return snapshot

    def snapshot(
        self,
        queue_id="00000000-0000-0000-0000-000000000001",
        revision=4,
    ):
        return {
            "schema_version": 1,
            "queue_id": queue_id,
            "revision": revision,
            "captured_at": 1_000_000,
            "registration_open": True,
            "terminal": {
                "id": "ignored-client-id",
                "online": True,
                "app_version": "0.2.0",
                "last_seen_at": 1_000_000,
            },
            "machines": {
                "A": self.machine(
                    name="左侧 · 机台 A",
                    playing=[self.registration("a" * 24, "公开昵称")]
                ),
                "B": self.machine(name="右侧 · 机台 B"),
            },
        }

    @staticmethod
    def machine(name, playing=None):
        playing = playing or []
        return {
            "id": "ignored",
            "name": name,
            "operational": True,
            "stop_reason": None,
            "stop_reason_detail": None,
            "stopped_at": None,
            "playing_started_at": 900_000 if playing else None,
            "registration_count": len(playing),
            "waiting_position_count": 0,
            "playing": playing,
            "waiting_positions": [],
        }

    @staticmethod
    def registration(registration_id, display_id):
        return {
            "registration_id": registration_id,
            "display_id": display_id,
            "preference": "SOLO",
            "deferred_once": False,
            "temporarily_away": False,
            "temporary_away_skipped_turns": 0,
            "fixed_pair": False,
            "fixed_pair_id": None,
            "no_show_count": 0,
            "last_no_show_action_was_defer": False,
            "registration_type": "PLAYER_PROFILE",
            "created_at": 800_000,
            "last_played_at": None,
        }

    @staticmethod
    def event(event_id, event_type, occurred_at):
        return {
            "event_id": event_id,
            "occurred_at": occurred_at,
            "machine_id": "A",
            "type": event_type,
            "title": "机台 A · 队列已更新",
            "detail": "“公开昵称”的排队状态已更新。",
            "registration_ids": ["a" * 24],
        }

    def player_profile(self):
        return {
            "profile_id": self.profile_id,
            "nickname": "公开昵称",
            "gender": "UNDISCLOSED",
            "default_preference": "OPEN_TO_JOIN",
            "qq_number": "12345678",
            "usage_count": 3,
            "last_used_at": 900_000,
            "qq_visibility": "TERMINAL_ONLY",
            "notification_enabled": True,
            "notify_queue_changes": True,
            "notify_playing_position": False,
            "notify_online_check_in": True,
            "notify_absence": True,
            "notify_machine_status": False,
            "setup_version": 1,
            "profile_revision": 3,
            "created_at": 800_000,
            "updated_at": 950_000,
        }


if __name__ == "__main__":
    unittest.main()
