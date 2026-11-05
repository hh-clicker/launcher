# Проверки и режимы launcher

## Автономные тесты

```powershell
.\gradlew.bat :shared:jvmTest --console=plain
```

Fake transport, probes и процессы в тестах заменяют реальные установку программ,
Docker и внешние сервисы. Сами команды приложения не запускаются этим документом.

## Разработка из checkout

```powershell
.\gradlew.bat :desktopApp:run
```

Реальный entry point передаёт PreflightLauncherController. Такой запуск не является
симуляцией: кнопки могут подготавливать окружение и управлять настоящими сервисами.
Без bundle marker/application image выбирается development-профиль с отдельными
Compose-проектами, конфигурацией, volumes и портами. Наличие source checkout определяется
по файловой структуре, а не отдельным параметром командной строки.

FakeLauncherController используется в тестах и по умолчанию при отдельном вызове
Compose App() без контроллера. У desktop entry point нет CLI-флага fake/mock;
для ручного UI-preview нужно явно использовать fake-контроллер в preview-коде.

## Nightly installer

```powershell
.\gradlew.bat --% :desktopApp:packageWindowsInstaller -Pnightly -PnightlyVersion=2.0.0 --console=plain
```

Выберите допустимую числовую версию. Nightly допускает изменённые и неотслеживаемые
неигнорируемые исходники, сохраняет HEAD/dirty metadata и не меняет официальный каталог.
Установленный nightly работает с production-профилем и существующими пользовательскими
данными. Он не создаёт отдельную песочницу. Для разрушительных сценариев нужна отдельная
тестовая установка; файловый rollback не откатывает изменения БД.

## macOS DMG

Сборка из полного Git checkout с JDK 21+, Node.js и npm:

```bash
bash ./gradlew :desktopApp:packageDmg -Pnightly -PnightlyVersion=2.1.1 --console=plain
```

JDK и Gradle должны иметь архитектуру целевого пакета: Intel x64 или Apple Silicon arm64.
В DMG входят runtime Java и подготовленный Browser Agent, поэтому Git, npm и JDK
нужны только на машине сборки. Подготовка Unix payload требует Git-репозитории сервисов
рядом с launcher; копии исходников без `.git` для этой команды недостаточно.
Минимальная версия пакета — macOS 14. Неподписанная тестовая сборка не нотарифицирована.
DMG устанавливается переносом приложения в Applications; данные остаются отдельно
в `~/Library/Application Support/HH Clicker/data`.

## Инструменты

`tools/generate-icon.ps1` вручную пересоздаёт ICO в desktopApp/src/main/resources.
Это dev-инструмент; installer не запускает его на компьютере пользователя.
Поведение обновления описано в [installer/README.md](installer/README.md),
публикация — в [регламенте выпуска](https://github.com/hh-clicker/.github/blob/master/RELEASING.md).
