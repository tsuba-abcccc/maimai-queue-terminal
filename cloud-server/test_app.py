import copy
import sqlite3
import tempfile
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from io import BytesIO
from pathlib import Path
from threading import Barrier
from unittest.mock import MagicMock, patch
from urllib.parse import urlsplit
from uuid import UUID

from app import (
    ValidationError,
    compact_middle_dots,
    create_app,
    initialize_database,
    normalize_avatar_upload,
)


class QueueStatusApiTest(unittest.TestCase):
    profile_id = "00000000-0000-0000-0000-000000000901"
    sync_token = "s" * 32
    bot_token = "b" * 32
    management_token = "m" * 32

    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.database_path = str(Path(self.temporary_directory.name) / "queue.db")
        self.app = create_app(
            {
                "TESTING": True,
                "DATABASE_PATH": self.database_path,
                "SYNC_TOKEN": self.sync_token,
                "BOT_TOKEN": self.bot_token,
                "MANAGEMENT_TOKEN": self.management_token,
                "ALLOWED_DEVICE_ID": "terminal-1",
                "CORS_ORIGIN": "https://queue.example.test",
                "PUBLIC_SITE_URL": "https://queue.example.test/queue-status",
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
        self.management_headers = {
            "Authorization": f"Bearer {self.management_token}"
        }

    def test_middle_dot_compaction_does_not_consume_line_breaks(self):
        self.assertEqual("甲·乙\n丙·丁", compact_middle_dots("甲 · 乙\n丙 \u3000·\u00a0丁"))
        self.assertEqual("甲\n·乙", compact_middle_dots("甲\n · 乙"))
        self.assertEqual("甲·\n乙", compact_middle_dots("甲 ·\n乙"))

    def schema_eight_terminal_headers(self):
        installation = self.client.get(
            "/api/queue-terminal/installation", headers=self.headers
        ).get_json()
        return {
            **self.headers,
            "X-Queue-Schema-Version": "8",
            "X-Queue-Venue-ID": installation["venue"]["id"],
        }

    def tearDown(self):
        self.temporary_directory.cleanup()

    def test_requires_terminal_authentication(self):
        response = self.client.post("/api/queue-status", json=self.snapshot())

        self.assertEqual(401, response.status_code)

    def test_terminal_installation_registration_is_idempotent_and_rename_keeps_id(self):
        initial = self.client.post(
            "/api/queue-terminal/installation",
            json={"venue_name": "初始机厅", "terminal_name": "入口终端"},
            headers=self.headers,
        )
        repeated = self.client.post(
            "/api/queue-terminal/installation",
            json={"venue_name": "初始机厅", "terminal_name": "入口终端"},
            headers=self.headers,
        )
        renamed = self.client.post(
            "/api/queue-terminal/installation",
            json={
                "venue_id": initial.get_json()["venue"]["id"],
                "venue_name": "更名后的机厅",
                "terminal_name": "服务台终端",
            },
            headers=self.headers,
        )

        self.assertEqual(200, initial.status_code)
        self.assertEqual(200, repeated.status_code)
        self.assertEqual(200, renamed.status_code)
        initial_payload = initial.get_json()
        self.assertEqual(initial_payload["venue"]["id"], repeated.get_json()["venue"]["id"])
        self.assertEqual(initial_payload["venue"]["code"], repeated.get_json()["venue"]["code"])
        self.assertEqual(initial_payload["venue"]["id"], renamed.get_json()["venue"]["id"])
        self.assertEqual(initial_payload["venue"]["code"], renamed.get_json()["venue"]["code"])
        self.assertEqual("更名后的机厅", renamed.get_json()["venue"]["name"])
        self.assertEqual("服务台终端", renamed.get_json()["terminal"]["name"])

    def test_terminal_installation_rejects_a_different_expected_venue(self):
        response = self.client.post(
            "/api/queue-terminal/installation",
            json={
                "venue_id": "00000000-0000-0000-0000-000000000099",
                "venue_name": "错误机厅",
                "terminal_name": "现场终端",
            },
            headers=self.headers,
        )

        self.assertEqual(409, response.status_code)
        self.assertEqual("VENUE_MISMATCH", response.get_json()["code"])
        fetched = self.client.get(
            "/api/queue-terminal/installation", headers=self.headers
        ).get_json()
        self.assertNotEqual("错误机厅", fetched["venue"]["name"])

    def test_venue_business_hours_are_bound_to_the_venue_and_require_schema_eight_identity(self):
        headers = self.schema_eight_terminal_headers()
        initial = self.client.get(
            "/api/queue-terminal/venue-settings", headers=headers
        )
        self.assertEqual(200, initial.status_code)
        self.assertIsNone(initial.get_json()["business_hours"])

        settings = {
            "enabled": True,
            "use_weekly_schedule": True,
            "default_hours": {"opening_minutes": 600, "closing_minutes": 1320},
            "weekly_hours": {
                day: {"opening_minutes": 660, "closing_minutes": 1260}
                for day in (
                    "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY",
                    "FRIDAY", "SATURDAY", "SUNDAY",
                )
            },
        }
        updated = self.client.put(
            "/api/queue-terminal/venue-settings",
            json={"business_hours": settings},
            headers=headers,
        )
        self.assertEqual(200, updated.status_code)
        self.assertEqual(settings, updated.get_json()["business_hours"])

        fetched = self.client.get(
            "/api/queue-terminal/venue-settings", headers=headers
        )
        self.assertEqual(settings, fetched.get_json()["business_hours"])

        missing_venue = {
            key: value for key, value in headers.items() if key != "X-Queue-Venue-ID"
        }
        rejected = self.client.get(
            "/api/queue-terminal/venue-settings", headers=missing_venue
        )
        self.assertEqual(409, rejected.status_code)
        self.assertEqual("VENUE_ID_REQUIRED", rejected.get_json()["code"])

    def test_player_account_binding_profile_update_and_password_change(self):
        snapshot = self.remote_ready_snapshot()
        published = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )
        binding = self.client.post(
            "/api/queue-terminal/player-bindings",
            json={"profile_id": self.profile_id},
            headers=self.headers,
        )

        self.assertEqual(204, published.status_code)
        self.assertEqual(201, binding.status_code)
        token = binding.get_json()["binding_token"]
        completed = self.client.post(
            f"/api/player-account/bindings/{token}/complete",
            json={"password": "first-password"},
        )
        self.assertEqual(201, completed.status_code)
        bound_profile = completed.get_json()["account"]["profile"]
        self.assertTrue(bound_profile["web_account_bound"])
        self.assertFalse(bound_profile["terminal_editing_allowed"])
        self.assertEqual("PUBLIC_WEBSITE", bound_profile["qq_visibility"])

        csrf_cookie = self.client.get_cookie("maimai_q_session_csrf")
        self.assertIsNotNone(csrf_cookie)
        csrf_headers = {"X-CSRF-Token": csrf_cookie.value}
        updated = self.client.patch(
            "/api/player-account/profile",
            json={
                "expected_profile_revision": bound_profile["profile_revision"],
                "nickname": "网页昵称",
                "terminal_editing_allowed": True,
            },
            headers=csrf_headers,
        )
        self.assertEqual(200, updated.status_code)
        self.assertEqual("网页昵称", updated.get_json()["account"]["profile"]["nickname"])
        self.assertTrue(
            updated.get_json()["account"]["profile"]["terminal_editing_allowed"]
        )

        changed_password = self.client.post(
            "/api/player-account/password",
            json={
                "current_password": "first-password",
                "new_password": "second-password",
                "new_password_confirmation": "second-password",
            },
            headers=csrf_headers,
        )
        self.assertEqual(200, changed_password.status_code)

        fresh_client = self.app.test_client()
        old_login = fresh_client.post(
            "/api/player-account/login",
            json={"qq": "12345678", "password": "first-password"},
        )
        new_login = fresh_client.post(
            "/api/player-account/login",
            json={"qq": "12345678", "password": "second-password"},
        )
        self.assertEqual(401, old_login.status_code)
        self.assertEqual(200, new_login.status_code)

        queued_snapshot = self.remote_ready_snapshot(
            revision=5, with_registration=True
        )
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=queued_snapshot, headers=self.headers
            ).status_code,
        )
        fresh_csrf = fresh_client.get_cookie("maimai_q_session_csrf")
        qq_updated = fresh_client.patch(
            "/api/player-account/profile",
            json={
                "expected_profile_revision": new_login.get_json()["account"]["profile"][
                    "profile_revision"
                ],
                "qq_number": "87654321",
                "current_password": "second-password",
            },
            headers={"X-CSRF-Token": fresh_csrf.value},
        )
        self.assertEqual(200, qq_updated.status_code)
        self.assertEqual(
            "87654321", qq_updated.get_json()["account"]["profile"]["qq_number"]
        )

        old_qq_queue = self.client.post(
            "/api/queue-bot/players",
            json={"qq": "12345678"},
            headers=self.bot_headers,
        ).get_json()["players"]
        new_qq_queue = self.client.post(
            "/api/queue-bot/players",
            json={"qq": "87654321"},
            headers=self.bot_headers,
        ).get_json()["players"]
        self.assertEqual([], old_qq_queue)
        self.assertEqual(1, len(new_qq_queue))
        self.assertEqual(self.profile_id, new_qq_queue[0]["profile_id"])

        # A current profile row is authoritative even if a legacy or damaged
        # profile no longer has a QQ. Do not expose its active registration
        # through the old queue-contact fallback.
        connection = sqlite3.connect(self.database_path)
        try:
            connection.execute(
                "UPDATE player_profile SET qq_number = NULL WHERE profile_id = ?",
                (self.profile_id,),
            )
            connection.commit()
        finally:
            connection.close()
        self.assertEqual(
            [],
            self.client.post(
                "/api/queue-bot/players",
                json={"qq": "12345678"},
                headers=self.bot_headers,
            ).get_json()["players"],
        )
        connection = sqlite3.connect(self.database_path)
        try:
            connection.execute(
                "UPDATE player_profile SET qq_number = ? WHERE profile_id = ?",
                ("87654321", self.profile_id),
            )
            connection.commit()
        finally:
            connection.close()

        queue_response = fresh_client.get("/api/player-account/queue")
        self.assertEqual(200, queue_response.status_code)
        queue_payload = queue_response.get_json()
        self.assertEqual(1, len(queue_payload["registrations"]))
        registration = queue_payload["registrations"][0]
        queue = queue_payload["queue"]
        command_payload = {
            "request_id": "00000000-0000-0000-0000-000000000777",
            "operation": "DEFER_ONE_ROUND",
            "expected_queue_id": queue["queue_id"],
            "expected_registration_id": registration["registration_id"],
            "expected_machine_id": registration["machine_id"],
            "expected_position": registration["position"],
            "expected_fixed_pair_id": registration["fixed_pair_id"],
            "expected_absence_status": "NONE",
            "expected_temporary_away_skipped_turns": 0,
            "expected_pending_check_in": False,
            "expected_machine_configuration_revision": queue[
                "machine_configuration_revision"
            ],
            "expected_machine_stable_id": registration["machine_stable_id"],
        }
        command = fresh_client.post(
            "/api/player-account/queue-commands",
            json=command_payload,
            headers={"X-CSRF-Token": fresh_csrf.value},
        )
        forged = fresh_client.post(
            "/api/player-account/queue-commands",
            json={**command_payload, "actor_qq": "87654321"},
            headers={"X-CSRF-Token": fresh_csrf.value},
        )
        self.assertEqual(202, command.status_code)
        self.assertEqual("87654321", command.get_json()["payload"]["actor_qq"])
        self.assertTrue(command.get_json()["payload"]["profile_identity_verified"])
        self.assertEqual(400, forged.status_code)

        command_status = fresh_client.get(
            f"/api/queue-online/commands/{command_payload['request_id']}"
        )
        anonymous_status = self.app.test_client().get(
            f"/api/queue-online/commands/{command_payload['request_id']}"
        )
        self.assertEqual(200, command_status.status_code)
        self.assertEqual("PENDING", command_status.get_json()["status"])
        self.assertEqual(404, anonymous_status.status_code)

    def test_player_account_login_lookup_returns_only_binding_state(self):
        snapshot = self.remote_ready_snapshot()
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )

        unbound = self.client.post(
            "/api/player-account/login-lookup",
            json={"qq": "12345678"},
        )
        self.assertEqual(200, unbound.status_code)
        self.assertEqual({"ok": True, "bound": False}, unbound.get_json())

        binding = self.client.post(
            "/api/queue-terminal/player-bindings",
            json={"profile_id": self.profile_id},
            headers=self.headers,
        ).get_json()
        self.assertEqual(
            201,
            self.client.post(
                f"/api/player-account/bindings/{binding['binding_token']}/complete",
                json={"password": "lookup-password"},
            ).status_code,
        )
        bound = self.client.post(
            "/api/player-account/login-lookup",
            json={"qq": "12345678"},
        )
        self.assertEqual(200, bound.status_code)
        self.assertEqual({"ok": True, "bound": True}, bound.get_json())
        self.assertEqual(
            400,
            self.client.post(
                "/api/player-account/login-lookup",
                json={"qq": "1234", "extra": "ignored"},
            ).status_code,
        )

    def test_player_account_login_lookup_has_a_separate_request_budget(self):
        self.app.config["PLAYER_AUTH_LOOKUP_LIMIT_REQUESTS"] = 3
        snapshot = self.remote_ready_snapshot()
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )
        for _ in range(3):
            response = self.client.post(
                "/api/player-account/login-lookup",
                json={"qq": "12345678"},
            )
            self.assertEqual(200, response.status_code)

        limited = self.client.post(
            "/api/player-account/login-lookup",
            json={"qq": "12345678"},
        )
        self.assertEqual(429, limited.status_code)
        self.assertEqual("AUTH_RATE_LIMITED", limited.get_json()["code"])

        # Lookup throttling must not consume the password-login failure budget
        # or make a later valid login impossible.
        binding = self.client.post(
            "/api/queue-terminal/player-bindings",
            json={"profile_id": self.profile_id},
            headers=self.headers,
        ).get_json()
        self.assertEqual(
            201,
            self.client.post(
                f"/api/player-account/bindings/{binding['binding_token']}/complete",
                json={"password": "lookup-budget-password"},
            ).status_code,
        )
        login = self.app.test_client().post(
            "/api/player-account/login",
            json={"qq": "12345678", "password": "lookup-budget-password"},
        )
        self.assertEqual(200, login.status_code, login.get_json())

    def test_mobile_player_profile_creation_creates_bound_account_and_syncs_profile(self):
        snapshot = self.remote_ready_snapshot()
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )

        session_response = self.client.post(
            "/api/queue-terminal/player-profile-sessions",
            json={"request_id": "00000000-0000-0000-0000-000000000951"},
            headers=self.headers,
        )
        self.assertEqual(201, session_response.status_code, session_response.get_json())
        session = session_response.get_json()
        self.assertIn("player_profile_creation=", session["profile_creation_url"])
        token = session["profile_creation_url"].split("player_profile_creation=", 1)[1]

        opened = self.client.get(
            f"/api/player-profile-creation/sessions/{token}"
        )
        self.assertEqual(200, opened.status_code)
        self.assertEqual("OPEN", opened.get_json()["status"])

        payload = {
            "nickname": "移动建档玩家",
            "gender": "UNDISCLOSED",
            "default_preference": "ASK_EVERY_TIME",
            "qq_number": "23456789",
            "password": "mobile-password",
            "password_confirmation": "mobile-password",
            "notification_enabled": True,
            "notify_queue_changes": True,
            "notify_playing_position": False,
            "notify_online_check_in": True,
            "notify_absence": True,
            "notify_machine_status": False,
        }
        completed = self.client.post(
            f"/api/player-profile-creation/sessions/{token}/complete",
            json=payload,
        )
        self.assertEqual(201, completed.status_code, completed.get_json())
        account_profile = completed.get_json()["account"]["profile"]
        self.assertEqual("移动建档玩家", account_profile["nickname"])
        self.assertTrue(account_profile["web_account_bound"])
        self.assertFalse(account_profile["terminal_editing_allowed"])
        self.assertEqual("PUBLIC_WEBSITE", account_profile["qq_visibility"])
        self.assertEqual(6, len(account_profile["public_player_id"]))

        login = self.app.test_client().post(
            "/api/player-account/login",
            json={"qq": "23456789", "password": "mobile-password"},
        )
        self.assertEqual(200, login.status_code, login.get_json())
        terminal_profiles = self.client.get(
            "/api/queue-terminal/profiles", headers=self.headers
        )
        self.assertEqual(200, terminal_profiles.status_code)
        self.assertTrue(
            any(
                profile["profile_id"] == account_profile["profile_id"]
                for profile in terminal_profiles.get_json()["profiles"]
            )
        )
        used = self.client.get(
            f"/api/player-profile-creation/sessions/{token}"
        )
        self.assertEqual(409, used.status_code)
        self.assertEqual("PLAYER_PROFILE_CREATION_USED", used.get_json()["code"])

    def test_mobile_player_profile_creation_survives_a_snapshot_before_terminal_echo(self):
        snapshot = self.remote_ready_snapshot()
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )
        session_response = self.client.post(
            "/api/queue-terminal/player-profile-sessions",
            json={"request_id": "00000000-0000-0000-0000-000000000952"},
            headers=self.headers,
        )
        self.assertEqual(201, session_response.status_code, session_response.get_json())
        token = session_response.get_json()["profile_creation_url"].split(
            "player_profile_creation=", 1
        )[1]
        payload = {
            "nickname": "尚未回显玩家",
            "gender": "UNDISCLOSED",
            "default_preference": "ASK_EVERY_TIME",
            "qq_number": "34567890",
            "password": "echo-delay-password",
            "password_confirmation": "echo-delay-password",
            "notification_enabled": True,
            "notify_queue_changes": True,
            "notify_playing_position": False,
            "notify_online_check_in": True,
            "notify_absence": True,
            "notify_machine_status": False,
        }
        completed = self.client.post(
            f"/api/player-profile-creation/sessions/{token}/complete",
            json=payload,
        )
        self.assertEqual(201, completed.status_code, completed.get_json())
        created_profile_id = completed.get_json()["profile_id"]

        # Simulate the terminal's next upload arriving before it has received
        # the newly-created profile from its own local store.
        next_snapshot = copy.deepcopy(snapshot)
        next_snapshot["revision"] += 1
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=next_snapshot, headers=self.headers
            ).status_code,
        )
        profiles = self.client.get(
            "/api/queue-terminal/profiles", headers=self.headers
        ).get_json()["profiles"]
        self.assertIn(created_profile_id, {profile["profile_id"] for profile in profiles})
        lookup = self.client.post(
            "/api/player-account/login-lookup", json={"qq": "34567890"}
        )
        self.assertEqual({"ok": True, "bound": True}, lookup.get_json())

    def test_mobile_player_profile_creation_request_id_is_idempotent_for_same_instance(self):
        snapshot = self.remote_ready_snapshot(revision=60)
        instance_headers = {
            **self.headers,
            "X-Terminal-Instance-ID": "00000000-0000-0000-0000-000000000061",
            "X-Terminal-Instance-Generation": "7",
        }
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=instance_headers
            ).status_code,
        )
        request = {"request_id": "00000000-0000-0000-0000-000000000961"}
        first = self.client.post(
            "/api/queue-terminal/player-profile-sessions",
            json=request,
            headers=instance_headers,
        )
        repeated = self.client.post(
            "/api/queue-terminal/player-profile-sessions",
            json=request,
            headers=instance_headers,
        )
        self.assertEqual(201, first.status_code, first.get_json())
        self.assertEqual(200, repeated.status_code, repeated.get_json())
        self.assertEqual(
            first.get_json()["session_id"], repeated.get_json()["session_id"]
        )
        self.assertEqual(
            first.get_json()["profile_creation_url"],
            repeated.get_json()["profile_creation_url"],
        )

        # A restarted instance on the same physical terminal must not reuse
        # the old idempotency key or its QR session.
        restarted_headers = {
            **instance_headers,
            "X-Terminal-Instance-ID": "00000000-0000-0000-0000-000000000062",
            "X-Terminal-Instance-Generation": "8",
        }
        conflict = self.client.post(
            "/api/queue-terminal/player-profile-sessions",
            json=request,
            headers=restarted_headers,
        )
        self.assertEqual(409, conflict.status_code, conflict.get_json())
        self.assertEqual(
            "PLAYER_PROFILE_CREATION_INSTANCE_CONFLICT",
            conflict.get_json()["code"],
        )

    def test_mobile_player_profile_creation_qr_is_invalidated_after_terminal_restart(self):
        initial_headers = {
            **self.headers,
            "X-Terminal-Instance-ID": "00000000-0000-0000-0000-000000000071",
            "X-Terminal-Instance-Generation": "11",
        }
        snapshot = self.remote_ready_snapshot(revision=61)
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=initial_headers
            ).status_code,
        )
        created = self.client.post(
            "/api/queue-terminal/player-profile-sessions",
            json={"request_id": "00000000-0000-0000-0000-000000000962"},
            headers=initial_headers,
        )
        self.assertEqual(201, created.status_code, created.get_json())
        token = created.get_json()["profile_creation_url"].split(
            "player_profile_creation=", 1
        )[1]

        restarted_snapshot = copy.deepcopy(snapshot)
        restarted_snapshot["revision"] += 1
        restarted_headers = {
            **self.headers,
            "X-Terminal-Instance-ID": "00000000-0000-0000-0000-000000000072",
            "X-Terminal-Instance-Generation": "12",
        }
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status",
                json=restarted_snapshot,
                headers=restarted_headers,
            ).status_code,
        )
        payload = {
            "nickname": "重启后旧二维码",
            "gender": "UNDISCLOSED",
            "default_preference": "ASK_EVERY_TIME",
            "qq_number": "45678901",
            "password": "restart-stale-password",
            "password_confirmation": "restart-stale-password",
            "notification_enabled": True,
            "notify_queue_changes": True,
            "notify_playing_position": False,
            "notify_online_check_in": True,
            "notify_absence": True,
            "notify_machine_status": False,
        }
        rejected = self.client.post(
            f"/api/player-profile-creation/sessions/{token}/complete",
            json=payload,
        )
        self.assertEqual(409, rejected.status_code, rejected.get_json())
        self.assertEqual("PLAYER_PROFILE_CREATION_STALE", rejected.get_json()["code"])
        connection = sqlite3.connect(self.database_path)
        try:
            self.assertEqual(
                0,
                connection.execute(
                    "SELECT COUNT(*) FROM player_account WHERE profile_id IN "
                    "(SELECT profile_id FROM player_profile WHERE nickname = ?)",
                    ("重启后旧二维码",),
                ).fetchone()[0],
            )
        finally:
            connection.close()

    def test_mobile_player_profile_creation_rejects_expired_session_without_creating_rows(self):
        self.app.config["PLAYER_PROFILE_CREATION_SESSION_TTL_SECONDS"] = 60
        snapshot = self.remote_ready_snapshot(revision=62)
        with patch("app.time.time", return_value=2_000_000):
            self.assertEqual(
                204,
                self.client.post(
                    "/api/queue-status", json=snapshot, headers=self.headers
                ).status_code,
            )
            created = self.client.post(
                "/api/queue-terminal/player-profile-sessions",
                json={"request_id": "00000000-0000-0000-0000-000000000963"},
                headers=self.headers,
            )
        self.assertEqual(201, created.status_code, created.get_json())
        token = created.get_json()["profile_creation_url"].split(
            "player_profile_creation=", 1
        )[1]
        with patch("app.time.time", return_value=2_000_061):
            expired = self.client.get(
                f"/api/player-profile-creation/sessions/{token}"
            )
            completed = self.client.post(
                f"/api/player-profile-creation/sessions/{token}/complete",
                json={
                    "nickname": "不应创建",
                    "gender": "UNDISCLOSED",
                    "default_preference": "ASK_EVERY_TIME",
                    "qq_number": "56789012",
                    "password": "expired-password",
                    "password_confirmation": "expired-password",
                    "notification_enabled": True,
                    "notify_queue_changes": True,
                    "notify_playing_position": False,
                    "notify_online_check_in": True,
                    "notify_absence": True,
                    "notify_machine_status": False,
                },
            )
        self.assertEqual(410, expired.status_code, expired.get_json())
        self.assertEqual("PLAYER_PROFILE_CREATION_EXPIRED", expired.get_json()["code"])
        self.assertEqual(410, completed.status_code, completed.get_json())
        self.assertEqual("PLAYER_PROFILE_CREATION_EXPIRED", completed.get_json()["code"])
        connection = sqlite3.connect(self.database_path)
        try:
            self.assertEqual(
                0,
                connection.execute(
                    "SELECT COUNT(*) FROM player_profile WHERE nickname = ?",
                    ("不应创建",),
                ).fetchone()[0],
            )
        finally:
            connection.close()

    def test_player_account_avatar_upload_is_processed_and_synced_to_terminal(self):
        try:
            from PIL import Image
        except ImportError:
            self.skipTest("Pillow is not installed in this test environment")
        snapshot = self.remote_ready_snapshot()
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )
        binding = self.client.post(
            "/api/queue-terminal/player-bindings",
            json={"profile_id": self.profile_id},
            headers=self.headers,
        ).get_json()
        completed = self.client.post(
            f"/api/player-account/bindings/{binding['binding_token']}/complete",
            json={"password": "avatar-password"},
        )
        profile = completed.get_json()["account"]["profile"]
        csrf = self.client.get_cookie("maimai_q_session_csrf").value

        with self.app.test_client() as anonymous_client:
            unauthenticated = anonymous_client.post(
                "/api/player-account/avatar",
                data={
                    "expected_profile_revision": str(profile["profile_revision"]),
                    "avatar": (BytesIO(b"not-an-image"), "avatar.png"),
                },
                content_type="multipart/form-data",
            )
        self.assertEqual(401, unauthenticated.status_code)

        missing_csrf = self.client.post(
            "/api/player-account/avatar",
            data={
                "expected_profile_revision": str(profile["profile_revision"]),
                "avatar": (BytesIO(b"not-an-image"), "avatar.png"),
            },
            content_type="multipart/form-data",
        )
        self.assertEqual(403, missing_csrf.status_code)

        invalid = self.client.post(
            "/api/player-account/avatar",
            data={
                "expected_profile_revision": str(profile["profile_revision"]),
                "avatar": (BytesIO(b"not-an-image"), "avatar.png"),
            },
            headers={"X-CSRF-Token": csrf},
            content_type="multipart/form-data",
        )
        self.assertEqual(400, invalid.status_code)
        self.assertEqual("AVATAR_INVALID", invalid.get_json()["code"])

        source = BytesIO()
        Image.new("RGB", (900, 450), (32, 116, 190)).save(source, format="PNG")
        uploaded = self.client.post(
            "/api/player-account/avatar",
            data={
                "expected_profile_revision": str(profile["profile_revision"]),
                "avatar": (BytesIO(source.getvalue()), "gallery-image.png"),
            },
            headers={"X-CSRF-Token": csrf},
            content_type="multipart/form-data",
        )
        self.assertEqual(200, uploaded.status_code)
        uploaded_profile = uploaded.get_json()["account"]["profile"]
        self.assertEqual(profile["profile_revision"] + 1, uploaded_profile["profile_revision"])
        avatar_url = uploaded_profile["avatar_url"]
        self.assertTrue(avatar_url.startswith("https://queue.example.test/api/player-avatars/"))

        avatar_response = self.client.get(urlsplit(avatar_url).path)
        self.assertEqual(200, avatar_response.status_code)
        self.assertEqual("image/webp", avatar_response.mimetype)
        self.assertIn("immutable", avatar_response.headers["Cache-Control"])
        self.assertEqual("nosniff", avatar_response.headers["X-Content-Type-Options"])
        avatar_bytes = avatar_response.get_data()
        avatar_response.close()
        with Image.open(BytesIO(avatar_bytes)) as processed:
            self.assertEqual("WEBP", processed.format)
            self.assertEqual((512, 512), processed.size)

        terminal_profiles = self.client.get(
            "/api/queue-terminal/profiles", headers=self.headers
        )
        self.assertEqual(200, terminal_profiles.status_code)
        self.assertEqual(
            avatar_url,
            terminal_profiles.get_json()["profiles"][0]["avatar_url"],
        )
        current = self.client.get("/api/player-account")
        self.assertEqual(avatar_url, current.get_json()["account"]["profile"]["avatar_url"])

        reuploaded = self.client.post(
            "/api/player-account/avatar",
            data={
                "expected_profile_revision": str(uploaded_profile["profile_revision"]),
                "avatar": (BytesIO(source.getvalue()), "gallery-image.png"),
            },
            headers={"X-CSRF-Token": csrf},
            content_type="multipart/form-data",
        )
        self.assertEqual(200, reuploaded.status_code)
        reuploaded_profile = reuploaded.get_json()["account"]["profile"]
        self.assertNotEqual(avatar_url, reuploaded_profile["avatar_url"])
        self.assertEqual(
            uploaded_profile["profile_revision"] + 1,
            reuploaded_profile["profile_revision"],
        )
        old_avatar_response = self.client.get(urlsplit(avatar_url).path)
        self.assertEqual(200, old_avatar_response.status_code)
        old_avatar_response.close()
        terminal_profiles = self.client.get(
            "/api/queue-terminal/profiles", headers=self.headers
        )
        self.assertEqual(
            reuploaded_profile["avatar_url"],
            terminal_profiles.get_json()["profiles"][0]["avatar_url"],
        )

    def test_avatar_pixel_limit_is_checked_before_image_decode(self):
        source = MagicMock(width=5001, height=5001)

        with patch("PIL.Image.open", return_value=source):
            with self.assertRaises(ValidationError) as raised:
                normalize_avatar_upload(b"image-header")

        self.assertEqual("AVATAR_INVALID", raised.exception.code)
        source.load.assert_not_called()

    def test_avatar_decompression_bomb_warning_is_reported_as_invalid(self):
        try:
            from PIL import Image
        except ImportError:
            self.skipTest("Pillow is not installed in this test environment")

        with patch(
            "PIL.Image.open",
            side_effect=Image.DecompressionBombWarning("suspicious image dimensions"),
        ):
            with self.assertRaises(ValidationError) as raised:
                normalize_avatar_upload(b"image-header")

        self.assertEqual("AVATAR_INVALID", raised.exception.code)

    def test_bound_player_account_cannot_be_rebound_and_validation_uses_http_errors(self):
        snapshot = self.remote_ready_snapshot()
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )
        stale_binding = self.client.post(
            "/api/queue-terminal/player-bindings",
            json={"profile_id": self.profile_id},
            headers=self.headers,
        ).get_json()
        first_binding = self.client.post(
            "/api/queue-terminal/player-bindings",
            json={"profile_id": self.profile_id},
            headers=self.headers,
        ).get_json()
        first = self.client.post(
            f"/api/player-account/bindings/{first_binding['binding_token']}/complete",
            json={"password": "first-password"},
        )
        csrf = self.client.get_cookie("maimai_q_session_csrf").value
        profile = first.get_json()["account"]["profile"]
        missing_csrf = self.client.patch(
            "/api/player-account/profile",
            json={
                "expected_profile_revision": profile["profile_revision"],
                "visited_venues_public": False,
            },
        )
        customized = self.client.patch(
            "/api/player-account/profile",
            json={
                "expected_profile_revision": profile["profile_revision"],
                "qq_visibility": "TERMINAL_ONLY",
                "visited_venues_public": False,
                "terminal_editing_allowed": True,
            },
            headers={"X-CSRF-Token": csrf},
        )
        self.assertEqual(403, missing_csrf.status_code)
        self.assertEqual("CSRF_TOKEN_INVALID", missing_csrf.get_json()["code"])
        self.assertEqual(200, customized.status_code)

        repeated_binding = self.client.post(
            "/api/queue-terminal/player-bindings",
            json={"profile_id": self.profile_id},
            headers=self.headers,
        )
        self.assertEqual(409, repeated_binding.status_code)
        self.assertEqual(
            "PLAYER_ACCOUNT_ALREADY_BOUND", repeated_binding.get_json()["code"]
        )

        # Simulate a binding page issued before another request completed the binding.
        connection = sqlite3.connect(self.database_path)
        try:
            connection.execute(
                "UPDATE player_binding_session SET invalidated_at = NULL WHERE token_hash IS NOT NULL AND consumed_at IS NULL"
            )
            connection.commit()
        finally:
            connection.close()
        stale_page = self.client.get(
            f"/api/player-account/bindings/{stale_binding['binding_token']}"
        )
        stale_completion = self.client.post(
            f"/api/player-account/bindings/{stale_binding['binding_token']}/complete",
            json={"password": "second-password"},
        )
        self.assertEqual(409, stale_page.status_code)
        self.assertEqual("PLAYER_ACCOUNT_ALREADY_BOUND", stale_page.get_json()["code"])
        self.assertEqual(409, stale_completion.status_code)
        self.assertEqual(
            "PLAYER_ACCOUNT_ALREADY_BOUND", stale_completion.get_json()["code"]
        )

        invalid_password_change = self.client.post(
            "/api/player-account/password",
            json={
                "current_password": "first-password",
                "new_password": "short",
                "new_password_confirmation": "short",
            },
            headers={"X-CSRF-Token": csrf},
        )
        self.assertEqual(400, invalid_password_change.status_code)
        self.assertEqual("PASSWORD_TOO_SHORT", invalid_password_change.get_json()["code"])

        fresh_client = self.app.test_client()
        original_login = fresh_client.post(
            "/api/player-account/login",
            json={"qq": "12345678", "password": "first-password"},
        )
        attacker_login = self.app.test_client().post(
            "/api/player-account/login",
            json={"qq": "12345678", "password": "second-password"},
        )
        self.assertEqual(200, original_login.status_code)
        self.assertEqual(401, attacker_login.status_code)

    def test_unbound_profile_invalidates_stale_account_and_sessions(self):
        snapshot = self.remote_ready_snapshot()
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )
        binding = self.client.post(
            "/api/queue-terminal/player-bindings",
            json={"profile_id": self.profile_id},
            headers=self.headers,
        ).get_json()
        completed = self.client.post(
            f"/api/player-account/bindings/{binding['binding_token']}/complete",
            json={"password": "bound-password"},
        )
        self.assertEqual(201, completed.status_code)

        # A stale imported database can retain credentials after the profile
        # itself has been marked unbound. The next profile sync must remove the
        # orphaned account and revoke the session instead of leaving a login
        # path that bypasses the current binding state.
        connection = sqlite3.connect(self.database_path)
        try:
            profile_scope_id = connection.execute(
                """
                SELECT profile_scope_id FROM player_account
                WHERE profile_id = ?
                """,
                (self.profile_id,),
            ).fetchone()[0]
            connection.execute(
                """
                UPDATE player_profile
                SET web_account_bound = 0,
                    terminal_editing_allowed = 1,
                    web_profile_revision = 0
                WHERE device_id = ? AND profile_id = ?
                """,
                (profile_scope_id, self.profile_id),
            )
            connection.commit()
        finally:
            connection.close()

        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )
        connection = sqlite3.connect(self.database_path)
        try:
            self.assertIsNone(
                connection.execute(
                    """
                    SELECT account_id FROM player_account
                    WHERE profile_scope_id = ? AND profile_id = ?
                    """,
                    (profile_scope_id, self.profile_id),
                ).fetchone()
            )
        finally:
            connection.close()

        current = self.client.get("/api/player-account")
        self.assertEqual(401, current.status_code, current.get_json())
        fresh_login = self.app.test_client().post(
            "/api/player-account/login",
            json={"qq": "12345678", "password": "bound-password"},
        )
        self.assertEqual(401, fresh_login.status_code)

        # The profile can be bound again after the stale account is removed.
        rebound = self.client.post(
            "/api/queue-terminal/player-bindings",
            json={"profile_id": self.profile_id},
            headers=self.headers,
        )
        self.assertEqual(201, rebound.status_code)

    def test_binding_cleans_an_orphan_account_before_recreating_session(self):
        snapshot = self.remote_ready_snapshot()
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )
        connection = sqlite3.connect(self.database_path)
        try:
            connection.execute(
                """
                INSERT INTO player_account
                    (account_id, profile_scope_id, profile_id, password_salt,
                     password_hash, created_at, updated_at, password_changed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    "orphan-account",
                    self.app.config["PROFILE_SCOPE_ID"],
                    self.profile_id,
                    b"orphan-salt",
                    b"orphan-hash",
                    1,
                    1,
                    1,
                ),
            )
            connection.execute(
                "UPDATE player_profile SET web_account_bound = 0 WHERE profile_id = ?",
                (self.profile_id,),
            )
            connection.commit()
        finally:
            connection.close()

        binding_response = self.client.post(
            "/api/queue-terminal/player-bindings",
            json={"profile_id": self.profile_id},
            headers=self.headers,
        )
        self.assertEqual(201, binding_response.status_code)
        completed = self.client.post(
            f"/api/player-account/bindings/{binding_response.get_json()['binding_token']}/complete",
            json={"password": "recreated-password"},
        )
        self.assertEqual(201, completed.status_code)
        self.assertEqual(
            "12345678",
            completed.get_json()["account"]["profile"]["qq_number"],
        )

    def test_logged_in_player_can_submit_leave_queue_with_confirmed_registration_state(self):
        snapshot = self.remote_ready_snapshot(with_registration=True)
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )
        binding = self.client.post(
            "/api/queue-terminal/player-bindings",
            json={"profile_id": self.profile_id},
            headers=self.headers,
        ).get_json()
        completed = self.client.post(
            f"/api/player-account/bindings/{binding['binding_token']}/complete",
            json={"password": "player-password"},
        )
        self.assertEqual(201, completed.status_code)

        queue_payload = self.client.get("/api/player-account/queue").get_json()
        queue = queue_payload["queue"]
        registration = queue_payload["registrations"][0]
        csrf = self.client.get_cookie("maimai_q_session_csrf").value
        command = self.client.post(
            "/api/player-account/queue-commands",
            json={
                "request_id": "00000000-0000-0000-0000-000000000778",
                "operation": "LEAVE_QUEUE",
                "expected_queue_id": queue["queue_id"],
                "expected_registration_id": registration["registration_id"],
                "expected_machine_id": registration["machine_id"],
                "expected_position": registration["position"],
                "expected_fixed_pair_id": registration["fixed_pair_id"],
                "expected_absence_status": "NONE",
                "expected_temporary_away_skipped_turns": 0,
                "expected_pending_check_in": False,
                "expected_machine_configuration_revision": queue[
                    "machine_configuration_revision"
                ],
                "expected_machine_stable_id": registration["machine_stable_id"],
            },
            headers={"X-CSRF-Token": csrf},
        )

        self.assertEqual(202, command.status_code)
        self.assertEqual("LEAVE_QUEUE", command.get_json()["payload"]["operation"])
        self.assertTrue(command.get_json()["payload"]["profile_identity_verified"])

    def test_bound_profile_accepts_only_terminal_confirmed_bot_updates(self):
        snapshot = self.remote_ready_snapshot(revision=4)
        self.upgrade_snapshot_to_schema_v7(snapshot)
        snapshot["schema_version"] = 8
        venue_id = self.client.get(
            "/api/queue-terminal/installation", headers=self.headers
        ).get_json()["venue"]["id"]
        snapshot["venue"] = {"id": venue_id}
        schema_eight_headers = self.schema_eight_terminal_headers()
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=schema_eight_headers
            ).status_code,
        )
        binding = self.client.post(
            "/api/queue-terminal/player-bindings",
            json={"profile_id": self.profile_id},
            headers=schema_eight_headers,
        ).get_json()
        self.assertEqual(
            201,
            self.client.post(
                f"/api/player-account/bindings/{binding['binding_token']}/complete",
                json={"password": "account-password"},
            ).status_code,
        )
        synced_profile = self.client.get(
            "/api/queue-terminal/profiles", headers=schema_eight_headers
        ).get_json()["profiles"][0]

        command_id = "00000000-0000-0000-0000-000000000776"
        created = self.client.patch(
            f"/api/queue-bot/profiles/{self.profile_id}",
            json={
                "request_id": command_id,
                "actor_qq": "12345678",
                "nickname": "Bot 修改昵称",
                "notify_queue_changes": False,
            },
            headers=self.bot_headers,
        )
        commands = self.client.get(
            "/api/queue-terminal/commands", headers=schema_eight_headers
        ).get_json()["commands"]
        acknowledged = self.client.post(
            f"/api/queue-terminal/commands/{command_id}/result",
            json={"status": "APPLIED", "detail": "玩家资料已由终端更新。"},
            headers=schema_eight_headers,
        )
        self.assertEqual(202, created.status_code)
        self.assertEqual([command_id], [command["command_id"] for command in commands])
        self.assertEqual(200, acknowledged.status_code)

        updated_snapshot = copy.deepcopy(snapshot)
        updated_snapshot["revision"] = 5
        updated_profile = {
            key: value
            for key, value in synced_profile.items()
            if key not in {"public_player_id_aliases", "synced_at"}
        }
        updated_profile.update(
            nickname="Bot 修改昵称",
            notify_queue_changes=False,
            profile_revision=synced_profile["profile_revision"] + 1,
            updated_at=synced_profile["updated_at"] + 1,
        )
        updated_snapshot["private_player_profiles"] = [updated_profile]
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status",
                json=updated_snapshot,
                headers=schema_eight_headers,
            ).status_code,
        )
        stored = self.client.post(
            "/api/queue-bot/profiles",
            json={"qq": "12345678"},
            headers=self.bot_headers,
        ).get_json()["profiles"][0]
        self.assertEqual("Bot 修改昵称", stored["nickname"])
        self.assertFalse(stored["notify_queue_changes"])

    def test_recent_player_usage_history_round_trips_and_legacy_updates_preserve_it(self):
        snapshot = self.remote_ready_snapshot(revision=4)
        self.upgrade_snapshot_to_schema_v7(snapshot)
        snapshot["schema_version"] = 8
        snapshot["venue"] = {
            "id": self.client.get(
                "/api/queue-terminal/installation", headers=self.headers
            ).get_json()["venue"]["id"]
        }
        profile = snapshot["private_player_profiles"][0]
        profile["recent_usage_at"] = [700_000, 850_000, 900_000]
        headers = self.schema_eight_terminal_headers()

        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=headers
            ).status_code,
        )
        synced = self.client.get(
            "/api/queue-terminal/profiles", headers=headers
        ).get_json()["profiles"][0]
        self.assertEqual([700_000, 850_000, 900_000], synced["recent_usage_at"])

        legacy_update = copy.deepcopy(snapshot)
        legacy_update["revision"] = 5
        legacy_profile = legacy_update["private_player_profiles"][0]
        legacy_profile.pop("recent_usage_at")
        legacy_profile["profile_revision"] += 1
        legacy_profile["updated_at"] += 1
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=legacy_update, headers=headers
            ).status_code,
        )
        preserved = self.client.get(
            "/api/queue-terminal/profiles", headers=headers
        ).get_json()["profiles"][0]
        self.assertEqual([700_000, 850_000, 900_000], preserved["recent_usage_at"])

    def test_recent_player_usage_history_is_bounded_and_validated(self):
        snapshot = self.remote_ready_snapshot(revision=4)
        self.upgrade_snapshot_to_schema_v7(snapshot)
        snapshot["schema_version"] = 8
        snapshot["venue"] = {
            "id": self.client.get(
                "/api/queue-terminal/installation", headers=self.headers
            ).get_json()["venue"]["id"]
        }
        headers = self.schema_eight_terminal_headers()

        for invalid_history in ([1] * 17, "900000", [0]):
            with self.subTest(invalid_history=invalid_history):
                invalid = copy.deepcopy(snapshot)
                invalid["private_player_profiles"][0][
                    "recent_usage_at"
                ] = invalid_history
                response = self.client.post(
                    "/api/queue-status", json=invalid, headers=headers
                )
                self.assertEqual(400, response.status_code)

    def test_schema_eight_private_terminal_routes_require_the_active_venue(self):
        snapshot = self.remote_ready_snapshot(revision=4)
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )
        missing = {**self.headers, "X-Queue-Schema-Version": "8"}
        mismatched = {
            **missing,
            "X-Queue-Venue-ID": "00000000-0000-0000-0000-000000000099",
        }

        for endpoint in (
            "/api/queue-terminal/profiles",
            "/api/queue-terminal/commands",
        ):
            missing_response = self.client.get(endpoint, headers=missing)
            mismatched_response = self.client.get(endpoint, headers=mismatched)
            allowed_response = self.client.get(
                endpoint, headers=self.schema_eight_terminal_headers()
            )
            self.assertEqual(409, missing_response.status_code)
            self.assertEqual("VENUE_ID_REQUIRED", missing_response.get_json()["code"])
            self.assertEqual(409, mismatched_response.status_code)
            self.assertEqual("VENUE_MISMATCH", mismatched_response.get_json()["code"])
            self.assertEqual(200, allowed_response.status_code)

        mobile_payload = {
            "request_id": "00000000-0000-0000-0000-000000000801",
            "queue_id": snapshot["queue_id"],
            "machine_id": "A",
        }
        self.assertEqual(
            409,
            self.client.post(
                "/api/queue-terminal/mobile-registration-sessions",
                json=mobile_payload,
                headers=missing,
            ).status_code,
        )
        self.assertEqual(
            201,
            self.client.post(
                "/api/queue-terminal/mobile-registration-sessions",
                json=mobile_payload,
                headers=self.schema_eight_terminal_headers(),
            ).status_code,
        )

    def test_schema_eight_snapshot_requires_a_matching_venue_object(self):
        snapshot = self.remote_ready_snapshot(revision=3)
        snapshot["schema_version"] = 8
        snapshot.pop("venue", None)

        missing = self.client.post(
            "/api/queue-status",
            json=snapshot,
            headers=self.schema_eight_terminal_headers(),
        )

        self.assertEqual(409, missing.status_code)
        self.assertEqual("VENUE_ID_REQUIRED", missing.get_json()["code"])

        snapshot["venue"] = {
            "id": "00000000-0000-0000-0000-000000000099",
        }
        mismatched = self.client.post(
            "/api/queue-status",
            json=snapshot,
            headers=self.schema_eight_terminal_headers(),
        )

        self.assertEqual(409, mismatched.status_code)
        self.assertEqual("VENUE_MISMATCH", mismatched.get_json()["code"])

    def test_schema_eight_requires_matching_body_and_header_versions(self):
        snapshot = self.remote_ready_snapshot(revision=41)
        self.upgrade_snapshot_to_schema_v7(snapshot)
        snapshot["schema_version"] = 8
        snapshot["venue"] = {
            "id": self.client.get(
                "/api/queue-terminal/installation", headers=self.headers
            ).get_json()["venue"]["id"]
        }

        header_without_schema = {
            key: value
            for key, value in self.headers.items()
            if key != "X-Queue-Schema-Version"
        }
        cases = (
            (
                "schema 8 正文加 schema 7 请求头",
                {**self.headers, "X-Queue-Schema-Version": "7"},
                "SCHEMA_VERSION_MISMATCH",
            ),
            (
                "schema 8 正文不带请求头",
                header_without_schema,
                "SCHEMA_VERSION_REQUIRED",
            ),
            (
                "schema 8 正文加无效请求头",
                {**self.headers, "X-Queue-Schema-Version": "invalid"},
                "SCHEMA_VERSION_INVALID",
            ),
        )
        for label, headers, code in cases:
            with self.subTest(label=label):
                response = self.client.post(
                    "/api/queue-status", json=copy.deepcopy(snapshot), headers=headers
                )
                self.assertEqual(400, response.status_code)
                self.assertEqual(code, response.get_json()["code"])

        schema_seven_snapshot = copy.deepcopy(snapshot)
        schema_seven_snapshot["schema_version"] = 7
        schema_seven_snapshot.pop("venue", None)
        schema_seven_headers = {**self.headers, "X-Queue-Schema-Version": "7"}
        accepted = self.client.post(
            "/api/queue-status",
            json=schema_seven_snapshot,
            headers=schema_seven_headers,
        )
        self.assertEqual(204, accepted.status_code)

        wrong_modern_header = {**self.headers, "X-Queue-Schema-Version": "8"}
        rejected = self.client.post(
            "/api/queue-status",
            json=schema_seven_snapshot,
            headers=wrong_modern_header,
        )
        self.assertEqual(400, rejected.status_code)
        self.assertEqual("SCHEMA_VERSION_MISMATCH", rejected.get_json()["code"])

    def test_legacy_snapshot_is_assigned_to_the_active_venue(self):
        published = self.client.post(
            "/api/queue-status", json=self.snapshot(), headers=self.headers
        )
        installation = self.client.get(
            "/api/queue-terminal/installation", headers=self.headers
        ).get_json()
        public_snapshot = self.client.get("/api/queue-status").get_json()
        connection = sqlite3.connect(self.database_path)
        try:
            stored_venue_id = connection.execute(
                "SELECT venue_id FROM queue_snapshot WHERE id = 1"
            ).fetchone()[0]
        finally:
            connection.close()

        self.assertEqual(204, published.status_code)
        self.assertEqual(installation["venue"]["id"], public_snapshot["venue"]["id"])
        self.assertEqual(installation["venue"]["id"], stored_venue_id)

    def test_player_public_id_collision_is_reassigned_and_synced_back(self):
        snapshot = self.remote_ready_snapshot(revision=4)
        self.upgrade_snapshot_to_schema_v7(snapshot)
        snapshot["schema_version"] = 8
        snapshot["venue"] = {
            "id": self.client.get(
                "/api/queue-terminal/installation", headers=self.headers
            ).get_json()["venue"]["id"]
        }
        first_profile = snapshot["private_player_profiles"][0]
        first_profile["public_player_id"] = "012345"
        second_profile = copy.deepcopy(first_profile)
        second_profile.update(
            profile_id="00000000-0000-0000-0000-000000000902",
            public_player_id="012345",
            nickname="另一位玩家",
            qq_number="87654321",
        )
        snapshot["private_player_profiles"] = [first_profile, second_profile]

        published = self.client.post(
            "/api/queue-status", json=snapshot,
            headers=self.schema_eight_terminal_headers()
        )
        synced = self.client.get(
            "/api/queue-terminal/profiles", headers=self.headers
        ).get_json()["profiles"]
        public_ids = {
            profile["profile_id"]: profile["public_player_id"] for profile in synced
        }

        self.assertEqual(204, published.status_code)
        self.assertEqual("012345", public_ids[self.profile_id])
        self.assertRegex(
            public_ids["00000000-0000-0000-0000-000000000902"], r"^\d{6}$"
        )
        self.assertNotEqual(
            public_ids[self.profile_id],
            public_ids["00000000-0000-0000-0000-000000000902"],
        )

    def test_profile_bound_on_another_server_is_unbound_and_synced_back(self):
        snapshot = self.remote_ready_snapshot(revision=4)
        self.upgrade_snapshot_to_schema_v7(snapshot)
        snapshot["schema_version"] = 8
        snapshot["venue"] = {
            "id": self.client.get(
                "/api/queue-terminal/installation", headers=self.headers
            ).get_json()["venue"]["id"]
        }
        profile = snapshot["private_player_profiles"][0]
        profile.update(
            public_player_id="012345",
            web_account_bound=True,
            terminal_editing_allowed=False,
            visited_venues_public=False,
            web_profile_revision=3,
        )

        published = self.client.post(
            "/api/queue-status",
            json=snapshot,
            headers=self.schema_eight_terminal_headers(),
        )
        synced = self.client.get(
            "/api/queue-terminal/profiles",
            headers=self.schema_eight_terminal_headers(),
        ).get_json()["profiles"][0]

        self.assertEqual(204, published.status_code)
        self.assertFalse(synced["web_account_bound"])
        self.assertTrue(synced["terminal_editing_allowed"])
        self.assertTrue(synced["visited_venues_public"])
        self.assertEqual(0, synced["web_profile_revision"])
        self.assertEqual(profile["profile_revision"] + 1, synced["profile_revision"])
        self.assertGreaterEqual(synced["updated_at"], profile["updated_at"])

    def test_historical_player_public_id_is_never_promoted_back_to_current(self):
        snapshot = self.remote_ready_snapshot(revision=4)
        self.upgrade_snapshot_to_schema_v7(snapshot)
        snapshot["schema_version"] = 8
        snapshot["venue"] = {
            "id": self.client.get(
                "/api/queue-terminal/installation", headers=self.headers
            ).get_json()["venue"]["id"]
        }
        profile = snapshot["private_player_profiles"][0]
        profile["public_player_id"] = "012345"
        connection = sqlite3.connect(self.database_path)
        try:
            connection.execute(
                """
                INSERT INTO player_public_id_alias
                    (profile_scope_id, public_player_id, canonical_profile_id, created_at)
                VALUES (?, '012345', ?, 1)
                """,
                (self.app.config["PROFILE_SCOPE_ID"], self.profile_id),
            )
            connection.commit()
        finally:
            connection.close()

        published = self.client.post(
            "/api/queue-status", json=snapshot,
            headers=self.schema_eight_terminal_headers()
        )
        synced = self.client.get(
            "/api/queue-terminal/profiles", headers=self.headers
        ).get_json()["profiles"][0]

        self.assertEqual(204, published.status_code)
        self.assertNotEqual("012345", synced["public_player_id"])
        self.assertEqual(["012345"], synced["public_player_id_aliases"])

    def test_self_hosted_defaults_do_not_point_to_maintainer_site(self):
        with tempfile.TemporaryDirectory() as directory:
            isolated = create_app(
                {
                    "TESTING": True,
                    "DATABASE_PATH": str(Path(directory) / "queue.db"),
                    "SYNC_TOKEN": self.sync_token,
                    "BOT_TOKEN": self.bot_token,
                }
            )
            self.assertEqual("", isolated.config["CORS_ORIGIN"])
            self.assertEqual("", isolated.config["PUBLIC_SITE_URL"])

    def test_mobile_registration_fails_closed_without_public_site_url(self):
        snapshot = self.remote_ready_snapshot(revision=1)
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )
        self.app.config["PUBLIC_SITE_URL"] = ""
        response = self.client.post(
            "/api/queue-terminal/mobile-registration-sessions",
            json={
                "request_id": "00000000-0000-0000-0000-000000000999",
                "queue_id": snapshot["queue_id"],
                "machine_id": "A",
            },
            headers=self.headers,
        )
        self.assertEqual(503, response.status_code)
        self.assertIn("公开网站地址", response.get_json()["error"])

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
            {"ok": False, "error": "服务器认证配置无效。"}, short_sync.get_json()
        )
        self.assertNotIn("s" * 31, short_sync.get_data(as_text=True))
        self.assertEqual(200, health.status_code)
        self.assertEqual(204, terminal_with_invalid_bot.status_code)
        self.assertEqual(503, short_bot.status_code)
        self.assertEqual(503, missing_bot.status_code)
        self.assertEqual(
            {"ok": False, "error": "服务器认证配置无效。"}, missing_bot.get_json()
        )
        self.assertEqual(200, bot_with_missing_sync.status_code)
        self.assertEqual(503, missing_sync.status_code)

    def test_health_rejects_an_incomplete_database_migration(self):
        connection = sqlite3.connect(self.database_path)
        try:
            connection.execute("ALTER TABLE queue_event RENAME TO queue_event_current")
            connection.execute(
                """
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
                )
                """
            )
            connection.commit()
        finally:
            connection.close()

        response = self.client.get("/healthz")

        self.assertEqual(503, response.status_code)
        self.assertEqual("database_schema_not_ready", response.get_json()["error"])

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
            snapshot_columns = {
                row[1] for row in connection.execute("PRAGMA table_info(queue_snapshot)")
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

        self.assertTrue(
            {
                "claimed_at",
                "claimed_terminal",
                "claimed_instance",
                "result_registration_id",
                "result_source",
            }
            <= command_columns
        )
        self.assertTrue(
            {"instance_id", "instance_generation"} <= snapshot_columns
        )
        self.assertIn("stored_at", recipient_columns)
        self.assertIn("operation_source", event_columns)
        self.assertIn("notification_categories", event_columns)
        self.assertIn("machine_stable_id", event_columns)
        self.assertIn("machine_name", event_columns)
        self.assertEqual("SERVER_TIMEOUT", result_source)
        self.assertEqual("ON_SITE_TERMINAL", operation_source)
        self.assertGreater(stored_at, 0)

    def test_migrates_legacy_service_identity_without_losing_bot_qq(self):
        legacy_database_path = str(
            Path(self.temporary_directory.name) / "legacy-service-identity.db"
        )
        connection = sqlite3.connect(legacy_database_path)
        try:
            connection.execute(
                """
                CREATE TABLE service_identity (
                    profile_scope_id TEXT PRIMARY KEY,
                    bot_qq TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """
            )
            connection.execute(
                "INSERT INTO service_identity VALUES ('default', '87654321', 123)"
            )
            connection.commit()
        finally:
            connection.close()

        initialize_database(legacy_database_path)

        connection = sqlite3.connect(legacy_database_path)
        try:
            columns = {
                row[1] for row in connection.execute("PRAGMA table_info(service_identity)")
            }
            row = connection.execute(
                """
                SELECT bot_qq, bot_version, bot_version_updated_at,
                       website_version, website_version_updated_at, updated_at
                FROM service_identity WHERE profile_scope_id = 'default'
                """
            ).fetchone()
        finally:
            connection.close()

        self.assertTrue(
            {
                "bot_version",
                "bot_version_updated_at",
                "website_version",
                "website_version_updated_at",
            }
            <= columns
        )
        self.assertEqual(("87654321", None, None, None, None, 123), row)

    def test_player_public_id_migration_is_unique_and_idempotent(self):
        legacy_database_path = str(
            Path(self.temporary_directory.name) / "legacy-player-public-id.db"
        )
        connection = sqlite3.connect(legacy_database_path)
        try:
            connection.execute(
                """
                CREATE TABLE player_profile (
                    device_id TEXT NOT NULL,
                    profile_id TEXT NOT NULL,
                    nickname TEXT NOT NULL,
                    gender TEXT NOT NULL,
                    default_preference TEXT NOT NULL,
                    qq_number TEXT,
                    usage_count INTEGER NOT NULL,
                    last_used_at INTEGER,
                    profile_revision INTEGER NOT NULL DEFAULT 1,
                    created_at INTEGER NOT NULL,
                    profile_updated_at INTEGER NOT NULL,
                    public_player_id TEXT,
                    received_at INTEGER NOT NULL,
                    PRIMARY KEY(device_id, profile_id)
                )
                """
            )
            connection.executemany(
                """
                INSERT INTO player_profile
                    (device_id, profile_id, nickname, gender, default_preference,
                     qq_number, usage_count, last_used_at, profile_revision,
                     created_at, profile_updated_at, public_player_id, received_at)
                VALUES ('default', ?, ?, 'UNDISCLOSED', 'OPEN_TO_JOIN', NULL,
                        0, NULL, 1, 1, 1, ?, 1)
                """,
                [
                    ("00000000-0000-0000-0000-000000000901", "甲", "000123"),
                    ("00000000-0000-0000-0000-000000000902", "乙", "000123"),
                    ("00000000-0000-0000-0000-000000000903", "丙", None),
                ],
            )
            connection.commit()
        finally:
            connection.close()

        initialize_database(legacy_database_path)
        connection = sqlite3.connect(legacy_database_path)
        try:
            first_pass = connection.execute(
                """
                SELECT profile_id, public_player_id FROM player_profile
                ORDER BY profile_id
                """
            ).fetchall()
        finally:
            connection.close()
        initialize_database(legacy_database_path)
        connection = sqlite3.connect(legacy_database_path)
        try:
            second_pass = connection.execute(
                """
                SELECT profile_id, public_player_id FROM player_profile
                ORDER BY profile_id
                """
            ).fetchall()
        finally:
            connection.close()

        self.assertEqual(first_pass, second_pass)
        self.assertEqual("000123", first_pass[0][1])
        self.assertEqual(3, len({row[1] for row in first_pass}))
        self.assertTrue(all(len(row[1]) == 6 and row[1].isdigit() for row in first_pass))

    def test_concurrent_workers_can_migrate_the_same_legacy_database(self):
        legacy_database_path = str(
            Path(self.temporary_directory.name) / "concurrent-legacy-queue.db"
        )
        connection = sqlite3.connect(legacy_database_path)
        try:
            connection.executescript(
                """
                PRAGMA journal_mode = WAL;
                CREATE TABLE queue_snapshot (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    queue_id TEXT NOT NULL,
                    revision INTEGER NOT NULL,
                    payload TEXT NOT NULL,
                    device_id TEXT NOT NULL,
                    received_at INTEGER NOT NULL
                );
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
                """
            )
            connection.commit()
        finally:
            connection.close()

        barrier = Barrier(2)

        def migrate(_: int):
            barrier.wait()
            initialize_database(legacy_database_path)

        with ThreadPoolExecutor(max_workers=2) as executor:
            list(executor.map(migrate, range(2)))

        connection = sqlite3.connect(legacy_database_path)
        try:
            snapshot_columns = {
                row[1] for row in connection.execute("PRAGMA table_info(queue_snapshot)")
            }
            command_columns = {
                row[1] for row in connection.execute("PRAGMA table_info(terminal_command)")
            }
        finally:
            connection.close()

        self.assertTrue(
            {"instance_id", "instance_generation"} <= snapshot_columns
        )
        self.assertTrue(
            {"claimed_instance", "result_registration_id"} <= command_columns
        )

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
        self.assertEqual(
            800_000,
            read.get_json()["machines"]["A"]["playing"][0]["online_check_in_started_at"],
        )
        self.assertTrue(read.get_json()["terminal"]["online"])

    def test_accepts_one_to_ten_contiguous_machines(self):
        for machine_count in range(1, 11):
            snapshot = self.snapshot(revision=10 + machine_count)
            snapshot["schema_version"] = 5
            machine_ids = list("ABCDEFGHIJ")[:machine_count]
            snapshot["machines"] = {
                machine_id: snapshot["machines"].get(machine_id)
                or self.machine(name=f"测试位 · 机台 {machine_id}")
                for machine_id in machine_ids
            }

            publish = self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            )
            stored = self.client.get("/api/queue-status").get_json()

            self.assertEqual(204, publish.status_code)
            self.assertEqual(machine_ids, list(stored["machines"]))

    def test_schema_v7_preserves_stable_machine_identity_and_groups(self):
        snapshot = self.snapshot(revision=31)
        for machine_id in "CDEFGHIJ":
            snapshot["machines"][machine_id] = self.machine(
                name=f"区域 {machine_id} · 机台 {machine_id}"
            )
        self.upgrade_snapshot_to_schema_v7(
            snapshot,
            group_assignments={
                **{machine_id: "1" * 32 for machine_id in "ABCDE"},
                **{machine_id: "2" * 32 for machine_id in "FGHIJ"},
            },
            groups=[
                {"id": "1" * 32, "name": "一楼"},
                {"id": "2" * 32, "name": "二楼"},
            ],
            default_group_id="2" * 32,
        )

        publish = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )
        stored = self.client.get("/api/queue-status").get_json()

        self.assertEqual(204, publish.status_code)
        self.assertEqual(8, stored["schema_version"])
        self.assertEqual("2" * 32, stored["default_machine_group_id"])
        self.assertEqual(["一楼", "二楼"], [group["name"] for group in stored["machine_groups"]])
        self.assertEqual("2" * 32, stored["machines"]["J"]["group_id"])
        self.assertEqual(f"{10:032x}", stored["machines"]["J"]["stable_id"])

    def test_schema_v7_rejects_duplicate_identity_invalid_group_and_empty_group(self):
        base = self.snapshot(revision=32)
        self.upgrade_snapshot_to_schema_v7(base)
        duplicate_identity = copy.deepcopy(base)
        duplicate_identity["machines"]["B"]["stable_id"] = duplicate_identity["machines"]["A"]["stable_id"]
        invalid_group = copy.deepcopy(base)
        invalid_group["machines"]["B"]["group_id"] = "9" * 32
        empty_group = copy.deepcopy(base)
        empty_group["machine_groups"].append({"id": "8" * 32, "name": "空分组"})

        for snapshot in (duplicate_identity, invalid_group, empty_group):
            response = self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            )
            self.assertEqual(400, response.status_code)

    def test_rejects_noncontiguous_or_unknown_machine_ids(self):
        for machine_ids in (("A", "C"), ("B",), ("A", "B", "E")):
            snapshot = self.snapshot(revision=20)
            snapshot["schema_version"] = 5
            snapshot["machines"] = {
                machine_id: self.machine(name=f"测试位 · 机台 {machine_id}")
                for machine_id in machine_ids
            }

            publish = self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            )

            self.assertEqual(400, publish.status_code)

    def test_preserves_a_restarted_online_check_in_timer_without_changing_creation_time(self):
        snapshot = self.snapshot(revision=5)
        registration = snapshot["machines"]["A"]["playing"][0]
        registration["online_check_in_started_at"] = 900_000

        publish = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )
        stored = self.client.get("/api/queue-status").get_json()
        stored_registration = stored["machines"]["A"]["playing"][0]

        self.assertEqual(204, publish.status_code)
        self.assertEqual(800_000, stored_registration["created_at"])
        self.assertEqual(900_000, stored_registration["online_check_in_started_at"])

    def test_common_play_preview_is_public_and_does_not_count_as_a_registration(self):
        snapshot = self.remote_ready_snapshot(revision=27, with_registration=True)
        machine = snapshot["machines"]["A"]
        registration = machine["waiting_positions"][0]["registrations"][0]
        registration["preference"] = "OPEN_TO_JOIN"
        partner = self.registration("c" * 24, "预计搭档")
        partner["preference"] = "OPEN_TO_JOIN"
        machine["playing"] = [partner]
        machine["playing_started_at"] = 900_000
        machine["registration_count"] = 2
        machine["waiting_positions"][0]["common_play_preview"] = {
            "registration_id": partner["registration_id"],
            "display_id": partner["display_id"],
        }

        publish = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )
        public_machine = self.client.get("/api/queue-status").get_json()["machines"]["A"]
        bot_player = self.client.post(
            "/api/queue-bot/players",
            json={"qq": "12345678"},
            headers=self.bot_headers,
        ).get_json()["players"][0]

        self.assertEqual(204, publish.status_code)
        self.assertEqual(2, public_machine["registration_count"])
        self.assertEqual(1, len(public_machine["waiting_positions"][0]["registrations"]))
        self.assertEqual(
            {
                "registration_id": partner["registration_id"],
                "display_id": "预计搭档",
            },
            public_machine["waiting_positions"][0]["common_play_preview"],
        )
        self.assertEqual(["预计搭档"], bot_player["co_player_display_ids"])
        self.assertEqual(
            "预计搭档", bot_player["common_play_preview_display_id"]
        )

    def test_rejects_invalid_common_play_preview_references(self):
        def snapshot_with_preview():
            snapshot = self.snapshot(revision=28)
            machine = snapshot["machines"]["A"]
            partner = machine["playing"][0]
            partner["preference"] = "OPEN_TO_JOIN"
            waiting = self.registration("c" * 24, "等待玩家")
            waiting["preference"] = "OPEN_TO_JOIN"
            machine["waiting_positions"] = [
                {
                    "index": 1,
                    "position_id": "d" * 24,
                    "fixed_pair": False,
                    "estimated_wait_minutes": 10,
                    "registrations": [waiting],
                    "common_play_preview": {
                        "registration_id": partner["registration_id"],
                        "display_id": partner["display_id"],
                    },
                }
            ]
            machine["registration_count"] = 2
            machine["waiting_position_count"] = 1
            return snapshot

        invalid_snapshots = []

        missing = snapshot_with_preview()
        missing["machines"]["A"]["waiting_positions"][0]["common_play_preview"] = {
            "registration_id": "e" * 24,
            "display_id": "不存在",
        }
        invalid_snapshots.append(("引用不存在的登记", missing))

        duplicate = snapshot_with_preview()
        position = duplicate["machines"]["A"]["waiting_positions"][0]
        position["common_play_preview"] = {
            "registration_id": position["registrations"][0]["registration_id"],
            "display_id": position["registrations"][0]["display_id"],
        }
        invalid_snapshots.append(("重复当前位置登记", duplicate))

        mismatched_name = snapshot_with_preview()
        mismatched_name["machines"]["A"]["waiting_positions"][0][
            "common_play_preview"
        ]["display_id"] = "错误昵称"
        invalid_snapshots.append(("昵称不一致", mismatched_name))

        solo = snapshot_with_preview()
        solo["machines"]["A"]["playing"][0]["preference"] = "SOLO"
        invalid_snapshots.append(("搭档要求单人游玩", solo))

        malformed = snapshot_with_preview()
        malformed["machines"]["A"]["waiting_positions"][0][
            "common_play_preview"
        ] = "invalid"
        invalid_snapshots.append(("字段类型无效", malformed))

        for label, snapshot in invalid_snapshots:
            with self.subTest(label=label):
                response = self.client.post(
                    "/api/queue-status", json=snapshot, headers=self.headers
                )
                self.assertEqual(400, response.status_code)
                self.assertIn("共同游玩预览", response.get_json()["error"])

    def paired_snapshot(self, revision=40):
        """Machines A and B form a pair; one registration waiting at A also queues at B."""
        snapshot = self.snapshot(revision=revision)
        machine_a = snapshot["machines"]["A"]
        machine_b = snapshot["machines"]["B"]
        waiting = self.registration("c" * 24, "同时排队玩家")
        waiting["also_queues_at_paired_machine"] = True
        machine_a["waiting_positions"] = [
            {
                "index": 1,
                "position_id": "d" * 24,
                "fixed_pair": False,
                "estimated_wait_minutes": 6,
                "registrations": [waiting],
                "common_play_preview": None,
            }
        ]
        machine_a["registration_count"] = 2
        machine_a["waiting_position_count"] = 1
        machine_a["paired_machine_id"] = "B"
        machine_b["playing"] = [self.registration("b" * 24, "机台乙玩家")]
        machine_b["playing_started_at"] = 900_000
        machine_b["registration_count"] = 1
        machine_b["paired_machine_id"] = "A"
        machine_b["paired_waiting_positions"] = [
            {
                "index": 7,
                "position_id": "e" * 24,
                "estimated_wait_minutes": 6,
                "registrations": [
                    {"registration_id": "c" * 24, "paired_place_preview": True}
                ],
                "common_play_preview": None,
            }
        ]
        return snapshot

    def test_paired_machine_fields_default_to_absent(self):
        publish = self.client.post(
            "/api/queue-status", json=self.snapshot(revision=39), headers=self.headers
        )
        machine = self.client.get("/api/queue-status").get_json()["machines"]["A"]

        self.assertEqual(204, publish.status_code)
        self.assertIsNone(machine["paired_machine_id"])
        self.assertIsNone(machine["paired_waiting_positions"])
        self.assertIs(False, machine["playing"][0]["also_queues_at_paired_machine"])
        self.assertIs(False, machine["playing"][0]["skips_paired_machine_this_round"])
        self.assertIs(False, machine["playing"][0]["paired_arrangement_announced"])
        self.assertIsNone(machine["playing"][0]["paired_arrangement_not_before"])

    def test_registration_that_gave_up_its_place_for_this_round_is_public(self):
        # The registration still has the player's choice on, but holds no place at B.
        snapshot = self.paired_snapshot(revision=43)
        waiting = snapshot["machines"]["A"]["waiting_positions"][0]["registrations"][0]
        waiting["skips_paired_machine_this_round"] = True
        snapshot["machines"]["B"]["paired_waiting_positions"] = None

        publish = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )
        machines = self.client.get("/api/queue-status").get_json()["machines"]
        published = machines["A"]["waiting_positions"][0]["registrations"][0]

        self.assertEqual(204, publish.status_code, publish.get_json())
        self.assertIs(True, published["also_queues_at_paired_machine"])
        self.assertIs(True, published["skips_paired_machine_this_round"])
        self.assertIsNone(machines["B"]["paired_waiting_positions"])
        # Registrations the terminal did not mark carry the key as false.
        self.assertIs(False, machines["A"]["playing"][0]["skips_paired_machine_this_round"])

    def test_giving_up_the_place_for_this_round_is_kept_while_the_choice_is_off(self):
        # The terminal keeps the mark when the player turns the choice off in the same round.
        snapshot = self.paired_snapshot(revision=44)
        waiting = snapshot["machines"]["A"]["waiting_positions"][0]["registrations"][0]
        waiting["also_queues_at_paired_machine"] = False
        waiting["skips_paired_machine_this_round"] = True
        snapshot["machines"]["B"]["paired_waiting_positions"] = None

        publish = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )
        published = self.client.get("/api/queue-status").get_json()["machines"]["A"][
            "waiting_positions"
        ][0]["registrations"][0]

        self.assertEqual(204, publish.status_code, publish.get_json())
        self.assertIs(False, published["also_queues_at_paired_machine"])
        self.assertIs(True, published["skips_paired_machine_this_round"])

    def test_announced_arrangement_is_public_with_its_earliest_moment(self):
        snapshot = self.paired_snapshot(revision=45)
        waiting = snapshot["machines"]["A"]["waiting_positions"][0]["registrations"][0]
        waiting["paired_arrangement_announced"] = True
        waiting["paired_arrangement_not_before"] = 1_700_000_300_000

        publish = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )
        machines = self.client.get("/api/queue-status").get_json()["machines"]
        published = machines["A"]["waiting_positions"][0]["registrations"][0]

        self.assertEqual(204, publish.status_code, publish.get_json())
        self.assertIs(True, published["paired_arrangement_announced"])
        self.assertEqual(1_700_000_300_000, published["paired_arrangement_not_before"])
        # Registrations without an announcement carry the keys as false and null.
        self.assertIs(False, machines["A"]["playing"][0]["paired_arrangement_announced"])
        self.assertIsNone(machines["A"]["playing"][0]["paired_arrangement_not_before"])

    def test_announced_arrangement_may_come_without_a_moment(self):
        # The terminal holds the count while a round runs unusually long: no time is promised.
        snapshot = self.paired_snapshot(revision=46)
        waiting = snapshot["machines"]["A"]["waiting_positions"][0]["registrations"][0]
        waiting["paired_arrangement_announced"] = True

        publish = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )
        published = self.client.get("/api/queue-status").get_json()["machines"]["A"][
            "waiting_positions"
        ][0]["registrations"][0]

        self.assertEqual(204, publish.status_code, publish.get_json())
        self.assertIs(True, published["paired_arrangement_announced"])
        self.assertIsNone(published["paired_arrangement_not_before"])

    def test_announcement_is_not_kept_for_a_registration_whose_choice_is_off(self):
        snapshot = self.paired_snapshot(revision=47)
        waiting = snapshot["machines"]["A"]["waiting_positions"][0]["registrations"][0]
        waiting["also_queues_at_paired_machine"] = False
        waiting["paired_arrangement_announced"] = True
        waiting["paired_arrangement_not_before"] = 1_700_000_300_000
        snapshot["machines"]["B"]["paired_waiting_positions"] = None

        publish = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )
        published = self.client.get("/api/queue-status").get_json()["machines"]["A"][
            "waiting_positions"
        ][0]["registrations"][0]

        self.assertEqual(204, publish.status_code, publish.get_json())
        self.assertIs(False, published["paired_arrangement_announced"])
        self.assertIsNone(published["paired_arrangement_not_before"])

    def test_paired_machine_preview_is_public_and_does_not_count_as_a_registration(self):
        publish = self.client.post(
            "/api/queue-status", json=self.paired_snapshot(), headers=self.headers
        )
        machines = self.client.get("/api/queue-status").get_json()["machines"]

        self.assertEqual(204, publish.status_code)
        self.assertEqual("B", machines["A"]["paired_machine_id"])
        self.assertEqual("A", machines["B"]["paired_machine_id"])
        self.assertIsNone(machines["A"]["paired_waiting_positions"])
        self.assertIs(
            True,
            machines["A"]["waiting_positions"][0]["registrations"][0][
                "also_queues_at_paired_machine"
            ],
        )
        # The registration is still counted and listed once, at machine A.
        self.assertEqual(2, machines["A"]["registration_count"])
        self.assertEqual(1, machines["B"]["registration_count"])
        self.assertEqual([], machines["B"]["waiting_positions"])
        self.assertEqual(
            [
                {
                    "index": 1,
                    "position_id": "e" * 24,
                    "estimated_wait_minutes": 6,
                    "estimated_wait_uncertain": False,
                    "registrations": [
                        {"registration_id": "c" * 24, "paired_place_preview": True}
                    ],
                    "common_play_preview": None,
                }
            ],
            machines["B"]["paired_waiting_positions"],
        )

    def test_paired_machine_preview_may_share_a_position_and_carry_common_play_preview(self):
        snapshot = self.paired_snapshot(revision=41)
        machine_a = snapshot["machines"]["A"]
        machine_b = snapshot["machines"]["B"]
        machine_a["waiting_positions"][0]["registrations"][0]["preference"] = "OPEN_TO_JOIN"
        own = self.registration("f" * 24, "机台乙等待玩家")
        own["preference"] = "OPEN_TO_JOIN"
        later = self.registration("9" * 24, "机台乙靠后玩家")
        later["preference"] = "OPEN_TO_JOIN"
        machine_b["playing"][0]["preference"] = "OPEN_TO_JOIN"
        machine_b["waiting_positions"] = [
            {
                "index": 1,
                "position_id": "1" * 24,
                "fixed_pair": False,
                "estimated_wait_minutes": 6,
                "registrations": [own, later],
                "common_play_preview": None,
            }
        ]
        machine_b["registration_count"] = 3
        machine_b["waiting_position_count"] = 1
        machine_b["paired_waiting_positions"] = [
            {
                "index": 1,
                "position_id": "2" * 24,
                "estimated_wait_minutes": 6,
                "registrations": [
                    {"registration_id": "f" * 24, "paired_place_preview": False},
                    {"registration_id": "c" * 24, "paired_place_preview": True},
                ],
                "common_play_preview": None,
            },
            {
                "index": 2,
                "position_id": "3" * 24,
                "estimated_wait_minutes": 21,
                "registrations": [
                    {"registration_id": "9" * 24, "paired_place_preview": False}
                ],
                "common_play_preview": {
                    "registration_id": "b" * 24,
                    "display_id": "机台乙玩家",
                },
            },
        ]

        publish = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )
        positions = self.client.get("/api/queue-status").get_json()["machines"]["B"][
            "paired_waiting_positions"
        ]

        self.assertEqual(204, publish.status_code, publish.get_json())
        self.assertEqual([2, 1], [len(position["registrations"]) for position in positions])
        self.assertEqual(
            {"registration_id": "b" * 24, "display_id": "机台乙玩家"},
            positions[1]["common_play_preview"],
        )

    def test_wait_estimate_uncertain_flag_is_optional(self):
        missing = object()

        def published_positions(revision, value, estimated_wait_minutes=6):
            snapshot = self.paired_snapshot(revision=revision)
            own = snapshot["machines"]["A"]["waiting_positions"][0]
            paired = snapshot["machines"]["B"]["paired_waiting_positions"][0]
            for position in (own, paired):
                position["estimated_wait_minutes"] = estimated_wait_minutes
                if value is not missing:
                    position["estimated_wait_uncertain"] = value
            publish = self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            )
            self.assertEqual(204, publish.status_code, publish.get_json())
            machines = self.client.get("/api/queue-status").get_json()["machines"]
            return (
                machines["A"]["waiting_positions"][0]["estimated_wait_uncertain"],
                machines["B"]["paired_waiting_positions"][0]["estimated_wait_uncertain"],
            )

        # Older terminals leave the flag out; it then means false.
        self.assertEqual((False, False), published_positions(50, missing))
        self.assertEqual((False, False), published_positions(51, None))
        self.assertEqual((False, False), published_positions(52, False))
        self.assertEqual((True, True), published_positions(53, True))
        self.assertEqual((True, True), published_positions(54, True, 0))
        # A position without an estimate is never marked.
        self.assertEqual((False, False), published_positions(55, True, None))

    def test_wait_estimate_uncertain_flag_reaches_player_account_and_bot(self):
        def published(revision, value):
            snapshot = self.paired_remote_snapshot(
                revision, with_registration=True, registration_queues_at_both=True
            )
            own = snapshot["machines"]["A"]["waiting_positions"][0]
            own["estimated_wait_minutes"] = 4
            paired = {
                "index": 1,
                "position_id": "e" * 24,
                "estimated_wait_minutes": 3,
                "registrations": [
                    {"registration_id": "a" * 24, "paired_place_preview": True}
                ],
                "common_play_preview": None,
            }
            if value is not None:
                own["estimated_wait_uncertain"] = value
                paired["estimated_wait_uncertain"] = value
            snapshot["machines"]["B"]["paired_waiting_positions"] = [paired]
            self.publish(snapshot)
            machines = self.client.get("/api/queue-status").get_json()["machines"]
            account = self.client.get("/api/player-account/queue").get_json()[
                "registrations"
            ][0]
            bot_player = self.client.post(
                "/api/queue-bot/players",
                json={"qq": "12345678"},
                headers=self.bot_headers,
            ).get_json()["players"][0]
            return machines, account, bot_player

        self.publish(self.paired_remote_snapshot(80))
        binding = self.client.post(
            "/api/queue-terminal/player-bindings",
            json={"profile_id": self.profile_id},
            headers=self.headers,
        )
        completed = self.client.post(
            f"/api/player-account/bindings/{binding.get_json()['binding_token']}/complete",
            json={"password": "first-password"},
        )
        self.assertEqual(201, completed.status_code, completed.get_json())

        machines, account, bot_player = published(81, True)
        self.assertIs(True, machines["A"]["waiting_positions"][0]["estimated_wait_uncertain"])
        self.assertIs(
            True, machines["B"]["paired_waiting_positions"][0]["estimated_wait_uncertain"]
        )
        self.assertEqual(4, account["estimated_wait_minutes"])
        self.assertIs(True, account["estimated_wait_uncertain"])
        self.assertEqual(4, bot_player["estimated_wait_minutes"])
        self.assertIs(True, bot_player["estimated_wait_uncertain"])

        machines, account, bot_player = published(82, None)
        self.assertIs(False, machines["A"]["waiting_positions"][0]["estimated_wait_uncertain"])
        self.assertIs(False, account["estimated_wait_uncertain"])
        self.assertIs(False, bot_player["estimated_wait_uncertain"])

    def test_playing_registration_wait_estimate_is_never_uncertain(self):
        snapshot = self.remote_ready_snapshot(
            revision=83, with_registration=True
        )
        machine = snapshot["machines"]["A"]
        registration = machine["waiting_positions"][0]["registrations"][0]
        machine["playing"] = [registration]
        machine["playing_started_at"] = 900_000
        machine["waiting_positions"] = []
        machine["waiting_position_count"] = 0
        self.publish(snapshot)

        bot_player = self.client.post(
            "/api/queue-bot/players",
            json={"qq": "12345678"},
            headers=self.bot_headers,
        ).get_json()["players"][0]

        self.assertEqual("PLAYING", bot_player["position"])
        self.assertEqual(0, bot_player["estimated_wait_minutes"])
        self.assertIs(False, bot_player["estimated_wait_uncertain"])

    def test_rejects_invalid_paired_machine_data(self):
        cases = []

        def case(label, expected_error, change):
            snapshot = self.paired_snapshot(revision=42)
            change(snapshot["machines"]["A"], snapshot["machines"]["B"])
            cases.append((label, expected_error, snapshot))

        def waiting_registration(machine):
            return machine["waiting_positions"][0]["registrations"][0]

        def preview_position(machine):
            return machine["paired_waiting_positions"][0]

        case(
            "同时排队标记不是布尔值",
            "also_queues_at_paired_machine 必须是布尔值。",
            lambda a, b: waiting_registration(a).update(also_queues_at_paired_machine="true"),
        )

        case(
            "本轮只在当前机台排队的标记不是布尔值",
            "skips_paired_machine_this_round 必须是布尔值。",
            lambda a, b: waiting_registration(a).update(
                skips_paired_machine_this_round="true"
            ),
        )

        case(
            "预告标记不是布尔值",
            "paired_arrangement_announced 必须是布尔值。",
            lambda a, b: waiting_registration(a).update(paired_arrangement_announced=1),
        )
        for label, value in (
            ("是负数", -1),
            ("是字符串", "1700000300000"),
            ("是布尔值", True),
            ("是小数", 1.5),
        ):
            case(
                f"最早转至时刻{label}",
                "paired_arrangement_not_before 数值无效。",
                lambda a, b, value=value: waiting_registration(a).update(
                    paired_arrangement_announced=True,
                    paired_arrangement_not_before=value,
                ),
            )
        case(
            "只有最早转至时刻而没有预告标记",
            "paired_arrangement_not_before 只能与 paired_arrangement_announced 一同出现。",
            lambda a, b: waiting_registration(a).update(
                paired_arrangement_not_before=1_700_000_300_000
            ),
        )

        def unpaired_flag(a, b):
            a["paired_machine_id"] = None
            b["paired_machine_id"] = None
            b["paired_waiting_positions"] = None

        case("机台未成对但登记同时排队", "不属于一对机台", unpaired_flag)

        def fixed_pair_flag(a, b):
            waiting_registration(a).update(fixed_pair=True, fixed_pair_id="8" * 24)

        case("固定组合登记同时排队", "固定组合登记不能在两台机台之间安排", fixed_pair_flag)

        def temporary_flag(a, b):
            waiting_registration(a).update(registration_type="TEMPORARY")

        case("临时登记允许系统安排", "临时登记不能在两台机台之间安排", temporary_flag)

        for label, value in (
            ("指向自身", "A"),
            ("不是机台字母", "Z"),
            ("不是字符串", 2),
            ("是数组", ["B"]),
            ("是对象", {"id": "B"}),
        ):
            case(
                f"另一机台标识{label}",
                "paired_machine_id 无效。",
                lambda a, b, value=value: a.update(paired_machine_id=value),
            )

        def one_sided(a, b):
            b["paired_machine_id"] = None
            b["paired_waiting_positions"] = None

        case("只有一方声明成对", "必须互为一对机台", one_sided)

        def unconfigured_partner(a, b):
            a["paired_machine_id"] = "C"
            b["paired_machine_id"] = None
            b["paired_waiting_positions"] = None

        case("另一机台未配置", "必须互为一对机台", unconfigured_partner)

        def preview_without_pair(a, b):
            waiting_registration(a)["also_queues_at_paired_machine"] = False
            a["paired_machine_id"] = None
            b["paired_machine_id"] = None

        case("未成对的机台包含预览", "不能包含另一机台的登记预览", preview_without_pair)

        def preview_on_stopped_machine(a, b):
            b.update(
                operational=False,
                stop_reason="MAINTENANCE",
                stopped_at=950_000,
                playing_started_at=None,
            )

        case("停止使用的机台包含预览", "停止使用的机台 B 不能包含", preview_on_stopped_machine)

        for label, value in (("空数组", []), ("不是数组", {"0": {}}), ("是字符串", "none")):
            case(
                f"预览顺序{label}",
                "必须是非空数组或 null",
                lambda a, b, value=value: b.update(paired_waiting_positions=value),
            )
        case(
            "位置不是对象",
            "的位置必须是对象",
            lambda a, b: b.update(paired_waiting_positions=["invalid"]),
        )
        case(
            "位置数量超过限制",
            "超过数量限制",
            lambda a, b: b.update(
                paired_waiting_positions=[preview_position(b)] * 41
            ),
        )
        case(
            "位置没有登记",
            "每个位置必须包含 1 或 2 个登记",
            lambda a, b: preview_position(b).update(registrations=[]),
        )
        case(
            "位置的登记不是数组",
            "每个位置必须包含 1 或 2 个登记",
            lambda a, b: preview_position(b).update(registrations="cccc"),
        )
        case(
            "登记引用不是对象",
            "包含无效登记",
            lambda a, b: preview_position(b).update(registrations=["c" * 24]),
        )
        case(
            "登记编号无效",
            "registration_id 公开编号无效。",
            lambda a, b: preview_position(b)["registrations"][0].update(
                registration_id=["c" * 24]
            ),
        )
        case(
            "预览标记不是布尔值",
            "paired_place_preview 必须是布尔值。",
            lambda a, b: preview_position(b)["registrations"][0].update(
                paired_place_preview=1
            ),
        )
        case(
            "重复包含同一份登记",
            "不能重复包含同一份登记",
            lambda a, b: preview_position(b)["registrations"].append(
                {"registration_id": "c" * 24, "paired_place_preview": True}
            ),
        )
        case(
            "位置编号无效",
            "position_id 公开编号无效。",
            lambda a, b: preview_position(b).update(position_id=None),
        )
        case(
            "估算时间无效",
            "estimated_wait_minutes 数值无效。",
            lambda a, b: preview_position(b).update(estimated_wait_minutes=-1),
        )
        case(
            "预览位置的估算不准确标记不是布尔值",
            "estimated_wait_uncertain 必须是布尔值。",
            lambda a, b: preview_position(b).update(estimated_wait_uncertain="true"),
        )
        case(
            "等待位置的估算不准确标记不是布尔值",
            "estimated_wait_uncertain 必须是布尔值。",
            lambda a, b: a["waiting_positions"][0].update(estimated_wait_uncertain=1),
        )

        def duplicate_position_ids(a, b):
            waiting_registration(a)["also_queues_at_paired_machine"] = True
            second = self.registration("7" * 24, "另一位同时排队玩家")
            second["also_queues_at_paired_machine"] = True
            a["waiting_positions"].append(
                {
                    "index": 2,
                    "position_id": "6" * 24,
                    "fixed_pair": False,
                    "estimated_wait_minutes": 18,
                    "registrations": [second],
                    "common_play_preview": None,
                }
            )
            a["registration_count"] = 3
            a["waiting_position_count"] = 2
            b["paired_waiting_positions"].append(
                {
                    "index": 2,
                    "position_id": "e" * 24,
                    "estimated_wait_minutes": 18,
                    "registrations": [
                        {"registration_id": "7" * 24, "paired_place_preview": True}
                    ],
                    "common_play_preview": None,
                }
            )

        case("位置编号重复", "位置编号不能重复", duplicate_position_ids)

        def missing_own_registration(a, b):
            own = self.registration("f" * 24, "机台乙等待玩家")
            b["waiting_positions"] = [
                {
                    "index": 1,
                    "position_id": "1" * 24,
                    "fixed_pair": False,
                    "estimated_wait_minutes": 6,
                    "registrations": [own],
                    "common_play_preview": None,
                }
            ]
            b["registration_count"] = 2
            b["waiting_position_count"] = 1

        case("缺少本机台等待中的登记", "必须正好包含当前机台等待中的全部登记", missing_own_registration)
        case(
            "把另一机台的登记写成本机台登记",
            "必须正好包含当前机台等待中的全部登记",
            lambda a, b: preview_position(b)["registrations"][0].update(
                paired_place_preview=False
            ),
        )

        def no_preview_at_all(a, b):
            missing_own_registration(a, b)
            preview_position(b)["registrations"] = [
                {"registration_id": "f" * 24, "paired_place_preview": False}
            ]

        case("没有任何预览", "没有另一机台的登记预览时必须为 null", no_preview_at_all)

        placeholder_error = "必须是正在机台 A 等待并已允许系统在两台机台之间安排的登记"
        case(
            "预览的登记不存在",
            placeholder_error,
            lambda a, b: preview_position(b)["registrations"][0].update(
                registration_id="5" * 24
            ),
        )
        case(
            "预览的登记没有同时排队",
            placeholder_error,
            lambda a, b: waiting_registration(a).update(
                also_queues_at_paired_machine=False
            ),
        )
        case(
            "预览的登记暂缓一次",
            placeholder_error,
            lambda a, b: waiting_registration(a).update(deferred_once=True),
        )
        case(
            "预览的登记暂时离开",
            placeholder_error,
            lambda a, b: waiting_registration(a).update(temporarily_away=True),
        )
        case(
            "预览的登记将轮空一次",
            placeholder_error,
            lambda a, b: waiting_registration(a).update(skips_one_turn=True),
        )
        case(
            "预览的登记尚未签到",
            placeholder_error,
            lambda a, b: waiting_registration(a).update(
                online_registration_pending_check_in=True
            ),
        )

        case(
            "预览的登记本轮只在当前机台排队",
            placeholder_error,
            lambda a, b: waiting_registration(a).update(
                skips_paired_machine_this_round=True
            ),
        )

        def preview_of_playing_registration(a, b):
            a["playing"][0]["also_queues_at_paired_machine"] = True
            preview_position(b)["registrations"][0]["registration_id"] = "a" * 24

        case("预览的登记正在游玩", placeholder_error, preview_of_playing_registration)

        def unknown_common_play_preview(a, b):
            preview_position(b)["common_play_preview"] = {
                "registration_id": "5" * 24,
                "display_id": "不存在",
            }

        case("共同游玩预览引用不存在的登记", "共同游玩预览登记不存在", unknown_common_play_preview)

        def repeated_common_play_preview(a, b):
            preview_position(b)["common_play_preview"] = {
                "registration_id": "c" * 24,
                "display_id": "同时排队玩家",
            }

        case("共同游玩预览重复当前位置登记", "共同游玩预览不能重复当前位置登记", repeated_common_play_preview)

        def misnamed_common_play_preview(a, b):
            waiting_registration(a)["preference"] = "OPEN_TO_JOIN"
            b["playing"][0]["preference"] = "OPEN_TO_JOIN"
            preview_position(b)["common_play_preview"] = {
                "registration_id": "b" * 24,
                "display_id": "错误昵称",
            }

        case("共同游玩预览昵称不一致", "共同游玩预览昵称不一致", misnamed_common_play_preview)

        def solo_common_play_preview(a, b):
            preview_position(b)["common_play_preview"] = {
                "registration_id": "b" * 24,
                "display_id": "机台乙玩家",
            }

        case("共同游玩预览与游玩偏好不一致", "共同游玩预览与游玩偏好不一致", solo_common_play_preview)

        for label, expected_error, snapshot in cases:
            with self.subTest(label=label):
                response = self.client.post(
                    "/api/queue-status", json=snapshot, headers=self.headers
                )
                self.assertEqual(400, response.status_code, label)
                self.assertIn(expected_error, response.get_json()["error"])
        # Nothing of the rejected uploads was stored.
        self.assertEqual(404, self.client.get("/api/queue-status").status_code)

    def uploaded_profile_snapshot(self, revision, **profile_fields):
        snapshot = self.remote_ready_snapshot(revision=revision)
        snapshot["private_player_profiles"][0].update(profile_fields)
        return snapshot

    def terminal_profile(self):
        response = self.client.get("/api/queue-terminal/profiles", headers=self.headers)
        self.assertEqual(200, response.status_code, response.get_json())
        return next(
            profile
            for profile in response.get_json()["profiles"]
            if profile["profile_id"] == self.profile_id
        )

    def test_profile_field_of_the_removed_setting_is_ignored(self):
        # A profile no longer carries a setting for paired machines. A terminal that still
        # uploads that field is not turned away, whatever it contains, and nothing of it is
        # kept or sent back.
        for revision, fields in (
            (50, {"paired_machine_queueing": "ON"}),
            (51, {"paired_machine_queueing": "SOMETIMES"}),
        ):
            with self.subTest(fields=fields):
                response = self.client.post(
                    "/api/queue-status",
                    json=self.uploaded_profile_snapshot(revision, **fields),
                    headers=self.headers,
                )
                profile = self.terminal_profile()
                self.assertEqual(204, response.status_code, response.get_json())
                self.assertNotIn("paired_machine_queueing", profile)

    def profile_was_shown_the_explanation(self):
        return self.terminal_profile()["paired_machine_queueing_introduced"]

    def test_explanation_record_of_a_profile_stays_once_it_is_set(self):
        # The terminal reports that a player has been shown the explanation only as true.
        # The server keeps it by OR: an upload without the key, or with false, resets nothing.
        def upload(revision, **fields):
            response = self.client.post(
                "/api/queue-status",
                json=self.uploaded_profile_snapshot(revision, **fields),
                headers=self.headers,
            )
            return response.status_code

        self.assertEqual(204, upload(50))
        not_yet = self.profile_was_shown_the_explanation()
        self.assertEqual(204, upload(51, paired_machine_queueing_introduced=True))
        shown = self.profile_was_shown_the_explanation()
        self.assertEqual(204, upload(52))
        after_upload_without_the_key = self.profile_was_shown_the_explanation()
        self.assertEqual(204, upload(53, paired_machine_queueing_introduced=False))
        after_upload_with_false = self.profile_was_shown_the_explanation()
        not_a_boolean = self.client.post(
            "/api/queue-status",
            json=self.uploaded_profile_snapshot(54, paired_machine_queueing_introduced=1),
            headers=self.headers,
        )

        self.assertIs(False, not_yet)
        self.assertIs(True, shown)
        self.assertIs(True, after_upload_without_the_key)
        self.assertIs(True, after_upload_with_false)
        self.assertEqual(400, not_a_boolean.status_code)
        self.assertEqual(
            "paired_machine_queueing_introduced 必须是布尔值。",
            not_a_boolean.get_json()["error"],
        )

    def paired_remote_snapshot(
        self,
        revision=60,
        *,
        with_registration=False,
        registration_queues_at_both=False,
    ):
        """Remote-ready snapshot whose machines A and B form a pair."""
        snapshot = self.remote_ready_snapshot(
            revision=revision,
            default_preference="SOLO",
            with_registration=with_registration,
        )
        snapshot["machines"]["A"]["paired_machine_id"] = "B"
        snapshot["machines"]["B"]["paired_machine_id"] = "A"
        if with_registration:
            registration = snapshot["machines"]["A"]["waiting_positions"][0]["registrations"][0]
            registration["also_queues_at_paired_machine"] = registration_queues_at_both
        return snapshot

    def publish(self, snapshot):
        response = self.client.post("/api/queue-status", json=snapshot, headers=self.headers)
        self.assertEqual(204, response.status_code, response.get_json())

    def pending_command_payloads(self):
        return [
            command["payload"]
            for command in self.client.get(
                "/api/queue-terminal/commands", headers=self.headers
            ).get_json()["commands"]
        ]

    def test_online_lookup_reports_paired_machines_and_no_profile_setting(self):
        self.publish(self.paired_remote_snapshot())

        lookup = self.client.post(
            "/api/queue-online/profile", json={"qq": "12345678"}
        ).get_json()

        self.assertNotIn("paired_machine_queueing", lookup["profile"])
        # Only whether the explanation has been shown is kept on a profile.
        self.assertIs(False, lookup["profile"]["paired_machine_queueing_introduced"])
        self.assertEqual(
            {"A": "B", "B": "A"},
            {machine["id"]: machine["paired_machine_id"] for machine in lookup["machines"]},
        )

    def test_online_join_carries_the_choice_to_queue_at_two_machines(self):
        self.publish(self.paired_remote_snapshot())
        request_body = {
            "request_id": "00000000-0000-0000-0000-000000000461",
            "qq": "12345678",
            "machine_id": "A",
            "also_queues_at_paired_machine": True,
        }

        created = self.client.post("/api/queue-online/join", json=request_body)
        repeated = self.client.post("/api/queue-online/join", json=request_body)
        changed = self.client.post(
            "/api/queue-online/join",
            json={**request_body, "also_queues_at_paired_machine": False},
        )
        payloads = self.pending_command_payloads()

        self.assertEqual(202, created.status_code, created.get_json())
        self.assertEqual(200, repeated.status_code)
        # The same request_id with another choice is a different request.
        self.assertEqual(409, changed.status_code)
        self.assertEqual(1, len(payloads))
        self.assertIs(True, payloads[0]["also_queues_at_paired_machine"])

    def online_join(self, request_number, ticked):
        return self.client.post(
            "/api/queue-online/join",
            json={
                "request_id": f"00000000-0000-0000-0000-0000000005{request_number}",
                "qq": "12345678",
                "machine_id": "A",
                "preference": "SOLO",
                "also_queues_at_paired_machine": ticked,
            },
        )

    def complete_command(self, command_id, status):
        """Lets the terminal fetch its commands and report the outcome of one."""
        self.client.get("/api/queue-terminal/commands", headers=self.headers)
        completed = self.client.post(
            f"/api/queue-terminal/commands/{command_id}/result",
            json={"status": status, "detail": "终端行为测试完成。"},
            headers=self.headers,
        )
        self.assertEqual(status, completed.get_json()["status"], completed.get_json())

    def test_online_join_with_the_tick_box_on_records_the_explanation_once_applied(self):
        # An online join only names a QQ number and needs no sign-in, so anybody can send
        # it for anybody. Accepting it therefore says nothing about what the player has
        # been shown; only a join the terminal has carried out is recorded.
        self.publish(self.paired_remote_snapshot())
        before = self.profile_was_shown_the_explanation()

        refused = self.online_join(10, True)
        after_request = self.profile_was_shown_the_explanation()
        self.complete_command(refused.get_json()["command_id"], "REJECTED")
        after_refusal = self.profile_was_shown_the_explanation()
        lookup_after_refusal = self.client.post(
            "/api/queue-online/profile", json={"qq": "12345678"}
        ).get_json()

        applied = self.online_join(13, True)
        self.complete_command(applied.get_json()["command_id"], "APPLIED")

        self.assertIs(False, before)
        self.assertEqual(202, refused.status_code, refused.get_json())
        self.assertIs(False, after_request)
        self.assertIs(False, after_refusal)
        self.assertIs(
            False, lookup_after_refusal["profile"]["paired_machine_queueing_introduced"]
        )
        self.assertEqual(202, applied.status_code, applied.get_json())
        self.assertIs(True, self.profile_was_shown_the_explanation())

    def test_applied_online_join_with_the_tick_box_off_records_nothing(self):
        self.publish(self.paired_remote_snapshot())

        applied = self.online_join(14, False)
        self.complete_command(applied.get_json()["command_id"], "APPLIED")

        self.assertEqual(202, applied.status_code, applied.get_json())
        self.assertIs(False, self.profile_was_shown_the_explanation())

    def test_applied_online_join_records_the_explanation_among_the_test_data(self):
        # The test data of a terminal keep their own profiles; the record goes to the data
        # set the command was made for, and the official profile stays as it was.
        self.app.config["ALLOWED_DEVICE_ID"] = ""
        self.app.config["PRIMARY_DEVICE_ID"] = "terminal-1"
        official = self.paired_remote_snapshot(revision=5)
        self.publish(official)
        connection = sqlite3.connect(self.database_path)
        try:
            connection.execute(
                "UPDATE queue_snapshot SET received_at = ? WHERE id = 1",
                (int(time.time()) - 91,),
            )
            connection.commit()
        finally:
            connection.close()
        test_headers = {
            **self.headers,
            "X-Device-ID": "terminal-2",
            "X-Queue-Sync-Mode": "test",
        }
        test_snapshot = copy.deepcopy(official)
        test_snapshot["revision"] = 99
        started = self.client.post(
            "/api/queue-status", json=test_snapshot, headers=test_headers
        )

        def records():
            connection = sqlite3.connect(self.database_path)
            try:
                return {
                    ("test" if ":test:" in scope else "official"): bool(introduced)
                    for scope, introduced in connection.execute(
                        """
                        SELECT device_id, paired_machine_queueing_introduced
                        FROM player_profile WHERE profile_id = ?
                        """,
                        (self.profile_id,),
                    )
                }
            finally:
                connection.close()

        before = records()
        applied = self.online_join(15, True)
        after_request = records()
        self.client.get("/api/queue-terminal/commands", headers=test_headers)
        completed = self.client.post(
            f"/api/queue-terminal/commands/{applied.get_json()['command_id']}/result",
            json={"status": "APPLIED", "detail": "终端行为测试完成。"},
            headers=test_headers,
        )

        self.assertEqual(204, started.status_code, started.get_json())
        self.assertTrue(self.client.get("/api/queue-status").get_json()["test_data"])
        self.assertEqual({"official": False, "test": False}, before)
        self.assertEqual(202, applied.status_code, applied.get_json())
        self.assertEqual({"official": False, "test": False}, after_request)
        self.assertEqual("APPLIED", completed.get_json()["status"], completed.get_json())
        self.assertEqual({"official": False, "test": True}, records())

    def test_online_join_with_the_tick_box_off_records_nothing(self):
        self.publish(self.paired_remote_snapshot())

        created = self.online_join(11, False)

        self.assertEqual(202, created.status_code, created.get_json())
        self.assertIs(False, self.profile_was_shown_the_explanation())

    def test_rejected_online_join_records_nothing(self):
        # The machine has no pair: the request is refused and nothing is noted.
        self.publish(self.remote_ready_snapshot(revision=61, default_preference="SOLO"))

        rejected = self.online_join(12, True)

        self.assertEqual(409, rejected.status_code, rejected.get_json())
        self.assertIs(False, self.profile_was_shown_the_explanation())

    def test_mobile_submit_with_the_tick_box_on_records_the_explanation(self):
        snapshot = self.paired_remote_snapshot(70)
        self.publish(snapshot)
        token = self.open_mobile_session(snapshot, 61)
        opened = self.client.get(f"/api/queue-mobile/sessions/{token}").get_json()

        submitted = self.client.post(
            f"/api/queue-mobile/sessions/{token}/submit",
            json={
                "request_id": "00000000-0000-0000-0000-000000000862",
                "profile_id": self.profile_id,
                "expected_profile_revision": 3,
                "also_queues_at_paired_machine": True,
            },
        )

        self.assertIs(False, opened["profiles"][0]["paired_machine_queueing_introduced"])
        self.assertEqual(202, submitted.status_code, submitted.get_json())
        self.assertIs(True, self.profile_was_shown_the_explanation())

    def test_account_turning_the_tick_box_on_records_the_explanation(self):
        self.publish(self.paired_remote_snapshot(with_registration=True))
        binding = self.client.post(
            "/api/queue-terminal/player-bindings",
            json={"profile_id": self.profile_id},
            headers=self.headers,
        )
        completed = self.client.post(
            f"/api/player-account/bindings/{binding.get_json()['binding_token']}/complete",
            json={"password": "first-password"},
        )
        queue_payload = self.client.get("/api/player-account/queue").get_json()
        registration = queue_payload["registrations"][0]
        queue = queue_payload["queue"]

        switched = self.client.post(
            "/api/player-account/queue-commands",
            json={
                "request_id": "00000000-0000-0000-0000-000000000778",
                "operation": "SET_PAIRED_MACHINE_QUEUEING",
                "also_queues_at_paired_machine": True,
                "expected_queue_id": queue["queue_id"],
                "expected_registration_id": registration["registration_id"],
                "expected_machine_id": registration["machine_id"],
                "expected_position": registration["position"],
                "expected_fixed_pair_id": registration["fixed_pair_id"],
                "expected_absence_status": "NONE",
                "expected_temporary_away_skipped_turns": 0,
                "expected_pending_check_in": False,
                "expected_machine_configuration_revision": queue[
                    "machine_configuration_revision"
                ],
                "expected_machine_stable_id": registration["machine_stable_id"],
            },
            headers={
                "X-CSRF-Token": self.client.get_cookie("maimai_q_session_csrf").value
            },
        )
        account = self.client.get("/api/player-account").get_json()

        self.assertEqual(201, completed.status_code, completed.get_json())
        self.assertIs(
            False,
            completed.get_json()["account"]["profile"]["paired_machine_queueing_introduced"],
        )
        self.assertEqual(202, switched.status_code, switched.get_json())
        self.assertIs(True, self.profile_was_shown_the_explanation())
        self.assertIs(
            True, account["account"]["profile"]["paired_machine_queueing_introduced"]
        )

    def test_bot_turning_the_tick_box_on_records_nothing(self):
        # The Bot shows no explanation, so its requests say nothing about one.
        self.publish(self.paired_remote_snapshot(with_registration=True))

        switched = self.client.post(
            "/api/queue-bot/queue-commands",
            json={
                "request_id": "00000000-0000-0000-0000-000000000484",
                "actor_qq": "12345678",
                "operation": "SET_PAIRED_MACHINE_QUEUEING",
                "also_queues_at_paired_machine": True,
            },
            headers=self.bot_headers,
        )

        self.assertEqual(202, switched.status_code, switched.get_json())
        self.assertIs(False, self.profile_was_shown_the_explanation())

    def test_messages_never_speak_of_queueing_at_two_machines(self):
        # Players and staff are told that the system arranges a registration between two
        # machines; the old wording must not come back in any message of the server.
        import ast

        source = (Path(__file__).parent / "app.py").read_text(encoding="utf-8")
        literals = [
            node.value
            for node in ast.walk(ast.parse(source))
            if isinstance(node, ast.Constant) and isinstance(node.value, str)
        ]
        offending = [
            text
            for text in literals
            if "同时排队" in text
            or ("同时在" in text and "排队" in text.split("同时在", 1)[1])
            # After 允许 the sentence names who arranges: 允许系统在……之间安排.
            or "允许在两台机台之间" in text
            or "允许在一对机台之间" in text
        ]
        self.assertEqual([], offending)

    def test_online_join_without_the_choice_sends_none_to_the_terminal(self):
        self.publish(self.paired_remote_snapshot())

        created = self.client.post(
            "/api/queue-online/join",
            json={
                "request_id": "00000000-0000-0000-0000-000000000462",
                "qq": "12345678",
                "machine_id": "A",
            },
        )

        self.assertEqual(202, created.status_code, created.get_json())
        # Without the tick box the registration is created for its own machine only.
        self.assertNotIn("also_queues_at_paired_machine", self.pending_command_payloads()[0])

    def test_online_join_rejects_a_choice_that_cannot_apply(self):
        cases = (
            (
                "机台不属于一对机台",
                self.remote_ready_snapshot(revision=61, default_preference="SOLO"),
                True,
                409,
                "不属于一对机台，不能自动在两台机台之间安排登记",
            ),
            (
                "不是布尔值",
                self.paired_remote_snapshot(64),
                "yes",
                400,
                "also_queues_at_paired_machine 必须是布尔值。",
            ),
        )
        for index, (label, snapshot, choice, status_code, expected_error) in enumerate(cases):
            with self.subTest(label=label):
                self.publish(snapshot)
                response = self.client.post(
                    "/api/queue-online/join",
                    json={
                        "request_id": f"00000000-0000-0000-0000-00000000047{index}",
                        "qq": "12345678",
                        "machine_id": "A",
                        "also_queues_at_paired_machine": choice,
                    },
                )
                self.assertEqual(status_code, response.status_code, response.get_json())
                self.assertIn(expected_error, response.get_json()["error"])
        self.assertEqual([], self.pending_command_payloads())

    def test_online_join_carries_an_unticked_box_as_given(self):
        self.publish(self.paired_remote_snapshot(65))

        created = self.client.post(
            "/api/queue-online/join",
            json={
                "request_id": "00000000-0000-0000-0000-000000000481",
                "qq": "12345678",
                "machine_id": "A",
                "also_queues_at_paired_machine": False,
            },
        )
        payloads = self.pending_command_payloads()

        # No profile setting stands in the way of either answer.
        self.assertEqual(202, created.status_code, created.get_json())
        self.assertEqual(1, len(payloads))
        self.assertIs(False, payloads[0]["also_queues_at_paired_machine"])

    def mobile_new_profile(self, nickname="移动新玩家", qq_number="87654321"):
        return {
            "nickname": nickname,
            "gender": "FEMALE",
            "default_preference": "SOLO",
            "qq_number": qq_number,
            "qq_visibility": "PUBLIC_WEBSITE",
            "notification_enabled": True,
            "notify_queue_changes": True,
            "notify_playing_position": False,
            "notify_online_check_in": True,
            "notify_absence": True,
            "notify_machine_status": False,
        }

    def test_mobile_registration_needs_no_profile_setting(self):
        snapshot = self.paired_remote_snapshot(70)
        self.publish(snapshot)
        token = self.open_mobile_session(snapshot, 60)
        body = {
            "request_id": "00000000-0000-0000-0000-000000000861",
            "profile_id": self.profile_id,
            "expected_profile_revision": 3,
        }

        opened = self.client.get(f"/api/queue-mobile/sessions/{token}").get_json()
        # The page of the removed setting sent this key; unknown keys are refused here.
        removed_field = self.client.post(
            f"/api/queue-mobile/sessions/{token}/submit",
            json={**body, "paired_machine_queueing": "ON"},
        )
        nothing_pending = self.pending_command_payloads()
        submitted = self.client.post(f"/api/queue-mobile/sessions/{token}/submit", json=body)
        payloads = self.pending_command_payloads()

        # The page shows the tick box where the venue has paired machines.
        self.assertIs(True, opened["session"]["paired_machine_queueing_available"])
        self.assertNotIn("paired_machine_queueing", opened["profiles"][0])
        self.assertIs(False, opened["profiles"][0]["paired_machine_queueing_introduced"])
        self.assertEqual(400, removed_field.status_code, removed_field.get_json())
        self.assertEqual("请求包含不支持的移动设备登记字段。", removed_field.get_json()["error"])
        self.assertEqual([], nothing_pending)
        self.assertEqual(202, submitted.status_code, submitted.get_json())
        self.assertEqual(1, len(payloads))
        self.assertNotIn("also_queues_at_paired_machine", payloads[0])
        self.assertNotIn("paired_machine_queueing", payloads[0]["profile"])

    def test_mobile_session_offers_no_tick_box_where_the_venue_has_no_paired_machines(self):
        snapshot = self.remote_ready_snapshot(revision=72, default_preference="SOLO")
        self.publish(snapshot)
        token = self.open_mobile_session(snapshot, 64)

        opened = self.client.get(f"/api/queue-mobile/sessions/{token}").get_json()
        submitted = self.client.post(
            f"/api/queue-mobile/sessions/{token}/submit",
            json={
                "request_id": "00000000-0000-0000-0000-000000000865",
                "profile_id": self.profile_id,
                "expected_profile_revision": 3,
            },
        )

        self.assertIs(False, opened["session"]["paired_machine_queueing_available"])
        self.assertIsNone(opened["session"]["paired_machine_id"])
        self.assertEqual(202, submitted.status_code, submitted.get_json())

    def test_mobile_registration_with_a_new_profile_carries_the_tick_box(self):
        snapshot = self.paired_remote_snapshot(73)
        self.publish(snapshot)
        token = self.open_mobile_session(snapshot, 66)

        submitted = self.client.post(
            f"/api/queue-mobile/sessions/{token}/submit",
            json={
                "request_id": "00000000-0000-0000-0000-000000000867",
                "preference": "SOLO",
                "new_profile": self.mobile_new_profile(),
                "also_queues_at_paired_machine": True,
            },
        )
        payloads = self.pending_command_payloads()

        self.assertEqual(202, submitted.status_code, submitted.get_json())
        self.assertEqual("NEW", payloads[0]["profile"]["mode"])
        # The tick box belongs to this registration; the new profile gets no setting from it.
        self.assertIs(True, payloads[0]["also_queues_at_paired_machine"])
        self.assertNotIn("paired_machine_queueing", payloads[0]["profile"])
        self.assertNotIn("paired_machine_queueing", payloads[0]["profile"]["profile"])

    def open_profile_creation(self, request_number):
        created = self.client.post(
            "/api/queue-terminal/player-profile-sessions",
            json={"request_id": f"00000000-0000-0000-0000-0000000009{request_number}"},
            headers=self.headers,
        )
        self.assertEqual(201, created.status_code, created.get_json())
        return created.get_json()["profile_creation_url"].split(
            "player_profile_creation=", 1
        )[1]

    def profile_creation_body(self, **fields):
        return {
            "nickname": "网页建档玩家",
            "gender": "UNDISCLOSED",
            "default_preference": "ASK_EVERY_TIME",
            "qq_number": "23456789",
            "password": "mobile-password",
            "password_confirmation": "mobile-password",
            "notification_enabled": True,
            "notify_queue_changes": True,
            "notify_playing_position": False,
            "notify_online_check_in": True,
            "notify_absence": True,
            "notify_machine_status": False,
            **fields,
        }

    def test_web_profile_creation_has_no_setting_for_paired_machines(self):
        self.publish(self.paired_remote_snapshot(74))
        token = self.open_profile_creation(61)
        endpoint = f"/api/player-profile-creation/sessions/{token}"

        opened = self.client.get(endpoint).get_json()
        # The page of the removed setting sent this field; the form takes exactly its fields.
        removed_field = self.client.post(
            f"{endpoint}/complete",
            json=self.profile_creation_body(paired_machine_queueing="ON"),
        )
        completed = self.client.post(f"{endpoint}/complete", json=self.profile_creation_body())
        account_profile = completed.get_json()["account"]["profile"]

        self.assertNotIn("paired_machine_queueing_available", opened)
        self.assertEqual(400, removed_field.status_code, removed_field.get_json())
        self.assertEqual(201, completed.status_code, completed.get_json())
        self.assertNotIn("paired_machine_queueing", account_profile)
        self.assertIs(False, account_profile["paired_machine_queueing_introduced"])

    def test_bot_can_switch_queueing_at_two_machines_for_a_registration(self):
        self.publish(self.paired_remote_snapshot(with_registration=True))

        def switch(request_number, **fields):
            return self.client.post(
                "/api/queue-bot/queue-commands",
                json={
                    "request_id": f"00000000-0000-0000-0000-0000000004{request_number}",
                    "actor_qq": "12345678",
                    "operation": "SET_PAIRED_MACHINE_QUEUEING",
                    **fields,
                },
                headers=self.bot_headers,
            )

        missing = switch(80)
        invalid = switch(81, also_queues_at_paired_machine=1)
        created = switch(82, also_queues_at_paired_machine=True)
        payloads = self.pending_command_payloads()
        other_operation = self.client.post(
            "/api/queue-bot/queue-commands",
            json={
                "request_id": "00000000-0000-0000-0000-000000000483",
                "actor_qq": "12345678",
                "operation": "LEAVE_QUEUE",
                "also_queues_at_paired_machine": True,
            },
            headers=self.bot_headers,
        )

        self.assertEqual(400, missing.status_code)
        self.assertEqual("请选择是否允许系统在两台机台之间安排登记。", missing.get_json()["error"])
        self.assertEqual(400, invalid.status_code)
        self.assertEqual(202, created.status_code, created.get_json())
        self.assertEqual(1, len(payloads))
        self.assertEqual("SET_PAIRED_MACHINE_QUEUEING", payloads[0]["operation"])
        self.assertIs(True, payloads[0]["also_queues_at_paired_machine"])
        self.assertEqual("A", payloads[0]["machine_id"])
        self.assertEqual(400, other_operation.status_code)
        self.assertIn("这项操作不接受", other_operation.get_json()["error"])

    def test_switching_queueing_at_two_machines_needs_a_paired_machine(self):
        self.publish(self.remote_ready_snapshot(revision=65, with_registration=True))

        for choice in (True, False):
            with self.subTest(choice=choice):
                response = self.client.post(
                    "/api/queue-bot/queue-commands",
                    json={
                        "request_id": f"00000000-0000-0000-0000-00000000049{int(choice)}",
                        "actor_qq": "12345678",
                        "operation": "SET_PAIRED_MACHINE_QUEUEING",
                        "also_queues_at_paired_machine": choice,
                    },
                    headers=self.bot_headers,
                )
                # Without a pair nothing is sent to the terminal, which may be one that does
                # not know this operation.
                self.assertEqual(409, response.status_code)
                self.assertEqual(
                    "系统规则不允许自动在两台机台之间安排登记。", response.get_json()["error"]
                )
        self.assertEqual([], self.pending_command_payloads())

    def test_registration_arranged_between_two_machines_can_still_be_moved_by_hand(self):
        self.publish(
            self.paired_remote_snapshot(
                with_registration=True, registration_queues_at_both=True
            )
        )

        response = self.client.post(
            "/api/queue-bot/queue-commands",
            json={
                "request_id": "00000000-0000-0000-0000-000000000495",
                "actor_qq": "12345678",
                "operation": "TRANSFER_MACHINE",
                "target_machine_id": "B",
            },
            headers=self.bot_headers,
        )

        # The terminal moves it to the tail of the other machine; the choice stays on.
        self.assertEqual(202, response.status_code, response.get_json())
        payloads = self.pending_command_payloads()
        self.assertEqual(1, len(payloads))
        self.assertEqual("B", payloads[0]["target_machine_id"])

    def open_mobile_session(self, snapshot, request_number):
        created = self.client.post(
            "/api/queue-terminal/mobile-registration-sessions",
            json={
                "request_id": f"00000000-0000-0000-0000-0000000008{request_number}",
                "queue_id": snapshot["queue_id"],
                "machine_id": "A",
            },
            headers=self.headers,
        )
        self.assertEqual(201, created.status_code, created.get_json())
        return created.get_json()["session_token"]

    def test_mobile_registration_offers_and_carries_the_choice_to_queue_at_two_machines(self):
        snapshot = self.paired_remote_snapshot(66)
        self.publish(snapshot)
        token = self.open_mobile_session(snapshot, 70)

        opened = self.client.get(f"/api/queue-mobile/sessions/{token}").get_json()
        submitted = self.client.post(
            f"/api/queue-mobile/sessions/{token}/submit",
            json={
                "request_id": "00000000-0000-0000-0000-000000000871",
                "profile_id": self.profile_id,
                "expected_profile_revision": 3,
                "also_queues_at_paired_machine": True,
            },
        )
        payloads = self.pending_command_payloads()

        self.assertEqual("B", opened["session"]["paired_machine_id"])
        self.assertEqual(snapshot["machines"]["B"]["name"].replace(" · ", "·"),
                         opened["session"]["paired_machine_name"])
        self.assertEqual(202, submitted.status_code, submitted.get_json())
        self.assertEqual(1, len(payloads))
        self.assertIs(True, payloads[0]["also_queues_at_paired_machine"])

    def test_mobile_registration_rejects_a_choice_that_cannot_apply(self):
        cases = (
            (
                "机台不属于一对机台",
                self.remote_ready_snapshot(revision=67, default_preference="SOLO"),
                3,
                True,
                "PAIRED_MACHINE_QUEUE_UNAVAILABLE",
                409,
            ),
            (
                "不是布尔值",
                self.paired_remote_snapshot(69),
                3,
                "true",
                None,
                400,
            ),
        )
        for index, (label, snapshot, revision, choice, code, status_code) in enumerate(cases):
            with self.subTest(label=label):
                self.publish(snapshot)
                token = self.open_mobile_session(snapshot, 80 + index)
                opened = self.client.get(f"/api/queue-mobile/sessions/{token}").get_json()
                response = self.client.post(
                    f"/api/queue-mobile/sessions/{token}/submit",
                    json={
                        "request_id": f"00000000-0000-0000-0000-00000000089{index}",
                        "profile_id": self.profile_id,
                        "expected_profile_revision": revision,
                        "also_queues_at_paired_machine": choice,
                    },
                )
                if label == "机台不属于一对机台":
                    self.assertIsNone(opened["session"]["paired_machine_id"])
                    self.assertIsNone(opened["session"]["paired_machine_name"])
                self.assertEqual(status_code, response.status_code, response.get_json())
                self.assertEqual(code, response.get_json().get("code"))
        self.assertEqual([], self.pending_command_payloads())

    def test_player_account_queue_reports_a_place_given_up_for_this_round(self):
        def account_registration(revision, **registration_fields):
            snapshot = self.paired_remote_snapshot(
                revision, with_registration=True, registration_queues_at_both=True
            )
            snapshot["machines"]["A"]["waiting_positions"][0]["registrations"][0].update(
                registration_fields
            )
            self.publish(snapshot)
            return self.client.get("/api/player-account/queue").get_json()[
                "registrations"
            ][0]

        self.publish(self.paired_remote_snapshot(70))
        binding = self.client.post(
            "/api/queue-terminal/player-bindings",
            json={"profile_id": self.profile_id},
            headers=self.headers,
        )
        completed = self.client.post(
            f"/api/player-account/bindings/{binding.get_json()['binding_token']}/complete",
            json={"password": "first-password"},
        )
        self.assertEqual(201, completed.status_code, completed.get_json())

        queueing_at_both = account_registration(71)
        gave_up_this_round = account_registration(
            72, skips_paired_machine_this_round=True
        )

        self.assertEqual("B", queueing_at_both["paired_machine_id"])
        self.assertIs(True, queueing_at_both["also_queues_at_paired_machine"])
        self.assertIs(False, queueing_at_both["skips_paired_machine_this_round"])
        self.assertIs(True, gave_up_this_round["also_queues_at_paired_machine"])
        self.assertIs(True, gave_up_this_round["skips_paired_machine_this_round"])
        self.assertIs(False, queueing_at_both["paired_arrangement_announced"])
        self.assertIsNone(queueing_at_both["paired_arrangement_not_before"])

        # A preview at machine B announces the move; the page is told from when on.
        announced = account_registration(
            73,
            paired_arrangement_announced=True,
            paired_arrangement_not_before=1_700_000_300_000,
        )
        held = account_registration(74, paired_arrangement_announced=True)
        self.assertIs(True, announced["paired_arrangement_announced"])
        self.assertEqual(1_700_000_300_000, announced["paired_arrangement_not_before"])
        self.assertIs(True, held["paired_arrangement_announced"])
        self.assertIsNone(held["paired_arrangement_not_before"])

    def test_player_account_profile_has_no_setting_for_paired_machines(self):
        self.publish(self.remote_ready_snapshot())
        binding = self.client.post(
            "/api/queue-terminal/player-bindings",
            json={"profile_id": self.profile_id},
            headers=self.headers,
        )
        completed = self.client.post(
            f"/api/player-account/bindings/{binding.get_json()['binding_token']}/complete",
            json={"password": "first-password"},
        )
        bound_profile = completed.get_json()["account"]["profile"]
        csrf_headers = {
            "X-CSRF-Token": self.client.get_cookie("maimai_q_session_csrf").value
        }

        # The account page of the removed setting sent this field.
        removed_field = self.client.patch(
            "/api/player-account/profile",
            json={
                "expected_profile_revision": bound_profile["profile_revision"],
                "paired_machine_queueing": "ON",
            },
            headers=csrf_headers,
        )

        self.assertEqual(201, completed.status_code, completed.get_json())
        self.assertNotIn("paired_machine_queueing", bound_profile)
        self.assertIs(False, bound_profile["paired_machine_queueing_introduced"])
        self.assertEqual(400, removed_field.status_code, removed_field.get_json())
        self.assertEqual("请求包含不支持的玩家资料字段。", removed_field.get_json()["error"])
        self.assertEqual(
            bound_profile["profile_revision"], self.terminal_profile()["profile_revision"]
        )

    def management_paired_snapshot(self, *, paired=True, queues_at_both=False):
        snapshot, terminal_headers = self.management_control_snapshot(with_registration=True)
        registration = snapshot["machines"]["A"]["waiting_positions"][0]["registrations"][0]
        if paired:
            snapshot["machines"]["A"]["paired_machine_id"] = "B"
            snapshot["machines"]["B"]["paired_machine_id"] = "A"
            registration["also_queues_at_paired_machine"] = queues_at_both
        response = self.client.post(
            "/api/queue-status", json=snapshot, headers=terminal_headers
        )
        self.assertEqual(204, response.status_code, response.get_json())
        return snapshot, terminal_headers

    def management_terminal_action_body(self, snapshot, request_number, **fields):
        machine = snapshot["machines"]["A"]
        return {
            "request_id": f"00000000-0000-0000-0000-0000000007{request_number}",
            "expected_queue_id": snapshot["queue_id"],
            "expected_queue_revision": snapshot["revision"],
            "expected_machine_configuration_revision": snapshot[
                "machine_configuration_revision"
            ],
            "machine_id": "A",
            "expected_machine_stable_id": machine["stable_id"],
            "expected_playing_registration_ids": [
                registration["registration_id"] for registration in machine["playing"]
            ],
            "expected_waiting_positions": [
                [registration["registration_id"] for registration in position["registrations"]]
                for position in machine["waiting_positions"]
            ],
            **fields,
        }

    def test_management_overview_and_action_cover_queueing_at_two_machines(self):
        snapshot, terminal_headers = self.management_paired_snapshot(queues_at_both=True)
        registration_id = snapshot["machines"]["A"]["waiting_positions"][0][
            "registrations"
        ][0]["registration_id"]

        overview = self.client.get(
            "/api/queue-management/overview", headers=self.management_headers
        ).get_json()
        machine_a = next(
            machine for machine in overview["queue"]["machines"] if machine["id"] == "A"
        )

        def act(request_number, **fields):
            return self.client.post(
                "/api/queue-management/terminal-actions",
                json=self.management_terminal_action_body(
                    snapshot,
                    request_number,
                    action="SET_PAIRED_MACHINE_QUEUEING",
                    registration_ids=[registration_id],
                    **fields,
                ),
                headers=self.management_headers,
            )

        missing = act(10)
        invalid = act(11, also_queues_at_paired_machine="no")
        created = act(12, also_queues_at_paired_machine=False)
        repeated = act(12, also_queues_at_paired_machine=False)
        changed = act(12, also_queues_at_paired_machine=True)
        misplaced = self.client.post(
            "/api/queue-management/terminal-actions",
            json=self.management_terminal_action_body(
                snapshot,
                13,
                action="CHECK_IN",
                registration_ids=[registration_id],
                also_queues_at_paired_machine=True,
            ),
            headers=self.management_headers,
        )
        commands = self.client.get(
            "/api/queue-terminal/commands", headers=terminal_headers
        ).get_json()["commands"]

        self.assertEqual("B", machine_a["paired_machine_id"])
        self.assertIs(
            True,
            machine_a["waiting_positions"][0]["registrations"][0][
                "also_queues_at_paired_machine"
            ],
        )
        self.assertEqual(400, missing.status_code)
        self.assertEqual("请选择是否允许系统在两台机台之间安排登记。", missing.get_json()["error"])
        self.assertEqual(400, invalid.status_code)
        self.assertEqual(202, created.status_code, created.get_json())
        self.assertEqual(200, repeated.status_code)
        self.assertEqual(409, changed.status_code)
        self.assertEqual(400, misplaced.status_code)
        self.assertIn("这项操作不接受", misplaced.get_json()["error"])
        self.assertEqual(1, len(commands))
        self.assertEqual("MANAGEMENT_QUEUE_ACTION", commands[0]["type"])
        self.assertEqual("SET_PAIRED_MACHINE_QUEUEING", commands[0]["payload"]["action"])
        self.assertIs(False, commands[0]["payload"]["also_queues_at_paired_machine"])
        self.assertEqual([registration_id], commands[0]["payload"]["registration_ids"])

    def test_management_overview_forwards_wait_estimate_uncertain_flag(self):
        snapshot, terminal_headers = self.management_control_snapshot(
            with_registration=True
        )
        position = snapshot["machines"]["A"]["waiting_positions"][0]
        position["estimated_wait_minutes"] = 5
        position["estimated_wait_uncertain"] = True
        response = self.client.post(
            "/api/queue-status", json=snapshot, headers=terminal_headers
        )
        self.assertEqual(204, response.status_code, response.get_json())

        overview = self.client.get(
            "/api/queue-management/overview", headers=self.management_headers
        ).get_json()
        machine_a = next(
            machine for machine in overview["queue"]["machines"] if machine["id"] == "A"
        )
        machine_b = next(
            machine for machine in overview["queue"]["machines"] if machine["id"] == "B"
        )

        self.assertEqual(5, machine_a["waiting_positions"][0]["estimated_wait_minutes"])
        self.assertIs(True, machine_a["waiting_positions"][0]["estimated_wait_uncertain"])
        self.assertEqual([], machine_b["waiting_positions"])

    def test_management_turning_the_tick_box_on_records_nothing(self):
        # Staff act for a player without showing that player anything.
        snapshot, _ = self.management_paired_snapshot()
        registration_id = snapshot["machines"]["A"]["waiting_positions"][0][
            "registrations"
        ][0]["registration_id"]

        switched = self.client.post(
            "/api/queue-management/terminal-actions",
            json=self.management_terminal_action_body(
                snapshot,
                30,
                action="SET_PAIRED_MACHINE_QUEUEING",
                registration_ids=[registration_id],
                also_queues_at_paired_machine=True,
            ),
            headers=self.management_headers,
        )
        connection = sqlite3.connect(self.database_path)
        try:
            profiles, shown = connection.execute(
                """
                SELECT COUNT(*), COALESCE(SUM(paired_machine_queueing_introduced), 0)
                FROM player_profile
                """
            ).fetchone()
        finally:
            connection.close()

        self.assertEqual(202, switched.status_code, switched.get_json())
        self.assertGreater(profiles, 0)
        self.assertEqual(0, shown)

    def test_management_overview_reports_a_place_given_up_for_this_round(self):
        snapshot, terminal_headers = self.management_control_snapshot(with_registration=True)
        snapshot["machines"]["A"]["paired_machine_id"] = "B"
        snapshot["machines"]["B"]["paired_machine_id"] = "A"
        registration = snapshot["machines"]["A"]["waiting_positions"][0]["registrations"][0]
        registration["also_queues_at_paired_machine"] = True
        registration["skips_paired_machine_this_round"] = True
        published = self.client.post(
            "/api/queue-status", json=snapshot, headers=terminal_headers
        )

        overview = self.client.get(
            "/api/queue-management/overview", headers=self.management_headers
        ).get_json()
        machine_a = next(
            machine for machine in overview["queue"]["machines"] if machine["id"] == "A"
        )
        reported = machine_a["waiting_positions"][0]["registrations"][0]

        self.assertEqual(204, published.status_code, published.get_json())
        self.assertIs(True, reported["also_queues_at_paired_machine"])
        self.assertIs(True, reported["skips_paired_machine_this_round"])
        self.assertIs(False, reported["paired_arrangement_announced"])
        self.assertIsNone(reported["paired_arrangement_not_before"])
        self.assertTrue(
            all(
                playing["skips_paired_machine_this_round"] is False
                for playing in machine_a["playing"]
            )
        )

    def test_management_overview_reports_an_announced_arrangement(self):
        snapshot, terminal_headers = self.management_control_snapshot(with_registration=True)
        snapshot["machines"]["A"]["paired_machine_id"] = "B"
        snapshot["machines"]["B"]["paired_machine_id"] = "A"
        registration = snapshot["machines"]["A"]["waiting_positions"][0]["registrations"][0]
        registration["also_queues_at_paired_machine"] = True
        registration["paired_arrangement_announced"] = True
        registration["paired_arrangement_not_before"] = 1_700_000_300_000
        published = self.client.post(
            "/api/queue-status", json=snapshot, headers=terminal_headers
        )

        overview = self.client.get(
            "/api/queue-management/overview", headers=self.management_headers
        ).get_json()
        machine_a = next(
            machine for machine in overview["queue"]["machines"] if machine["id"] == "A"
        )
        reported = machine_a["waiting_positions"][0]["registrations"][0]

        self.assertEqual(204, published.status_code, published.get_json())
        self.assertIs(True, reported["paired_arrangement_announced"])
        self.assertEqual(1_700_000_300_000, reported["paired_arrangement_not_before"])

    def test_management_cannot_switch_queueing_at_two_machines_without_a_pair(self):
        snapshot, terminal_headers = self.management_paired_snapshot(paired=False)
        registration_id = snapshot["machines"]["A"]["waiting_positions"][0][
            "registrations"
        ][0]["registration_id"]

        response = self.client.post(
            "/api/queue-management/terminal-actions",
            json=self.management_terminal_action_body(
                snapshot,
                20,
                action="SET_PAIRED_MACHINE_QUEUEING",
                registration_ids=[registration_id],
                also_queues_at_paired_machine=True,
            ),
            headers=self.management_headers,
        )
        commands = self.client.get(
            "/api/queue-terminal/commands", headers=terminal_headers
        ).get_json()["commands"]

        self.assertEqual(409, response.status_code, response.get_json())
        self.assertEqual("系统规则不允许自动在两台机台之间安排登记。", response.get_json()["error"])
        self.assertEqual([], commands)

    def management_pair_settings_snapshot(self, *, enabled=True, reports_setting=True):
        """A bound terminal whose machines A and B are configured as a pair."""
        snapshot, terminal_headers = self.management_control_snapshot()
        stable_a = snapshot["machines"]["A"]["stable_id"]
        stable_b = snapshot["machines"]["B"]["stable_id"]
        if reports_setting:
            snapshot["paired_machine_queue_settings"] = {
                "enabled": enabled,
                "pairs": [[stable_a, stable_b]],
            }
        if enabled and reports_setting:
            snapshot["machines"]["A"]["paired_machine_id"] = "B"
            snapshot["machines"]["B"]["paired_machine_id"] = "A"
        return snapshot, terminal_headers

    def test_terminal_reports_the_pair_setting_to_the_management_overview(self):
        snapshot, terminal_headers = self.management_pair_settings_snapshot()
        published = self.client.post(
            "/api/queue-status", json=snapshot, headers=terminal_headers
        )
        overview = self.client.get(
            "/api/queue-management/overview", headers=self.management_headers
        ).get_json()
        public = self.client.get("/api/queue-status").get_json()

        self.assertEqual(204, published.status_code, published.get_json())
        self.assertEqual(
            snapshot["paired_machine_queue_settings"],
            overview["queue"]["terminal_settings"]["paired_machine_queue_settings"],
        )
        self.assertIs(True, overview["capabilities"]["PAIRED_MACHINE_QUEUE_EDIT"])
        self.assertEqual(
            snapshot["paired_machine_queue_settings"],
            public["paired_machine_queue_settings"],
        )

    def test_terminal_without_the_pair_setting_does_not_offer_it_to_management(self):
        snapshot, terminal_headers = self.management_pair_settings_snapshot(
            reports_setting=False
        )
        self.client.post("/api/queue-status", json=snapshot, headers=terminal_headers)
        overview = self.client.get(
            "/api/queue-management/overview", headers=self.management_headers
        ).get_json()
        stable_a = snapshot["machines"]["A"]["stable_id"]
        stable_b = snapshot["machines"]["B"]["stable_id"]

        rejected = self.client.patch(
            "/api/queue-management/terminal-settings",
            json={
                **self.management_terminal_settings_body(
                    snapshot, "00000000-0000-0000-0000-000000000760"
                ),
                "paired_machine_queue_settings": {
                    "enabled": True,
                    "pairs": [[stable_a, stable_b]],
                },
            },
            headers=self.management_headers,
        )

        self.assertIsNone(overview["queue"]["terminal_settings"]["paired_machine_queue_settings"])
        self.assertIs(False, overview["capabilities"]["PAIRED_MACHINE_QUEUE_EDIT"])
        self.assertEqual(409, rejected.status_code, rejected.get_json())
        self.assertEqual(
            "当前现场终端不支持远程修改一对机台的设置。", rejected.get_json()["error"]
        )

    def test_rejects_a_reported_pair_setting_that_does_not_match_the_machines(self):
        def snapshot_with(change):
            snapshot, terminal_headers = self.management_pair_settings_snapshot()
            change(snapshot)
            return snapshot, terminal_headers

        stable = "f" * 32
        cases = (
            ("设置说成对而机台没有", lambda s: s["machines"]["A"].update(paired_machine_id=None)
                or s["machines"]["B"].update(paired_machine_id=None), "与一对机台的设置不一致"),
            ("设置关闭而机台仍成对", lambda s: s["paired_machine_queue_settings"].update(enabled=False),
                "与一对机台的设置不一致"),
            ("不是对象", lambda s: s.update(paired_machine_queue_settings=[]), "必须是对象"),
            ("缺少字段", lambda s: s["paired_machine_queue_settings"].pop("pairs"),
                "必须正好包含 enabled 和 pairs"),
            ("开关不是布尔值", lambda s: s["paired_machine_queue_settings"].update(enabled=1),
                "enabled 必须是布尔值。"),
            ("pairs 不是数组", lambda s: s["paired_machine_queue_settings"].update(pairs={}),
                "一对机台的设置无效。"),
            ("一对不是两台", lambda s: s["paired_machine_queue_settings"].update(pairs=[[stable]]),
                "必须是两个机台内部标识"),
            ("标识不是字符串", lambda s: s["paired_machine_queue_settings"].update(pairs=[[1, 2]]),
                "必须是两个机台内部标识"),
            ("引用不存在的机台", lambda s: s["paired_machine_queue_settings"].update(
                pairs=[[s["machines"]["A"]["stable_id"], stable]]), "引用了不存在的机台"),
            ("同一台机台两次", lambda s: s["paired_machine_queue_settings"].update(
                pairs=[[s["machines"]["A"]["stable_id"]] * 2]), "最多属于一对"),
        )
        for label, change, expected_error in cases:
            with self.subTest(label=label):
                snapshot, terminal_headers = snapshot_with(change)
                response = self.client.post(
                    "/api/queue-status", json=snapshot, headers=terminal_headers
                )
                self.assertEqual(400, response.status_code, label)
                self.assertIn(expected_error, response.get_json()["error"])

    def test_management_settings_command_carries_the_pair_setting(self):
        snapshot, terminal_headers = self.management_pair_settings_snapshot(enabled=False)
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=terminal_headers
            ).status_code,
        )
        stable_a = snapshot["machines"]["A"]["stable_id"]
        stable_b = snapshot["machines"]["B"]["stable_id"]
        desired = {"enabled": True, "pairs": [[stable_a, stable_b]]}

        def submit(request_number, settings):
            body = self.management_terminal_settings_body(
                snapshot, f"00000000-0000-0000-0000-0000000007{request_number}"
            )
            if settings is not None:
                body["paired_machine_queue_settings"] = settings
            return self.client.patch(
                "/api/queue-management/terminal-settings",
                json=body,
                headers=self.management_headers,
            )

        invalid = [
            submit(70, {"enabled": True, "pairs": [[stable_a, "e" * 32]]}),
            submit(71, {"enabled": True, "pairs": [[stable_a, stable_a]]}),
            submit(72, {"enabled": "yes", "pairs": []}),
            submit(73, {"enabled": True}),
            submit(74, "on"),
        ]
        created = submit(75, desired)
        commands = self.client.get(
            "/api/queue-terminal/commands", headers=terminal_headers
        ).get_json()["commands"]

        for response in invalid:
            self.assertEqual(400, response.status_code, response.get_json())
        self.assertEqual(202, created.status_code, created.get_json())
        self.assertEqual(1, len(commands))
        self.assertEqual("UPDATE_TERMINAL_SETTINGS", commands[0]["type"])
        self.assertEqual(desired, commands[0]["payload"]["paired_machine_queue_settings"])

    def test_management_settings_command_without_the_pair_setting_leaves_it_alone(self):
        snapshot, terminal_headers = self.management_pair_settings_snapshot()
        self.client.post("/api/queue-status", json=snapshot, headers=terminal_headers)

        created = self.client.patch(
            "/api/queue-management/terminal-settings",
            json=self.management_terminal_settings_body(
                snapshot, "00000000-0000-0000-0000-000000000776"
            ),
            headers=self.management_headers,
        )
        payload = self.client.get(
            "/api/queue-terminal/commands", headers=terminal_headers
        ).get_json()["commands"][0]["payload"]

        self.assertEqual(202, created.status_code, created.get_json())
        # The terminal keeps its own setting when the command does not mention it.
        self.assertNotIn("paired_machine_queue_settings", payload)

    def test_management_cannot_pair_machines_of_different_capacity(self):
        snapshot, terminal_headers = self.management_pair_settings_snapshot(enabled=False)
        snapshot["paired_machine_queue_settings"]["pairs"] = []
        snapshot["registration_open"] = False
        snapshot["registration_control_open"] = False
        self.client.post("/api/queue-status", json=snapshot, headers=terminal_headers)
        body = self.management_terminal_settings_body(
            snapshot, "00000000-0000-0000-0000-000000000777"
        )
        # The same command changes machine B to single play and asks to pair it with A.
        body["machines"]["B"]["configuration"]["capacity"] = 1
        body["paired_machine_queue_settings"] = {
            "enabled": True,
            "pairs": [
                [snapshot["machines"]["A"]["stable_id"], snapshot["machines"]["B"]["stable_id"]]
            ],
        }

        response = self.client.patch(
            "/api/queue-management/terminal-settings",
            json=body,
            headers=self.management_headers,
        )

        self.assertEqual(400, response.status_code, response.get_json())
        self.assertEqual("一对机台的游玩容量必须相同。", response.get_json()["error"])

    def test_contact_of_a_deleted_profile_is_dropped_without_failing_the_upload(self):
        def snapshot_with_public_contact(revision):
            snapshot = self.snapshot(revision=revision)
            snapshot["schema_version"] = 5
            profile = self.player_profile()
            profile["qq_visibility"] = "PUBLIC_WEBSITE"
            snapshot["private_player_profiles"] = [profile]
            snapshot["private_player_contacts"] = [
                {
                    "registration_id": snapshot["machines"]["A"]["playing"][0][
                        "registration_id"
                    ],
                    "profile_id": self.profile_id,
                    "qq_number": "12345678",
                }
            ]
            return snapshot

        first = self.client.post(
            "/api/queue-status", json=snapshot_with_public_contact(70), headers=self.headers
        )
        before = self.client.get("/api/queue-status").get_json()
        # The server learns that the profile was deleted, while a terminal that still holds
        # it keeps uploading the profile's contact.
        connection = sqlite3.connect(self.database_path)
        try:
            scopes = [
                row[0]
                for row in connection.execute(
                    "SELECT DISTINCT device_id FROM player_profile WHERE profile_id = ?",
                    (self.profile_id,),
                )
            ]
            for scope in scopes:
                connection.execute(
                    """
                    INSERT INTO deleted_player_profile
                        (profile_scope_id, profile_id, deleted_at, command_id)
                    VALUES (?, ?, ?, ?)
                    """,
                    (scope, self.profile_id, 1_000, "00000000-0000-0000-0000-000000000700"),
                )
            connection.commit()
        finally:
            connection.close()
        second = self.client.post(
            "/api/queue-status", json=snapshot_with_public_contact(71), headers=self.headers
        )
        after = self.client.get("/api/queue-status").get_json()
        connection = sqlite3.connect(self.database_path)
        try:
            stored_contacts = connection.execute(
                "SELECT COUNT(*) FROM queue_private_contact WHERE player_id = ?",
                (self.profile_id,),
            ).fetchone()[0]
        finally:
            connection.close()

        self.assertEqual(204, first.status_code, first.get_json())
        self.assertEqual(1, len(scopes))
        self.assertEqual("12345678", before["machines"]["A"]["playing"][0]["qq_number"])
        # The upload used to fail on every attempt for as long as the contact was sent.
        self.assertEqual(204, second.status_code, second.get_json())
        self.assertEqual(71, after["revision"])
        # Neither the contact nor the deleted profile's public QQ comes back.
        self.assertIsNone(after["machines"]["A"]["playing"][0]["qq_number"])
        self.assertEqual(0, stored_contacts)

    def test_identifiers_that_are_not_strings_are_rejected_as_invalid(self):
        self.publish(self.remote_ready_snapshot())
        for label, value in (("数字", 5), ("数组", ["x"]), ("对象", {"id": "x"}), ("布尔值", True)):
            with self.subTest(label=label, endpoint="player-bindings"):
                binding = self.client.post(
                    "/api/queue-terminal/player-bindings",
                    json={"profile_id": value},
                    headers=self.headers,
                )
                self.assertEqual(400, binding.status_code)
                self.assertEqual("玩家资料编号无效。", binding.get_json()["error"])
            with self.subTest(label=label, endpoint="installation"):
                installation = self.client.post(
                    "/api/queue-terminal/installation",
                    json={"venue_id": value, "venue_name": "机厅", "terminal_name": "终端"},
                    headers=self.headers,
                )
                self.assertEqual(400, installation.status_code)
                self.assertEqual("机厅 ID 无效。", installation.get_json()["error"])
            with self.subTest(label=label, endpoint="queue-status"):
                snapshot, terminal_headers = self.management_schema_eight_snapshot()
                snapshot["revision"] = 80
                snapshot["venue"] = {"id": value}
                upload = self.client.post(
                    "/api/queue-status", json=snapshot, headers=terminal_headers
                )
                self.assertEqual(409, upload.status_code)
                self.assertEqual("VENUE_ID_REQUIRED", upload.get_json()["code"])

    def test_values_that_cannot_be_looked_up_are_rejected_as_invalid(self):
        # A list or an object in place of a text value used to end in an unhandled error.
        event_snapshot = self.snapshot(revision=81)
        event_snapshot["recent_events"] = [
            {**self.event("00000000-0000-0000-0000-000000000781", "QUEUE_UPDATED", 990_000),
             "machine_id": ["A"]}
        ]
        event_upload = self.client.post(
            "/api/queue-status", json=event_snapshot, headers=self.headers
        )

        ask_snapshot = self.remote_ready_snapshot(revision=82, default_preference="ASK_EVERY_TIME")
        self.publish(ask_snapshot)
        token = self.open_mobile_session(ask_snapshot, 95)
        mobile = self.client.post(
            f"/api/queue-mobile/sessions/{token}/submit",
            json={
                "request_id": "00000000-0000-0000-0000-000000000896",
                "profile_id": self.profile_id,
                "expected_profile_revision": 3,
                "preference": ["SOLO"],
            },
        )
        join = self.client.post(
            "/api/queue-online/join",
            json={
                "request_id": "00000000-0000-0000-0000-000000000897",
                "qq": "12345678",
                "machine_id": "A",
                "preference": {"value": "SOLO"},
            },
        )

        self.assertEqual(400, event_upload.status_code)
        self.assertEqual("invalid_recent_event_machine_id", event_upload.get_json()["code"])
        self.assertEqual(400, mobile.status_code)
        self.assertEqual("请选择本次游玩偏好。", mobile.get_json()["error"])
        self.assertEqual(400, join.status_code)
        self.assertEqual("preference 选项无效。", join.get_json()["error"])

    def test_rejects_paired_machines_of_different_capacity(self):
        snapshot = self.paired_snapshot(revision=43)
        snapshot["machines"]["A"]["waiting_positions"][0]["registrations"][0][
            "also_queues_at_paired_machine"
        ] = False
        snapshot["machines"]["B"]["paired_waiting_positions"] = None
        self.upgrade_snapshot_to_schema_v6(snapshot, capacities={"A": 2, "B": 1})

        response = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )

        self.assertEqual(400, response.status_code)
        self.assertIn("游玩容量不同，不能设为一对机台", response.get_json()["error"])

    def test_rejects_paired_preview_positions_that_exceed_single_capacity(self):
        snapshot = self.paired_snapshot(revision=44)
        own = self.registration("f" * 24, "机台乙等待玩家")
        machine_b = snapshot["machines"]["B"]
        machine_b["waiting_positions"] = [
            {
                "index": 1,
                "position_id": "1" * 24,
                "fixed_pair": False,
                "estimated_wait_minutes": 6,
                "registrations": [own],
                "common_play_preview": None,
            }
        ]
        machine_b["registration_count"] = 2
        machine_b["waiting_position_count"] = 1
        machine_b["paired_waiting_positions"][0]["registrations"].insert(
            0, {"registration_id": "f" * 24, "paired_place_preview": False}
        )
        self.upgrade_snapshot_to_schema_v6(snapshot, capacities={"A": 1, "B": 1})

        response = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )

        self.assertEqual(400, response.status_code)
        self.assertIn("每个位置只能包含 1 个登记", response.get_json()["error"])

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
        second["recent_events"][0]["machine_stable_id"] = "2" * 32
        second["recent_events"][0]["machine_name"] = "二楼 · 机台 A"

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
        self.assertEqual("2" * 32, first_page["logs"][0]["machine_stable_id"])
        self.assertEqual("二楼·机台 A", first_page["logs"][0]["machine_name"])
        self.assertEqual("NO_SHOW_MOVED_TO_TAIL", second_page["logs"][0]["type"])
        self.assertEqual("ON_SITE_TERMINAL", second_page["logs"][0]["operation_source"])
        self.assertIsNone(second_page["logs"][0]["machine_stable_id"])
        self.assertIsNone(second_page["logs"][0]["machine_name"])
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

    def test_system_event_rejects_machine_identity_with_exact_event_context(self):
        snapshot = self.snapshot()
        event = self.event(
            "00000000-0000-0000-0000-000000000104",
            "REGISTRATION_CLOSED",
            1_000_400,
        )
        event["machine_id"] = None
        event["machine_stable_id"] = "2" * 32
        event["machine_name"] = "二楼 · 机台 A"
        snapshot["recent_events"] = [event]

        response = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )

        self.assertEqual(400, response.status_code)
        body = response.get_json()
        self.assertEqual("system_event_has_machine_identity", body["code"])
        self.assertEqual("recent_events[0].machine_stable_id", body["field"])
        self.assertEqual(1, body["details"]["event_number"])
        self.assertEqual(event["event_id"], body["details"]["event_id"])
        self.assertIn("最近事件第 1 条《机台 A · 队列已更新》", body["error"])
        self.assertIn("系统事件不应关联单一机台", body["error"])
        self.assertIn("字段：recent_events[0].machine_stable_id", body["error"])
        self.assertIn(f"事件编号：{event['event_id']}", body["error"])

    def test_legacy_literal_null_system_machine_name_is_repaired(self):
        snapshot = self.snapshot()
        event = self.event(
            "00000000-0000-0000-0000-000000000106",
            "REGISTRATION_CLOSED",
            1_000_600,
        )
        event["machine_id"] = None
        event["machine_stable_id"] = None
        event["machine_name"] = "null"
        event["title"] = "关闭登记排队"
        snapshot["recent_events"] = [event]

        response = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )

        self.assertEqual(204, response.status_code)
        stored_event = self.client.get("/api/queue-logs").get_json()["logs"][0]
        self.assertIsNone(stored_event["machine_id"])
        self.assertIsNone(stored_event["machine_stable_id"])
        self.assertIsNone(stored_event["machine_name"])

    def test_other_system_machine_names_remain_invalid_and_identify_name_field(self):
        snapshot = self.snapshot()
        event = self.event(
            "00000000-0000-0000-0000-000000000107",
            "REGISTRATION_CLOSED",
            1_000_700,
        )
        event["machine_id"] = None
        event["machine_name"] = "入口侧 · 机台 A"
        snapshot["recent_events"] = [event]

        response = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )

        self.assertEqual(400, response.status_code)
        body = response.get_json()
        self.assertEqual("system_event_has_machine_identity", body["code"])
        self.assertEqual("recent_events[0].machine_name", body["field"])
        self.assertIn("机台名称无效", body["error"])

    def test_invalid_second_event_reports_its_own_title_id_and_nested_field(self):
        snapshot = self.snapshot()
        first = self.event(
            "00000000-0000-0000-0000-000000000108",
            "REGISTRATION_UPDATED",
            1_000_800,
        )
        second = self.event(
            "00000000-0000-0000-0000-000000000109",
            "REGISTRATION_UPDATED",
            1_000_900,
        )
        second["title"] = "机台 A · 移除登记"
        second["registration_ids"] = ["a" * 24, "invalid"]
        snapshot["recent_events"] = [first, second]

        response = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )

        self.assertEqual(400, response.status_code)
        body = response.get_json()
        self.assertEqual("invalid_recent_event_registration_id", body["code"])
        self.assertEqual("recent_events[1].registration_ids[1]", body["field"])
        self.assertEqual(2, body["details"]["event_number"])
        self.assertEqual(second["event_id"], body["details"]["event_id"])
        self.assertEqual(second["title"], body["details"]["event_title"])
        self.assertIn("最近事件第 2 条《机台 A · 移除登记》", body["error"])

    def test_republished_event_backfills_machine_identity_without_reinserting_it(self):
        event_id = "00000000-0000-0000-0000-000000000105"
        first = self.snapshot(revision=4)
        first["schema_version"] = 2
        first["recent_events"] = [
            self.event(event_id, "REGISTRATION_UPDATED", 1_000_500)
        ]
        second = copy.deepcopy(first)
        second["revision"] = 5
        second["recent_events"][0]["machine_stable_id"] = "3" * 32
        second["recent_events"][0]["machine_name"] = "入口侧 · 机台 A"

        self.assertEqual(
            204,
            self.client.post("/api/queue-status", json=first, headers=self.headers).status_code,
        )
        self.assertEqual(
            204,
            self.client.post("/api/queue-status", json=second, headers=self.headers).status_code,
        )
        logs = self.client.get("/api/queue-logs").get_json()["logs"]

        self.assertEqual(1, len(logs))
        self.assertEqual(event_id, logs[0]["event_id"])
        self.assertEqual("3" * 32, logs[0]["machine_stable_id"])
        self.assertEqual("入口侧·机台 A", logs[0]["machine_name"])

        connection = sqlite3.connect(self.database_path)
        try:
            event_count = connection.execute(
                "SELECT COUNT(*) FROM queue_event WHERE event_id = ?", (event_id,)
            ).fetchone()[0]
        finally:
            connection.close()
        self.assertEqual(1, event_count)

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
        self.assertEqual(8, public_response.get_json()["schema_version"])
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
        self.assertEqual("左侧·机台 A", player["machine_name"])
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
        self.assertEqual(800_000, player["created_at"])
        self.assertEqual(800_000, player["online_check_in_started_at"])
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
                        "estimated_wait_uncertain": True,
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
        self.assertIs(
            False, public_machine["waiting_positions"][0]["estimated_wait_uncertain"]
        )
        self.assertFalse(bot_player["machine_operational"])
        self.assertIsNone(bot_player["playing_started_at"])
        self.assertIsNone(bot_player["estimated_wait_minutes"])
        self.assertIs(False, bot_player["estimated_wait_uncertain"])

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
                response.get_json()["error"] == "QQ Bot 联动已关闭。"
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
            ("REJECTED", "BOT_DISABLED", "QQ Bot 联动已关闭，这次操作没有执行。"),
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

    def test_bot_profile_update_is_rejected_immediately_while_terminal_is_offline(self):
        snapshot = self.snapshot(revision=4)
        snapshot["schema_version"] = 3
        snapshot["private_player_profiles"] = [self.player_profile()]
        snapshot["private_player_contacts"] = []
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

        response = self.client.patch(
            f"/api/queue-bot/profiles/{self.profile_id}",
            json={
                "request_id": "00000000-0000-0000-0000-000000000786",
                "actor_qq": "12345678",
                "nickname": "不应排队等待的修改",
            },
            headers=self.bot_headers,
        )
        connection = sqlite3.connect(self.database_path)
        try:
            command_count = connection.execute(
                "SELECT COUNT(*) FROM terminal_command"
            ).fetchone()[0]
        finally:
            connection.close()

        self.assertEqual(503, response.status_code)
        self.assertEqual(
            "现场终端暂时离线，暂不能修改玩家资料。",
            response.get_json()["error"],
        )
        self.assertEqual(0, command_count)

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
        self.client.get("/api/queue-terminal/commands", headers=self.headers)

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

    def test_terminal_instance_claim_lease_returns_a_command_only_once(self):
        instance_headers = {
            **self.headers,
            "X-Terminal-Instance-ID": "00000000-0000-0000-0000-000000000811",
            "X-Terminal-Instance-Generation": "1",
        }
        snapshot = self.snapshot(revision=4)
        snapshot["schema_version"] = 3
        snapshot["private_player_profiles"] = [self.player_profile()]
        snapshot["private_player_contacts"] = []
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=instance_headers
            ).status_code,
        )
        command_id = "00000000-0000-0000-0000-000000000812"
        self.client.patch(
            f"/api/queue-bot/profiles/{self.profile_id}",
            json={
                "request_id": command_id,
                "actor_qq": "12345678",
                "nickname": "租约测试",
            },
            headers=self.bot_headers,
        )

        first = self.client.get(
            "/api/queue-terminal/commands", headers=instance_headers
        ).get_json()["commands"]
        repeated = self.client.get(
            "/api/queue-terminal/commands", headers=instance_headers
        ).get_json()["commands"]

        self.assertEqual([command_id], [item["command_id"] for item in first])
        self.assertEqual(instance_headers["X-Terminal-Instance-ID"], first[0]["claimed_instance"])
        self.assertEqual([], repeated)

        connection = sqlite3.connect(self.database_path)
        try:
            connection.execute(
                "UPDATE terminal_command SET claimed_at = ? WHERE command_id = ?",
                (int(time.time()) - 20, command_id),
            )
            connection.commit()
        finally:
            connection.close()

        reclaimed = self.client.get(
            "/api/queue-terminal/commands", headers=instance_headers
        ).get_json()["commands"]
        self.assertEqual([command_id], [item["command_id"] for item in reclaimed])

    def test_newer_runtime_instance_excludes_the_previous_instance(self):
        old_headers = {
            **self.headers,
            "X-Terminal-Instance-ID": "00000000-0000-0000-0000-000000000821",
            "X-Terminal-Instance-Generation": "1",
        }
        new_headers = {
            **self.headers,
            "X-Terminal-Instance-ID": "00000000-0000-0000-0000-000000000822",
            "X-Terminal-Instance-Generation": "2",
        }
        snapshot = self.snapshot(revision=4)
        snapshot["schema_version"] = 3
        snapshot["private_player_profiles"] = [self.player_profile()]
        snapshot["private_player_contacts"] = []
        self.client.post("/api/queue-status", json=snapshot, headers=old_headers)
        command_id = "00000000-0000-0000-0000-000000000823"
        self.client.patch(
            f"/api/queue-bot/profiles/{self.profile_id}",
            json={
                "request_id": command_id,
                "actor_qq": "12345678",
                "nickname": "实例接管",
            },
            headers=self.bot_headers,
        )
        self.assertEqual(
            [command_id],
            [
                item["command_id"]
                for item in self.client.get(
                    "/api/queue-terminal/commands", headers=old_headers
                ).get_json()["commands"]
            ],
        )

        replacement_snapshot = copy.deepcopy(snapshot)
        replacement_snapshot["revision"] = 5
        replaced = self.client.post(
            "/api/queue-status", json=replacement_snapshot, headers=new_headers
        )
        stale_publish = self.client.post(
            "/api/queue-status",
            json={**replacement_snapshot, "revision": 6},
            headers=old_headers,
        )
        stale_fetch = self.client.get(
            "/api/queue-terminal/commands", headers=old_headers
        )
        stale_result = self.client.post(
            f"/api/queue-terminal/commands/{command_id}/result",
            json={"status": "APPLIED", "detail": "旧实例不应成功"},
            headers=old_headers,
        )
        new_commands = self.client.get(
            "/api/queue-terminal/commands", headers=new_headers
        ).get_json()["commands"]
        completed = self.client.post(
            f"/api/queue-terminal/commands/{command_id}/result",
            json={
                "status": "APPLIED",
                "detail": "新实例已处理",
                "result_registration_id": "c" * 24,
            },
            headers=new_headers,
        )

        self.assertEqual(204, replaced.status_code)
        self.assertEqual(409, stale_publish.status_code)
        self.assertEqual("STALE_TERMINAL_INSTANCE", stale_publish.get_json()["code"])
        self.assertEqual(409, stale_fetch.status_code)
        self.assertEqual(409, stale_result.status_code)
        self.assertEqual([command_id], [item["command_id"] for item in new_commands])
        self.assertEqual("APPLIED", completed.get_json()["status"])
        self.assertEqual("c" * 24, completed.get_json()["result_registration_id"])

    def test_new_runtime_instance_rejects_queue_command_for_reassigned_machine(self):
        old_headers = {
            **self.headers,
            "X-Terminal-Instance-ID": "00000000-0000-0000-0000-000000000831",
            "X-Terminal-Instance-Generation": "1",
        }
        new_headers = {
            **self.headers,
            "X-Terminal-Instance-ID": "00000000-0000-0000-0000-000000000832",
            "X-Terminal-Instance-Generation": "2",
        }
        snapshot = self.remote_ready_snapshot(revision=4)
        self.upgrade_snapshot_to_schema_v7(snapshot)
        snapshot["machine_configuration_revision"] = 9
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=old_headers
            ).status_code,
        )
        command_id = "00000000-0000-0000-0000-000000000833"
        created = self.client.post(
            "/api/queue-bot/queue-commands",
            json={
                "request_id": command_id,
                "actor_qq": "12345678",
                "operation": "JOIN_QUEUE",
                "machine_id": "B",
                "expected_queue_id": snapshot["queue_id"],
                "expected_machine_configuration_revision": 9,
            },
            headers=self.bot_headers,
        )
        self.assertEqual(202, created.status_code)

        replacement = copy.deepcopy(snapshot)
        replacement["revision"] += 1
        replacement["machines"]["B"]["stable_id"] = "f" * 32
        published = self.client.post(
            "/api/queue-status", json=replacement, headers=new_headers
        )
        command = self.client.get(
            f"/api/queue-bot/commands/{command_id}", headers=self.bot_headers
        ).get_json()
        pending = self.client.get(
            "/api/queue-terminal/commands", headers=new_headers
        ).get_json()["commands"]

        self.assertEqual(204, published.status_code)
        self.assertEqual("REJECTED", command["status"])
        self.assertEqual("SERVER_MIGRATION", command["result_source"])
        self.assertIn("原队列或机台配置发生变化", command["result_detail"])
        self.assertNotIn(command_id, [item["command_id"] for item in pending])

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
        )

        self.assertEqual(409, old_terminal_result.status_code)
        self.assertEqual(
            "STALE_TERMINAL_INSTANCE", old_terminal_result.get_json()["code"]
        )

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
        )
        new_commands = self.client.get(
            "/api/queue-terminal/commands", headers=terminal_two_headers
        ).get_json()["commands"]

        self.assertEqual(204, takeover.status_code)
        self.assertEqual(409, old_commands.status_code)
        self.assertEqual("STALE_TERMINAL_INSTANCE", old_commands.get_json()["code"])
        self.assertEqual([command_id], [item["command_id"] for item in new_commands])

    def test_terminal_takeover_rejects_queue_commands_for_changed_machine_identity(self):
        self.app.config["ALLOWED_DEVICE_ID"] = ""
        self.app.config["PRIMARY_DEVICE_ID"] = "terminal-2"
        snapshot = self.remote_ready_snapshot(revision=4)
        self.upgrade_snapshot_to_schema_v7(snapshot)
        snapshot["machine_configuration_revision"] = 9
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )
        command_id = "00000000-0000-0000-0000-000000000782"
        created = self.client.post(
            "/api/queue-bot/queue-commands",
            json={
                "request_id": command_id,
                "actor_qq": "12345678",
                "operation": "JOIN_QUEUE",
                "machine_id": "B",
                "expected_queue_id": snapshot["queue_id"],
                "expected_machine_configuration_revision": 9,
            },
            headers=self.bot_headers,
        )
        self.assertEqual(202, created.status_code)

        takeover_snapshot = copy.deepcopy(snapshot)
        takeover_snapshot["revision"] += 1
        takeover_snapshot["machines"]["B"]["stable_id"] = "f" * 32
        terminal_two_headers = {**self.headers, "X-Device-ID": "terminal-2"}
        takeover = self.client.post(
            "/api/queue-status",
            json=takeover_snapshot,
            headers=terminal_two_headers,
        )
        command = self.client.get(
            f"/api/queue-bot/commands/{command_id}", headers=self.bot_headers
        ).get_json()
        new_commands = self.client.get(
            "/api/queue-terminal/commands", headers=terminal_two_headers
        ).get_json()["commands"]

        self.assertEqual(204, takeover.status_code)
        self.assertEqual("REJECTED", command["status"])
        self.assertEqual("SERVER_MIGRATION", command["result_source"])
        self.assertIn("原队列或机台配置发生变化", command["result_detail"])
        self.assertNotIn(command_id, [item["command_id"] for item in new_commands])

    def test_terminal_takeover_keeps_queue_commands_for_same_machine_identity(self):
        self.app.config["ALLOWED_DEVICE_ID"] = ""
        self.app.config["PRIMARY_DEVICE_ID"] = "terminal-2"
        snapshot = self.remote_ready_snapshot(revision=4)
        self.upgrade_snapshot_to_schema_v7(snapshot)
        snapshot["machine_configuration_revision"] = 9
        self.client.post("/api/queue-status", json=snapshot, headers=self.headers)
        command_id = "00000000-0000-0000-0000-000000000781"
        created = self.client.post(
            "/api/queue-bot/queue-commands",
            json={
                "request_id": command_id,
                "actor_qq": "12345678",
                "operation": "JOIN_QUEUE",
                "machine_id": "B",
                "expected_queue_id": snapshot["queue_id"],
                "expected_machine_configuration_revision": 9,
            },
            headers=self.bot_headers,
        )
        self.assertEqual(202, created.status_code)

        takeover_snapshot = copy.deepcopy(snapshot)
        takeover_snapshot["revision"] += 1
        terminal_two_headers = {**self.headers, "X-Device-ID": "terminal-2"}
        takeover = self.client.post(
            "/api/queue-status",
            json=takeover_snapshot,
            headers=terminal_two_headers,
        )
        new_commands = self.client.get(
            "/api/queue-terminal/commands", headers=terminal_two_headers
        ).get_json()["commands"]

        self.assertEqual(204, takeover.status_code)
        self.assertIn(command_id, [item["command_id"] for item in new_commands])

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

    def test_join_and_leave_between_snapshots_still_route_both_events(self):
        snapshot = self.snapshot(revision=4)
        snapshot["schema_version"] = 5
        registration = snapshot["machines"]["A"]["playing"][0]
        snapshot["machines"]["A"] = self.machine(name="左侧 · 机台 A")
        snapshot["private_player_profiles"] = [self.player_profile()]
        snapshot["private_player_contacts"] = [
            {
                "registration_id": registration["registration_id"],
                "profile_id": self.profile_id,
                "qq_number": "12345678",
            }
        ]
        snapshot["recent_events"] = [
            self.event(
                "00000000-0000-0000-0000-000000000194",
                "ONLINE_REGISTRATION_ADDED",
                1_000_100,
            ),
            self.event(
                "00000000-0000-0000-0000-000000000195",
                "REGISTRATION_REMOVED",
                1_000_200,
            ),
        ]

        response = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )
        players = self.client.get(
            "/api/queue-bot/players", headers=self.bot_headers
        ).get_json()["players"]
        events = self.client.post(
            "/api/queue-bot/events",
            json={"qq": "12345678", "after": 0},
            headers=self.bot_headers,
        ).get_json()["events"]

        self.assertEqual(204, response.status_code)
        self.assertEqual([], players)
        self.assertEqual(
            ["ONLINE_REGISTRATION_ADDED", "REGISTRATION_REMOVED"],
            [event["type"] for event in events],
        )
        self.assertTrue(all(event["affected_players"] for event in events))

    def test_composite_event_uses_every_related_notification_category(self):
        snapshot = self.snapshot(revision=4)
        snapshot["schema_version"] = 5
        registration = snapshot["machines"]["A"]["playing"][0]
        profile = self.player_profile()
        profile.update(
            notify_queue_changes=False,
            notify_playing_position=True,
            notify_online_check_in=False,
            notify_absence=False,
            notify_machine_status=False,
        )
        event = self.event(
            "00000000-0000-0000-0000-000000000196",
            "ONLINE_CHECK_IN_MISSED",
            1_000_100,
        )
        event["notification_categories"] = [
            "ONLINE_CHECK_IN",
            "PLAYING_POSITION",
            "QUEUE_CHANGES",
        ]
        snapshot["private_player_profiles"] = [profile]
        snapshot["private_player_contacts"] = [{
            "registration_id": registration["registration_id"],
            "profile_id": self.profile_id,
            "qq_number": "12345678",
        }]
        snapshot["recent_events"] = [event]

        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )
        events = self.client.post(
            "/api/queue-bot/events",
            json={"qq": "12345678", "after": 0},
            headers=self.bot_headers,
        ).get_json()["events"]

        self.assertEqual(1, len(events))
        self.assertEqual(event["notification_categories"], events[0]["notification_categories"])

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
            },
            {
                "registration_id": first_registration["registration_id"],
                "profile_id": self.profile_id,
                "qq_number": "12345678",
            },
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

    def test_cross_machine_event_accepts_full_capacity_and_routes_recipients(self):
        snapshot = self.remote_ready_snapshot(revision=25)
        for machine_id in "CDEFGHIJ":
            snapshot["machines"][machine_id] = self.machine(
                name=f"区域 {machine_id} · 机台 {machine_id}"
            )
        registration_ids = [f"{index:024x}" for index in range(1, 201)]
        profiles = []
        contacts = []
        for index, registration_id in enumerate(registration_ids, start=1):
            profile = self.player_profile()
            profile_id = f"00000000-0000-0000-0000-{index:012d}"
            qq_number = str(10_000_000 + index)
            profile.update(
                {
                    "profile_id": profile_id,
                    "nickname": f"玩家{index}",
                    "qq_number": qq_number,
                    "notify_machine_status": True,
                }
            )
            profiles.append(profile)
            contacts.append(
                {
                    "registration_id": registration_id,
                    "profile_id": profile_id,
                    "qq_number": qq_number,
                }
            )
        event = self.event(
            "00000000-0000-0000-0000-000000000199",
            "REGISTRATION_CLOSED",
            1_000_200,
        )
        event.update(
            {
                "machine_id": None,
                "title": "关闭登记排队",
                "detail": "登记排队已关闭，并清除了所有机台的 200 份登记。",
                "registration_ids": registration_ids,
            }
        )
        snapshot["private_player_profiles"] = profiles
        snapshot["private_player_contacts"] = contacts
        snapshot["recent_events"] = [event]

        response = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )

        self.assertEqual(204, response.status_code)
        public_event = self.client.get("/api/queue-logs").get_json()["logs"][0]
        self.assertEqual(200, len(public_event["registration_ids"]))
        filtered_events = self.client.post(
            "/api/queue-bot/events",
            json={"qq": "10000001", "after": 0},
            headers=self.bot_headers,
        ).get_json()["events"]
        self.assertEqual(1, len(filtered_events))
        self.assertEqual(
            ["10000001"],
            [
                player["qq_number"]
                for player in filtered_events[0]["affected_players"]
            ],
        )

    def test_event_rejects_more_than_cross_machine_capacity(self):
        snapshot = self.remote_ready_snapshot(revision=26)
        event = self.event(
            "00000000-0000-0000-0000-000000000200",
            "REGISTRATION_CLOSED",
            1_000_200,
        )
        event["machine_id"] = None
        event["registration_ids"] = [f"{index:024x}" for index in range(1, 202)]
        snapshot["recent_events"] = [event]

        response = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )

        self.assertEqual(400, response.status_code)
        body = response.get_json()
        self.assertEqual("invalid_recent_event_registration_ids", body["code"])
        self.assertEqual("recent_events[0].registration_ids", body["field"])
        self.assertEqual(1, body["details"]["event_number"])
        self.assertEqual(event["event_id"], body["details"]["event_id"])
        self.assertIn("不能超过 200 项", body["error"])

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

    def test_rejects_multiple_current_registrations_for_one_profile(self):
        snapshot = self.snapshot(revision=4)
        snapshot["schema_version"] = 5
        second_registration = self.registration("b" * 24, "公开昵称")
        snapshot["machines"]["A"]["playing"].append(second_registration)
        snapshot["machines"]["A"]["registration_count"] = 2
        snapshot["private_player_profiles"] = [self.player_profile()]
        snapshot["private_player_contacts"] = [
            {
                "registration_id": "a" * 24,
                "profile_id": self.profile_id,
                "qq_number": "12345678",
            },
            {
                "registration_id": "b" * 24,
                "profile_id": self.profile_id,
                "qq_number": "12345678",
            },
        ]

        response = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )

        self.assertEqual(400, response.status_code)
        self.assertEqual(
            "同一玩家资料不能关联多份当前登记。",
            response.get_json()["error"],
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

    def test_same_terminal_scope_change_preserves_a_claimed_command_result(self):
        snapshot = self.remote_ready_snapshot(revision=7)
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )
        command_id = "00000000-0000-0000-0000-000000000787"
        self.assertEqual(
            202,
            self.client.post(
                "/api/queue-online/join",
                json={
                    "request_id": command_id,
                    "qq": "12345678",
                    "machine_id": "A",
                },
            ).status_code,
        )
        claimed = self.client.get(
            "/api/queue-terminal/commands", headers=self.headers
        ).get_json()["commands"]
        self.assertIn(command_id, {command["command_id"] for command in claimed})

        test_snapshot = copy.deepcopy(snapshot)
        test_snapshot["revision"] = 8
        switched = self.client.post(
            "/api/queue-status",
            json=test_snapshot,
            headers={**self.headers, "X-Queue-Sync-Mode": "test"},
        )
        pending = self.client.get(
            f"/api/queue-online/commands/{command_id}"
        ).get_json()
        completed = self.client.post(
            f"/api/queue-terminal/commands/{command_id}/result",
            json={
                "status": "APPLIED",
                "detail": "线上登记已保存。",
                "result_registration_id": "e" * 24,
            },
            headers=self.headers,
        )

        self.assertEqual(204, switched.status_code)
        self.assertEqual("PENDING", pending["status"])
        self.assertEqual("APPLIED", completed.get_json()["status"])
        self.assertEqual("TERMINAL", completed.get_json()["result_source"])

    def test_same_terminal_restore_rejects_a_claimed_test_command(self):
        snapshot = self.remote_ready_snapshot(revision=8)
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )
        test_snapshot = copy.deepcopy(snapshot)
        test_snapshot["revision"] = 9
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status",
                json=test_snapshot,
                headers={**self.headers, "X-Queue-Sync-Mode": "test"},
            ).status_code,
        )
        command_id = "00000000-0000-0000-0000-000000000790"
        self.assertEqual(
            202,
            self.client.post(
                "/api/queue-online/join",
                json={
                    "request_id": command_id,
                    "qq": "12345678",
                    "machine_id": "A",
                },
            ).status_code,
        )
        claimed = self.client.get(
            "/api/queue-terminal/commands", headers=self.headers
        ).get_json()["commands"]
        self.assertIn(command_id, {command["command_id"] for command in claimed})

        restored_snapshot = copy.deepcopy(snapshot)
        restored_snapshot["revision"] = 10
        restored = self.client.post(
            "/api/queue-status",
            json=restored_snapshot,
            headers={**self.headers, "X-Queue-Sync-Mode": "takeover"},
        )
        command = self.client.get(
            f"/api/queue-online/commands/{command_id}"
        ).get_json()
        available = self.client.get(
            "/api/queue-terminal/commands", headers=self.headers
        ).get_json()["commands"]

        self.assertEqual(204, restored.status_code)
        self.assertEqual("REJECTED", command["status"])
        self.assertEqual("SERVER_MIGRATION", command["result_source"])
        self.assertIn("测试同步已经结束", command["result_detail"])
        self.assertNotIn(command_id, {value["command_id"] for value in available})

    def test_recent_join_guard_does_not_cross_official_and_test_scopes(self):
        snapshot = self.remote_ready_snapshot(revision=10)
        self.client.post("/api/queue-status", json=snapshot, headers=self.headers)
        first_request_id = "00000000-0000-0000-0000-000000000788"
        self.client.post(
            "/api/queue-online/join",
            json={
                "request_id": first_request_id,
                "qq": "12345678",
                "machine_id": "A",
            },
        )
        self.client.get("/api/queue-terminal/commands", headers=self.headers)
        self.assertEqual(
            "APPLIED",
            self.client.post(
                f"/api/queue-terminal/commands/{first_request_id}/result",
                json={
                    "status": "APPLIED",
                    "detail": "线上登记已保存。",
                    "result_registration_id": "f" * 24,
                },
                headers=self.headers,
            ).get_json()["status"],
        )

        test_snapshot = copy.deepcopy(snapshot)
        test_snapshot["revision"] = 11
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status",
                json=test_snapshot,
                headers={**self.headers, "X-Queue-Sync-Mode": "test"},
            ).status_code,
        )
        test_request_id = "00000000-0000-0000-0000-000000000789"
        test_join = self.client.post(
            "/api/queue-online/join",
            json={
                "request_id": test_request_id,
                "qq": "12345678",
                "machine_id": "A",
            },
        )

        self.assertEqual(202, test_join.status_code, test_join.get_json())
        self.assertNotIn("_queue_storage_id", test_join.get_json()["payload"])
        connection = sqlite3.connect(self.database_path)
        try:
            stored_scope = connection.execute(
                "SELECT json_extract(payload, '$._queue_storage_id') "
                "FROM terminal_command WHERE command_id = ?",
                (test_request_id,),
            ).fetchone()[0]
        finally:
            connection.close()
        self.assertTrue(stored_scope.startswith("test:terminal-1:"))

    def test_test_sync_isolates_same_queue_profiles_events_and_higher_revision(self):
        self.app.config["ALLOWED_DEVICE_ID"] = ""
        self.app.config["PRIMARY_DEVICE_ID"] = "terminal-1"
        primary = self.remote_ready_snapshot(revision=5, with_registration=True)
        primary["recent_events"] = [
            self.event(
                "00000000-0000-0000-0000-000000000111",
                "REGISTRATION_UPDATED",
                1_000_001,
            )
        ]
        published = self.client.post(
            "/api/queue-status", json=primary, headers=self.headers
        )
        self.assertEqual(204, published.status_code, published.get_json())
        official_command_id = "00000000-0000-0000-0000-000000000785"
        self.assertEqual(
            202,
            self.client.patch(
                f"/api/queue-bot/profiles/{self.profile_id}",
                json={
                    "request_id": official_command_id,
                    "actor_qq": "12345678",
                    "nickname": "正式资料待处理",
                },
                headers=self.bot_headers,
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

        test_snapshot = copy.deepcopy(primary)
        test_snapshot["revision"] = 99
        test_snapshot["private_player_profiles"][0]["nickname"] = "测试资料"
        test_snapshot["private_player_profiles"][0]["profile_revision"] = 99
        test_snapshot["recent_events"][0]["detail"] = "这是测试终端产生的事件。"
        secondary_headers = {
            **self.headers,
            "X-Device-ID": "terminal-2",
            "X-Queue-Sync-Mode": "test",
        }
        started = self.client.post(
            "/api/queue-status", json=test_snapshot, headers=secondary_headers
        )
        self.assertEqual(204, started.status_code)

        test_profiles = self.client.get(
            "/api/queue-bot/profiles", headers=self.bot_headers
        ).get_json()
        test_events = self.client.get(
            "/api/queue-bot/events?after=0&limit=100", headers=self.bot_headers
        ).get_json()
        self.assertEqual("测试资料", test_profiles["profiles"][0]["nickname"])
        self.assertTrue(test_events["test_data"])
        self.assertTrue(test_events["notification_scope_id"].startswith("test:"))
        self.assertEqual(
            "这是测试终端产生的事件。", test_events["events"][0]["detail"]
        )
        official_command = self.client.get(
            f"/api/queue-bot/commands/{official_command_id}",
            headers=self.bot_headers,
        ).get_json()
        self.assertEqual("PENDING", official_command["status"])

        test_command_id = "00000000-0000-0000-0000-000000000786"
        self.assertEqual(
            202,
            self.client.patch(
                f"/api/queue-bot/profiles/{self.profile_id}",
                json={
                    "request_id": test_command_id,
                    "actor_qq": "12345678",
                    "nickname": "测试资料待处理",
                },
                headers=self.bot_headers,
            ).status_code,
        )

        primary["revision"] = 6
        restored = self.client.post(
            "/api/queue-status", json=primary, headers=self.headers
        )
        self.assertEqual(204, restored.status_code)
        current = self.client.get("/api/queue-status").get_json()
        restored_profiles = self.client.get(
            "/api/queue-bot/profiles", headers=self.bot_headers
        ).get_json()
        restored_events = self.client.get(
            "/api/queue-bot/events?after=0&limit=100", headers=self.bot_headers
        ).get_json()
        self.assertFalse(current["test_data"])
        self.assertEqual(6, current["revision"])
        self.assertEqual("公开昵称", restored_profiles["profiles"][0]["nickname"])
        self.assertFalse(restored_events["test_data"])
        self.assertEqual(primary["queue_id"], restored_events["notification_scope_id"])
        self.assertEqual(
            "“公开昵称”的排队状态已更新。",
            restored_events["events"][0]["detail"],
        )
        official_command = self.client.get(
            f"/api/queue-bot/commands/{official_command_id}",
            headers=self.bot_headers,
        ).get_json()
        test_command = self.client.get(
            f"/api/queue-bot/commands/{test_command_id}",
            headers=self.bot_headers,
        ).get_json()
        self.assertEqual("PENDING", official_command["status"])
        self.assertEqual("REJECTED", test_command["status"])

        connection = sqlite3.connect(self.database_path)
        try:
            profile_scopes = {
                row[0]
                for row in connection.execute(
                    "SELECT DISTINCT device_id FROM player_profile"
                )
            }
            stored_queue_ids = {
                row[0]
                for row in connection.execute(
                    "SELECT DISTINCT queue_id FROM queue_event"
                )
            }
        finally:
            connection.close()
        self.assertIn("default", profile_scopes)
        self.assertIn("default:test:terminal-2", profile_scopes)
        self.assertIn(primary["queue_id"], stored_queue_ids)
        self.assertIn(f"test:terminal-2:{primary['queue_id']}", stored_queue_ids)

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
        self.assertEqual("入口侧·机台 A", current["machines"]["A"]["name"])
        self.assertEqual("墙侧·机台 B", current["machines"]["B"]["name"])

    def test_schema_v1_keeps_default_machine_names(self):
        snapshot = self.snapshot()
        snapshot["machines"]["A"]["name"] = "入口侧 · 机台 A"

        response = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )
        current = self.client.get("/api/queue-status").get_json()

        self.assertEqual(204, response.status_code)
        self.assertEqual("左侧·机台 A", current["machines"]["A"]["name"])

    def test_legacy_terminal_without_app_version_still_syncs_as_unknown(self):
        snapshot = self.snapshot(revision=5)
        snapshot["terminal"].pop("app_version")

        response = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )
        versions = self.client.get("/api/queue-versions").get_json()["components"]

        self.assertEqual(204, response.status_code)
        self.assertIsNone(versions["terminal"]["current_version"])
        self.assertEqual("UNKNOWN", versions["terminal"]["status"])

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

    def test_schema_v6_preserves_configuration_and_rejects_single_player_violations(self):
        valid = self.remote_ready_snapshot(revision=40)
        self.upgrade_snapshot_to_schema_v6(
            valid,
            capacities={"A": 1, "B": 2},
            configuration_revision=7,
        )
        valid["machines"]["A"]["configuration"].update(
            game_type="OTHER",
            custom_game_type="其他音游",
            game_version="1.2",
            game_version_visible=True,
            solo_round_minutes=20,
            shared_round_minutes=30,
        )

        accepted = self.client.post(
            "/api/queue-status", json=valid, headers=self.headers
        )
        current = self.client.get("/api/queue-status").get_json()

        self.assertEqual(204, accepted.status_code)
        self.assertEqual(7, current["machine_configuration_revision"])
        self.assertEqual(1, current["machines"]["A"]["configuration"]["capacity"])
        self.assertEqual(
            "其他音游",
            current["machines"]["A"]["configuration"]["custom_game_type"],
        )
        self.assertEqual(
            20, current["machines"]["A"]["configuration"]["solo_round_minutes"]
        )

        open_preference = copy.deepcopy(valid)
        open_preference["revision"] = 41
        open_registration = self.registration("c" * 24, "开放玩家")
        open_registration["preference"] = "OPEN_TO_JOIN"
        open_preference["machines"]["A"]["waiting_positions"] = [
            {
                "index": 1,
                "position_id": "d" * 24,
                "fixed_pair": False,
                "estimated_wait_minutes": 0,
                "registrations": [open_registration],
            }
        ]
        open_preference["machines"]["A"]["registration_count"] = 1
        open_preference["machines"]["A"]["waiting_position_count"] = 1

        double_playing = copy.deepcopy(valid)
        double_playing["revision"] = 42
        double_playing["machines"]["A"]["playing"] = [
            self.registration("e" * 24, "玩家一"),
            self.registration("f" * 24, "玩家二"),
        ]
        double_playing["machines"]["A"]["playing_started_at"] = 900_000
        double_playing["machines"]["A"]["registration_count"] = 2

        for label, snapshot in (
            ("开放偏好", open_preference),
            ("双人游玩位置", double_playing),
        ):
            with self.subTest(label=label):
                response = self.client.post(
                    "/api/queue-status", json=snapshot, headers=self.headers
                )
                self.assertEqual(400, response.status_code)
                self.assertIn("游玩容量为 1", response.get_json()["error"])

    def test_schema_v6_accepts_wait_estimates_from_maximum_planned_round_time(self):
        snapshot = self.remote_ready_snapshot(revision=43, with_registration=True)
        self.upgrade_snapshot_to_schema_v6(snapshot, capacities={"A": 1})
        snapshot["machines"]["A"]["configuration"]["solo_round_minutes"] = 120
        snapshot["machines"]["A"]["new_registration_estimated_wait_minutes"] = 2400
        snapshot["machines"]["A"]["waiting_positions"][0][
            "estimated_wait_minutes"
        ] = 2400

        accepted = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )

        too_large = copy.deepcopy(snapshot)
        too_large["revision"] += 1
        too_large["machines"]["A"]["new_registration_estimated_wait_minutes"] = 2401
        rejected = self.client.post(
            "/api/queue-status", json=too_large, headers=self.headers
        )

        self.assertEqual(204, accepted.status_code)
        self.assertEqual(400, rejected.status_code)

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

    def test_single_player_remote_join_forces_solo_and_carries_configuration_revision(self):
        snapshot = self.remote_ready_snapshot(
            revision=43,
            default_preference="ASK_EVERY_TIME",
        )
        self.upgrade_snapshot_to_schema_v6(
            snapshot,
            capacities={"A": 1, "B": 2},
            configuration_revision=11,
        )
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )

        joined = self.client.post(
            "/api/queue-bot/queue-commands",
            json={
                "request_id": "00000000-0000-0000-0000-000000000454",
                "actor_qq": "12345678",
                "operation": "JOIN_QUEUE",
                "machine_id": "A",
                "expected_queue_id": snapshot["queue_id"],
                "expected_machine_configuration_revision": 11,
            },
            headers=self.bot_headers,
        )
        command = joined.get_json()

        self.assertEqual(202, joined.status_code)
        self.assertEqual("SOLO", command["payload"]["preference"])
        self.assertEqual(11, command["payload"]["machine_configuration_revision"])

    def test_remote_join_confirmation_rejects_changed_queue_or_machine_configuration(self):
        snapshot = self.remote_ready_snapshot(revision=44)
        self.upgrade_snapshot_to_schema_v6(
            snapshot,
            configuration_revision=9,
        )
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )

        matching = {
            "request_id": "00000000-0000-0000-0000-000000000461",
            "qq": "12345678",
            "machine_id": "A",
            "expected_queue_id": snapshot["queue_id"],
            "expected_machine_configuration_revision": 9,
        }
        changed_queue = {
            **matching,
            "request_id": "00000000-0000-0000-0000-000000000462",
            "expected_queue_id": "00000000-0000-0000-0000-000000000099",
        }
        changed_configuration = {
            **matching,
            "request_id": "00000000-0000-0000-0000-000000000463",
            "expected_machine_configuration_revision": 8,
        }
        incomplete = {
            key: value
            for key, value in matching.items()
            if key != "expected_machine_configuration_revision"
        }
        incomplete["request_id"] = "00000000-0000-0000-0000-000000000464"

        queue_response = self.client.post(
            "/api/queue-online/join", json=changed_queue
        )
        configuration_response = self.client.post(
            "/api/queue-online/join", json=changed_configuration
        )
        incomplete_response = self.client.post(
            "/api/queue-online/join", json=incomplete
        )
        accepted = self.client.post("/api/queue-online/join", json=matching)
        commands = self.client.get(
            "/api/queue-terminal/commands", headers=self.headers
        ).get_json()["commands"]

        self.assertEqual(409, queue_response.status_code)
        self.assertEqual("QUEUE_CONTEXT_CHANGED", queue_response.get_json()["code"])
        self.assertEqual(409, configuration_response.status_code)
        self.assertEqual(
            "QUEUE_CONTEXT_CHANGED", configuration_response.get_json()["code"]
        )
        self.assertEqual(400, incomplete_response.status_code)
        self.assertIn("确认字段不完整", incomplete_response.get_json()["error"])
        self.assertEqual(202, accepted.status_code)
        self.assertEqual(1, len(commands))
        self.assertEqual(9, commands[0]["payload"]["machine_configuration_revision"])

    def test_confirmed_transfer_rejects_reassigned_machine_identifier(self):
        snapshot = self.remote_ready_snapshot(revision=45, with_registration=True)
        self.upgrade_snapshot_to_schema_v6(
            snapshot,
            configuration_revision=12,
        )
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )
        registration = snapshot["machines"]["A"]["waiting_positions"][0][
            "registrations"
        ][0]
        confirmation = {
            "request_id": "00000000-0000-0000-0000-000000000465",
            "actor_qq": "12345678",
            "operation": "TRANSFER_MACHINE",
            "target_machine_id": "B",
            "expected_queue_id": snapshot["queue_id"],
            "expected_registration_id": registration["registration_id"],
            "expected_machine_id": "A",
            "expected_position": "WAITING",
            "expected_fixed_pair_id": None,
            "expected_absence_status": "NONE",
            "expected_temporary_away_skipped_turns": 0,
            "expected_pending_check_in": False,
            "expected_machine_configuration_revision": 11,
        }

        stale = self.client.post(
            "/api/queue-bot/queue-commands",
            json=confirmation,
            headers=self.bot_headers,
        )
        legacy_confirmation = {
            key: value
            for key, value in confirmation.items()
            if key != "expected_machine_configuration_revision"
        }
        legacy_confirmation["request_id"] = (
            "00000000-0000-0000-0000-000000000466"
        )
        legacy = self.client.post(
            "/api/queue-bot/queue-commands",
            json=legacy_confirmation,
            headers=self.bot_headers,
        )

        self.assertEqual(409, stale.status_code)
        self.assertEqual("QUEUE_CONTEXT_CHANGED", stale.get_json()["code"])
        self.assertIn("机台配置已更新", stale.get_json()["error"])
        self.assertEqual(202, legacy.status_code)
        self.assertEqual(
            12,
            legacy.get_json()["payload"]["machine_configuration_revision"],
        )

    def test_single_player_machine_rejects_preference_change_and_fixed_pair_transfer(self):
        single = self.remote_ready_snapshot(revision=44, with_registration=True)
        self.upgrade_snapshot_to_schema_v6(
            single,
            capacities={"A": 1, "B": 2},
            configuration_revision=12,
        )
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=single, headers=self.headers
            ).status_code,
        )

        preference = self.client.post(
            "/api/queue-bot/queue-commands",
            json={
                "request_id": "00000000-0000-0000-0000-000000000455",
                "actor_qq": "12345678",
                "operation": "CHANGE_PLAY_PREFERENCE",
                "preference": "OPEN_TO_JOIN",
            },
            headers=self.bot_headers,
        )
        self.assertEqual(409, preference.status_code)
        self.assertIn("仅能容纳一人游玩", preference.get_json()["error"])

        paired = self.remote_ready_snapshot(revision=45)
        first = self.registration("a" * 24, "公开昵称")
        second = self.registration("c" * 24, "固定搭档")
        for registration in (first, second):
            registration.update(
                preference="OPEN_TO_JOIN",
                fixed_pair=True,
                fixed_pair_id="f" * 24,
            )
        paired["machines"]["A"]["waiting_positions"] = [
            {
                "index": 1,
                "position_id": "b" * 24,
                "fixed_pair": True,
                "estimated_wait_minutes": 0,
                "registrations": [first, second],
            }
        ]
        paired["machines"]["A"]["registration_count"] = 2
        paired["machines"]["A"]["waiting_position_count"] = 1
        paired["private_player_contacts"] = [
            {
                "registration_id": first["registration_id"],
                "profile_id": self.profile_id,
                "qq_number": "12345678",
            }
        ]
        self.upgrade_snapshot_to_schema_v6(
            paired,
            capacities={"A": 2, "B": 1},
            configuration_revision=13,
        )
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=paired, headers=self.headers
            ).status_code,
        )

        transfer = self.client.post(
            "/api/queue-bot/queue-commands",
            json={
                "request_id": "00000000-0000-0000-0000-000000000456",
                "actor_qq": "12345678",
                "operation": "TRANSFER_MACHINE",
                "target_machine_id": "B",
            },
            headers=self.bot_headers,
        )
        self.assertEqual(409, transfer.status_code)
        self.assertIn("先解除固定组合", transfer.get_json()["error"])

    def test_four_machine_profile_lookup_and_website_join_target_machine_c(self):
        snapshot = self.remote_ready_snapshot(revision=31)
        snapshot["machines"]["C"] = self.machine(name="靠窗 · 机台 C")
        snapshot["machines"]["D"] = self.machine(name="入口 · 机台 D")
        first_group_id = "1" * 32
        second_group_id = "2" * 32
        self.upgrade_snapshot_to_schema_v7(
            snapshot,
            groups=[
                {"id": first_group_id, "name": "左侧区域"},
                {"id": second_group_id, "name": "右侧区域"},
            ],
            group_assignments={
                "A": first_group_id,
                "B": first_group_id,
                "C": second_group_id,
                "D": second_group_id,
            },
            default_group_id=second_group_id,
        )
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )

        profile = self.client.post(
            "/api/queue-online/profile", json={"qq": "12345678"}
        )
        created = self.client.post(
            "/api/queue-online/join",
            json={
                "request_id": "00000000-0000-0000-0000-000000000451",
                "qq": "12345678",
                "machine_id": "C",
            },
        )
        commands = self.client.get(
            "/api/queue-terminal/commands", headers=self.headers
        ).get_json()["commands"]

        self.assertEqual(200, profile.status_code)
        self.assertEqual(
            list("ABCD"),
            [machine["id"] for machine in profile.get_json()["machines"]],
        )
        profile_payload = profile.get_json()
        self.assertEqual(
            [
                {"id": first_group_id, "name": "左侧区域"},
                {"id": second_group_id, "name": "右侧区域"},
            ],
            profile_payload["machine_groups"],
        )
        self.assertEqual(second_group_id, profile_payload["default_machine_group_id"])
        self.assertEqual(
            [f"{index:032x}" for index in range(1, 5)],
            [machine["stable_id"] for machine in profile_payload["machines"]],
        )
        self.assertEqual(
            [first_group_id, first_group_id, second_group_id, second_group_id],
            [machine["group_id"] for machine in profile_payload["machines"]],
        )
        self.assertEqual(202, created.status_code)
        self.assertEqual("C", commands[0]["payload"]["machine_id"])

    def test_bot_can_join_c_and_transfer_an_existing_registration_from_c_to_d(self):
        join_snapshot = self.remote_ready_snapshot(revision=32)
        join_snapshot["machines"]["C"] = self.machine(name="靠窗 · 机台 C")
        join_snapshot["machines"]["D"] = self.machine(name="入口 · 机台 D")
        self.client.post(
            "/api/queue-status", json=join_snapshot, headers=self.headers
        )
        joined = self.client.post(
            "/api/queue-bot/queue-commands",
            json={
                "request_id": "00000000-0000-0000-0000-000000000452",
                "actor_qq": "12345678",
                "operation": "JOIN_QUEUE",
                "machine_id": "C",
            },
            headers=self.bot_headers,
        )
        join_commands = self.client.get(
            "/api/queue-terminal/commands", headers=self.headers
        ).get_json()["commands"]

        self.assertEqual(202, joined.status_code)
        self.assertEqual("C", join_commands[0]["payload"]["machine_id"])
        self.assertEqual(
            "APPLIED",
            self.client.post(
                "/api/queue-terminal/commands/00000000-0000-0000-0000-000000000452/result",
                json={"status": "APPLIED", "detail": "线上登记已保存。"},
                headers=self.headers,
            ).get_json()["status"],
        )

        transfer_snapshot = self.remote_ready_snapshot(
            revision=33,
            with_registration=True,
        )
        registration = transfer_snapshot["machines"]["A"]["waiting_positions"][0][
            "registrations"
        ][0]
        machine_c = transfer_snapshot["machines"]["A"]
        machine_c["name"] = "靠窗 · 机台 C"
        transfer_snapshot["machines"]["A"] = self.machine(name="左侧 · 机台 A")
        transfer_snapshot["machines"]["A"][
            "new_registration_estimated_wait_minutes"
        ] = 0
        transfer_snapshot["machines"]["C"] = machine_c
        transfer_snapshot["machines"]["D"] = self.machine(name="入口 · 机台 D")
        self.client.post(
            "/api/queue-status", json=transfer_snapshot, headers=self.headers
        )
        transferred = self.client.post(
            "/api/queue-bot/queue-commands",
            json={
                "request_id": "00000000-0000-0000-0000-000000000453",
                "actor_qq": "12345678",
                "operation": "TRANSFER_MACHINE",
                "target_machine_id": "D",
                "expected_queue_id": transfer_snapshot["queue_id"],
                "expected_registration_id": registration["registration_id"],
                "expected_machine_id": "C",
                "expected_position": "WAITING",
                "expected_fixed_pair_id": None,
                "expected_absence_status": "NONE",
                "expected_temporary_away_skipped_turns": 0,
                "expected_pending_check_in": False,
            },
            headers=self.bot_headers,
        )
        transfer_commands = self.client.get(
            "/api/queue-terminal/commands", headers=self.headers
        ).get_json()["commands"]
        transfer_command = next(
            command
            for command in transfer_commands
            if command["command_id"]
            == "00000000-0000-0000-0000-000000000453"
        )

        self.assertEqual(202, transferred.status_code)
        self.assertEqual("C", transfer_command["payload"]["machine_id"])
        self.assertEqual("D", transfer_command["payload"]["target_machine_id"])

    def test_recently_applied_website_join_cannot_be_duplicated_before_snapshot_sync(self):
        snapshot = self.remote_ready_snapshot(revision=30)
        self.client.post("/api/queue-status", json=snapshot, headers=self.headers)
        first_request_id = "00000000-0000-0000-0000-000000000431"
        self.client.post(
            "/api/queue-online/join",
            json={
                "request_id": first_request_id,
                "qq": "12345678",
                "machine_id": "A",
            },
        )
        self.client.get("/api/queue-terminal/commands", headers=self.headers)
        completed = self.client.post(
            f"/api/queue-terminal/commands/{first_request_id}/result",
            json={
                "status": "APPLIED",
                "detail": "线上登记已保存。",
                "result_registration_id": "d" * 24,
            },
            headers=self.headers,
        )

        repeated = self.client.post(
            "/api/queue-online/join",
            json={
                "request_id": "00000000-0000-0000-0000-000000000432",
                "qq": "12345678",
                "machine_id": "A",
            },
        )

        self.assertEqual("APPLIED", completed.get_json()["status"])
        self.assertEqual(409, repeated.status_code)
        self.assertEqual("PLAYER_OPERATION_SYNCING", repeated.get_json()["code"])

        connection = sqlite3.connect(self.database_path)
        try:
            completed_at = connection.execute(
                "SELECT completed_at FROM terminal_command WHERE command_id = ?",
                (first_request_id,),
            ).fetchone()[0]
            connection.execute(
                "UPDATE queue_snapshot SET received_at = ? WHERE id = 1",
                (completed_at + 1,),
            )
            connection.commit()
        finally:
            connection.close()
        rejoined_after_newer_snapshot = self.client.post(
            "/api/queue-online/join",
            json={
                "request_id": "00000000-0000-0000-0000-000000000433",
                "qq": "12345678",
                "machine_id": "A",
            },
        )
        self.assertEqual(202, rejoined_after_newer_snapshot.status_code)

    def test_recently_applied_website_join_blocks_mobile_registration(self):
        snapshot = self.remote_ready_snapshot(revision=31)
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )
        first_request_id = "00000000-0000-0000-0000-000000000434"
        self.assertEqual(
            202,
            self.client.post(
                "/api/queue-online/join",
                json={
                    "request_id": first_request_id,
                    "qq": "12345678",
                    "machine_id": "A",
                },
            ).status_code,
        )
        self.client.get("/api/queue-terminal/commands", headers=self.headers)
        self.assertEqual(
            "APPLIED",
            self.client.post(
                f"/api/queue-terminal/commands/{first_request_id}/result",
                json={
                    "status": "APPLIED",
                    "detail": "线上登记已保存。",
                    "result_registration_id": "a" * 24,
                },
                headers=self.headers,
            ).get_json()["status"],
        )
        session = self.client.post(
            "/api/queue-terminal/mobile-registration-sessions",
            json={
                "request_id": "00000000-0000-0000-0000-000000000435",
                "queue_id": snapshot["queue_id"],
                "machine_id": "A",
            },
            headers=self.headers,
        ).get_json()

        repeated = self.client.post(
            f"/api/queue-mobile/sessions/{session['session_token']}/submit",
            json={
                "request_id": "00000000-0000-0000-0000-000000000436",
                "profile_id": self.profile_id,
                "expected_profile_revision": 3,
            },
        )

        self.assertEqual(409, repeated.status_code)
        self.assertEqual("PLAYER_OPERATION_SYNCING", repeated.get_json()["code"])

    def test_recently_applied_mobile_registration_blocks_website_join(self):
        snapshot = self.remote_ready_snapshot(revision=32)
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )
        session = self.client.post(
            "/api/queue-terminal/mobile-registration-sessions",
            json={
                "request_id": "00000000-0000-0000-0000-000000000437",
                "queue_id": snapshot["queue_id"],
                "machine_id": "A",
            },
            headers=self.headers,
        ).get_json()
        command_id = "00000000-0000-0000-0000-000000000438"
        submitted = self.client.post(
            f"/api/queue-mobile/sessions/{session['session_token']}/submit",
            json={
                "request_id": command_id,
                "profile_id": self.profile_id,
                "expected_profile_revision": 3,
            },
        )
        commands = self.client.get(
            "/api/queue-terminal/commands", headers=self.headers
        ).get_json()["commands"]
        command = next(value for value in commands if value["command_id"] == command_id)
        self.assertNotIn("_queue_storage_id", command["payload"])
        self.assertEqual(
            "APPLIED",
            self.client.post(
                f"/api/queue-terminal/commands/{command_id}/result",
                json={
                    "status": "APPLIED",
                    "detail": "已通过移动设备加入排队。",
                    "result_registration_id": "b" * 24,
                },
                headers=self.headers,
            ).get_json()["status"],
        )

        repeated = self.client.post(
            "/api/queue-online/join",
            json={
                "request_id": "00000000-0000-0000-0000-000000000439",
                "qq": "12345678",
                "machine_id": "A",
            },
        )

        self.assertEqual(202, submitted.status_code)
        self.assertEqual(409, repeated.status_code)
        self.assertEqual("PLAYER_OPERATION_SYNCING", repeated.get_json()["code"])

        connection = sqlite3.connect(self.database_path)
        try:
            stored_scope = connection.execute(
                "SELECT json_extract(payload, '$._queue_storage_id') "
                "FROM terminal_command WHERE command_id = ?",
                (command_id,),
            ).fetchone()[0]
        finally:
            connection.close()
        self.assertEqual(snapshot["queue_id"], stored_scope)

    def test_recent_mobile_join_guard_does_not_cross_test_scope(self):
        snapshot = self.remote_ready_snapshot(revision=33)
        self.client.post("/api/queue-status", json=snapshot, headers=self.headers)
        session = self.client.post(
            "/api/queue-terminal/mobile-registration-sessions",
            json={
                "request_id": "00000000-0000-0000-0000-000000000440",
                "queue_id": snapshot["queue_id"],
                "machine_id": "A",
            },
            headers=self.headers,
        ).get_json()
        command_id = "00000000-0000-0000-0000-000000000441"
        self.client.post(
            f"/api/queue-mobile/sessions/{session['session_token']}/submit",
            json={
                "request_id": command_id,
                "profile_id": self.profile_id,
                "expected_profile_revision": 3,
            },
        )
        self.client.get("/api/queue-terminal/commands", headers=self.headers)
        self.client.post(
            f"/api/queue-terminal/commands/{command_id}/result",
            json={
                "status": "APPLIED",
                "detail": "已通过移动设备加入排队。",
                "result_registration_id": "c" * 24,
            },
            headers=self.headers,
        )

        test_snapshot = copy.deepcopy(snapshot)
        test_snapshot["revision"] = 34
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status",
                json=test_snapshot,
                headers={**self.headers, "X-Queue-Sync-Mode": "test"},
            ).status_code,
        )
        joined = self.client.post(
            "/api/queue-online/join",
            json={
                "request_id": "00000000-0000-0000-0000-000000000442",
                "qq": "12345678",
                "machine_id": "A",
            },
        )

        self.assertEqual(202, joined.status_code, joined.get_json())

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
        self.assertTrue(public_status["capabilities"]["remote_actions"])
        self.assertEqual(503, website_profile.status_code)
        self.assertEqual("系统规则不允许线上登记。", website_profile.get_json()["error"])
        self.assertEqual(503, website_join.status_code)
        self.assertEqual(503, bot_join.status_code)
        self.assertEqual(200, bot_players.status_code)
        self.assertEqual(202, bot_leave.status_code)
        self.assertTrue(bot_players.get_json()["capabilities"]["remote_actions"])
        self.assertFalse(bot_players.get_json()["capabilities"]["online_registration"])

    def test_remote_action_capabilities_reflect_terminal_and_bot_state(self):
        snapshot = self.remote_ready_snapshot(with_registration=True)
        self.assertEqual(
            204,
            self.client.post("/api/queue-status", json=snapshot, headers=self.headers).status_code,
        )

        enabled = self.client.post(
            "/api/queue-bot/players", json={}, headers=self.bot_headers
        ).get_json()["capabilities"]
        self.assertTrue(enabled["remote_actions"])
        self.assertTrue(enabled["online_registration"])

        bot_disabled = copy.deepcopy(snapshot)
        bot_disabled["revision"] += 1
        bot_disabled["onebot_sync_enabled"] = False
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=bot_disabled, headers=self.headers
            ).status_code,
        )
        disabled_response = self.client.post(
            "/api/queue-bot/players", json={}, headers=self.bot_headers
        )
        self.assertEqual(503, disabled_response.status_code)
        self.assertIn("QQ Bot 联动已关闭。", disabled_response.get_json()["error"])

        online_again = copy.deepcopy(bot_disabled)
        online_again["revision"] += 1
        online_again["onebot_sync_enabled"] = True
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=online_again, headers=self.headers
            ).status_code,
        )
        connection = sqlite3.connect(self.database_path)
        try:
            connection.execute(
                "UPDATE queue_snapshot SET received_at = ? WHERE id = 1",
                (int(time.time()) - self.app.config["ONLINE_TIMEOUT_SECONDS"] - 1,),
            )
            connection.commit()
        finally:
            connection.close()
        offline_response = self.client.post(
            "/api/queue-bot/players", json={}, headers=self.bot_headers
        )
        self.assertEqual(200, offline_response.status_code)
        self.assertFalse(offline_response.get_json()["capabilities"]["remote_actions"])
        self.assertFalse(offline_response.get_json()["capabilities"]["online_registration"])

    def test_website_profile_lookup_is_blocked_when_remote_registration_is_unavailable(self):
        enabled = self.remote_ready_snapshot(revision=61)
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=enabled, headers=self.headers
            ).status_code,
        )

        disabled = copy.deepcopy(enabled)
        disabled["revision"] += 1
        disabled["website_remote_enabled"] = False
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=disabled, headers=self.headers
            ).status_code,
        )
        disabled_lookup = self.client.post(
            "/api/queue-online/profile", json={"qq": "12345678"}
        )
        self.assertEqual(503, disabled_lookup.status_code)
        self.assertIn("关闭与服务端同步", disabled_lookup.get_json()["error"])

        reopened = copy.deepcopy(enabled)
        reopened["revision"] += 2
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=reopened, headers=self.headers
            ).status_code,
        )
        with patch("app.time.time", return_value=int(time.time()) + 1_000):
            offline_lookup = self.client.post(
                "/api/queue-online/profile", json={"qq": "12345678"}
            )
        self.assertEqual(503, offline_lookup.status_code)
        self.assertIn("终端暂时离线", offline_lookup.get_json()["error"])

    def test_closing_grace_and_machine_stop_block_remote_queue_changes(self):
        closing = self.remote_ready_snapshot(revision=25)
        closing["business_hours"] = {
            "enabled": True,
            "outside": True,
            "closing_soon": False,
            "closing_grace": True,
            "closes_at": None,
            "registration_closes_at": 2_200_000,
        }
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=closing, headers=self.headers
            ).status_code,
        )
        public_status = self.client.get("/api/queue-status").get_json()
        website_profile = self.client.post(
            "/api/queue-online/profile", json={"qq": "12345678"}
        )
        website_join = self.client.post(
            "/api/queue-online/join",
            json={
                "request_id": "00000000-0000-0000-0000-000000000424",
                "qq": "12345678",
                "machine_id": "A",
            },
        )
        bot_join = self.client.post(
            "/api/queue-bot/queue-commands",
            json={
                "request_id": "00000000-0000-0000-0000-000000000425",
                "actor_qq": "12345678",
                "operation": "JOIN_QUEUE",
                "machine_id": "A",
            },
            headers=self.bot_headers,
        )
        mobile_session = self.client.post(
            "/api/queue-terminal/mobile-registration-sessions",
            json={
                "request_id": "00000000-0000-0000-0000-000000000426",
                "queue_id": closing["queue_id"],
                "machine_id": "A",
            },
            headers=self.headers,
        )

        self.assertFalse(public_status["capabilities"]["online_registration"])
        self.assertEqual(409, website_profile.status_code)
        self.assertIn("闭店收尾", website_profile.get_json()["error"])
        self.assertEqual(409, website_join.status_code)
        self.assertIn("闭店收尾", website_join.get_json()["error"])
        self.assertEqual(409, bot_join.status_code)
        self.assertIn("闭店收尾", bot_join.get_json()["error"])
        self.assertEqual(409, mobile_session.status_code)
        self.assertIn("闭店收尾", mobile_session.get_json()["error"])

        stopped = self.remote_ready_snapshot(revision=26, with_registration=True)
        stopped["machines"]["A"].update(
            operational=False,
            stop_reason="MAINTENANCE",
            stopped_at=2_300_000,
        )
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=stopped, headers=self.headers
            ).status_code,
        )
        defer = self.client.post(
            "/api/queue-bot/queue-commands",
            json={
                "request_id": "00000000-0000-0000-0000-000000000427",
                "actor_qq": "12345678",
                "operation": "DEFER_ONE_ROUND",
                "machine_id": "A",
            },
            headers=self.bot_headers,
        )

        self.assertEqual(409, defer.status_code)
        self.assertEqual(
            "左侧·机台 A 已停止使用，恢复正常使用前只能退出排队或转至其他机台。",
            defer.get_json()["error"],
        )

    def stopped_machine_snapshot(self, revision=26):
        """Remote-ready snapshot whose only registration waits at a stopped machine A."""
        snapshot = self.remote_ready_snapshot(revision=revision, with_registration=True)
        snapshot["machines"]["A"].update(
            operational=False,
            stop_reason="MAINTENANCE",
            stopped_at=2_300_000,
        )
        return snapshot

    def bot_operation(self, request_number, operation, **fields):
        return self.client.post(
            "/api/queue-bot/queue-commands",
            json={
                "request_id": f"00000000-0000-0000-0000-0000000004{request_number}",
                "actor_qq": "12345678",
                "operation": operation,
                **fields,
            },
            headers=self.bot_headers,
        )

    def test_registration_at_a_stopped_machine_can_still_leave_the_queue(self):
        # Nobody is held at a machine that cannot be played on.
        self.publish(self.stopped_machine_snapshot())

        leave = self.bot_operation(60, "LEAVE_QUEUE")
        commands = self.pending_command_payloads()

        self.assertEqual(202, leave.status_code, leave.get_json())
        self.assertEqual(["LEAVE_QUEUE"], [command["operation"] for command in commands])
        self.assertEqual("A", commands[0]["machine_id"])

    def test_registration_at_a_stopped_machine_can_still_move_to_a_running_machine(self):
        self.publish(self.stopped_machine_snapshot())

        transfer = self.bot_operation(61, "TRANSFER_MACHINE", target_machine_id="B")
        commands = self.pending_command_payloads()

        self.assertEqual(202, transfer.status_code, transfer.get_json())
        self.assertEqual(["TRANSFER_MACHINE"], [command["operation"] for command in commands])
        self.assertEqual("A", commands[0]["machine_id"])
        self.assertEqual("B", commands[0]["target_machine_id"])

    def test_everything_else_waits_while_the_machine_of_a_registration_is_stopped(self):
        self.publish(self.stopped_machine_snapshot())

        refused = {
            operation: self.bot_operation(62 + index, operation, **fields)
            for index, (operation, fields) in enumerate(
                (
                    ("DEFER_ONE_ROUND", {}),
                    ("TEMPORARILY_LEAVE", {}),
                    ("CANCEL_DEFER_ONE_ROUND", {}),
                    ("CANCEL_TEMPORARY_LEAVE", {}),
                    ("CHANGE_PLAY_PREFERENCE", {"preference": "SOLO"}),
                )
            )
        }

        for operation, response in refused.items():
            with self.subTest(operation=operation):
                self.assertEqual(409, response.status_code, response.get_json())
                self.assertEqual(
                    "左侧·机台 A 已停止使用，恢复正常使用前只能退出排队或转至其他机台。",
                    response.get_json()["error"],
                )
        self.assertEqual([], self.pending_command_payloads())

    def test_no_registration_moves_to_a_stopped_machine(self):
        snapshot = self.remote_ready_snapshot(revision=26, with_registration=True)
        snapshot["machines"]["B"].update(
            operational=False,
            stop_reason="MAINTENANCE",
            stopped_at=2_300_000,
        )
        self.publish(snapshot)

        transfer = self.bot_operation(70, "TRANSFER_MACHINE", target_machine_id="B")

        self.assertEqual(409, transfer.status_code, transfer.get_json())
        self.assertIn("已停止使用，暂不能转入", transfer.get_json()["error"])

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
        self.assertEqual("请选择本次游玩偏好。", missing.get_json()["error"])
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

    def management_schema_eight_snapshot(self, *, with_registration=False, pending_check_in=False):
        snapshot = self.remote_ready_snapshot(
            with_registration=with_registration,
            pending_check_in=pending_check_in,
        )
        self.upgrade_snapshot_to_schema_v7(snapshot)
        snapshot["schema_version"] = 8
        venue_id = self.client.get(
            "/api/queue-terminal/installation", headers=self.headers
        ).get_json()["venue"]["id"]
        snapshot["venue"] = {"id": venue_id}
        return snapshot, self.schema_eight_terminal_headers()

    @staticmethod
    def management_business_hours():
        default_hours = {"opening_minutes": 600, "closing_minutes": 1320}
        return {
            "enabled": False,
            "use_weekly_schedule": False,
            "default_hours": default_hours,
            "weekly_hours": {
                day: dict(default_hours)
                for day in (
                    "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY",
                    "FRIDAY", "SATURDAY", "SUNDAY",
                )
            },
        }

    def management_control_snapshot(
        self,
        *,
        bound=True,
        registration_open=True,
        with_registration=False,
    ):
        snapshot, terminal_headers = self.management_schema_eight_snapshot(
            with_registration=with_registration,
            pending_check_in=False,
        )
        snapshot.update(
            {
                "registration_open": registration_open,
                "management_policy_supported": True,
                "management_app_bound": bound,
                "management_policy_revision": 2,
                "management_policy": {
                    "allow_online_registration": True,
                    "allow_defer_one_round": True,
                    "allow_temporary_leave": True,
                    "onebot_sync_enabled": True,
                },
                "management_settings_supported": True,
                "management_settings_revision": 3,
                "show_common_play_preview": True,
                "registration_control_open": registration_open,
                "business_hours_settings": self.management_business_hours(),
            }
        )
        return snapshot, terminal_headers

    def management_terminal_settings_body(self, snapshot, request_id):
        machines = {
            machine_id: {
                "stable_id": machine["stable_id"],
                "group_id": machine["group_id"],
                "remark": machine["remark"],
                "configuration": copy.deepcopy(machine["configuration"]),
            }
            for machine_id, machine in snapshot["machines"].items()
        }
        return {
            "request_id": request_id,
            "expected_queue_id": snapshot["queue_id"],
            "expected_settings_revision": snapshot["management_settings_revision"],
            "expected_policy_revision": snapshot["management_policy_revision"],
            "expected_machine_configuration_revision": snapshot[
                "machine_configuration_revision"
            ],
            "expected_registration_open": snapshot["registration_control_open"],
            "show_common_play_preview": False,
            "business_hours": copy.deepcopy(snapshot["business_hours_settings"]),
            "machine_groups": copy.deepcopy(snapshot["machine_groups"]),
            "default_machine_group_id": snapshot["default_machine_group_id"],
            "machines": machines,
            "reason": "管理后台更新终端设置",
        }

    def management_registration_availability_body(
        self, snapshot, request_id, *, registration_open
    ):
        return {
            "request_id": request_id,
            "expected_queue_id": snapshot["queue_id"],
            "expected_queue_revision": snapshot["revision"],
            "expected_machine_configuration_revision": snapshot[
                "machine_configuration_revision"
            ],
            "expected_registration_open": snapshot["registration_control_open"],
            "registration_open": registration_open,
            "expected_registration_ids": sorted(
                registration["registration_id"]
                for machine in snapshot["machines"].values()
                for registration in (
                    machine["playing"]
                    + [
                        item
                        for position in machine["waiting_positions"]
                        for item in position["registrations"]
                    ]
                )
            ),
            "confirm_clear_queue": not registration_open,
            "reason": (
                "管理后台开启登记排队"
                if registration_open
                else "管理后台关闭登记排队"
            ),
        }

    def management_machine_status_body(self, snapshot, request_id):
        machine = snapshot["machines"]["A"]
        return {
            "request_id": request_id,
            "expected_queue_id": snapshot["queue_id"],
            "expected_machine_configuration_revision": snapshot[
                "machine_configuration_revision"
            ],
            "machine_id": "A",
            "expected_machine_stable_id": machine["stable_id"],
            "expected_operational": True,
            "operational": False,
            "stop_reason": "OTHER",
            "stop_reason_detail": "临时检修",
            "reason": "管理后台停止机台",
        }

    def test_management_sensitive_settings_require_takeover_and_logs_use_management_auth(self):
        snapshot, terminal_headers = self.management_control_snapshot(bound=False)
        snapshot["recent_events"] = [
            self.event(
                "00000000-0000-0000-0000-000000000980",
                "REGISTRATION_UPDATED",
                1_000_100,
            )
        ]
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=terminal_headers
            ).status_code,
        )

        settings = self.client.patch(
            "/api/queue-management/terminal-settings",
            json=self.management_terminal_settings_body(
                snapshot, "00000000-0000-0000-0000-000000000981"
            ),
            headers=self.management_headers,
        )
        registration = self.client.patch(
            "/api/queue-management/registration-availability",
            json=self.management_registration_availability_body(
                snapshot,
                "00000000-0000-0000-0000-000000000982",
                registration_open=False,
            ),
            headers=self.management_headers,
        )
        machine = self.client.patch(
            "/api/queue-management/machine-status",
            json=self.management_machine_status_body(
                snapshot, "00000000-0000-0000-0000-000000000983"
            ),
            headers=self.management_headers,
        )
        for response in (settings, registration, machine):
            self.assertEqual(409, response.status_code)
            self.assertIn("接管", response.get_json()["error"])

        self.assertEqual(
            401,
            self.client.get("/api/queue-management/logs").status_code,
        )
        self.assertEqual(
            401,
            self.client.get(
                "/api/queue-management/logs", headers=self.bot_headers
            ).status_code,
        )
        logs = self.client.get(
            "/api/queue-management/logs", headers=self.management_headers
        )
        self.assertEqual(200, logs.status_code)
        self.assertEqual("REGISTRATION_UPDATED", logs.get_json()["logs"][0]["type"])

    def test_management_registration_availability_is_confirmed_context_bound_and_idempotent(self):
        snapshot, terminal_headers = self.management_control_snapshot(
            with_registration=True
        )
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=terminal_headers
            ).status_code,
        )
        body = self.management_registration_availability_body(
            snapshot,
            "00000000-0000-0000-0000-000000000984",
            registration_open=False,
        )

        duplicate_ids = copy.deepcopy(body)
        duplicate_ids["expected_registration_ids"].append(
            duplicate_ids["expected_registration_ids"][0]
        )
        self.assertEqual(
            400,
            self.client.patch(
                "/api/queue-management/registration-availability",
                json=duplicate_ids,
                headers=self.management_headers,
            ).status_code,
        )
        missing_confirmation = copy.deepcopy(body)
        missing_confirmation["confirm_clear_queue"] = False
        self.assertEqual(
            400,
            self.client.patch(
                "/api/queue-management/registration-availability",
                json=missing_confirmation,
                headers=self.management_headers,
            ).status_code,
        )
        stale_context = copy.deepcopy(body)
        stale_context["expected_queue_revision"] += 1
        self.assertEqual(
            409,
            self.client.patch(
                "/api/queue-management/registration-availability",
                json=stale_context,
                headers=self.management_headers,
            ).status_code,
        )

        created = self.client.patch(
            "/api/queue-management/registration-availability",
            json=body,
            headers=self.management_headers,
        )
        repeated = self.client.patch(
            "/api/queue-management/registration-availability",
            json=body,
            headers=self.management_headers,
        )
        self.assertEqual(202, created.status_code)
        self.assertEqual(200, repeated.status_code)
        payload = created.get_json()["payload"]
        self.assertEqual("MANAGEMENT_APP", payload["operation_source"])
        self.assertTrue(payload["confirm_clear_queue"])
        self.assertEqual(
            created.get_json()["command_id"], repeated.get_json()["command_id"]
        )

    def test_management_terminal_settings_validate_layout_revision_and_risk(self):
        snapshot, terminal_headers = self.management_control_snapshot()
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=terminal_headers
            ).status_code,
        )
        body = self.management_terminal_settings_body(
            snapshot, "00000000-0000-0000-0000-000000000985"
        )

        malformed = copy.deepcopy(body)
        malformed["machines"]["B"] = malformed["machines"].pop("A")
        self.assertEqual(
            400,
            self.client.patch(
                "/api/queue-management/terminal-settings",
                json=malformed,
                headers=self.management_headers,
            ).status_code,
        )
        stale_revision = copy.deepcopy(body)
        stale_revision["expected_settings_revision"] += 1
        self.assertEqual(
            409,
            self.client.patch(
                "/api/queue-management/terminal-settings",
                json=stale_revision,
                headers=self.management_headers,
            ).status_code,
        )
        risk_sensitive = copy.deepcopy(body)
        risk_sensitive["machines"]["A"]["configuration"]["capacity"] = 1
        self.assertEqual(
            409,
            self.client.patch(
                "/api/queue-management/terminal-settings",
                json=risk_sensitive,
                headers=self.management_headers,
            ).status_code,
        )

        created = self.client.patch(
            "/api/queue-management/terminal-settings",
            json=body,
            headers=self.management_headers,
        )
        repeated = self.client.patch(
            "/api/queue-management/terminal-settings",
            json=body,
            headers=self.management_headers,
        )
        self.assertEqual(202, created.status_code)
        self.assertEqual(200, repeated.status_code)
        self.assertEqual(4, created.get_json()["payload"]["next_settings_revision"])
        self.assertEqual(
            created.get_json()["command_id"], repeated.get_json()["command_id"]
        )

    def test_management_machine_status_validates_identity_detail_and_idempotency(self):
        snapshot, terminal_headers = self.management_control_snapshot()
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=terminal_headers
            ).status_code,
        )
        body = self.management_machine_status_body(
            snapshot, "00000000-0000-0000-0000-000000000986"
        )

        overlong_detail = copy.deepcopy(body)
        overlong_detail["stop_reason_detail"] = "字" * 41
        self.assertEqual(
            400,
            self.client.patch(
                "/api/queue-management/machine-status",
                json=overlong_detail,
                headers=self.management_headers,
            ).status_code,
        )
        changed_identity = copy.deepcopy(body)
        changed_identity["expected_machine_stable_id"] = "f" * 32
        self.assertEqual(
            409,
            self.client.patch(
                "/api/queue-management/machine-status",
                json=changed_identity,
                headers=self.management_headers,
            ).status_code,
        )

        created = self.client.patch(
            "/api/queue-management/machine-status",
            json=body,
            headers=self.management_headers,
        )
        repeated = self.client.patch(
            "/api/queue-management/machine-status",
            json=body,
            headers=self.management_headers,
        )
        self.assertEqual(202, created.status_code)
        self.assertEqual(200, repeated.status_code)
        payload = created.get_json()["payload"]
        self.assertEqual("OTHER", payload["stop_reason"])
        self.assertEqual("临时检修", payload["stop_reason_detail"])
        self.assertEqual(
            created.get_json()["command_id"], repeated.get_json()["command_id"]
        )

    def test_management_overview_requires_its_own_token_and_contains_private_queue_data(self):
        snapshot, terminal_headers = self.management_schema_eight_snapshot(
            with_registration=True,
            pending_check_in=True,
        )
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=terminal_headers
            ).status_code,
        )
        missing = self.client.get("/api/queue-management/overview")
        wrong = self.client.get(
            "/api/queue-management/overview",
            headers={"Authorization": "Bearer " + "x" * 32},
        )
        overview = self.client.get(
            "/api/queue-management/overview", headers=self.management_headers
        )

        self.assertEqual(401, missing.status_code)
        self.assertEqual(401, wrong.status_code)
        self.assertEqual(200, overview.status_code)
        payload = overview.get_json()
        self.assertEqual(snapshot["queue_id"], payload["queue"]["queue_id"])
        self.assertEqual(self.profile_id, payload["profiles"][0]["profile_id"])
        registration = payload["queue"]["machines"][0]["waiting_positions"][0][
            "registrations"
        ][0]
        self.assertEqual("a" * 24, registration["registration_id"])
        self.assertTrue(registration["online_registration_pending_check_in"])
        self.assertTrue(payload["capabilities"]["PROFILE_EDIT_ALL"])

    def test_management_check_in_command_is_idempotent_and_terminal_scoped(self):
        snapshot, terminal_headers = self.management_schema_eight_snapshot(
            with_registration=True,
            pending_check_in=True,
        )
        self.client.post("/api/queue-status", json=snapshot, headers=terminal_headers)
        body = {
            "request_id": "00000000-0000-0000-0000-000000000971",
            "operation": "CHECK_IN",
            "registration_id": "a" * 24,
            "expected_queue_id": snapshot["queue_id"],
            "expected_machine_id": "A",
            "expected_position": "WAITING",
            "expected_pending_check_in": True,
            "expected_machine_configuration_revision": 1,
        }
        created = self.client.post(
            "/api/queue-management/commands",
            json=body,
            headers=self.management_headers,
        )
        repeated = self.client.post(
            "/api/queue-management/commands",
            json=body,
            headers=self.management_headers,
        )
        self.assertEqual(202, created.status_code)
        self.assertEqual(200, repeated.status_code)
        self.assertEqual(
            "MANAGEMENT_APP", created.get_json()["payload"]["operation_source"]
        )
        self.assertEqual(
            created.get_json()["command_id"], repeated.get_json()["command_id"]
        )

    def test_management_queue_command_can_edit_any_profile_registration(self):
        snapshot, terminal_headers = self.management_schema_eight_snapshot(
            with_registration=True,
            pending_check_in=False,
        )
        self.client.post("/api/queue-status", json=snapshot, headers=terminal_headers)
        response = self.client.post(
            "/api/queue-management/queue-commands",
            json={
                "request_id": "00000000-0000-0000-0000-000000000974",
                "profile_id": self.profile_id,
                "operation": "LEAVE_QUEUE",
                "expected_queue_id": snapshot["queue_id"],
                "expected_registration_id": "a" * 24,
                "expected_machine_id": "A",
                "expected_position": "WAITING",
                "expected_fixed_pair_id": None,
                "expected_absence_status": "NONE",
                "expected_temporary_away_skipped_turns": 0,
                "expected_pending_check_in": False,
                "expected_machine_configuration_revision": 1,
                "expected_machine_stable_id": "00000000000000000000000000000001",
            },
            headers=self.management_headers,
        )
        self.assertEqual(202, response.status_code)
        self.assertEqual(
            "MANAGEMENT_APP", response.get_json()["payload"]["operation_source"]
        )
        self.assertEqual("", response.get_json()["payload"]["actor_qq"])

    def test_management_can_neither_defer_nor_leave_for_the_playing_position(self):
        # The same rule as for players: a registration in the playing position has to be
        # taken back to the front of the waiting order first.
        snapshot, terminal_headers = self.management_schema_eight_snapshot(
            with_registration=True,
            pending_check_in=False,
        )
        machine = snapshot["machines"]["A"]
        registration = machine["waiting_positions"][0]["registrations"][0]
        machine["playing"] = [registration]
        machine["playing_started_at"] = 900_000
        machine["waiting_positions"] = []
        machine["waiting_position_count"] = 0
        published = self.client.post(
            "/api/queue-status", json=snapshot, headers=terminal_headers
        )
        refusals = {
            "DEFER_ONE_ROUND": "处于游玩位置的登记不能暂缓一次，请先在现场终端撤回至等待顺序。",
            "TEMPORARILY_LEAVE": "处于游玩位置的登记不能暂时离开，请先在现场终端撤回至等待顺序。",
        }

        self.assertEqual(204, published.status_code, published.get_json())
        for index, (operation, refusal) in enumerate(refusals.items()):
            with self.subTest(operation=operation):
                by_profile = self.client.post(
                    "/api/queue-management/queue-commands",
                    json={
                        "request_id": f"00000000-0000-0000-0000-00000000098{index}",
                        "profile_id": self.profile_id,
                        "operation": operation,
                        "expected_queue_id": snapshot["queue_id"],
                        "expected_registration_id": registration["registration_id"],
                        "expected_machine_id": "A",
                        "expected_position": "PLAYING",
                        "expected_fixed_pair_id": None,
                        "expected_absence_status": "NONE",
                        "expected_temporary_away_skipped_turns": 0,
                        "expected_pending_check_in": False,
                        "expected_machine_configuration_revision": 1,
                        "expected_machine_stable_id": machine["stable_id"],
                    },
                    headers=self.management_headers,
                )
                by_registration = self.client.post(
                    "/api/queue-management/terminal-actions",
                    json=self.management_terminal_action_body(
                        snapshot,
                        20 + index,
                        action=operation,
                        registration_ids=[registration["registration_id"]],
                    ),
                    headers=self.management_headers,
                )
                self.assertEqual(409, by_profile.status_code, by_profile.get_json())
                self.assertEqual(refusal, by_profile.get_json()["error"])
                self.assertEqual(
                    409, by_registration.status_code, by_registration.get_json()
                )
                self.assertEqual(refusal, by_registration.get_json()["error"])
        self.assertEqual(
            [],
            self.client.get(
                "/api/queue-terminal/commands", headers=terminal_headers
            ).get_json()["commands"],
        )

    def test_management_can_edit_temporary_registration_and_reorder_waiting_queue(self):
        snapshot, terminal_headers = self.management_schema_eight_snapshot(
            with_registration=True,
            pending_check_in=False,
        )
        temporary = self.registration("c" * 24, "临时登记")
        temporary["registration_type"] = "TEMPORARY"
        snapshot["machines"]["A"]["waiting_positions"][0]["registrations"].append(
            temporary
        )
        snapshot["machines"]["A"]["registration_count"] = 2
        self.client.post("/api/queue-status", json=snapshot, headers=terminal_headers)

        leave = self.client.post(
            "/api/queue-management/queue-commands",
            json={
                "request_id": "00000000-0000-0000-0000-000000000975",
                "operation": "LEAVE_QUEUE",
                "expected_queue_id": snapshot["queue_id"],
                "expected_registration_id": "c" * 24,
                "expected_machine_id": "A",
                "expected_position": "WAITING",
                "expected_fixed_pair_id": None,
                "expected_absence_status": "NONE",
                "expected_temporary_away_skipped_turns": 0,
                "expected_pending_check_in": False,
                "expected_machine_configuration_revision": 1,
                "expected_machine_stable_id": "00000000000000000000000000000001",
            },
            headers=self.management_headers,
        )
        self.assertEqual(202, leave.status_code)
        self.assertNotIn("profile_id", leave.get_json()["payload"])

        reorder_body = {
            "request_id": "00000000-0000-0000-0000-000000000976",
            "expected_queue_id": snapshot["queue_id"],
            "machine_id": "A",
            "expected_machine_stable_id": "00000000000000000000000000000001",
            "expected_machine_configuration_revision": 1,
            "expected_registration_order": ["a" * 24, "c" * 24],
            "registration_order": ["c" * 24, "a" * 24],
        }
        reordered = self.client.post(
            "/api/queue-management/queue-reorders",
            json=reorder_body,
            headers=self.management_headers,
        )
        repeated = self.client.post(
            "/api/queue-management/queue-reorders",
            json=reorder_body,
            headers=self.management_headers,
        )
        self.assertEqual(202, reordered.status_code)
        self.assertEqual(200, repeated.status_code)
        self.assertEqual(
            "REORDER_QUEUE", reordered.get_json()["payload"]["operation"]
        )

    def test_management_can_create_registration_without_opening_mobile_registration(self):
        snapshot, terminal_headers = self.management_schema_eight_snapshot()
        self.client.post("/api/queue-status", json=snapshot, headers=terminal_headers)
        response = self.client.post(
            "/api/queue-management/registrations",
            json={
                "request_id": "00000000-0000-0000-0000-000000000972",
                "profile_id": self.profile_id,
                "expected_queue_id": snapshot["queue_id"],
                "expected_machine_id": "A",
                "expected_machine_stable_id": "00000000000000000000000000000001",
                "expected_machine_configuration_revision": 1,
            },
            headers=self.management_headers,
        )
        self.assertEqual(202, response.status_code)
        payload = response.get_json()["payload"]
        self.assertEqual("CREATE_REGISTRATION", payload["operation"])
        self.assertEqual("MANAGEMENT_APP", payload["operation_source"])
        self.assertEqual(self.profile_id, payload["profile_id"])
        self.assertEqual("A", payload["machine_id"])

    def test_management_profile_update_and_password_reset_are_separate_from_bot_auth(self):
        snapshot, terminal_headers = self.management_schema_eight_snapshot()
        self.client.post("/api/queue-status", json=snapshot, headers=terminal_headers)
        update = self.client.patch(
            f"/api/queue-management/profiles/{self.profile_id}",
            json={
                "request_id": "00000000-0000-0000-0000-000000000973",
                "nickname": "管理昵称",
                "terminal_editing_allowed": False,
                "visited_venues_public": False,
            },
            headers=self.management_headers,
        )
        self.assertEqual(202, update.status_code)
        self.assertEqual(
            "MANAGEMENT_APP", update.get_json()["payload"]["operation_source"]
        )
        self.assertFalse(update.get_json()["payload"]["terminal_editing_allowed"])

        binding = self.client.post(
            "/api/queue-terminal/player-bindings",
            json={"profile_id": self.profile_id},
            headers=terminal_headers,
        )
        self.assertEqual(201, binding.status_code)
        self.assertEqual(
            201,
            self.client.post(
                f"/api/player-account/bindings/{binding.get_json()['binding_token']}/complete",
                json={"password": "initial-password"},
            ).status_code,
        )
        password = self.client.post(
            f"/api/queue-management/profiles/{self.profile_id}/password",
            json={
                "new_password": "management-password",
                "new_password_confirmation": "management-password",
            },
            headers=self.management_headers,
        )
        self.assertEqual(200, password.status_code)
        self.assertIn("已修改", password.get_json()["detail"])

    def test_management_profile_delete_waits_for_terminal_and_cleans_private_rows(self):
        snapshot, terminal_headers = self.management_control_snapshot()
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=terminal_headers
            ).status_code,
        )
        binding = self.client.post(
            "/api/queue-terminal/player-bindings",
            json={"profile_id": self.profile_id},
            headers=terminal_headers,
        )
        self.assertEqual(201, binding.status_code)
        self.assertEqual(
            201,
            self.client.post(
                f"/api/player-account/bindings/{binding.get_json()['binding_token']}/complete",
                json={"password": "delete-password"},
            ).status_code,
        )
        overview = self.client.get(
            "/api/queue-management/overview", headers=self.management_headers
        ).get_json()
        profile = next(
            value for value in overview["profiles"] if value["profile_id"] == self.profile_id
        )
        request_body = {
            "request_id": "00000000-0000-0000-0000-000000000977",
            "expected_profile_revision": profile["profile_revision"],
            "expected_updated_at": profile["updated_at"],
            "reason": "测试删除玩家资料",
            "confirm": True,
        }
        created = self.client.delete(
            f"/api/queue-management/profiles/{self.profile_id}",
            json=request_body,
            headers=self.management_headers,
        )
        repeated = self.client.delete(
            f"/api/queue-management/profiles/{self.profile_id}",
            json=request_body,
            headers=self.management_headers,
        )
        self.assertEqual(202, created.status_code)
        self.assertEqual(200, repeated.status_code)
        self.assertEqual("DELETE_PLAYER_PROFILE", created.get_json()["type"])
        self.assertEqual(
            created.get_json()["command_id"], repeated.get_json()["command_id"]
        )

        commands = self.client.get(
            "/api/queue-terminal/commands", headers=terminal_headers
        ).get_json()["commands"]
        self.assertEqual(
            [request_body["request_id"]],
            [command["command_id"] for command in commands],
        )
        completed = self.client.post(
            f"/api/queue-terminal/commands/{request_body['request_id']}/result",
            json={"status": "APPLIED", "detail": "终端已删除玩家资料。"},
            headers=terminal_headers,
        )
        self.assertEqual(200, completed.status_code)

        connection = sqlite3.connect(self.database_path)
        try:
            self.assertIsNone(
                connection.execute(
                    "SELECT 1 FROM player_profile WHERE profile_id = ?",
                    (self.profile_id,),
                ).fetchone()
            )
            self.assertIsNone(
                connection.execute(
                    "SELECT 1 FROM player_account WHERE profile_id = ?",
                    (self.profile_id,),
                ).fetchone()
            )
            self.assertIsNotNone(
                connection.execute(
                    "SELECT 1 FROM deleted_player_profile WHERE profile_id = ?",
                    (self.profile_id,),
                ).fetchone()
            )
        finally:
            connection.close()

        stale = copy.deepcopy(snapshot)
        stale["revision"] += 1
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=stale, headers=terminal_headers
            ).status_code,
        )
        refreshed = self.client.get(
            "/api/queue-management/overview", headers=self.management_headers
        ).get_json()
        self.assertNotIn(
            self.profile_id,
            {profile["profile_id"] for profile in refreshed["profiles"]},
        )

    def test_management_profile_delete_rejects_a_profile_with_a_registration(self):
        snapshot, terminal_headers = self.management_control_snapshot(
            with_registration=True
        )
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=terminal_headers
            ).status_code,
        )
        rejected = self.client.delete(
            f"/api/queue-management/profiles/{self.profile_id}",
            json={
                "request_id": "00000000-0000-0000-0000-000000000978",
                "expected_profile_revision": 3,
                "expected_updated_at": 950000,
                "confirm": True,
            },
            headers=self.management_headers,
        )
        self.assertEqual(409, rejected.status_code)
        self.assertIn("仍关联当前登记", rejected.get_json()["error"])

    def test_bot_confirmation_context_rejects_a_changed_registration_state(self):
        snapshot = self.remote_ready_snapshot(with_registration=True)
        self.client.post("/api/queue-status", json=snapshot, headers=self.headers)

        response = self.client.post(
            "/api/queue-bot/queue-commands",
            json={
                "request_id": "00000000-0000-0000-0000-000000000406",
                "actor_qq": "12345678",
                "operation": "LEAVE_QUEUE",
                "expected_queue_id": snapshot["queue_id"],
                "expected_registration_id": "a" * 24,
                "expected_machine_id": "A",
                "expected_position": "WAITING",
                "expected_fixed_pair_id": None,
                "expected_absence_status": "DEFER_ONE_ROUND",
                "expected_temporary_away_skipped_turns": 0,
                "expected_pending_check_in": False,
            },
            headers=self.bot_headers,
        )

        self.assertEqual(409, response.status_code)
        self.assertIn("确认期间登记状态已经变化", response.get_json()["error"])

    def test_bot_confirmation_context_becomes_the_terminal_command_precondition(self):
        snapshot = self.remote_ready_snapshot(with_registration=True)
        self.client.post("/api/queue-status", json=snapshot, headers=self.headers)

        response = self.client.post(
            "/api/queue-bot/queue-commands",
            json={
                "request_id": "00000000-0000-0000-0000-000000000407",
                "actor_qq": "12345678",
                "operation": "LEAVE_QUEUE",
                "expected_queue_id": snapshot["queue_id"],
                "expected_registration_id": "a" * 24,
                "expected_machine_id": "A",
                "expected_position": "WAITING",
                "expected_fixed_pair_id": None,
                "expected_absence_status": "NONE",
                "expected_temporary_away_skipped_turns": 0,
                "expected_pending_check_in": False,
            },
            headers=self.bot_headers,
        )
        commands = self.client.get(
            "/api/queue-terminal/commands", headers=self.headers
        ).get_json()["commands"]

        self.assertEqual(202, response.status_code)
        self.assertEqual("a" * 24, commands[0]["payload"]["registration_id"])
        self.assertEqual("WAITING", commands[0]["payload"]["expected_position"])
        self.assertEqual("NONE", commands[0]["payload"]["expected_absence_status"])

    def test_playing_registration_can_neither_defer_nor_leave_temporarily(self):
        # Deferring and leaving temporarily are for registrations that wait. One in the
        # playing position has to be taken back to the front of the waiting order on the
        # terminal first; leaving the queue stays possible.
        snapshot = self.remote_ready_snapshot(with_registration=True)
        registration = snapshot["machines"]["A"]["waiting_positions"][0][
            "registrations"
        ][0]
        snapshot["machines"]["A"]["playing"] = [registration]
        snapshot["machines"]["A"]["playing_started_at"] = 900_000
        snapshot["machines"]["A"]["waiting_positions"] = []
        snapshot["machines"]["A"]["waiting_position_count"] = 0
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )
        expected_context = {
            "expected_queue_id": snapshot["queue_id"],
            "expected_registration_id": registration["registration_id"],
            "expected_machine_id": "A",
            "expected_position": "PLAYING",
            "expected_fixed_pair_id": None,
            "expected_absence_status": "NONE",
            "expected_temporary_away_skipped_turns": 0,
            "expected_pending_check_in": False,
        }

        for request_id, operation, refusal in (
            (
                "00000000-0000-0000-0000-000000000408",
                "DEFER_ONE_ROUND",
                "处于游玩位置的登记不能暂缓一次，请先在现场终端撤回至等待顺序。",
            ),
            (
                "00000000-0000-0000-0000-000000000409",
                "TEMPORARILY_LEAVE",
                "处于游玩位置的登记不能暂时离开，请先在现场终端撤回至等待顺序。",
            ),
        ):
            with self.subTest(operation=operation):
                response = self.client.post(
                    "/api/queue-bot/queue-commands",
                    json={
                        "request_id": request_id,
                        "actor_qq": "12345678",
                        "operation": operation,
                        **expected_context,
                    },
                    headers=self.bot_headers,
                )
                self.assertEqual(409, response.status_code, response.get_json())
                self.assertEqual(refusal, response.get_json()["error"])
        self.assertEqual(
            [],
            self.client.get(
                "/api/queue-terminal/commands", headers=self.headers
            ).get_json()["commands"],
        )

        transfer = self.client.post(
            "/api/queue-bot/queue-commands",
            json={
                "request_id": "00000000-0000-0000-0000-000000000410",
                "actor_qq": "12345678",
                "operation": "TRANSFER_MACHINE",
                "target_machine_id": "B",
                **expected_context,
            },
            headers=self.bot_headers,
        )
        self.assertEqual(409, transfer.status_code)
        self.assertIn("游玩位置", transfer.get_json()["error"])

    # 轮空一次 and the limits on postponing (maintainer, 2026-11-25).

    def postponement_snapshot(self, revision=4, pending_check_in=False, **fields):
        """Remote-ready snapshot whose only registration waits at machine A with [fields]."""
        snapshot = self.remote_ready_snapshot(
            revision=revision, with_registration=True, pending_check_in=pending_check_in
        )
        snapshot["machines"]["A"]["waiting_positions"][0]["registrations"][0].update(fields)
        return snapshot

    def bot_confirmation_context(self, **fields):
        return {
            "expected_queue_id": self.postponement_snapshot()["queue_id"],
            "expected_registration_id": "a" * 24,
            "expected_machine_id": "A",
            "expected_position": "WAITING",
            "expected_fixed_pair_id": None,
            "expected_absence_status": "NONE",
            "expected_temporary_away_skipped_turns": 0,
            "expected_pending_check_in": False,
            **fields,
        }

    def stored_waiting_registration(self):
        return self.client.get("/api/queue-status").get_json()["machines"]["A"][
            "waiting_positions"
        ][0]["registrations"][0]

    def test_snapshot_keeps_skip_one_turn_and_the_postponement_limits(self):
        # 将轮空一次 counts its turn together with 暂时离开, so the count goes with it.
        self.publish(
            self.postponement_snapshot(
                skips_one_turn=True,
                temporary_away_skipped_turns=2,
                defer_one_round_used=True,
                online_registration_not_played=True,
            )
        )
        stored = self.stored_waiting_registration()
        self.assertIs(True, stored["skips_one_turn"])
        self.assertIs(False, stored["deferred_once"])
        self.assertIs(False, stored["temporarily_away"])
        self.assertEqual(2, stored["temporary_away_skipped_turns"])
        self.assertIs(True, stored["defer_one_round_used"])
        self.assertIs(True, stored["online_registration_not_played"])

        # Older terminals leave the three fields out or send null.
        older = self.postponement_snapshot(revision=5, skips_one_turn=None)
        self.publish(older)
        stored = self.stored_waiting_registration()
        self.assertIs(False, stored["skips_one_turn"])
        self.assertIs(False, stored["defer_one_round_used"])
        self.assertIs(False, stored["online_registration_not_played"])

        rejected = {
            "skips_one_turn 不是布尔值": (
                self.postponement_snapshot(revision=6, skips_one_turn="true"),
                "skips_one_turn 必须是布尔值。",
            ),
            "defer_one_round_used 不是布尔值": (
                self.postponement_snapshot(revision=7, defer_one_round_used=1),
                "defer_one_round_used 必须是布尔值。",
            ),
            "online_registration_not_played 不是布尔值": (
                self.postponement_snapshot(revision=8, online_registration_not_played=0),
                "online_registration_not_played 必须是布尔值。",
            ),
            "轮空次数不属于暂时离开或将轮空一次": (
                self.postponement_snapshot(revision=9, temporary_away_skipped_turns=1),
                "只有暂时离开或将轮空一次的登记才能包含轮空次数。",
            ),
            "将轮空一次与将暂缓一次": (
                self.postponement_snapshot(
                    revision=10, skips_one_turn=True, deferred_once=True
                ),
                "登记最多只能处于将暂缓一次、将轮空一次和暂时离开中的一种状态。",
            ),
            "将轮空一次与暂时离开": (
                self.postponement_snapshot(
                    revision=11, skips_one_turn=True, temporarily_away=True
                ),
                "登记最多只能处于将暂缓一次、将轮空一次和暂时离开中的一种状态。",
            ),
            "待签到时将轮空一次": (
                self.postponement_snapshot(
                    revision=12, pending_check_in=True, skips_one_turn=True
                ),
                "待签到的线上登记不能处于将暂缓一次、将轮空一次或暂时离开状态。",
            ),
        }
        for label, (snapshot, error) in rejected.items():
            with self.subTest(label):
                response = self.client.post(
                    "/api/queue-status", json=snapshot, headers=self.headers
                )
                self.assertEqual(400, response.status_code, response.get_json())
                self.assertEqual(error, response.get_json()["error"])

    def test_fixed_pair_shares_skip_one_turn(self):
        def pair_snapshot(revision, second_skips):
            snapshot = self.remote_ready_snapshot(revision=revision)
            first = self.registration("a" * 24, "公开昵称")
            second = self.registration("c" * 24, "固定搭档")
            for registration in (first, second):
                registration.update(
                    preference="OPEN_TO_JOIN", fixed_pair=True, fixed_pair_id="f" * 24
                )
            first["skips_one_turn"] = True
            second["skips_one_turn"] = second_skips
            snapshot["machines"]["A"]["waiting_positions"] = [
                {
                    "index": 1,
                    "position_id": "b" * 24,
                    "fixed_pair": True,
                    "estimated_wait_minutes": 0,
                    "registrations": [first, second],
                }
            ]
            snapshot["machines"]["A"]["registration_count"] = 2
            snapshot["machines"]["A"]["waiting_position_count"] = 1
            return snapshot

        self.publish(pair_snapshot(4, True))
        mismatched = self.client.post(
            "/api/queue-status", json=pair_snapshot(5, False), headers=self.headers
        )
        self.assertEqual(400, mismatched.status_code)
        self.assertIn("固定组合可用状态不一致", mismatched.get_json()["error"])

    def test_skip_one_turn_reaches_player_account_bot_and_management(self):
        snapshot = self.management_control_snapshot(with_registration=True)[0]
        snapshot["machines"]["A"]["waiting_positions"][0]["registrations"][0].update(
            skips_one_turn=True,
            temporary_away_skipped_turns=1,
            defer_one_round_used=True,
            online_registration_not_played=True,
        )
        response = self.client.post(
            "/api/queue-status",
            json=snapshot,
            headers=self.schema_eight_terminal_headers(),
        )
        self.assertEqual(204, response.status_code, response.get_json())
        binding = self.client.post(
            "/api/queue-terminal/player-bindings",
            json={"profile_id": self.profile_id},
            headers=self.headers,
        )
        completed = self.client.post(
            f"/api/player-account/bindings/{binding.get_json()['binding_token']}/complete",
            json={"password": "first-password"},
        )
        self.assertEqual(201, completed.status_code, completed.get_json())

        account = self.client.get("/api/player-account/queue").get_json()[
            "registrations"
        ][0]
        bot_player = self.client.post(
            "/api/queue-bot/players", json={"qq": "12345678"}, headers=self.bot_headers
        ).get_json()["players"][0]
        overview = self.client.get(
            "/api/queue-management/overview", headers=self.management_headers
        ).get_json()
        machine_a = next(
            machine for machine in overview["queue"]["machines"] if machine["id"] == "A"
        )
        managed = machine_a["waiting_positions"][0]["registrations"][0]

        for label, registration in (
            ("account", account),
            ("bot", bot_player),
            ("management", managed),
        ):
            with self.subTest(label):
                self.assertIs(True, registration["skips_one_turn"])
                self.assertIs(False, registration["deferred_once"])
                self.assertIs(False, registration["temporarily_away"])
                self.assertEqual(1, registration["temporary_away_skipped_turns"])
                self.assertIs(True, registration["defer_one_round_used"])
                self.assertIs(True, registration["online_registration_not_played"])

    def test_skip_one_turn_is_forwarded_like_defer_one_round(self):
        self.publish(self.postponement_snapshot())

        skip = self.bot_operation(80, "SKIP_ONE_TURN", **self.bot_confirmation_context())
        commands = self.pending_command_payloads()

        self.assertEqual(202, skip.status_code, skip.get_json())
        self.assertEqual(["SKIP_ONE_TURN"], [command["operation"] for command in commands])
        self.assertEqual("a" * 24, commands[0]["registration_id"])
        self.assertEqual("NONE", commands[0]["expected_absence_status"])

    def test_cancel_skip_one_turn_confirms_the_skip_one_turn_state(self):
        self.publish(
            self.postponement_snapshot(skips_one_turn=True, temporary_away_skipped_turns=1)
        )

        stale = self.bot_operation(
            81,
            "CANCEL_SKIP_ONE_TURN",
            **self.bot_confirmation_context(expected_temporary_away_skipped_turns=1),
        )
        cancel = self.bot_operation(
            82,
            "CANCEL_SKIP_ONE_TURN",
            **self.bot_confirmation_context(
                expected_absence_status="SKIP_ONE_TURN",
                expected_temporary_away_skipped_turns=1,
            ),
        )
        commands = self.pending_command_payloads()

        self.assertEqual(409, stale.status_code, stale.get_json())
        self.assertIn("确认期间登记状态已经变化", stale.get_json()["error"])
        self.assertEqual(202, cancel.status_code, cancel.get_json())
        self.assertEqual(
            ["CANCEL_SKIP_ONE_TURN"], [command["operation"] for command in commands]
        )
        self.assertEqual("SKIP_ONE_TURN", commands[0]["expected_absence_status"])
        self.assertEqual(1, commands[0]["expected_temporary_away_skipped_turns"])

    def test_skip_one_turn_is_refused_for_the_playing_position(self):
        snapshot = self.remote_ready_snapshot(with_registration=True)
        machine = snapshot["machines"]["A"]
        registration = machine["waiting_positions"][0]["registrations"][0]
        machine["playing"] = [registration]
        machine["playing_started_at"] = 900_000
        machine["waiting_positions"] = []
        machine["waiting_position_count"] = 0
        self.publish(snapshot)

        response = self.bot_operation(
            83,
            "SKIP_ONE_TURN",
            **self.bot_confirmation_context(expected_position="PLAYING"),
        )

        self.assertEqual(409, response.status_code, response.get_json())
        self.assertEqual(
            "处于游玩位置的登记不能轮空一次，请先在现场终端撤回至等待顺序。",
            response.get_json()["error"],
        )
        self.assertEqual([], self.pending_command_payloads())

    def test_used_defer_one_round_is_refused_until_the_registration_has_played(self):
        self.publish(self.postponement_snapshot(defer_one_round_used=True))

        defer = self.bot_operation(84, "DEFER_ONE_ROUND")
        self.assertEqual(409, defer.status_code, defer.get_json())
        self.assertEqual(
            "这份登记已暂缓过一次，游玩一轮之后才能再次暂缓。",
            defer.get_json()["error"],
        )
        self.assertEqual([], self.pending_command_payloads())

        # The other two remain.
        skip = self.bot_operation(85, "SKIP_ONE_TURN")
        self.assertEqual(202, skip.status_code, skip.get_json())

    def test_checked_in_online_registration_cannot_be_postponed_before_playing(self):
        self.publish(self.postponement_snapshot(online_registration_not_played=True))

        for request_number, operation in (
            (86, "DEFER_ONE_ROUND"),
            (87, "SKIP_ONE_TURN"),
            (88, "TEMPORARILY_LEAVE"),
        ):
            with self.subTest(operation):
                response = self.bot_operation(request_number, operation)
                self.assertEqual(409, response.status_code, response.get_json())
                self.assertEqual(
                    "已签到的线上登记在游玩一轮之前不能延后。",
                    response.get_json()["error"],
                )
        self.assertEqual([], self.pending_command_payloads())

        # Leaving the queue stays possible.
        leave = self.bot_operation(89, "LEAVE_QUEUE")
        self.assertEqual(202, leave.status_code, leave.get_json())

    def test_management_app_may_postpone_despite_the_player_limits(self):
        snapshot, terminal_headers = self.management_schema_eight_snapshot(
            with_registration=True
        )
        registration = snapshot["machines"]["A"]["waiting_positions"][0]["registrations"][0]
        registration.update(defer_one_round_used=True, online_registration_not_played=True)
        published = self.client.post(
            "/api/queue-status", json=snapshot, headers=terminal_headers
        )
        self.assertEqual(204, published.status_code, published.get_json())

        by_profile = self.client.post(
            "/api/queue-management/queue-commands",
            json={
                "request_id": "00000000-0000-0000-0000-000000000990",
                "profile_id": self.profile_id,
                "operation": "DEFER_ONE_ROUND",
                "expected_queue_id": snapshot["queue_id"],
                "expected_registration_id": registration["registration_id"],
                "expected_machine_id": "A",
                "expected_position": "WAITING",
                "expected_fixed_pair_id": None,
                "expected_absence_status": "NONE",
                "expected_temporary_away_skipped_turns": 0,
                "expected_pending_check_in": False,
                "expected_machine_configuration_revision": 1,
                "expected_machine_stable_id": snapshot["machines"]["A"]["stable_id"],
            },
            headers=self.management_headers,
        )
        by_registration = self.client.post(
            "/api/queue-management/terminal-actions",
            json=self.management_terminal_action_body(
                snapshot,
                30,
                action="SKIP_ONE_TURN",
                registration_ids=[registration["registration_id"]],
            ),
            headers=self.management_headers,
        )

        self.assertEqual(202, by_profile.status_code, by_profile.get_json())
        self.assertEqual("DEFER_ONE_ROUND", by_profile.get_json()["payload"]["operation"])
        self.assertEqual(202, by_registration.status_code, by_registration.get_json())
        self.assertEqual("SKIP_ONE_TURN", by_registration.get_json()["payload"]["action"])

    def test_management_cannot_skip_one_turn_for_the_playing_position(self):
        snapshot, terminal_headers = self.management_schema_eight_snapshot(
            with_registration=True
        )
        machine = snapshot["machines"]["A"]
        registration = machine["waiting_positions"][0]["registrations"][0]
        machine["playing"] = [registration]
        machine["playing_started_at"] = 900_000
        machine["waiting_positions"] = []
        machine["waiting_position_count"] = 0
        published = self.client.post(
            "/api/queue-status", json=snapshot, headers=terminal_headers
        )
        self.assertEqual(204, published.status_code, published.get_json())

        by_registration = self.client.post(
            "/api/queue-management/terminal-actions",
            json=self.management_terminal_action_body(
                snapshot,
                31,
                action="SKIP_ONE_TURN",
                registration_ids=[registration["registration_id"]],
            ),
            headers=self.management_headers,
        )

        self.assertEqual(409, by_registration.status_code, by_registration.get_json())
        self.assertEqual(
            "处于游玩位置的登记不能轮空一次，请先在现场终端撤回至等待顺序。",
            by_registration.get_json()["error"],
        )

    def test_fixed_pair_cancel_temporary_leave_is_forwarded_for_terminal_validation(self):
        snapshot = self.remote_ready_snapshot()
        first = self.registration("a" * 24, "公开昵称")
        second = self.registration("c" * 24, "固定搭档")
        for registration in (first, second):
            registration.update(
                preference="OPEN_TO_JOIN",
                fixed_pair=True,
                fixed_pair_id="f" * 24,
            )
        snapshot["machines"]["A"]["waiting_positions"] = [
            {
                "index": 1,
                "position_id": "b" * 24,
                "fixed_pair": True,
                "estimated_wait_minutes": 0,
                "registrations": [first, second],
            }
        ]
        snapshot["machines"]["A"]["registration_count"] = 2
        snapshot["machines"]["A"]["waiting_position_count"] = 1
        snapshot["private_player_contacts"] = [
            {
                "registration_id": first["registration_id"],
                "profile_id": self.profile_id,
                "qq_number": "12345678",
            }
        ]
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )

        players = self.client.post(
            "/api/queue-bot/players",
            json={"qq": "12345678"},
            headers=self.bot_headers,
        ).get_json()["players"]
        self.assertEqual("f" * 24, players[0]["fixed_pair_id"])

        changed_pair = self.client.post(
            "/api/queue-bot/queue-commands",
            json={
                "request_id": "00000000-0000-0000-0000-000000000423",
                "actor_qq": "12345678",
                "operation": "LEAVE_QUEUE",
                "expected_queue_id": snapshot["queue_id"],
                "expected_registration_id": first["registration_id"],
                "expected_machine_id": "A",
                "expected_position": "WAITING",
                "expected_fixed_pair_id": "e" * 24,
                "expected_absence_status": "NONE",
                "expected_temporary_away_skipped_turns": 0,
                "expected_pending_check_in": False,
            },
            headers=self.bot_headers,
        )
        self.assertEqual(409, changed_pair.status_code)
        self.assertIn("登记状态已经变化", changed_pair.get_json()["error"])

        response = self.client.post(
            "/api/queue-bot/queue-commands",
            json={
                "request_id": "00000000-0000-0000-0000-000000000424",
                "actor_qq": "12345678",
                "operation": "CANCEL_TEMPORARY_LEAVE",
            },
            headers=self.bot_headers,
        )

        self.assertEqual(202, response.status_code)
        command = response.get_json()
        self.assertEqual("CANCEL_TEMPORARY_LEAVE", command["payload"]["operation"])
        self.assertEqual(first["registration_id"], command["payload"]["registration_id"])
        self.assertEqual("WAITING", command["payload"]["expected_position"])
        self.assertEqual("f" * 24, command["payload"]["expected_fixed_pair_id"])
        self.assertEqual("NONE", command["payload"]["expected_absence_status"])
        self.assertEqual(
            0, command["payload"]["expected_temporary_away_skipped_turns"]
        )
        self.assertFalse(command["payload"]["expected_pending_check_in"])

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

    def test_disabling_server_sync_rejects_only_unclaimed_website_work(self):
        enabled = self.remote_ready_snapshot(revision=6)
        second_profile = copy.deepcopy(enabled["private_player_profiles"][0])
        second_profile.update(
            profile_id="00000000-0000-0000-0000-000000000902",
            nickname="第二名玩家",
            qq_number="87654321",
        )
        enabled["private_player_profiles"].append(second_profile)
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=enabled, headers=self.headers
            ).status_code,
        )

        website_command_id = "00000000-0000-0000-0000-000000000461"
        website_command = self.client.post(
            "/api/queue-online/join",
            json={
                "request_id": website_command_id,
                "qq": "12345678",
                "machine_id": "A",
            },
        )
        bot_command_id = "00000000-0000-0000-0000-000000000462"
        bot_command = self.client.patch(
            "/api/queue-bot/profiles/00000000-0000-0000-0000-000000000902",
            json={
                "request_id": bot_command_id,
                "actor_qq": "87654321",
                "nickname": "第二名玩家新昵称",
            },
            headers=self.bot_headers,
        )
        session_id = "00000000-0000-0000-0000-000000000463"
        mobile_session = self.client.post(
            "/api/queue-terminal/mobile-registration-sessions",
            json={
                "request_id": session_id,
                "queue_id": enabled["queue_id"],
                "machine_id": "B",
            },
            headers=self.headers,
        )
        self.assertEqual(202, website_command.status_code)
        self.assertEqual(202, bot_command.status_code)
        self.assertEqual(201, mobile_session.status_code)

        disabled = copy.deepcopy(enabled)
        disabled["revision"] += 1
        disabled["website_remote_enabled"] = False
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=disabled, headers=self.headers
            ).status_code,
        )
        website_result = self.client.get(
            f"/api/queue-online/commands/{website_command_id}"
        ).get_json()
        bot_result = self.client.get(
            f"/api/queue-bot/commands/{bot_command_id}", headers=self.bot_headers
        ).get_json()
        invalidated = self.client.get(
            f"/api/queue-mobile/sessions/{mobile_session.get_json()['session_token']}"
        )
        invalidated_result = self.client.get(
            f"/api/queue-mobile/sessions/{mobile_session.get_json()['session_token']}/result"
        )

        self.assertEqual("REJECTED", website_result["status"])
        self.assertEqual("SYNC_DISABLED", website_result["result_source"])
        self.assertEqual("PENDING", bot_result["status"])
        self.assertEqual(409, invalidated.status_code)
        self.assertEqual("SESSION_INVALIDATED", invalidated.get_json()["code"])
        self.assertEqual(409, invalidated_result.status_code)
        self.assertEqual("SESSION_INVALIDATED", invalidated_result.get_json()["code"])

        reopened = copy.deepcopy(disabled)
        reopened["revision"] += 1
        reopened["website_remote_enabled"] = True
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=reopened, headers=self.headers
            ).status_code,
        )
        still_invalid = self.client.get(
            f"/api/queue-mobile/sessions/{mobile_session.get_json()['session_token']}"
        )
        pending = self.client.get(
            "/api/queue-terminal/commands", headers=self.headers
        ).get_json()["commands"]
        self.assertEqual(409, still_invalid.status_code)
        self.assertEqual([bot_command_id], [item["command_id"] for item in pending])

    def test_disabling_server_sync_leaves_a_claimed_command_to_terminal_result(self):
        enabled = self.remote_ready_snapshot(revision=8)
        self.client.post("/api/queue-status", json=enabled, headers=self.headers)
        command_id = "00000000-0000-0000-0000-000000000464"
        created = self.client.post(
            "/api/queue-online/join",
            json={
                "request_id": command_id,
                "qq": "12345678",
                "machine_id": "A",
            },
        )
        claimed = self.client.get(
            "/api/queue-terminal/commands", headers=self.headers
        ).get_json()["commands"]
        self.assertEqual(202, created.status_code)
        self.assertEqual([command_id], [item["command_id"] for item in claimed])

        disabled = copy.deepcopy(enabled)
        disabled["revision"] += 1
        disabled["website_remote_enabled"] = False
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=disabled, headers=self.headers
            ).status_code,
        )
        pending = self.client.get(
            f"/api/queue-online/commands/{command_id}"
        ).get_json()
        completed = self.client.post(
            f"/api/queue-terminal/commands/{command_id}/result",
            json={
                "status": "REJECTED",
                "detail": "与服务端同步已关闭，这次操作没有执行。",
            },
            headers=self.headers,
        ).get_json()

        self.assertEqual("PENDING", pending["status"])
        self.assertEqual("REJECTED", completed["status"])
        self.assertEqual("TERMINAL", completed["result_source"])

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
            "已有一项操作正在等待终端处理。",
            profile_command.get_json()["error"],
        )

    def test_version_status_uses_real_reports_for_all_three_clients(self):
        snapshot = self.snapshot()
        snapshot["terminal"]["app_version"] = "0.10.0"
        with patch("app.time.time", return_value=1_234):
            published = self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            )
            identity = self.client.post(
                "/api/queue-bot/identity",
                json={
                    "bot_qq": "87654321",
                    "bot_version": "0.4.0",
                    "website_version": "v0.13.2",
                },
                headers=self.bot_headers,
            )
            versions = self.client.get("/api/queue-versions")

        self.assertEqual(204, published.status_code)
        self.assertEqual(200, identity.status_code)
        self.assertEqual("0.13.2", identity.get_json()["website_version"])
        self.assertEqual(200, versions.status_code)
        payload = versions.get_json()
        self.assertEqual(1_234_000, payload["checked_at"])
        self.assertNotIn("bot_qq", payload)
        self.assertEqual(
            {
                "name": "现场终端",
                "current_version": "0.10.0",
                "latest_version": "1.0.0",
                "status": "UPDATE_AVAILABLE",
                "updated_at": 1_234_000,
            },
            payload["components"]["terminal"],
        )
        self.assertEqual(
            "1.0.0", payload["components"]["website"]["latest_version"]
        )
        self.assertEqual(
            "UPDATE_AVAILABLE", payload["components"]["website"]["status"]
        )
        self.assertEqual("LATEST", payload["components"]["bot"]["status"])

    def test_version_status_is_unknown_without_reports_or_valid_release_config(self):
        self.app.config["LATEST_TERMINAL_VERSION"] = "not-a-version"

        response = self.client.get("/api/queue-versions")

        self.assertEqual(200, response.status_code)
        components = response.get_json()["components"]
        self.assertEqual(
            {
                "name": "现场终端",
                "current_version": None,
                "latest_version": None,
                "status": "UNKNOWN",
                "updated_at": None,
            },
            components["terminal"],
        )
        self.assertEqual("UNKNOWN", components["website"]["status"])
        self.assertEqual("UNKNOWN", components["bot"]["status"])

    def test_version_status_compares_prereleases_and_versions_ahead(self):
        self.app.config["LATEST_BOT_VERSION"] = "0.3.12"
        self.app.config["LATEST_WEBSITE_VERSION"] = "0.10.2"
        identity = self.client.post(
            "/api/queue-bot/identity",
            json={
                "bot_qq": "87654321",
                "bot_version": "0.3.13-beta.1",
                "website_version": "0.10.2-beta.2",
            },
            headers=self.bot_headers,
        )

        self.assertEqual(200, identity.status_code)
        components = self.client.get("/api/queue-versions").get_json()["components"]
        self.assertEqual("AHEAD", components["bot"]["status"])
        self.assertEqual("UPDATE_AVAILABLE", components["website"]["status"])

    def test_legacy_bot_heartbeat_clears_stale_bot_version_but_preserves_site_report(self):
        with patch("app.time.time", return_value=100):
            first = self.client.post(
                "/api/queue-bot/identity",
                json={
                    "bot_qq": "87654321",
                    "bot_version": "0.3.12",
                    "website_version": "0.10.1",
                },
                headers=self.bot_headers,
            )
        with patch("app.time.time", return_value=200):
            legacy = self.client.post(
                "/api/queue-bot/identity",
                json={"bot_qq": "87654321"},
                headers=self.bot_headers,
            )

        self.assertEqual(200, first.status_code)
        self.assertEqual(200, legacy.status_code)
        payload = legacy.get_json()
        self.assertIsNone(payload["bot_version"])
        self.assertIsNone(payload["bot_version_updated_at"])
        self.assertEqual("0.10.1", payload["website_version"])
        self.assertEqual(100_000, payload["website_version_updated_at"])
        self.assertEqual(200_000, payload["updated_at"])

    def test_bot_identity_rejects_invalid_or_unknown_version_fields(self):
        invalid_version = self.client.post(
            "/api/queue-bot/identity",
            json={"bot_qq": "87654321", "bot_version": "0.3"},
            headers=self.bot_headers,
        )
        invalid_prerelease = self.client.post(
            "/api/queue-bot/identity",
            json={"bot_qq": "87654321", "website_version": "0.10.1-beta.01"},
            headers=self.bot_headers,
        )
        unknown_field = self.client.post(
            "/api/queue-bot/identity",
            json={"bot_qq": "87654321", "version": "0.3.12"},
            headers=self.bot_headers,
        )

        self.assertEqual(400, invalid_version.status_code)
        self.assertIn("有效的语义版本号", invalid_version.get_json()["error"])
        self.assertEqual(400, invalid_prerelease.status_code)
        self.assertEqual(400, unknown_field.status_code)

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
        registration_url = created.get_json()["registration_url"]
        self.assertIn("/queue-status?mobile_registration=", registration_url)
        self.assertNotIn("abcccc.top", registration_url)

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

    def test_mobile_profile_search_skips_a_legacy_profile_without_public_id(self):
        snapshot = self.remote_ready_snapshot(revision=20)
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )
        created = self.client.post(
            "/api/queue-terminal/mobile-registration-sessions",
            json={
                "request_id": "00000000-0000-0000-0000-000000000824",
                "queue_id": snapshot["queue_id"],
                "machine_id": "A",
            },
            headers=self.headers,
        )
        self.assertEqual(201, created.status_code)
        token = created.get_json()["session_token"]

        connection = sqlite3.connect(self.database_path)
        try:
            connection.execute(
                "UPDATE player_profile SET public_player_id = NULL WHERE profile_id = ?",
                (self.profile_id,),
            )
            connection.commit()
        finally:
            connection.close()

        searched = self.client.get(
            f"/api/queue-mobile/sessions/{token}?q=999999"
        )
        self.assertEqual(200, searched.status_code)
        self.assertEqual([], searched.get_json()["profiles"])

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
            "这名玩家已经有一份正在排队的登记。",
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
        original_public_player_id = self.client.get(
            "/api/queue-terminal/profiles", headers=self.headers
        ).get_json()["profiles"][0]["public_player_id"]
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

        completed_snapshot = copy.deepcopy(snapshot)
        completed_snapshot["revision"] = 23
        completed_snapshot["private_player_profiles"][0].update(
            qq_number="12345678",
            setup_version=1,
            profile_revision=2,
            updated_at=900_000,
        )
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=completed_snapshot, headers=self.headers
            ).status_code,
        )
        completed_profiles = self.client.post(
            "/api/queue-bot/profiles",
            json={"qq": "12345678"},
            headers=self.bot_headers,
        ).get_json()

        self.assertEqual(
            [alias_id],
            [profile["profile_id"] for profile in completed_profiles["profiles"]],
        )
        self.assertEqual("12345678", completed_profiles["profiles"][0]["qq_number"])
        self.assertEqual({}, completed_profiles["profile_aliases"])
        self.assertEqual(
            [original_public_player_id],
            completed_profiles["profiles"][0]["public_player_id_aliases"],
        )

        new_session = self.client.post(
            "/api/queue-terminal/mobile-registration-sessions",
            json={
                "request_id": "00000000-0000-0000-0000-000000000836",
                "queue_id": completed_snapshot["queue_id"],
                "machine_id": "A",
            },
            headers=self.headers,
        ).get_json()
        searched_by_old_public_id = self.client.get(
            f"/api/queue-mobile/sessions/{new_session['session_token']}"
            f"?q={original_public_player_id}"
        ).get_json()["profiles"]
        terminal_profiles = self.client.get(
            "/api/queue-terminal/profiles", headers=self.headers
        ).get_json()["profiles"]

        self.assertEqual([alias_id], [profile["profile_id"] for profile in searched_by_old_public_id])
        self.assertEqual(
            [original_public_player_id],
            searched_by_old_public_id[0]["public_player_id_aliases"],
        )
        self.assertEqual(
            [original_public_player_id],
            terminal_profiles[0]["public_player_id_aliases"],
        )

    def test_profile_aliases_are_resolved_before_qq_search_in_the_same_second(self):
        alias_id = "00000000-0000-0000-0000-000000000902"
        with patch("app.time.time", return_value=1_700_000_000):
            original = self.remote_ready_snapshot(revision=21)
            self.assertEqual(
                204,
                self.client.post(
                    "/api/queue-status", json=original, headers=self.headers
                ).status_code,
            )

            current = self.remote_ready_snapshot(revision=22)
            current["private_player_profiles"][0].update(
                profile_id=alias_id,
                qq_number=None,
                setup_version=0,
                profile_revision=1,
                created_at=700_000,
                updated_at=850_000,
            )
            self.assertEqual(
                204,
                self.client.post(
                    "/api/queue-status", json=current, headers=self.headers
                ).status_code,
            )

            created = self.client.post(
                "/api/queue-terminal/mobile-registration-sessions",
                json={
                    "request_id": "00000000-0000-0000-0000-000000000835",
                    "queue_id": current["queue_id"],
                    "machine_id": "A",
                },
                headers=self.headers,
            ).get_json()
            mobile_profiles = self.client.get(
                f"/api/queue-mobile/sessions/{created['session_token']}?q=12345678"
            ).get_json()["profiles"]
            bot_profiles = self.client.post(
                "/api/queue-bot/profiles",
                json={"qq": "12345678"},
                headers=self.bot_headers,
            ).get_json()["profiles"]
            website_profile = self.client.post(
                "/api/queue-online/profile", json={"qq": "12345678"}
            )
            bot_join = self.client.post(
                "/api/queue-bot/queue-commands",
                json={
                    "request_id": "00000000-0000-0000-0000-000000000836",
                    "actor_qq": "12345678",
                    "operation": "JOIN_QUEUE",
                    "machine_id": "A",
                },
                headers=self.bot_headers,
            )

        self.assertEqual([alias_id], [profile["profile_id"] for profile in mobile_profiles])
        self.assertEqual([alias_id], [profile["profile_id"] for profile in bot_profiles])
        self.assertIsNone(bot_profiles[0]["qq_number"])
        self.assertEqual(409, website_profile.status_code)
        self.assertEqual("PROFILE_SYNC_PENDING", website_profile.get_json()["code"])
        self.assertIn("补全并保存 QQ", website_profile.get_json()["error"])
        self.assertEqual(409, bot_join.status_code)
        self.assertEqual("PROFILE_SYNC_PENDING", bot_join.get_json()["code"])

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

    def test_mobile_registration_profile_conflicts_return_actionable_codes(self):
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
                "request_id": "00000000-0000-0000-0000-000000000842",
                "queue_id": snapshot["queue_id"],
                "machine_id": "A",
            },
            headers=self.headers,
        ).get_json()
        endpoint = f"/api/queue-mobile/sessions/{created['session_token']}/submit"

        def new_profile_submission(request_id, nickname, qq_number):
            return {
                "request_id": request_id,
                "preference": "OPEN_TO_JOIN",
                "new_profile": {
                    "nickname": nickname,
                    "gender": "UNDISCLOSED",
                    "default_preference": "OPEN_TO_JOIN",
                    "qq_number": qq_number,
                    "qq_visibility": "TERMINAL_ONLY",
                    "notification_enabled": True,
                    "notify_queue_changes": True,
                    "notify_playing_position": False,
                    "notify_online_check_in": True,
                    "notify_absence": True,
                    "notify_machine_status": False,
                },
            }

        duplicate_qq = self.client.post(
            endpoint,
            json=new_profile_submission(
                "00000000-0000-0000-0000-000000000843",
                "移动新玩家",
                "12345678",
            ),
        )
        duplicate_nickname = self.client.post(
            endpoint,
            json=new_profile_submission(
                "00000000-0000-0000-0000-000000000844",
                "公开昵称",
                "87654321",
            ),
        )

        self.assertEqual(409, duplicate_qq.status_code)
        self.assertEqual("QQ_ALREADY_USED", duplicate_qq.get_json()["code"])
        self.assertEqual(409, duplicate_nickname.status_code)
        self.assertEqual(
            "NICKNAME_ALREADY_USED", duplicate_nickname.get_json()["code"]
        )

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

    def test_single_player_mobile_registration_skips_preference_and_carries_revision(self):
        snapshot = self.remote_ready_snapshot(
            revision=46,
            default_preference="ASK_EVERY_TIME",
        )
        self.upgrade_snapshot_to_schema_v6(
            snapshot,
            capacities={"A": 1, "B": 2},
            configuration_revision=14,
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
                "request_id": "00000000-0000-0000-0000-000000000854",
                "queue_id": snapshot["queue_id"],
                "machine_id": "A",
            },
            headers=self.headers,
        ).get_json()
        opened = self.client.get(
            f"/api/queue-mobile/sessions/{created['session_token']}"
        ).get_json()

        submitted = self.client.post(
            f"/api/queue-mobile/sessions/{created['session_token']}/submit",
            json={
                "request_id": "00000000-0000-0000-0000-000000000855",
                "profile_id": self.profile_id,
                "expected_profile_revision": 3,
            },
        )
        commands = self.client.get(
            "/api/queue-terminal/commands", headers=self.headers
        ).get_json()["commands"]
        command = next(
            value
            for value in commands
            if value["command_id"] == "00000000-0000-0000-0000-000000000855"
        )

        self.assertEqual(1, opened["session"]["machine_configuration"]["capacity"])
        self.assertEqual(14, opened["session"]["machine_configuration_revision"])
        self.assertEqual(202, submitted.status_code)
        self.assertEqual("SOLO", command["payload"]["preference"])
        self.assertEqual(14, command["payload"]["machine_configuration_revision"])

    def test_mobile_registration_rechecks_queue_closure_before_submission(self):
        snapshot = self.remote_ready_snapshot(revision=24)
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )
        created = self.client.post(
            "/api/queue-terminal/mobile-registration-sessions",
            json={
                "request_id": "00000000-0000-0000-0000-000000000852",
                "queue_id": snapshot["queue_id"],
                "machine_id": "A",
            },
            headers=self.headers,
        ).get_json()
        submission = {
            "request_id": "00000000-0000-0000-0000-000000000853",
            "profile_id": self.profile_id,
            "expected_profile_revision": 3,
        }

        closed = copy.deepcopy(snapshot)
        closed["revision"] += 1
        closed["registration_open"] = False
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=closed, headers=self.headers
            ).status_code,
        )
        rejected = self.client.post(
            f"/api/queue-mobile/sessions/{created['session_token']}/submit",
            json=submission,
        )

        self.assertEqual(409, rejected.status_code)
        self.assertEqual("REGISTRATION_CLOSED", rejected.get_json()["code"])
        commands = self.client.get(
            "/api/queue-terminal/commands", headers=self.headers
        ).get_json()["commands"]
        self.assertFalse(any(
            command["command_id"] == submission["request_id"]
            for command in commands
        ))

        reopened = copy.deepcopy(closed)
        reopened["revision"] += 1
        reopened["registration_open"] = True
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=reopened, headers=self.headers
            ).status_code,
        )
        accepted = self.client.post(
            f"/api/queue-mobile/sessions/{created['session_token']}/submit",
            json=submission,
        )
        self.assertEqual(202, accepted.status_code)

    def test_mobile_registration_session_rejects_offline_or_disabled_online_registration(self):
        snapshot = self.remote_ready_snapshot(revision=25)
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
        offline = self.client.post(
            "/api/queue-terminal/mobile-registration-sessions",
            json={
                "request_id": "00000000-0000-0000-0000-000000000856",
                "queue_id": snapshot["queue_id"],
                "machine_id": "A",
            },
            headers=self.headers,
        )
        self.assertEqual(503, offline.status_code)
        self.assertIn("终端暂时离线", offline.get_json()["error"])

        enabled_again = copy.deepcopy(snapshot)
        enabled_again["revision"] += 1
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=enabled_again, headers=self.headers
            ).status_code,
        )
        disabled = copy.deepcopy(enabled_again)
        disabled["revision"] += 1
        disabled["queue_rules"]["allow_online_registration"] = False
        disabled_response = self.client.post(
            "/api/queue-status", json=disabled, headers=self.headers
        )
        self.assertEqual(204, disabled_response.status_code)
        rejected = self.client.post(
            "/api/queue-terminal/mobile-registration-sessions",
            json={
                "request_id": "00000000-0000-0000-0000-000000000857",
                "queue_id": disabled["queue_id"],
                "machine_id": "A",
            },
            headers=self.headers,
        )
        self.assertEqual(409, rejected.status_code)
        self.assertEqual("系统规则不允许线上登记。", rejected.get_json()["error"])

    def test_mobile_registration_rechecks_online_registration_switch_before_submission(self):
        snapshot = self.remote_ready_snapshot(revision=26)
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )
        created = self.client.post(
            "/api/queue-terminal/mobile-registration-sessions",
            json={
                "request_id": "00000000-0000-0000-0000-000000000858",
                "queue_id": snapshot["queue_id"],
                "machine_id": "A",
            },
            headers=self.headers,
        )
        self.assertEqual(201, created.status_code)

        disabled = copy.deepcopy(snapshot)
        disabled["revision"] += 1
        disabled["queue_rules"]["allow_online_registration"] = False
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=disabled, headers=self.headers
            ).status_code,
        )

        rejected = self.client.post(
            f"/api/queue-mobile/sessions/{created.get_json()['session_token']}/submit",
            json={
                "request_id": "00000000-0000-0000-0000-000000000859",
                "profile_id": self.profile_id,
                "expected_profile_revision": 3,
            },
        )

        self.assertEqual(409, rejected.status_code)
        self.assertEqual("ONLINE_REGISTRATION_DISABLED", rejected.get_json()["code"])
        commands = self.client.get(
            "/api/queue-terminal/commands", headers=self.headers
        ).get_json()["commands"]
        self.assertFalse(any(
            command["command_id"] == "00000000-0000-0000-0000-000000000859"
            for command in commands
        ))

    def test_mobile_registration_request_id_collision_returns_conflict(self):
        snapshot = self.remote_ready_snapshot(revision=25)
        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=snapshot, headers=self.headers
            ).status_code,
        )
        created = self.client.post(
            "/api/queue-terminal/mobile-registration-sessions",
            json={
                "request_id": "00000000-0000-0000-0000-000000000858",
                "queue_id": snapshot["queue_id"],
                "machine_id": "A",
            },
            headers=self.headers,
        ).get_json()
        command_id = "00000000-0000-0000-0000-000000000859"
        connection = sqlite3.connect(self.database_path)
        try:
            connection.execute(
                """
                INSERT INTO terminal_command
                    (command_id, device_id, command_type, payload, status, created_at)
                VALUES (?, ?, ?, ?, 'APPLIED', ?)
                """,
                (
                    command_id,
                    "terminal-1",
                    "QUEUE_OPERATION",
                    '{"operation":"JOIN_QUEUE"}',
                    int(time.time()),
                ),
            )
            connection.commit()
        finally:
            connection.close()
        response = self.client.post(
            f"/api/queue-mobile/sessions/{created['session_token']}/submit",
            json={
                "request_id": command_id,
                "profile_id": self.profile_id,
                "expected_profile_revision": 3,
            },
        )
        self.assertEqual(409, response.status_code)
        self.assertEqual("REQUEST_ID_CONFLICT", response.get_json()["code"])

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

    @staticmethod
    def upgrade_snapshot_to_schema_v6(
        snapshot,
        capacities=None,
        configuration_revision=1,
    ):
        capacities = capacities or {}
        snapshot["schema_version"] = 6
        snapshot["machine_configuration_revision"] = configuration_revision
        for machine_id, machine in snapshot["machines"].items():
            suffix = f" · 机台 {machine_id}"
            remark = (
                machine["name"][: -len(suffix)]
                if machine["name"].endswith(suffix)
                else machine["name"]
            )
            machine["remark"] = remark
            machine["configuration"] = {
                "game_type": "MAIMAI_DX",
                "custom_game_type": None,
                "server": "HIDDEN",
                "custom_server": None,
                "game_version": None,
                "game_version_visible": False,
                "capacity": capacities.get(machine_id, 2),
                "solo_round_minutes": 12,
                "shared_round_minutes": 15,
            }

    @classmethod
    def upgrade_snapshot_to_schema_v7(
        cls,
        snapshot,
        group_assignments=None,
        groups=None,
        default_group_id=None,
    ):
        cls.upgrade_snapshot_to_schema_v6(snapshot)
        snapshot["schema_version"] = 7
        groups = groups or [{"id": "1" * 32, "name": "分组 1"}]
        default_group_id = default_group_id or groups[0]["id"]
        group_assignments = group_assignments or {}
        snapshot["machine_groups"] = groups
        snapshot["default_machine_group_id"] = default_group_id
        for index, (machine_id, machine) in enumerate(snapshot["machines"].items(), start=1):
            machine["stable_id"] = f"{index:032x}"
            machine["group_id"] = group_assignments.get(machine_id, groups[0]["id"])

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
