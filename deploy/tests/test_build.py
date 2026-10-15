import importlib.util
from pathlib import Path
import shutil
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
MODULE_SPEC = importlib.util.spec_from_file_location("qdata_build", ROOT / "deploy/tools/build.py")
builder = importlib.util.module_from_spec(MODULE_SPEC)
MODULE_SPEC.loader.exec_module(builder)
TOOL_BUILD = Path("qdata-ui/src/views/sys/tool/build")


class SourceSnapshotTest(unittest.TestCase):
    def test_system_tool_page_survives_the_reported_subtree_copy(self):
        source = ROOT / "qdata-work" / TOOL_BUILD.parent
        with tempfile.TemporaryDirectory() as directory:
            snapshot = Path(directory) / "snapshot"
            shutil.copytree(source, snapshot, ignore=builder.ignore_source)
            self.assertEqual((source / "build/index.vue").read_bytes(),
                             (snapshot / "build/index.vue").read_bytes())

    def test_full_source_snapshot_preserves_all_vue_pages(self):
        source = ROOT / "qdata-work"
        with tempfile.TemporaryDirectory() as directory:
            snapshot = Path(directory) / "source"
            shutil.copytree(source, snapshot, ignore=builder.ignore_source)
            source_pages = {path.relative_to(source): path.read_bytes()
                            for path in (source / "qdata-ui/src").rglob("*.vue")}
            snapshot_pages = {path.relative_to(snapshot): path.read_bytes()
                              for path in (snapshot / "qdata-ui/src").rglob("*.vue")}
            self.assertIn(TOOL_BUILD / "index.vue", source_pages)
            self.assertEqual(source_pages.keys(), snapshot_pages.keys())
            for path, content in source_pages.items():
                with self.subTest(path=str(path)):
                    self.assertEqual(content, snapshot_pages[path])

    def test_generated_directories_and_other_build_paths_stay_excluded(self):
        excluded = [parent / name
                    for parent in (Path(), Path("qdata-ui"), Path("qdata-server"), TOOL_BUILD)
                    for name in ("build", "target", "dist", "node_modules")]
        excluded.extend(Path(path) for path in (
            "qdata-ui/src/views/sys/other/build",
            "qdata-ui/src/views/dp/tool/build",
            "other-ui/src/views/sys/tool/build",
        ))
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "qdata-work"
            page = source / TOOL_BUILD / "index.vue"
            page.parent.mkdir(parents=True)
            page.write_text("<template>fixture</template>\n", encoding="utf-8")
            for relative in excluded:
                output = source / relative / "generated.txt"
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_text("generated fixture\n", encoding="utf-8")
            snapshot = Path(directory) / "snapshot"
            shutil.copytree(source, snapshot, ignore=builder.ignore_source)
            self.assertEqual(page.read_bytes(), (snapshot / TOOL_BUILD / "index.vue").read_bytes())
            for relative in excluded:
                with self.subTest(path=str(relative)):
                    self.assertFalse((snapshot / relative).exists())
                    self.assertTrue((source / relative / "generated.txt").is_file())

    def test_source_exception_keeps_private_file_filters(self):
        excluded = (".env", ".env.production", "application.yml", "application-local.yml",
                    "private.pem", "private.key", "old.jar", "Old.class", "build.log",
                    ".flattened-pom.xml", "dependency-reduced-pom.xml")
        included = ("index.vue", ".env.production.example", "application.example.yml")
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "qdata-work"
            tool = source / TOOL_BUILD
            tool.mkdir(parents=True)
            for name in excluded + included:
                (tool / name).write_text("synthetic fixture\n", encoding="utf-8")
            snapshot = Path(directory) / "snapshot"
            shutil.copytree(source, snapshot, ignore=builder.ignore_source)
            for name in excluded:
                with self.subTest(name=name):
                    self.assertFalse((snapshot / TOOL_BUILD / name).exists())
            for name in included:
                with self.subTest(name=name):
                    self.assertEqual((tool / name).read_bytes(),
                                     (snapshot / TOOL_BUILD / name).read_bytes())


if __name__ == "__main__":
    unittest.main()
