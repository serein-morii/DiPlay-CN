import unittest

from check_release_version import check


def build(code: int, name: str) -> str:
    return f'versionCode = {code}\nversionName = "{name}"\n'


class ReleaseVersionTest(unittest.TestCase):
    def test_accepts_increasing_code(self):
        check("v0.2.17-cn.2", build(111, "0.2.17-cn.2"), {
            "v0.2.16-cn.5": build(109, "0.2.16-cn.5")
        })

    def test_rejects_the_cn_one_downgrade(self):
        with self.assertRaisesRegex(ValueError, "must exceed 109"):
            check("v0.2.17-cn.1", build(106, "0.2.17-cn.1"), {
                "v0.2.16-cn.5": build(109, "0.2.16-cn.5")
            })

    def test_rejects_name_mismatch(self):
        with self.assertRaisesRegex(ValueError, "does not match"):
            check("v0.2.17-cn.2", build(111, "0.2.17-cn.1"), {})

    def test_includes_historical_four_component_cn_tags(self):
        with self.assertRaisesRegex(ValueError, "must exceed 112"):
            check("v0.2.17-cn.2", build(111, "0.2.17-cn.2"), {
                "v0.2.8.4-cn.1": build(112, "0.2.8.4")
            })

    def test_checks_every_existing_cn_tag_even_if_tags_were_created_out_of_order(self):
        with self.assertRaisesRegex(ValueError, "must exceed 112"):
            check("v0.2.17-cn.2", build(111, "0.2.17-cn.2"), {
                "v0.2.18-cn.1": build(112, "0.2.18-cn.1")
            })


if __name__ == "__main__":
    unittest.main()
