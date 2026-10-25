"""Behavior checks for the development loop; never contact a real phone."""

from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import dev_device as dev


class DevicePreviewTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.root_patch = patch.object(dev, "ROOT", self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)
        self.adb = "/fake/adb"
        self.serial = "192.0.2.10:34567"

    def write(self, name, contents="test"):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents)
        return path

    def completed(self, code=0, stdout="", stderr=""):
        return subprocess.CompletedProcess([], code, stdout, stderr)

    def test_build_failure_never_installs_stale_apk(self):
        self.write(dev.APK, "old APK")
        with patch.object(dev.subprocess, "run", return_value=self.completed(1)) as run:
            self.assertFalse(dev.deploy(self.adb, self.serial))
        self.assertEqual(run.call_count, 1)
        self.assertNotIn(self.adb, run.call_args.args[0])

    def test_missing_apk_does_not_install(self):
        with patch.object(dev.subprocess, "run", return_value=self.completed()) as run:
            self.assertFalse(dev.deploy(self.adb, self.serial))
        self.assertEqual(run.call_count, 1)

    def test_failed_install_does_not_clear_data_or_launch(self):
        self.write(dev.APK)
        with patch.object(dev.subprocess, "run", side_effect=[self.completed(), self.completed(1)]) as run:
            self.assertFalse(dev.deploy(self.adb, self.serial))
        self.assertEqual(run.call_count, 2)
        command = run.call_args.args[0]
        self.assertEqual(command[:5], [self.adb, "-s", self.serial, "install", "-r"])

    def test_success_targets_only_requested_phone(self):
        self.write(dev.APK)
        with patch.object(dev.subprocess, "run", return_value=self.completed(stdout="Status: ok\n")) as run:
            self.assertTrue(dev.deploy(self.adb, self.serial))
        for call in run.call_args_list[1:]:
            self.assertEqual(call.args[0][:3], [self.adb, "-s", self.serial])
        self.assertEqual(run.call_args.args[0][-1], dev.ACTIVITY)

    def test_activity_error_is_failure_even_with_zero_exit_code(self):
        self.write(dev.APK)
        results = [self.completed(), self.completed(), self.completed(stderr="Error: Activity class does not exist\n")]
        with patch.object(dev.subprocess, "run", side_effect=results):
            self.assertFalse(dev.deploy(self.adb, self.serial))

    def test_sources_and_resources_trigger_but_outputs_do_not(self):
        baseline = dev.snapshot()
        self.write(dev.APK)
        self.write(".gradle/cache.bin")
        self.assertEqual(dev.snapshot(), baseline)
        source = self.write("app/src/main/java/Screen.kt")
        added = dev.snapshot()
        self.assertNotEqual(added, baseline)
        source.unlink()
        self.assertEqual(dev.snapshot(), baseline)
        self.write("app/src/main/res/values/strings.xml")
        self.assertNotEqual(dev.snapshot(), baseline)

    def test_edit_during_build_is_deployed_again(self):
        source = self.write("app/src/main/java/Screen.kt", "before")
        calls = []

        def deploy(*_):
            calls.append(True)
            if len(calls) == 1:
                source.write_text("changed during build")
                return True
            raise KeyboardInterrupt

        with patch.object(dev, "device_ready", return_value=True), patch.object(dev, "deploy", side_effect=deploy):
            with patch.object(dev.time, "sleep"), self.assertRaises(KeyboardInterrupt):
                dev.watch(self.adb, self.serial, 0)
        self.assertEqual(len(calls), 2)

    def test_offline_phone_reconnects_without_another_save(self):
        clock = iter(range(0, 100, 10))
        with patch.object(dev, "device_ready", side_effect=[False, True]) as ready:
            with patch.object(dev, "deploy", side_effect=KeyboardInterrupt) as deploy:
                with patch.object(dev.time, "monotonic", side_effect=lambda: next(clock)):
                    with patch.object(dev.time, "sleep"), self.assertRaises(KeyboardInterrupt):
                        dev.watch(self.adb, self.serial, 0)
        self.assertEqual(ready.call_count, 2)
        deploy.assert_called_once_with(self.adb, self.serial)

    def test_unauthorized_or_offline_device_is_not_ready(self):
        for state in ("unauthorized\n", "offline\n"):
            with self.subTest(state=state), patch.object(dev.subprocess, "run", return_value=self.completed(stdout=state)):
                self.assertFalse(dev.device_ready(self.adb, self.serial))


if __name__ == "__main__":
    unittest.main()
