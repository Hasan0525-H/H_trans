"""Provider and HTTP boundary regressions without third-party accounts."""
import httpx
from fastapi.testclient import TestClient

from arabiflow.ai_translator import AITranslator
from arabiflow.api import app

def test_ai_provider_keeps_placeholders():
    def respond(request):
        assert request.headers["authorization"] == "Bearer test-key"
        request_body = __import__("json").loads(request.content)
        assert "AFPHX00000XHPAF" in request_body["messages"][1]["content"]
        return httpx.Response(200, json={"choices": [
            {"message": {"content": "مرحبًا AFPHX00000XHPAF"}}
        ]})
    client = httpx.Client(transport=httpx.MockTransport(respond))
    translator = AITranslator(base="https://example.invalid/v1",
                              api_key="test-key", model="test-model", client=client)
    assert translator.translate("Welcome %s") == "مرحبًا %s"

def test_api_auth_and_invalid_archive(monkeypatch):
    monkeypatch.setenv("ARABIFLOW_API_TOKEN", "a-secret")
    monkeypatch.setenv("LIBRETRANSLATE_URL", "https://translation.example.test")
    monkeypatch.setattr("arabiflow.api.shutil.which", lambda tool: "/usr/local/bin/" + tool)
    monkeypatch.setattr("arabiflow.api.verify_translation", lambda: None)
    client = TestClient(app)
    assert client.get("/health").status_code == 200
    assert client.get("/jobs/nonexistent").status_code == 401
    assert client.get("/jobs/nonexistent", headers={"Authorization": "Bearer a-secret"}).status_code == 404
    response = client.post("/jobs", headers={"Authorization": "Bearer a-secret"},
        files={"apk": ("sample.apk", b"this is not an apk",
                       "application/vnd.android.package-archive")})
    assert response.status_code == 422


def test_readiness_requires_authenticated_complete_configuration(monkeypatch):
    monkeypatch.setenv("ARABIFLOW_API_TOKEN", "a-secret")
    monkeypatch.delenv("LIBRETRANSLATE_URL", raising=False)
    monkeypatch.delenv("TRANSLATION_PROVIDER", raising=False)
    monkeypatch.setattr("arabiflow.api.shutil.which", lambda tool: None)
    client = TestClient(app)
    auth = {"Authorization": "Bearer a-secret"}
    assert client.get("/ready").status_code == 401
    missing = client.get("/ready", headers=auth).json()
    assert not missing["ready"]
    assert "LIBRETRANSLATE_URL" in missing["missing"]
    assert "APKTOOL" in missing["missing"]
    monkeypatch.setenv("LIBRETRANSLATE_URL", "https://translation.example.test")
    monkeypatch.setattr("arabiflow.api.shutil.which", lambda tool: "/opt/" + tool)
    monkeypatch.setattr("arabiflow.api.verify_translation", lambda: None)
    verified = client.get("/ready", headers=auth).json()
    assert verified["ready"] is True
    assert verified["missing"] == []


def test_jobs_rejected_before_upload_if_backend_not_ready(monkeypatch):
    monkeypatch.setenv("ARABIFLOW_API_TOKEN", "a-secret")
    monkeypatch.delenv("LIBRETRANSLATE_URL", raising=False)
    monkeypatch.delenv("TRANSLATION_PROVIDER", raising=False)
    client = TestClient(app)
    response = client.post("/jobs", headers={"Authorization": "Bearer a-secret"},
        files={"apk": ("example.apk", b"not an apk", "application/vnd.android.package-archive")})
    assert response.status_code == 503


def test_readiness_identifies_protocol_without_exposing_token(monkeypatch):
    monkeypatch.setenv("ARABIFLOW_API_TOKEN", "a-secret")
    monkeypatch.setenv("LIBRETRANSLATE_URL", "https://translator.example.invalid")
    monkeypatch.setattr("arabiflow.api.shutil.which", lambda cmd: "/opt/bin/" + cmd)
    monkeypatch.setattr("arabiflow.api.verify_translation", lambda: None)
    response = TestClient(app).get("/ready",
        headers={"Authorization": "Bearer a-secret"})
    assert response.status_code == 200
    data = response.json()
    assert data["service"] == "arabiflow"
    assert data["protocol_version"] == 1
    assert data["ready"] is True
    assert "a-secret" not in response.text


def test_readiness_rejects_translation_provider_echo(monkeypatch):
    import arabiflow.api as api_module
    monkeypatch.setenv("ARABIFLOW_API_TOKEN", "a-secret")
    monkeypatch.setenv("LIBRETRANSLATE_URL", "https://translator.invalid")
    monkeypatch.setenv("TRANSLATION_PROVIDER", "libre")
    monkeypatch.setattr("arabiflow.api.shutil.which", lambda cmd: "/opt/" + cmd)
    monkeypatch.setattr("arabiflow.translator.Translator.translate",
                        lambda self, value, source="auto": value)
    api_module._translation_cache["signature"] = None
    response = TestClient(app).get("/ready",
        headers={"Authorization": "Bearer a-secret"})
    result = response.json()
    assert result["ready"] is False
    assert "TRANSLATION_NOT_ARABIC" in result["missing"]
    assert "a-secret" not in response.text


def test_readiness_verifies_arabic_response_without_user_text(monkeypatch):
    import arabiflow.api as api_module
    monkeypatch.setenv("ARABIFLOW_API_TOKEN", "a-secret")
    monkeypatch.setenv("LIBRETRANSLATE_URL", "https://translator.invalid")
    monkeypatch.setenv("TRANSLATION_PROVIDER", "libre")
    monkeypatch.setattr("arabiflow.api.shutil.which", lambda cmd: "/opt/" + cmd)
    seen = []
    def translate(self, value, source="auto"):
        seen.append((value, source))
        return "مرحباً"
    monkeypatch.setattr("arabiflow.translator.Translator.translate", translate)
    api_module._translation_cache["signature"] = None
    response = TestClient(app).get("/ready",
        headers={"Authorization": "Bearer a-secret"})
    assert response.json()["ready"] is True
    assert seen == [("Hello", "en")]
