"""Authenticated, bounded job API. Deploy only behind TLS and an upload-limited reverse proxy."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from threading import Lock
import hmac
import os
import shutil
import tempfile
import uuid
import asyncio
import time

from fastapi import FastAPI, File, Header, HTTPException, UploadFile
from fastapi.responses import FileResponse
from .pipeline import convert
from .security import MAX_UPLOAD, inspect_apk, UnsupportedApk

app = FastAPI(title="ArabiFlow conversion API", version="0.1.0", docs_url=None, redoc_url=None)
_pool = ThreadPoolExecutor(max_workers=2)
_lock = Lock()
_jobs = {}
OUTPUT_ROOT = Path(os.getenv("ARABIFLOW_OUTPUT_DIR", tempfile.gettempdir() + "/arabiflow-results"))
RESULT_TTL_SECONDS = 24 * 3600

async def cleanup_expired():
    while True:
        await asyncio.sleep(3600)
        now = time.time()
        with _lock:
            expired = [key for key, job in _jobs.items()
                       if job["status"] in ("completed", "failed")
                       and now - job.get("created_at", now) > RESULT_TTL_SECONDS]
            removed = [_jobs.pop(key) for key in expired]
        for job in removed:
            if job.get("output"):
                Path(job["output"]).unlink(missing_ok=True)
        if OUTPUT_ROOT.exists():
            for path in OUTPUT_ROOT.glob("*.apk"):
                if now - path.stat().st_mtime > RESULT_TTL_SECONDS:
                    path.unlink(missing_ok=True)

@app.on_event("startup")
async def start_cleanup():
    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
    asyncio.create_task(cleanup_expired())

def authenticate(authorization):
    secret = os.getenv("ARABIFLOW_API_TOKEN", "")
    if not secret:
        raise HTTPException(503, "Server is not configured")
    if not authorization or not hmac.compare_digest(authorization, "Bearer " + secret):
        raise HTTPException(401, "Invalid authorization")

def snapshot(job_id):
    with _lock:
        record = _jobs.get(job_id)
        if record is None:
            raise HTTPException(404, "Unknown job")
        return {k: v for k, v in record.items() if k != "output"}

def set_stage(job_id, progress, stage):
    with _lock:
        _jobs[job_id].update(progress=progress, stage=stage, status="running")

def run_job(job_id, path, working_dir):
    result = OUTPUT_ROOT / (job_id + ".apk")
    try:
        report = convert(path, working_dir, result, lambda pct, msg: set_stage(job_id, pct, msg))
        with _lock:
            _jobs[job_id].update(status="completed", stage="Completed", progress=100,
                                 report=report, output=str(result))
    except Exception as exc:
        result.unlink(missing_ok=True)
        with _lock:
            _jobs[job_id].update(status="failed", stage="Failed",
                                 error=str(exc)[:650])
    finally:
        shutil.rmtree(working_dir, ignore_errors=True)

@app.get("/health")
def health():
    return {"status": "up"}

def missing_requirements():
    """Check local configuration only. This is not an end-to-end translation test."""
    missing = []
    for key, fallback in (
        ("APKTOOL", "apktool"), ("ZIPALIGN", "zipalign"),
        ("APKSIGNER", "apksigner"), ("KEYTOOL", "keytool")
    ):
        if not shutil.which(os.getenv(key, fallback)):
            missing.append(key)
    mode = os.getenv("TRANSLATION_PROVIDER", "libre")
    if mode == "openai_compatible":
        endpoint = os.getenv("AI_BASE_URL", "")
        if not endpoint.startswith("https://"):
            missing.append("AI_BASE_URL")
        if not os.getenv("AI_MODEL"):
            missing.append("AI_MODEL")
        if not os.getenv("AI_API_KEY"):
            missing.append("AI_API_KEY")
    elif mode == "libre":
        endpoint = os.getenv("LIBRETRANSLATE_URL", "")
        if not endpoint.startswith("https://"):
            missing.append("LIBRETRANSLATE_URL")
    else:
        missing.append("TRANSLATION_PROVIDER")
    return missing


@app.get("/ready")
def ready(authorization: str | None = Header(None)):
    authenticate(authorization)
    missing = missing_requirements()
    return {"ready": not missing, "missing": missing,
            "note": "Configuration only; translation provider availability is not tested"}



@app.post("/jobs", status_code=202)
async def create_job(apk: UploadFile = File(...), authorization: str | None = Header(None)):
    authenticate(authorization)
    missing = missing_requirements()
    if missing:
        raise HTTPException(503, "Backend prerequisites missing: " + ", ".join(missing))
    working_dir = tempfile.mkdtemp(prefix="arabiflow-")
    job_id = uuid.uuid4().hex
    original = Path(working_dir) / "input.apk"
    total = 0
    try:
        with original.open("wb") as dest:
            while chunk := await apk.read(1024 * 1024):
                total += len(chunk)
                if total > MAX_UPLOAD:
                    raise HTTPException(413, "APK exceeds 512 MiB limit")
                dest.write(chunk)
        if not total:
            raise HTTPException(400, "Empty APK")
        try:
            preflight = inspect_apk(original)
        except UnsupportedApk as exc:
            raise HTTPException(422, str(exc)) from exc
        OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
        with _lock:
            _jobs[job_id] = {"id": job_id, "status": "queued", "stage": "Queued", "created_at": time.time(),
                             "progress": 0, "input_bytes": total, "preflight": preflight,
                             "error": None, "report": None}
        _pool.submit(run_job, job_id, original, working_dir)
        return {"id": job_id, "status": "queued"}
    except Exception:
        shutil.rmtree(working_dir, ignore_errors=True)
        raise
    finally:
        await apk.close()

@app.get("/jobs/{job_id}")
def get_job(job_id: str, authorization: str | None = Header(None)):
    authenticate(authorization)
    return snapshot(job_id)

@app.get("/jobs/{job_id}/download")
def download(job_id: str, authorization: str | None = Header(None)):
    authenticate(authorization)
    state = snapshot(job_id)
    if state["status"] != "completed":
        raise HTTPException(409, "Conversion not completed")
    with _lock:
        path = _jobs[job_id]["output"]
    return FileResponse(path, media_type="application/vnd.android.package-archive",
                        filename="ArabiFlow-" + job_id + ".apk")

@app.delete("/jobs/{job_id}")
def delete_job(job_id: str, authorization: str | None = Header(None)):
    authenticate(authorization)
    with _lock:
        job = _jobs.get(job_id)
        if not job:
            raise HTTPException(404, "Unknown job")
        if job["status"] in ("running", "queued"):
            raise HTTPException(409, "Cannot delete a running job")
        _jobs.pop(job_id)
    if job.get("output"):
        Path(job["output"]).unlink(missing_ok=True)
    return {"deleted": True}
