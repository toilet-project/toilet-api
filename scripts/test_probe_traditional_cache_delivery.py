import importlib.util
import io
import json
from pathlib import Path
from unittest import TestCase, main
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("probe", Path(__file__).with_name("probe_traditional_cache_delivery.py"))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class Response(io.BytesIO):
    status = 200


class CacheProbeTest(TestCase):
    def test_signed_single_event_never_acknowledges_outbox(self):
        secret = "s" * 32
        environment = ["WEB_CACHE_ORIGIN=https://geupddong.com",
                       "WEB_CACHE_REVALIDATION_ENABLED=true", "WEB_CACHE_CONTRACT_VERSION=3",
                       "WEB_CACHE_REVALIDATION_SECRET=" + secret,
                       "SPRING_DB_USERNAME=read", "SPRING_DB_PASSWORD=private"]
        commands = []

        def run(command, **kwargs):
            commands.append((command, kwargs))
            if command[:2] == ["docker", "inspect"]:
                return type("Result", (), {"returncode": 0, "stdout": json.dumps([{"Config": {"Env": environment}}])})()
            return type("Result", (), {"returncode": 0, "stdout": json.dumps({
                "toiletId": 53585, "revision": 4, "action": "UPSERT", "catalogChanged": 1}) + "\n"})()

        def opener(request, timeout):
            if request.data == b"{}":
                self.assertEqual(timeout, 15)
                response = Response(b"")
                response.status = 401
                return response
            self.assertEqual(timeout, 8)
            self.assertEqual(request.get_header("User-agent"), "Java-http-client/21.0.12")
            body = json.loads(request.data)
            self.assertEqual(body, {"contractVersion": 2, "events": [{
                "toiletId": 53585, "revision": 4, "action": "UPSERT", "catalogChanged": True}]})
            return Response(json.dumps({"ok": True, "acceptedEvents": [{"toiletId": 53585,
                "revision": 4}]}).encode())

        with patch.object(probe.subprocess, "run", side_effect=run):
            result = probe.probe(opener)
        self.assertEqual(result["outcome"], "acknowledged")
        self.assertEqual(result["batchSize"], 1)
        self.assertEqual(result["unsignedStatusByAgent"], {"python": 401, "java": 401, "browser": 401})
        self.assertFalse(result["outboxAcknowledged"])
        self.assertFalse(result["rawSourceExported"])
        self.assertEqual(len(commands), 2)
        self.assertIn("START TRANSACTION READ ONLY", commands[1][1]["input"])
        self.assertEqual(commands[1][1]["env"]["MYSQL_PWD"], "private")

    def test_targeted_twenty_event_probe_matches_sender_timeout_without_outbox_ack(self):
        version = "10974447-2030-493b-9ea6-9bcded1f958a"
        environment = ["WEB_CACHE_ORIGIN=https://geupddong.com",
                       "WEB_CACHE_REVALIDATION_ENABLED=true", "WEB_CACHE_CONTRACT_VERSION=3",
                       "WEB_CACHE_REVALIDATION_SECRET=" + "s" * 32,
                       "SPRING_DB_USERNAME=read", "SPRING_DB_PASSWORD=private"]
        rows = [dict(toiletId=index, revision=2, action="UPSERT", catalogChanged=1)
                for index in range(1, 21)]
        commands = []

        def run(command, **kwargs):
            commands.append((command, kwargs))
            stdout = json.dumps([{"Config": {"Env": environment}}]) if command[:2] == ["docker", "inspect"] \
                else "\n".join(json.dumps(row) for row in rows) + "\n"
            return type("Result", (), {"returncode": 0, "stdout": stdout})()

        def opener(request, timeout):
            self.assertEqual(timeout, 8)
            self.assertEqual(request.get_header("Cloudflare-workers-version-overrides"),
                             f'geupddong-web-production="{version}"')
            self.assertEqual(json.loads(request.data)["events"], [dict(row, catalogChanged=True) for row in rows])
            ack = {"ok": True, "acceptedEvents": [{"toiletId": row["toiletId"],
                                                     "revision": row["revision"]} for row in rows]}
            return Response(json.dumps(ack).encode())

        with patch.object(probe.subprocess, "run", side_effect=run):
            result = probe.probe(opener, 20, version)
        self.assertEqual(result["outcome"], "acknowledged")
        self.assertEqual(result["batchSize"], 20)
        self.assertEqual(result["workerVersion"], version)
        self.assertEqual(result["unsignedStatusByAgent"], {})
        self.assertFalse(result["outboxAcknowledged"])
        self.assertIn("LIMIT 20", commands[1][1]["input"])
        self.assertIn("START TRANSACTION READ ONLY", commands[1][1]["input"])

    def test_rejects_unsupported_size_and_invalid_version_before_access(self):
        with self.assertRaises(ValueError):
            probe.probe(batch_size=100)
        with self.assertRaises(ValueError):
            probe.probe(version_id='bad; command')


if __name__ == "__main__":
    main()
