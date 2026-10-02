"""LibreTranslate-compatible provider with strict placeholder integrity."""
import os
import re
import httpx

# Protect Android printf, XML/HTML-like tags, ICU parameters, escapes and literal resource references.
PLACEHOLDER = re.compile(
    r'%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]'
    r'|\{[\w., ]+\}|<[^>]*>|\\[nrt]|@[\w./]+'
)
TOKEN = re.compile(r'AFPHX(\d{5})XHPAF')

class TranslationError(Exception):
    pass

def protect(value):
    found = []
    def sub(match):
        found.append(match.group(0))
        return "AFPHX%05dXHPAF" % (len(found) - 1)
    return PLACEHOLDER.sub(sub, value), found

def restore(value, found):
    seen = []
    def sub(match):
        index = int(match.group(1))
        if index >= len(found) or index in seen:
            raise TranslationError("Placeholder altered by translation service")
        seen.append(index)
        return found[index]
    result = TOKEN.sub(sub, value)
    if sorted(seen) != list(range(len(found))):
        raise TranslationError("Placeholder missing from translation")
    if TOKEN.search(result):
        raise TranslationError("Unresolved placeholder")
    return result

class Translator:
    def __init__(self, url=None, key=None, client=None):
        self.url = (url or os.getenv("LIBRETRANSLATE_URL") or "").rstrip("/")
        self.key = key if key is not None else os.getenv("LIBRETRANSLATE_API_KEY", "")
        self.client = client
        internal_docker = (os.getenv("LOCAL_TRANSLATION_CONTAINER") == "1"
                           and self.url == "http://libretranslate:5000")
        if not self.url or not (self.url.startswith(("https://", "http://127.0.0.1:", "http://localhost:"))
                                or internal_docker):
            raise TranslationError("Configure HTTPS translation or the explicit local Docker network service")

    def translate(self, value, source="auto"):
        if not value.strip() or value.strip().startswith(("@", "?")):
            return value
        if len(value) > 4000:
            raise TranslationError("Oversized individual string; manual review required")
        safe, placeholders = protect(value)
        payload = {"q": safe, "source": source, "target": "ar", "format": "text"}
        if self.key:
            payload["api_key"] = self.key
        try:
            if self.client:
                response = self.client.post(self.url + "/translate", json=payload, timeout=90)
            else:
                with httpx.Client(timeout=90) as client:
                    response = client.post(self.url + "/translate", json=payload)
            response.raise_for_status()
            translated = response.json()["translatedText"]
            if not isinstance(translated, str) or not translated.strip():
                raise TranslationError("Translation service returned empty output")
            return restore(translated, placeholders)
        except (httpx.HTTPError, KeyError, ValueError) as exc:
            raise TranslationError("Translation request failed: " + type(exc).__name__) from exc
