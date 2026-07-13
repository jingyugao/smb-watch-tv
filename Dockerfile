FROM code-buddy:codex-base-test

USER root

ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV GRADLE_HOME=/opt/gradle-8.9
ENV PATH=${GRADLE_HOME}/bin:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools:${PATH}

RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-21-jdk \
    wget \
    unzip \
    curl \
    ca-certificates \
    bash && \
    rm -rf /var/lib/apt/lists/*

RUN mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools

WORKDIR /tmp

RUN wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdline-tools.zip \
    && unzip -q cmdline-tools.zip -d ${ANDROID_SDK_ROOT}/cmdline-tools \
    && mv ${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest \
    && rm cmdline-tools.zip

RUN wget -q https://services.gradle.org/distributions/gradle-8.9-bin.zip -O gradle.zip \
    && unzip -q gradle.zip -d /opt \
    && rm gradle.zip

RUN yes | sdkmanager --licenses >/dev/null \
    && sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

WORKDIR /workspace
VOLUME ["/workspace", "/root/.gradle"]

CMD ["gradle", "assembleDebug"]
