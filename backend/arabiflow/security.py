"""Strict archive preflight before invoking apktool on untrusted input."""
from pathlib import PurePosixPath
from zipfile import ZipFile, BadZipFile

MAX_UPLOAD = 512 * 1024 * 1024
MAX_ENTRIES = 70000
MAX_UNCOMPRESSED = 2 * 1024 * 1024 * 1024

class UnsupportedApk(ValueError):
    pass

def inspect_apk(path):
    try:
        with ZipFile(path) as archive:
            entries = archive.infolist()
            if len(entries) > MAX_ENTRIES:
                raise UnsupportedApk("Too many ZIP entries")
            names = set()
            total = 0
            for entry in entries:
                name = entry.filename
                normalized = PurePosixPath(name)
                if (not name or name.startswith("/") or "\\" in name
                        or ".." in normalized.parts or ":" in name
                        or entry.flag_bits & 1):
                    raise UnsupportedApk("Unsafe or encrypted ZIP member")
                total += entry.file_size
                if total > MAX_UNCOMPRESSED:
                    raise UnsupportedApk("Uncompressed size limit exceeded")
                if entry.file_size > 1024 * 1024 and entry.file_size / max(1, entry.compress_size) > 300:
                    raise UnsupportedApk("Suspicious ZIP compression ratio")
                if name in names:
                    raise UnsupportedApk("Duplicate ZIP member")
                names.add(name)
            if "AndroidManifest.xml" not in names or "resources.arsc" not in names:
                raise UnsupportedApk("Requires one conventional, resource-bearing base APK")
            if not any(n.endswith(".dex") for n in names):
                raise UnsupportedApk("No classes.dex: unsupported APK format")
            return {"entry_count": len(entries), "uncompressed_bytes": total}
    except (BadZipFile, OSError) as exc:
        raise UnsupportedApk("Invalid APK ZIP archive") from exc
