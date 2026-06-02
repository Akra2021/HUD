#!/bin/bash

# Путь к APK после сборки
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

echo "Выберите тип установки:"
echo "a) Телефон (обычная установка)"
echo "b) Машина (Huawei/Car установка)"
read -p "Ваш выбор (a/b): " choice

# 1. Сборка проекта
echo "--- Сборка APK... ---"
./gradlew assembleDebug

if [ $? -ne 0 ]; then
    echo "Ошибка сборки! Проверьте код."
    exit 1
fi

# 2. Установка в зависимости от выбора
if [ "$choice" == "a" ]; then
    echo "--- Установка на телефон... ---"
    adb install -r "$APK_PATH"
elif [ "$choice" == "b" ]; then
    echo "--- Выполнение команд для Car... ---"

    echo "Push APK..."
    adb push "$APK_PATH" /data/local/tmp/app-debug.apk

    echo "Отключение packageinstaller..."
    adb shell "pm disable-user --user 13 com.android.packageinstaller"

    echo "Установка через Huawei Installer..."
    adb shell "pm install -r -d -g -i com.huawei.appinstaller.car /data/local/tmp/app-debug.apk"

    echo "--- Готово ---"
else
    echo "Неверный выбор. Используйте 'a' или 'b'."
fi
