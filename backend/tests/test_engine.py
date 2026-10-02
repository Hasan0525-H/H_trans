import io
import tempfile
from pathlib import Path
from zipfile import ZipFile
import xml.etree.ElementTree as ET
import pytest

from arabiflow.security import UnsupportedApk, inspect_apk
from arabiflow.translator import protect, restore, TranslationError
from arabiflow.pipeline import translate_resources, adapt_xml, ANDROID

class StubTranslator:
    def translate(self, value):
        return "عربي " + value

def test_placeholders_preserved():
    source = "Welcome %1$s, {name}\\n"
    safe, slots = protect(source)
    assert restore(safe, slots) == source
    with pytest.raises(TranslationError):
        restore(safe.replace("AFPHX00000XHPAF", ""), slots)

def test_preflight_rejects_traversal():
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "invalid.apk"
        with ZipFile(path, "w") as z:
            z.writestr("AndroidManifest.xml", "x")
            z.writestr("resources.arsc", "x")
            z.writestr("classes.dex", "x")
            z.writestr("../danger", "x")
        with pytest.raises(UnsupportedApk):
            inspect_apk(path)

def test_translation_and_direction(tmp_path):
    base = tmp_path / "res" / "values"
    layout = tmp_path / "res" / "layout"
    base.mkdir(parents=True)
    layout.mkdir()
    (base / "strings.xml").write_text('<resources><string name="welcome">Hello, %s</string>'
                                      '<string name="keep" translatable="false">API</string></resources>')
    (tmp_path / "AndroidManifest.xml").write_text(
        '<manifest xmlns:android="' + ANDROID + '"><application/></manifest>')
    (layout / "main.xml").write_text(
        '<LinearLayout xmlns:android="' + ANDROID + '" android:paddingLeft="8dp">'
        '<TextView android:text="Hello"/></LinearLayout>')
    report = dict(translated_strings=0, hardcoded_xml_strings=0, rtl_layouts=0, rtl_manifest=False,
                  skipped_formatted=0, warnings=[])
    translate_resources(tmp_path, StubTranslator(), report)
    adapt_xml(tmp_path, StubTranslator(), report)
    ar = ET.parse(tmp_path / "res" / "values-ar" / "strings.xml")
    assert ar.getroot().find("string").text == "عربي Hello, %s"
    assert len(ar.getroot().findall("string")) == 1
    parsed = ET.parse(layout / "main.xml").getroot()
    assert parsed.get("{" + ANDROID + "}paddingStart") == "8dp"
    assert parsed.get("{" + ANDROID + "}layoutDirection") == "rtl"
    assert report["translated_strings"] == 1 and report["hardcoded_xml_strings"] == 1
