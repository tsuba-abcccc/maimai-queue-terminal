import copy
import sqlite3
import tempfile
import time
import unittest
from pathlib import Path

from app import create_app


class QueueStatusApiTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.database_path = str(Path(self.temporary_directory.name) / "queue.db")
        self.app = create_app(
            {
                "TESTING": True,
                "DATABASE_PATH": self.database_path,
                "SYNC_TOKEN": "test-token",
                "ALLOWED_DEVICE_ID": "terminal-1",
                "ONLINE_TIMEOUT_SECONDS": 90,
            }
        )
        self.client = self.app.test_client()
        self.headers = {
            "Authorization": "Bearer test-token",
            "X-Device-ID": "terminal-1",
            "X-Queue-Schema-Version": "1",
        }

    def tearDown(self):
        self.temporary_directory.cleanup()

    def test_requires_terminal_authentication(self):
        response = self.client.post("/api/queue-status", json=self.snapshot())

        self.assertEqual(401, response.status_code)

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
        self.assertEqual("NO_SHOW_MOVED_TO_TAIL", second_page["logs"][0]["type"])
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

    def test_rejects_duplicate_registration_ids_across_machines(self):
        snapshot = self.snapshot()
        duplicate = copy.deepcopy(snapshot["machines"]["A"]["playing"][0])
        snapshot["machines"]["B"]["playing"] = [duplicate]
        snapshot["machines"]["B"]["registration_count"] = 1

        response = self.client.post(
            "/api/queue-status", json=snapshot, headers=self.headers
        )

        self.assertEqual(400, response.status_code)

    def test_primary_terminal_has_priority_and_cross_device_takeover_preserves_queues(self):
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

        self.assertEqual(
            204,
            self.client.post(
                "/api/queue-status", json=secondary, headers=secondary_headers
            ).status_code,
        )
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
        self.assertNotIn(primary["queue_id"], retired_queue_ids)
        self.assertNotIn(secondary["queue_id"], retired_queue_ids)

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


if __name__ == "__main__":
    unittest.main()
