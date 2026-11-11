# Каталог релизов

Каталог `<version>` содержит `release.json` и `notes.ru.md`. Версия соответствует
hhClickerVersion в gradle.properties. Каждый опубликованный каталог неизменяем.

## Манифест

- `schemaVersion` — версия формата манифеста.
- `version` — числовая версия приложения.
- `minimumUpgradeVersion` — минимальная поддерживаемая установленная версия.
- `configVersion` — версия формата конфигурации.
- `postgresMajor` — поддерживаемая major-версия PostgreSQL.
- `databaseMigration` — наличие изменений схемы относительно поддерживаемой базы обновления.
- `repositories` — полные SHA backend, browser-agent, frontend, generation-agent и infra.

SHA должны указывать на окончательные коммиты проверяемого выпуска. Stable staging
проверяет чистоту и соответствие checkout. Nightly использует рабочие деревья и записывает
их HEAD/dirty metadata в сборочный манифест, не меняя официальный каталог. Для nightly
признак databaseMigration выставляется консервативно в true.

## Обновление

Текущий выпуск 3.0.0 поддерживает обновление с 2.0.0 на PostgreSQL 17, `configVersion=1`.
В 3.0.0 нет новых миграций БД (`databaseMigration=false`). Windows EXE заменяет приложение через установщик;
на Linux заменяется AppImage, на macOS — приложение в Applications. Пользовательские данные и Docker volumes
хранятся отдельно. Подробности находятся в [общем руководстве](https://github.com/hh-clicker/.github/blob/master/profile/README.md#updates).

### История перехода с 1.x

Версия 2.0.0 поддерживает обновление с полностью запущенной 1.12.0 и PostgreSQL 17;
configVersion равен 1. Миграция 036 добавляет состояние партий анализа рынка, поэтому
манифест содержит databaseMigration=true. Адаптер Liquibase сохраняет прежнюю историю.
Перед обновлением нужна резервная копия БД. Установщик не делает дамп и не откатывает миграции.

## Автоматизация

Windows installer, Linux AppImage и macOS DMG для Intel и Apple Silicon собираются в репозитории `.github` при push тега той же версии. Workflow
получает launcher по тегу, сервисы — по SHA этого манифеста, публикует артефакты в draft.
Порядок проверки, подписания и публикации описан в
[регламенте выпуска](https://github.com/hh-clicker/.github/blob/master/RELEASING.md).
