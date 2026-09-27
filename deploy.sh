#!/usr/bin/env bash
set -e

ADB="/home/koddedai/Android/Sdk/platform-tools/adb"
JAVA_HOME="/home/koddedai/.sdkman/candidates/java/17.0.20-tem"
PACKAGE_NAME="com.example.ime"
ACTIVITY_NAME="com.example.ime.MainActivity"
IME_SERVICE="${PACKAGE_NAME}/.SimpleIME"

# 接続先デバイスの取得（mDNS/TLS デバイスまたは IP:PORT）
DEVICE=$(${ADB} devices | grep -v "List of devices" | grep "device$" | head -n 1 | awk '{print $1}')

if [ -z "$DEVICE" ]; then
    DEVICE_IP="100.108.99.37"
    PORT="${1:-38835}"
    echo "==> 1. ADB 接続試行: ${DEVICE_IP}:${PORT}"
    ${ADB} connect "${DEVICE_IP}:${PORT}" || true
    DEVICE=$(${ADB} devices | grep -v "List of devices" | grep "device$" | head -n 1 | awk '{print $1}')
fi

echo "==> 1. 接続先デバイス: ${DEVICE}"

echo "==> 2. ビルド中..."
JAVA_HOME="${JAVA_HOME}" ./gradlew assembleDebug --no-daemon

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

echo "==> 3. 実機へインストール (${DEVICE})..."
${ADB} -s "${DEVICE}" install -r -t -g "${APK_PATH}"

echo "==> 4. IME の有効化 & 選択..."
${ADB} -s "${DEVICE}" shell ime enable "${IME_SERVICE}" || true
${ADB} -s "${DEVICE}" shell ime set "${IME_SERVICE}"

echo "==> 5. アプリ再起動..."
${ADB} -s "${DEVICE}" shell am force-stop "${PACKAGE_NAME}" || true
${ADB} -s "${DEVICE}" shell am start -n "${PACKAGE_NAME}/${ACTIVITY_NAME}"

echo "==> デプロイ完了！"
