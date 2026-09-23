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
            self.assertEqual(timeout, 15)
            body = json.loads(request.data)
            self.assertEqual(body, {"contractVersion": 2, "events": [{
                "toiletId": 53585, "revision": 4, "action": "UPSERT", "catalogChanged": True}]})
            return Response(json.dumps({"ok": True, "acceptedEvents": [{"toiletId": 53585,
                "revision": 4}]}).encode())

        with patch.object(probe.subprocess, "run", side_effect=run):
            result = probe.probe(opener)
        self.assertEqual(result["outcome"], "acknowledged")
        self.assertFalse(result["outboxAcknowledged"])
        self.assertFalse(result["rawSourceExported"])
        self.assertEqual(len(commands), 2)
        self.assertIn("START TRANSACTION READ ONLY", commands[1][1]["input"])
        self.assertEqual(commands[1][1]["env"]["MYSQL_PWD"], "private")


if __name__ == "__main__":
    main()
