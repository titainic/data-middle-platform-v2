"""Offline only: manifest and synthetic count maps; never runs installation/SQL."""
import importlib.util
import json
from pathlib import Path
import unittest
import warnings

SPEC = importlib.util.spec_from_file_location("clean_seed_install", Path(__file__).parents[1] / "install.py")
installer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(installer)
DATABASE = Path(__file__).parents[1] / "database"

class CleanCategorySeedContractTest(unittest.TestCase):
    def test_real_manifest_exposes_missing_catalog_before_any_installation(self):
        with self.assertWarnsRegex(UserWarning, "att_clean_rule.*att_clean_cat"):
            installer.checked_database_manifest(DATABASE)

    def test_count_verification_warns_on_the_same_gap(self):
        counts = {"att_clean_rule":"40", "att_clean_cat":"0"}
        with self.assertWarnsRegex(UserWarning, "att_clean_rule.*att_clean_cat"):
            installer.validate_seed_counts(counts, set(counts), {"att_clean_rule":40}, self.checks())

    def test_empty_rule_table_needs_no_warning(self):
        with warnings.catch_warnings(record=True) as caught:
            installer.validate_seed_counts({"att_clean_rule":"0","att_clean_cat":"0"},
                {"att_clean_rule","att_clean_cat"}, {}, self.checks())
        self.assertEqual([], caught)

    def test_nonempty_catalog_removes_count_gap_but_does_not_prove_paths(self):
        with warnings.catch_warnings(record=True) as caught:
            installer.validate_seed_counts({"att_clean_rule":"40","att_clean_cat":"21"},
                {"att_clean_rule","att_clean_cat"}, {"att_clean_rule":40,"att_clean_cat":21}, self.checks())
        self.assertEqual([], caught)

    def test_unexpected_counts_still_fail(self):
        with self.assertRaises(installer.InstallError):
            installer.validate_seed_counts({"att_clean_rule":"41","att_clean_cat":"0"},
                {"att_clean_rule","att_clean_cat"}, {"att_clean_rule":40}, self.checks())

    def checks(self):
        return json.loads((DATABASE / "manifest.json").read_text())["cross_table_requirements"]
