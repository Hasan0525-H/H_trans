"""Rebuild an APK owned by this repository without contacting a translation service."""
import json
import os
from pathlib import Path
import sys
import tempfile
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from arabiflow.pipeline import convert, run_command, ANDROID

class FixtureTranslator:
    def translate(self, value, source="auto"):
        return {"ArabiFlow Test App": "تطبيق عربي تجريبي",
                "Welcome": "مرحبًا"}.get(value, value)

def main():
    apk = Path(sys.argv[1]).resolve()
    assert apk.is_file(), apk
    events = []
    with tempfile.TemporaryDirectory(prefix="arabiflow-integration-") as tmp:
        tmp = Path(tmp)
        final = tmp / "signed-result.apk"
        report = convert(apk, tmp / "workspace", final,
                         lambda progress, stage: events.append((progress, stage)),
                         translator=FixtureTranslator())
        assert final.is_file() and final.stat().st_size > 0
        assert report["translated_strings"] >= 1, report
        assert report["hardcoded_xml_strings"] >= 1, report
        assert report["rtl_layouts"] >= 1, report
        assert events[-1][0] == 100, events
        with ZipFile(final) as z:
            assert "AndroidManifest.xml" in z.namelist()
            assert "resources.arsc" in z.namelist()
        decoded = tmp / "verified"
        run_command(["apktool", "d", "--no-src", str(final), "-o", str(decoded)], timeout=300)
        application = ET.parse(decoded / "AndroidManifest.xml").getroot().find("application")
        assert application.get("{" + ANDROID + "}supportsRtl") == "true"
        matching_strings = []
        for path in (decoded / "res").glob("values*/strings.xml"):
            root = ET.parse(path).getroot()
            matching_strings.extend(e.text or "" for e in root.findall("string"))
        assert any("تطبيق عربي تجريبي" in x for x in matching_strings), matching_strings
        print(json.dumps({"result": "full decode-build-sign-decode passed", "report": report,
                          "progress_events": events}, ensure_ascii=False, default=str))

if __name__ == "__main__":
    main()
