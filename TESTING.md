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

### Индикатор обновления

Для проверки боковой панели можно задать `HH_CLICKER_UPDATE_PREVIEW` только процессу лаунчера:
`critical:3.0.0`, `normal:3.0.1` или `none`. Подменяется только состояние индикатора;
версия установленного приложения, конфигурация и ограничения установщика не меняются.
Без переменной проверка использует опубликованные релизы GitHub. Текущий лаунчер нужно закрыть,
иначе single-instance guard активирует уже запущенное окно без тестового состояния.

После установки новой nightly закройте лаунчер и выполните в PowerShell:

```powershell
$env:HH_CLICKER_UPDATE_PREVIEW = 'critical:3.0.0'
try {
    Start-Process -FilePath "$env:LOCALAPPDATA\HH Clicker\application\HH Clicker Launcher.exe"
} finally {
    Remove-Item Env:HH_CLICKER_UPDATE_PREVIEW
}
```

Для проверки обычного обновления используйте `normal:3.0.1`, без обновления — `none`.
Переменная удаляется из текущей оболочки сразу после запуска; дочерний процесс получает свою копию.
Прочие функции лаунчера в этом режиме остаются настоящими.

## Nightly installer

```powershell
.\gradlew.bat --% :desktopApp:packageWindowsInstaller -Pnightly --console=plain
```

Выберите допустимую числовую версию. Nightly допускает изменённые и неотслеживаемые
неигнорируемые исходники, сохраняет HEAD/dirty metadata и не меняет официальный каталог.
Установленный nightly работает с production-профилем и существующими пользовательскими
данными. Он не создаёт отдельную песочницу. Для разрушительных сценариев нужна отдельная
тестовая установка; файловый rollback не откатывает изменения БД.

## Изоляция DLL в Windows

Проверяется на установленной nightly, а не только через Gradle: наследуемый каталог DLL
выставляет именно EXE-обёртка jpackage. Перед проверкой должны завершиться прежние
экземпляры управляемого Chrome и связанные с ними KeePassXC proxy, иначе они сохранят
старые загруженные DLL независимо от новой сборки.

После запуска лаунчера откройте управляемый Chrome с расширением KeePassXC и вызовите
его соединение с KeePassXC. В списке модулей нового `keepassxc-proxy.exe` не должно быть
DLL из каталога `HH Clicker\application\runtime`. Проверьте также открытие внешней ссылки
из лаунчера, повторный запуск сервисов и обновление установщиком после закрытия лаунчера.
Наличие всех необходимых Java-модулей и загрузку Compose проверяют установленной сборкой.
Автономный `WindowsJavaDllIsolationTest` проверяет порядок настройки через fake Windows API,
отказ до сброса Java-каталога при ошибке и точечную фильтрацию PATH без изменения соседних путей.

## macOS DMG

Сборка из полного Git checkout с JDK 21+, Node.js и npm:

```bash
bash ./gradlew :desktopApp:packageDmg -Pnightly --console=plain
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
