import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class StartupTimelineSourceTest(unittest.TestCase):
    def test_capture_has_page_and_worker_profiles(self):
        source = (ROOT / "port/scripts/wasm-startup-timeline.mjs").read_text(encoding="utf-8")
        self.assertIn('Target.setAutoAttach', source)
        self.assertIn('Profiler.stop", {}, worker.sessionId', source)
        self.assertIn('${label}.world.main.cpuprofile', source)
        self.assertIn('requestStarts', source)

    def test_idle_prefetch_change_is_dropped_without_measurement(self):
        source = (ROOT / "port/web/boot/gaius-boot.js").read_text(encoding="utf-8")
        self.assertNotIn('Promise.all(pending.map((target) => prefetch(target)))', source)
        self.assertIn('Date.now() - titleSince < 2500', source)


if __name__ == "__main__":
    unittest.main()
