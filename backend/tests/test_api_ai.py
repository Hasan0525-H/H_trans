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
    client = TestClient(app)
    assert client.get("/health").status_code == 200
    assert client.get("/jobs/nonexistent").status_code == 401
    assert client.get("/jobs/nonexistent", headers={"Authorization": "Bearer a-secret"}).status_code == 404
    response = client.post("/jobs", headers={"Authorization": "Bearer a-secret"},
        files={"apk": ("sample.apk", b"this is not an apk",
                       "application/vnd.android.package-archive")})
    assert response.status_code == 422
