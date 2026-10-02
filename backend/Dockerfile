FROM ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive PYTHONDONTWRITEBYTECODE=1 \
    ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk \
    TMPDIR=/srv/work ARABIFLOW_OUTPUT_DIR=/srv/out \
    ZIPALIGN=/opt/android-sdk/build-tools/35.0.0/zipalign \
    APKSIGNER=/opt/android-sdk/build-tools/35.0.0/apksigner
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates curl unzip python3 python3-venv openjdk-21-jre-headless \
    && rm -rf /var/lib/apt/lists/*
RUN mkdir -p /opt/android-sdk/cmdline-tools /srv/work /srv/out \
    && curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
         -o /tmp/sdk.zip \
    && unzip -q /tmp/sdk.zip -d /tmp/sdk-unpack \
    && mv /tmp/sdk-unpack/cmdline-tools /opt/android-sdk/cmdline-tools/latest \
    && rm -rf /tmp/sdk.zip /tmp/sdk-unpack
ENV PATH="/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:$PATH"
RUN yes | sdkmanager --sdk_root=/opt/android-sdk --licenses > /dev/null \
    && sdkmanager --sdk_root=/opt/android-sdk "build-tools;35.0.0"
# Official upstream's published SHA-256 for the July 2026 release:
# https://apktool.org/blog/apktool-3.0.3/
RUN curl -fsSL https://bitbucket.org/iBotPeaches/apktool/downloads/apktool_3.0.3.jar \
      -o /opt/apktool.jar \
    && echo "dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423  /opt/apktool.jar" | sha256sum -c - \
    && printf '#!/bin/sh\nexec java -Xmx3g -jar /opt/apktool.jar "$@"\n' > /usr/local/bin/apktool \
    && chmod 755 /usr/local/bin/apktool
WORKDIR /srv
COPY requirements.txt /srv/requirements.txt
RUN python3 -m venv /opt/arabiflow \
    && /opt/arabiflow/bin/pip install --no-cache-dir -r /srv/requirements.txt
COPY arabiflow /srv/arabiflow
RUN groupadd --system arabiflow && useradd --system --gid arabiflow --home /srv arabiflow \
    && chown arabiflow:arabiflow /srv/work /srv/out
USER arabiflow
EXPOSE 8000
CMD ["sh", "-c", "exec /opt/arabiflow/bin/uvicorn arabiflow.api:app --host 0.0.0.0 --port ${PORT:-8000} --workers 1 --proxy-headers"]
