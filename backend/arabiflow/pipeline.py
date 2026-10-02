"""Actual decode -> translate -> adapt RTL -> build -> align -> sign -> verify pipeline."""
from copy import deepcopy
from pathlib import Path
import os
import re
import secrets
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

from .security import inspect_apk, UnsupportedApk
from .translator import Translator

ANDROID = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", ANDROID)
TEXT_ATTRS = {"text", "hint", "contentDescription", "title", "summary", "label"}
RESOURCE_TAGS = {"string", "plurals", "string-array"}
MAX_TRANSLATIONS = 20000

def run_command(args, timeout=600, cwd=None):
    completed = subprocess.run(args, cwd=cwd, capture_output=True, text=True, timeout=timeout,
                               shell=False, check=False)
    if completed.returncode:
        raise RuntimeError("%s failed: %s" % (Path(args[0]).name, (completed.stderr or completed.stdout)[-1400:]))
    return completed.stdout

def write_xml(tree, path):
    tree.write(path, encoding="utf-8", xml_declaration=True)

def is_literal(value):
    return bool(value and value.strip() and not value.lstrip().startswith(("@", "?")))

def append_localized(target_root, element):
    name = element.attrib.get("name")
    if not name:
        return
    for old in list(target_root):
        if old.tag == element.tag and old.attrib.get("name") == name:
            target_root.remove(old)
    target_root.append(element)

def translate_resources(root, translator, report):
    """Generate values-ar and Arabic defaults, plus overwrite matching locale variants."""
    values = sorted(p for p in root.glob("res/values*") if p.is_dir() and p.name != "values-ar")
    report["resource_directories"] = [p.name for p in values]
    preferred = [p for p in values if p.name == "values"]
    if not preferred:
        preferred = [p for p in values if p.name == "values-en"]
    if not preferred:
        raise UnsupportedApk("No default or English resources; manual source selection is required")
    output_dir = root / "res" / "values-ar"
    output_dir.mkdir(exist_ok=True)
    arabic = {}
    for source_dir in preferred:
        for source_file in sorted(source_dir.glob("*.xml")):
            try:
                parsed = ET.parse(source_file)
            except ET.ParseError:
                report["warnings"].append("Unable to parse " + source_file.name)
                continue
            if parsed.getroot().tag != "resources":
                continue
            out_file = output_dir / source_file.name
            try:
                existing = ET.parse(out_file) if out_file.exists() else ET.ElementTree(ET.Element("resources"))
            except ET.ParseError:
                raise UnsupportedApk("Malformed existing Arabic resource file")
            changed_file = False
            for element in parsed.getroot():
                if element.tag not in RESOURCE_TAGS or element.get("translatable") == "false":
                    continue
                localized = deepcopy(element)
                nodes = [localized] if localized.tag == "string" else list(localized)
                changed = False
                for node in nodes:
                    if list(node):
                        report["skipped_formatted"] += 1
                        continue
                    if not is_literal(node.text):
                        continue
                    if report["translated_strings"] >= MAX_TRANSLATIONS:
                        raise UnsupportedApk("Translation count safety limit exceeded")
                    translated_text = translator.translate(node.text)
                    if translated_text != node.text:
                        node.text = translated_text
                        changed = True
                        report["translated_strings"] += 1
                if changed:
                    key = (localized.tag, localized.attrib.get("name"))
                    arabic[key] = deepcopy(localized)
                    append_localized(existing.getroot(), localized)
                    # Writing Arabic to default resources makes localization effective
                    # even when the device's default language is not Arabic.
                    element.text = localized.text
                    element[:] = [deepcopy(child) for child in localized]
                    changed_file = True
            if changed_file:
                write_xml(parsed, source_file)
                write_xml(existing, out_file)
    if not arabic:
        report["warnings"].append("No translatable default resource strings found")
        return
    report["rewritten_locale_entries"] = 0
    for source_dir in values:
        if source_dir in preferred:
            continue
        for source_file in sorted(source_dir.glob("*.xml")):
            try:
                parsed = ET.parse(source_file)
            except ET.ParseError:
                report["warnings"].append("Skipped locale XML: " + source_file.name)
                continue
            dirty = False
            for element in parsed.getroot():
                if element.get("translatable") == "false":
                    continue
                candidate = arabic.get((element.tag, element.attrib.get("name")))
                if candidate is None:
                    continue
                element.text = candidate.text
                element[:] = [deepcopy(child) for child in candidate]
                dirty = True
                report["rewritten_locale_entries"] += 1
            if dirty:
                write_xml(parsed, source_file)
    report["warnings"].append(
        "Locale-specific strings missing from the default resource set require manual audit.")

def rewrite_direction(value):
    # RTL-relative properties mirror correctly on Android; raw absolute coordinates cannot be fixed safely.
    return re.sub(r'(?<![a-zA-Z])(left|right)(?![a-zA-Z])',
                  lambda m: "start" if m.group(1) == "left" else "end", value)

def adapt_xml(root, translator, report):
    manifest = root / "AndroidManifest.xml"
    if not manifest.is_file():
        raise UnsupportedApk("Decoded manifest is missing")
    tree = ET.parse(manifest)
    application = tree.getroot().find("application")
    if application is None:
        raise UnsupportedApk("No application declaration")
    application.set("{" + ANDROID + "}supportsRtl", "true")
    write_xml(tree, manifest)
    report["rtl_manifest"] = True
    for path in sorted((root / "res").glob("layout*/*.xml")):
        try:
            tree = ET.parse(path)
            node_root = tree.getroot()
            node_root.set("{" + ANDROID + "}layoutDirection", "rtl")
            modified = True
            for node in node_root.iter():
                for attribute, value in list(node.attrib.items()):
                    if not attribute.startswith("{" + ANDROID + "}"):
                        continue
                    short = attribute.split("}", 1)[1]
                    if short in ("paddingLeft", "layout_marginLeft"):
                        node.attrib.pop(attribute)
                        node.set("{" + ANDROID + "}paddingStart" if short == "paddingLeft"
                                 else "{" + ANDROID + "}layout_marginStart", value)
                    elif short in ("paddingRight", "layout_marginRight"):
                        node.attrib.pop(attribute)
                        node.set("{" + ANDROID + "}paddingEnd" if short == "paddingRight"
                                 else "{" + ANDROID + "}layout_marginEnd", value)
                    elif short in ("gravity", "layout_gravity"):
                        node.set(attribute, rewrite_direction(value))
                    elif short in TEXT_ATTRS and is_literal(value) and not value.startswith("%"):
                        node.set(attribute, translator.translate(value))
                        report["hardcoded_xml_strings"] += 1
            if modified:
                write_xml(tree, path)
                report["rtl_layouts"] += 1
        except ET.ParseError:
            report["warnings"].append("Unparseable layout: " + path.name)

def locate(tool_env, fallback):
    value = os.getenv(tool_env, fallback)
    found = shutil.which(value)
    if not found:
        raise RuntimeError("Required tool missing: " + value)
    return found

def convert(apk, workspace, final, update, translator=None, runner=run_command):
    t0 = time.monotonic()
    report = {"translated_strings": 0, "hardcoded_xml_strings": 0, "rtl_layouts": 0,
              "rtl_manifest": False, "skipped_formatted": 0, "warnings": [],
              "limitations": ["Dynamic UI, WebViews, Compose, native strings and encoded assets are not automatically translated.",
                              "Signatures of the original developer cannot be preserved.",
                              "RTL mirroring of custom drawings and absolute-positioned layouts requires manual review."]}
    update(0, "Preparing APK")
    report.update(inspect_apk(apk))
    apktool = locate("APKTOOL", "apktool")
    zipalign = locate("ZIPALIGN", "zipalign")
    signer = locate("APKSIGNER", "apksigner")
    keytool = locate("KEYTOOL", "keytool")
    if translator is None:
        if os.getenv("TRANSLATION_PROVIDER", "libre") == "openai_compatible":
            from .ai_translator import AITranslator
            translator = AITranslator()
        else:
            translator = Translator()
    workspace = Path(workspace)
    decoded = workspace / "decoded"
    update(15, "Extracting resources")
    runner([apktool, "d", "-f", "--no-src", str(apk), "-o", str(decoded)], timeout=900)
    update(30, "Analyzing application")
    report["decoded_resource_xml"] = len(list((decoded / "res").glob("**/*.xml")))
    update(50, "Translating content")
    translate_resources(decoded, translator, report)
    update(70, "Applying Arabic RTL")
    adapt_xml(decoded, translator, report)
    if not report["translated_strings"] and not report["hardcoded_xml_strings"]:
        raise UnsupportedApk("No text changed; refusing to emit a misleading Arabic APK")
    update(85, "Rebuilding package")
    unsigned = workspace / "unsigned.apk"
    try:
        runner([apktool, "b", str(decoded), "-o", str(unsigned)], timeout=900)
    except RuntimeError:
        # One documented rebuild retry for stale/generated resource artifacts.
        report["warnings"].append("Initial rebuild failed; attempted full rebuild")
        runner([apktool, "b", "--force", str(decoded), "-o", str(unsigned)], timeout=900)
    aligned = workspace / "aligned.apk"
    runner([zipalign, "-f", "4", str(unsigned), str(aligned)], timeout=180)
    update(95, "Signing APK")
    password = secrets.token_urlsafe(30)
    keystore = workspace / "temporary.jks"
    runner([keytool, "-genkeypair", "-noprompt", "-keystore", str(keystore),
            "-storepass", password, "-keypass", password, "-alias", "arabiflow",
            "-keyalg", "RSA", "-keysize", "3072", "-validity", "3650",
            "-dname", "CN=ArabiFlow conversion (not original publisher)"], timeout=90)
    runner([signer, "sign", "--ks", str(keystore), "--ks-key-alias", "arabiflow",
            "--ks-pass", "pass:" + password, "--key-pass", "pass:" + password,
            "--out", str(final), str(aligned)], timeout=180)
    runner([signer, "verify", "--verbose", str(final)], timeout=90)
    inspect_apk(final)
    report["elapsed_seconds"] = round(time.monotonic() - t0, 1)
    report["output_bytes"] = Path(final).stat().st_size
    report["warnings"].append("This job uses a NEW temporary signing key: it cannot update an existing installation signed by another key.")
    update(100, "Completed")
    return report
