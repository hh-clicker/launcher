# HH Clicker на Linux

Начиная с версии 2.1.0, Linux-версия поставляется как AppImage для x86_64. Лаунчер, запуск сервисов, холостой проход и аналитика проверены на Debian 13; на Arch проверены запуск лаунчера, поиск, холостой проход и оценка. Эта инструкция также описывает запуск из исходников без лаунчера.

## AppImage

AppImage содержит лаунчер, Java runtime и исходники сервисов. Для работы на компьютере нужны Docker Engine с Compose v2, Google Chrome, графический сеанс и интернет. Совместимый Node.js лаунчер может подготовить в своём каталоге данных. PostgreSQL и Codex CLI отдельно устанавливать не требуется: они работают в контейнерах. Для AI-функций понадобится авторизация Codex.

Скачанный файл запустите от обычного пользователя:

```bash
chmod +x HH-Clicker-<версия>-x86_64.AppImage
./HH-Clicker-<версия>-x86_64.AppImage
```

Если система не поддерживает запуск AppImage через FUSE, используйте `--appimage-extract-and-run`. Лаунчер проверит окружение, затем в разделе «Настройки» предложит следующий шаг. Для Debian 13 x86_64 он умеет установить Docker Engine/Compose и Google Chrome после явного нажатия кнопки; для других систем показывает ручные шаги и ссылки на документацию. После добавления пользователя в группу `docker` завершите графический сеанс и войдите снова: уже запущенный лаунчер не получит новые права группы.

Чтобы запускать HH Clicker из меню приложений, откройте AppImage и нажмите «Добавить» в блоке «Запуск из меню приложений» на странице настроек. Лаунчер скопирует AppImage в постоянный каталог `$XDG_DATA_HOME/hh-clicker/app` (или `~/.local/share/hh-clicker/app`), установит иконку и создаст ярлык только для текущего пользователя, без `sudo`. Для обновления запустите новый скачанный AppImage и нажмите «Обновить» в том же блоке. Исходный скачанный файл остаётся на месте; данные и Docker volumes сохраняются.

Значок рядом с версией открывает [страницу релизов](https://github.com/hh-clicker/.github/releases). При запуске лаунчер проверяет последнюю опубликованную стабильную версию: обычный значок обновления означает, что доступна новая версия, красный восклицательный знак — что установленная версия ниже минимально поддерживаемой. Найтли-сборки сравниваются по номеру версии со стабильными релизами. Если GitHub недоступен, значок всё равно открывает страницу релизов; проверка не мешает запуску.

Перед запуском сервисов проверьте от того же пользователя:

```bash
docker info
docker compose version
google-chrome-stable --version
```

На Arch Docker и Compose ставятся по [инструкции ArchWiki](https://wiki.archlinux.org/title/Docker). Google не выпускает отдельный пакет Chrome для Arch; пакет `google-chrome` доступен в AUR и поддерживается сообществом. [Инструкция Google](https://support.google.com/chrome/answer/95346) перечисляет официально поддерживаемые Linux-дистрибутивы. На других системах используйте [инструкцию Docker](https://docs.docker.com/engine/install/) и подходящий для дистрибутива способ установки Chrome.

Нажмите «Запустить» на главной странице лаунчера. Первая сборка контейнеров скачивает зависимости и может занять время. После запуска откройте веб-интерфейс кнопкой лаунчера и авторизуйтесь в HH в открываемом Browser Agent окне Chrome. Для оценки вакансий и других AI-функций отдельно выполните вход в Codex через «Настройки» лаунчера.

Новая версия AppImage заменяет только файл приложения; пользовательские данные и Docker volumes находятся отдельно. Перед заменой остановите сервисы и закройте лаунчер. Постоянные настройки лаунчера лежат в `$XDG_DATA_HOME/hh-clicker/data` либо в `~/.local/share/hh-clicker/data`, если `XDG_DATA_HOME` не задан. Профиль браузера создаёт Browser Agent в `$XDG_DATA_HOME/HH Clicker/data/browser-agent/profiles` либо в `~/.local/share/HH Clicker/data/browser-agent/profiles`. Не удаляйте эти каталоги и Docker volumes, если хотите сохранить данные.

## Запуск без лаунчера из исходников

Этот способ нужен для разработки и ручной диагностики. Установите Docker Engine с Compose v2, Node.js 24.12+ с npm (также поддерживается 22.18+), Google Chrome и Git. JDK/Gradle на хосте не нужны: backend собирается в Docker. Разместите рядом каталоги `backend`, `browser-agent`, `frontend`, `generation-agent` и `infra` из [организации hh-clicker](https://github.com/hh-clicker). Для Linux используйте дополнительные Compose-файлы: они подключают Backend и Generation Agent к общей Docker-сети.

Сначала создайте настройки infra и замените `POSTGRES_PASSWORD=change-me` своим паролем в `infra/.env`. Существующий `.env` не перезаписывайте:

```bash
cd infra
cp -n .env.example .env
docker compose -f compose.yaml -f compose.linux.yaml up -d --build
docker compose -f compose.yaml -f compose.linux.yaml ps
```

Затем настройте Generation Agent. Его Linux override ожидает сеть, созданную командой для infra:

```bash
cd ../generation-agent
cp -n .env.example .env
docker compose -f compose.yaml -f compose.linux.yaml build
docker compose -f compose.yaml -f compose.linux.yaml run --rm generation-agent codex -c 'cli_auth_credentials_store="file"' login --device-auth
docker compose -f compose.yaml -f compose.linux.yaml up -d
```

Вход в Codex нужен только для AI-функций. Одноразовый код и ссылку покажет команда в терминале. Для запуска Browser Agent откройте отдельный терминал:

```bash
cd browser-agent
npm ci
npm run build
node dist/main.js
```

Веб-интерфейс доступен по `http://localhost`, API по `http://localhost:8080`. Browser Agent остаётся процессом на хосте, чтобы открывать видимый Google Chrome; остановите его через `Ctrl+C`. В медленной VM можно увеличить время открытия Chrome: `HH_CLICKER_CHROME_LAUNCH_TIMEOUT_MS=90000 node dist/main.js`.

Чтобы остановить контейнеры, сначала остановите Generation Agent, затем infra. Не добавляйте `-v`: этот параметр удаляет volumes с данными.

```bash
cd generation-agent
docker compose -f compose.yaml -f compose.linux.yaml down
cd ../infra
docker compose -f compose.yaml -f compose.linux.yaml down
```

Для следующего запуска используйте те же команды `up -d` без `--build`, если исходники и образы не менялись.

## Запуск лаунчера из checkout

Для разработки лаунчера нужны JDK 21 и соседние каталоги сервисов. Сборка из checkout использует отдельный development-профиль. После смены групп пользователя перезапустите графический сеанс: старый Gradle daemon может сохранить прежние права и не получить доступ к Docker.

```bash
cd launcher
bash ./gradlew :desktopApp:run --no-daemon --console=plain
```

## Сборка AppImage из исходников

Сборка выполняется на Debian 13 x86_64. Нужны JDK 21 с `jpackage`, Node.js с npm, `binutils` (`objcopy`) и `appimagetool`. Каталоги сервисов должны лежать рядом с `launcher`. Nightly берёт текущие рабочие деревья; stable требует чистые checkout по SHA из `launcher/releases/<версия>/release.json`.

```bash
cd launcher
bash ./gradlew :desktopApp:packageLinuxAppImage -Pnightly -PnightlyVersion=2.0.1 --no-daemon --console=plain
```

Результат появляется в `desktopApp/build/compose/binaries/main/appimage`. Числовую версию nightly подставьте подходящую к текущему каталогу релиза.
