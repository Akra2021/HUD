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
    echo "--- Установка на машину (Huawei car installer)... ---"
    adb push "$APK_PATH" /data/local/tmp/app-debug.apk
    if adb shell pm install -r -d -g -i com.huawei.appinstaller.car /data/local/tmp/app-debug.apk; then
        echo "Установлено."
    else
        echo "Huawei installer failed, пробуем pm install -r ..."
        adb shell pm install -r /data/local/tmp/app-debug.apk
    fi
    echo ""
    echo "Обычный adb install на машине падает с INSTALL_FAILED_INTERNAL_ERROR — используйте b или:"
    echo "  scripts/install_car.sh"
    echo ""
    echo "Установлено. Выберите Yandex или 2GIS и включите HUD в приложении."
else
    echo "Неверный выбор. Используйте 'a' или 'b'."
fi
