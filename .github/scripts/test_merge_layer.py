"""Regression tests for mandatory-layer merge safety, using real Git repositories."""

import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("merge-layer.py").resolve()
spec = importlib.util.spec_from_file_location("merge_layer", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class LayerMergeTest(unittest.TestCase):
    def test_reviewed_fragments_preserve_surrounding_changes(self):
        for name in ("properties", "metadata"):
            directory = module.RESOLUTIONS
            ours = (directory / f"{name}.ours").read_text()
            theirs = (directory / f"{name}.theirs").read_text()
            text = "RPM and upstream changes\n<<<<<<< HEAD\n" + ours + "=======\n" + theirs + ">>>>>>> FETCH_HEAD\nother changes\n"
            result = module.resolve(text, [{"fragment": name}])
            self.assertEqual(result, "RPM and upstream changes\n" + (directory / f"{name}.resolved").read_text() + "other changes\n")
            with self.assertRaises(ValueError):
                module.resolve(text.replace(ours, ours + "unexpected change\n"), [{"fragment": name}])

    def test_unknown_conflict_aborts_and_keeps_all_layers_required(self):
        with tempfile.TemporaryDirectory() as folder:
            def git(*args):
                return subprocess.check_output(["git", *args], cwd=folder, stderr=subprocess.DEVNULL, text=True).strip()

            git("init", "-b", "base")
            git("config", "user.name", "Test")
            git("config", "user.email", "test@example.invalid")
            path = Path(folder) / "unknown.txt"
            path.write_text("base\n")
            git("add", ".")
            git("commit", "-qm", "base")
            git("switch", "-c", "layer")
            path.write_text("layer\n")
            git("commit", "-qam", "layer")
            git("switch", "base")
            path.write_text("upstream\n")
            git("commit", "-qam", "upstream")
            head = git("rev-parse", "HEAD")
            result = subprocess.run(["python3", str(SCRIPT), "pr/experimental-channel", "layer"], cwd=folder, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(git("rev-parse", "HEAD"), head)
            self.assertEqual(path.read_text(), "upstream\n")
            self.assertEqual(git("status", "--porcelain"), "")
            self.assertIn(b"no layer was skipped", result.stderr)


if __name__ == "__main__":
    unittest.main()
